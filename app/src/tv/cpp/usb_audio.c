#include <jni.h>
#include <linux/usbdevice_fs.h>
#include <sys/ioctl.h>
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

enum { QUEUE = 8, PACKETS = 32, PACKET_BYTES = 32,
       URB_BYTES = PACKETS * PACKET_BYTES, URB_SAMPLES = URB_BYTES / 2 };

struct capture {
    int fd;
    int started;
    int error;
    struct usbdevfs_urb *urbs[QUEUE];
    unsigned char *buffers[QUEUE];
    int active[QUEUE];
    jshort pending[URB_SAMPLES];
    int pending_count;
    int pending_offset;
};

static int64_t now_ms(void) {
    struct timespec t;
    if (clock_gettime(CLOCK_MONOTONIC, &t) < 0) return -1;
    return (int64_t)t.tv_sec * 1000 + t.tv_nsec / 1000000;
}

static int urb_index(const struct capture *c, const struct usbdevfs_urb *u) {
    for (int i = 0; i < QUEUE; ++i) {
        if (c->urbs[i] == u) return i;
    }
    return -1;
}

static int submit(struct capture *c, int i) {
    struct usbdevfs_urb *u = c->urbs[i];
    memset(u, 0, sizeof(*u) + PACKETS * sizeof(u->iso_frame_desc[0]));
    u->type = USBDEVFS_URB_TYPE_ISO;
    u->endpoint = 0x81;
    u->flags = USBDEVFS_URB_ISO_ASAP;
    u->buffer = c->buffers[i];
    u->buffer_length = URB_BYTES;
    u->number_of_packets = PACKETS;
    for (int j = 0; j < PACKETS; ++j) u->iso_frame_desc[j].length = PACKET_BYTES;
    if (ioctl(c->fd, USBDEVFS_SUBMITURB, u) < 0) return -errno;
    c->active[i] = 1;
    return 0;
}

/* Java serializes read/close. Before nativeClose it MUST reset alt 0, release
 * the audio interface and close the original UsbDeviceConnection. Our dup is
 * then the last fd: closing it drains kernel references even if reaping stalls.
 * Never free URBs/buffers before that close, including on partial submission. */
static void destroy(struct capture *c) {
    for (int i = 0; i < QUEUE; ++i) {
        if (c->active[i]) (void)ioctl(c->fd, USBDEVFS_DISCARDURB, c->urbs[i]);
    }
    int64_t start = now_ms();
    for (int attempts = 0; attempts < 128; ++attempts) {
        int outstanding = 0;
        for (int i = 0; i < QUEUE; ++i) outstanding += c->active[i];
        if (!outstanding) break;
        int64_t now = now_ms();
        if (start < 0 || now < 0 || now - start >= 100) break;
        struct usbdevfs_urb *u = NULL;
        if (ioctl(c->fd, USBDEVFS_REAPURBNDELAY, &u) == 0) {
            int i = urb_index(c, u);
            if (i < 0 || !c->active[i]) break;
            c->active[i] = 0;
        } else {
            if (errno != EAGAIN && errno != EINTR) break;
            (void)usleep(1000);
        }
    }
    /* On Android/Linux close releases the fd even on EINTR; do not retry it. */
    if (c->fd >= 0) (void)close(c->fd);
    for (int i = 0; i < QUEUE; ++i) {
        free(c->urbs[i]);
        free(c->buffers[i]);
    }
    free(c);
}

JNIEXPORT jlong JNICALL Java_org_futo_voiceinput_tv_UsbAudio_nativeOpen(
        JNIEnv *env, jclass cls, jint fd, jint endpoint, jint packet_size) {
    (void)env;
    (void)cls;
    /* This receiver is fixed mono PCM16LE / 16 kHz: one 32-byte frame/ms. */
    if (fd < 0 || endpoint != 0x81 || packet_size != PACKET_BYTES) return 0;
    struct capture *c = calloc(1, sizeof(*c));
    if (!c) return 0;
    c->fd = dup(fd);
    if (c->fd < 0) { free(c); return 0; }
    if (fcntl(c->fd, F_SETFD, FD_CLOEXEC) < 0) { destroy(c); return 0; }
    for (int i = 0; i < QUEUE; ++i) {
        c->urbs[i] = calloc(1, sizeof(*c->urbs[i]) +
                PACKETS * sizeof(c->urbs[i]->iso_frame_desc[0]));
        c->buffers[i] = malloc(URB_BYTES);
        if (!c->urbs[i] || !c->buffers[i]) { destroy(c); return 0; }
    }
    return (jlong)(uintptr_t)c;
}

