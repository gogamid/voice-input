package org.futo.voiceinput.tv;

import org.junit.Test;
import static org.junit.Assert.*;

public class UsbAudioTest {
    private byte[] descriptor() {
        return new byte[] {9,4,1,1,1,1,2,0,0, 11,36,2,1,1,2,16,1,(byte)0x80,0x3e,0};
    }

    @Test public void acceptsOnlyTheTestedPcmFormatOnTheStreamingAlternate() {
        assertTrue(UsbAudio.supportsFormat(descriptor()));
        int[] positions = {2,3,5,6,12,13,14,15,16,17,18,19};
        for (int position : positions) {
            byte[] changed = descriptor();
            changed[position]++;
            assertFalse("Changed descriptor byte " + position, UsbAudio.supportsFormat(changed));
        }
    }

    @Test public void refusesTruncatedAndMalformedUsbDescriptors() {
        byte[] valid = descriptor();
        for (int length = 0; length < valid.length; length++) {
            byte[] truncated = java.util.Arrays.copyOf(valid, length);
            assertFalse("Truncated at " + length, UsbAudio.supportsFormat(truncated));
        }
        assertFalse(UsbAudio.supportsFormat(new byte[] {0,4}));
        assertFalse(UsbAudio.supportsFormat(new byte[] {127,4,1}));
    }
}
