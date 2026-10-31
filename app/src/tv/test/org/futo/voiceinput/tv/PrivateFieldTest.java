package org.futo.voiceinput.tv;

import android.text.InputType;
import org.junit.Test;
import static org.junit.Assert.*;

public class PrivateFieldTest {
    @Test public void rejectsPasswordsWithoutRejectingOrdinaryTextOrNumbers() {
        int[] passwordTypes = {
            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD
        };
        for (int type : passwordTypes) {
            assertTrue(TvInputMethodService.Companion.privateField(type));
            assertTrue(TvInputMethodService.Companion.privateField(type | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS));
        }
        assertFalse(TvInputMethodService.Companion.privateField(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES));
        assertFalse(TvInputMethodService.Companion.privateField(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS));
        assertFalse(TvInputMethodService.Companion.privateField(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL));
    }
}