static int copy_pending(JNIEnv *env, struct capture *c, jshortArray samples,
                        jsize capacity) {
    int count = c->pending_count - c->pending_offset;
    if (count > capacity) count = capacity;
    (*env)->SetShortArrayRegion(env, samples, 0, count,
                              c->pending + c->pending_offset);
    if ((*env)->ExceptionCheck(env)) return -EFAULT;
    c->pending_offset += count;
    return count;
}

JNIEXPORT jint JNICALL Java_org_futo_voiceinput_tv_UsbAudio_nativeRead(
        JNIEnv *env, jclass cls, jlong handle, jshortArray samples, jboolean blocking) {
    (void)cls;
    if (!handle || !samples) return -EINVAL;
    jsize capacity = (*env)->GetArrayLength(env, samples);
    if (capacity <= 0) return -EINVAL;
    struct capture *c = (struct capture *)(uintptr_t)handle;
    if (c->error) return c->error;
    if (c->pending_offset < c->pending_count)
        return copy_pending(env, c, samples, capacity);

    int64_t start = now_ms();
    if (start < 0) return c->error = -EIO;
    if (!c->started) {
        c->started = 1;
        for (int i = 0; i < QUEUE; ++i) {
            int result = submit(c, i);
            if (result < 0) return c->error = result;
        }
    }
    /* All reaps are non-delay, even in blocking mode. A finite immediate scan
     * also bounds nonblocking reads when the device supplies empty packets. */
    for (int attempts = 0; ; ++attempts) {
        int64_t now = now_ms();
        if (now < 0) return c->error = -EIO;
        if (now - start >= 500) return c->error = -ETIMEDOUT;
        if (!blocking && attempts >= QUEUE) return 0;
        struct usbdevfs_urb *u = NULL;
        if (ioctl(c->fd, USBDEVFS_REAPURBNDELAY, &u) < 0) {
            int error = errno;
            if (error != EAGAIN && error != EINTR) return c->error = -error;
            if (!blocking) return error == EAGAIN ? 0 : -EINTR;
            (void)usleep(1000);
            continue;
        }
        int i = urb_index(c, u);
        if (i < 0 || !c->active[i]) return c->error = -EIO;
        c->active[i] = 0;
        if (u->status) return c->error = u->status < 0 ? u->status : -EIO;
        if (u->number_of_packets != PACKETS || u->buffer_length != URB_BYTES ||
                u->actual_length < 0 || u->actual_length > URB_BYTES)
            return c->error = -EPROTO;
        c->pending_count = 0;
        c->pending_offset = 0;
        for (int j = 0; j < PACKETS; ++j) {
            const struct usbdevfs_iso_packet_desc *p = &u->iso_frame_desc[j];
            if (p->status) return c->error = -EIO;
            if (p->length != PACKET_BYTES || p->actual_length > PACKET_BYTES ||
                    (p->actual_length & 1)) return c->error = -EPROTO;
            const unsigned char *bytes = c->buffers[i] + j * PACKET_BYTES;
            for (unsigned int k = 0; k < p->actual_length; k += 2) {
                unsigned int value = bytes[k] | ((unsigned int)bytes[k + 1] << 8);
                c->pending[c->pending_count++] = (jshort)(value >= 0x8000 ?
                        (int)value - 0x10000 : (int)value);
            }
        }
        int result = submit(c, i);
        if (result < 0) return c->error = result;
        if (c->pending_count) return copy_pending(env, c, samples, capacity);
    }
}

JNIEXPORT void JNICALL Java_org_futo_voiceinput_tv_UsbAudio_nativeClose(
        JNIEnv *env, jclass cls, jlong handle) {
    (void)env;
    (void)cls;
    if (handle) destroy((struct capture *)(uintptr_t)handle);
}
