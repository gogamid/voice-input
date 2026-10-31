package org.futo.voiceinput.tv;

import android.Manifest;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.usb.*;
import android.media.AudioManager;
import android.os.Process;
import java.io.Closeable;
import java.io.IOException;

/** Fixed-format input from the tested G20S receiver; never opens AudioRecord. */
public final class UsbAudio implements Closeable {
    private final UsbDeviceConnection connection;
    private final UsbInterface streaming;
    private final UsbInterface idle;
    private final Context context;
    private long handle;

    static native long nativeOpen(int fd, int endpoint, int packetSize);
    static native int nativeRead(long handle, short[] samples, boolean blocking);
    static native void nativeClose(long handle);

    public static UsbDevice receiver(Context context) {
        UsbManager manager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        for (UsbDevice device : manager.getDeviceList().values()) {
            if (device.getVendorId() == 0x4842 && device.getProductId() == 1) return device;
        }
        return null;
    }

    public static void checkPrivacy(Context context) throws IOException {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            throw new IOException("Microphone permission is off. Open Voice Input TV setup.");
        AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audio.isMicrophoneMute()) throw new IOException("Microphone is muted. No audio captured.");
        // Sensor privacy restricts RECORD_AUDIO through AppOps. AudioManager's
        // mute query intentionally excludes that toggle on Android 12.
        AppOpsManager ops = context.getSystemService(AppOpsManager.class);
        if (ops.noteOpNoThrow(AppOpsManager.OPSTR_RECORD_AUDIO, Process.myUid(), context.getPackageName()) != AppOpsManager.MODE_ALLOWED)
            throw new IOException("Microphone privacy or app permission blocks recording.");
    }

    /** Accept only the descriptor proved on this receiver, not just its USB ID. */
    public static boolean supportsFormat(byte[] raw) {
        boolean streaming = false;
        for (int p = 0; p + 2 <= raw.length;) {
            int length = raw[p] & 255;
            int type = raw[p + 1] & 255;
            if (length < 2 || length > raw.length - p) return false;
            if (type == 4) {
                if (length < 9) return false;
                streaming = raw[p + 2] == 1 && raw[p + 3] == 1 && raw[p + 5] == 1 && raw[p + 6] == 2;
            }
            if (streaming && type == 36 && length >= 3 && raw[p + 2] == 2) {
                return length == 11 && raw[p + 3] == 1 && raw[p + 4] == 1 && raw[p + 5] == 2
                    && raw[p + 6] == 16 && raw[p + 7] == 1 && (raw[p + 8] & 255) == 0x80
                    && (raw[p + 9] & 255) == 0x3e && raw[p + 10] == 0;
            }
            p += length;
        }
        return false;
    }

    public static UsbAudio open(Context context) throws IOException {
        checkPrivacy(context);
        UsbDevice device = receiver(context);
        if (device == null) throw new IOException("Remote USB receiver is unplugged. TV microphone will not be used.");
        UsbManager manager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        if (!manager.hasPermission(device)) throw new IOException("USB permission required. Open Voice Input TV setup.");
        System.loadLibrary("tvusb");
        UsbInterface streaming = null, idle = null;
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface candidate = device.getInterface(i);
            if (candidate.getId() == 1 && candidate.getAlternateSetting() == 1) streaming = candidate;
            if (candidate.getId() == 1 && candidate.getAlternateSetting() == 0) idle = candidate;
        }
        if (streaming == null || idle == null || streaming.getEndpointCount() != 1)
            throw new IOException("Receiver audio interface is unsupported.");
        UsbEndpoint endpoint = streaming.getEndpoint(0);
        if (endpoint.getAddress() != 0x81 || endpoint.getType() != UsbConstants.USB_ENDPOINT_XFER_ISOC
                || endpoint.getMaxPacketSize() != 32 || endpoint.getInterval() != 1)
            throw new IOException("Receiver audio endpoint is unsupported.");
        UsbDeviceConnection connection = manager.openDevice(device);
        if (connection == null) throw new IOException("Cannot open USB receiver.");
        boolean claimed = false;
        boolean recordingOp = false;
        long handle = 0;
        try {
            if (!supportsFormat(connection.getRawDescriptors())) throw new IOException("Expected mono PCM16 at 16 kHz.");
            if (context.getSystemService(AppOpsManager.class).startOpNoThrow(AppOpsManager.OPSTR_RECORD_AUDIO,
                    Process.myUid(), context.getPackageName()) != AppOpsManager.MODE_ALLOWED)
                throw new IOException("Microphone privacy blocks recording.");
            recordingOp = true;
            claimed = connection.claimInterface(streaming, true);
            if (!claimed || !connection.setInterface(streaming)) throw new IOException("Cannot activate USB audio interface.");
            handle = nativeOpen(connection.getFileDescriptor(), endpoint.getAddress(), endpoint.getMaxPacketSize());
            if (handle == 0) throw new IOException("Cannot allocate USB audio stream.");
            return new UsbAudio(context.getApplicationContext(), connection, streaming, idle, handle);
        } catch (Throwable error) {
            if (claimed) { connection.setInterface(idle); connection.releaseInterface(streaming); }
            connection.close();
            if (handle != 0) nativeClose(handle);
            if (recordingOp) context.getSystemService(AppOpsManager.class).finishOp(AppOpsManager.OPSTR_RECORD_AUDIO,
                Process.myUid(), context.getPackageName());
            throw error;
        }
    }

    private UsbAudio(Context context, UsbDeviceConnection connection, UsbInterface streaming, UsbInterface idle, long handle) {
        this.context = context;
        this.connection = connection;
        this.streaming = streaming;
        this.idle = idle;
        this.handle = handle;
    }

    public synchronized int read(short[] samples) throws IOException {
        if (handle == 0) throw new IOException("USB audio stream is closed.");
        int count = nativeRead(handle, samples, true);
        if (count <= 0) throw new IOException("USB audio stopped (error " + count + "). No TV microphone fallback.");
        return count;
    }

    @Override public synchronized void close() {
        if (handle == 0) return;
        // Native owns a dup of this connection. Close the original first so its
        // final close can cancel all kernel transfers before freeing buffers.
        try {
            connection.setInterface(idle);
            connection.releaseInterface(streaming);
        } finally {
            connection.close();
            nativeClose(handle);
            handle = 0;
            context.getSystemService(AppOpsManager.class).finishOp(AppOpsManager.OPSTR_RECORD_AUDIO,
                Process.myUid(), context.getPackageName());
        }
    }
}
