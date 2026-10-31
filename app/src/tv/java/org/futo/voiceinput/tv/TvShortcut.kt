package org.futo.voiceinput.tv

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.inputmethod.InputMethodManager

/** Switches temporarily to the voice-only IME; Gboard remains the typing IME. */
object TvShortcut {
    fun imeId(context: Context) = "${context.packageName}/${TvInputMethodService::class.java.name}"

    fun start(context: Context) {
        check(context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED) {
            "Remote shortcut needs its one-time ADB setup."
        }
        val manager = context.getSystemService(InputMethodManager::class.java)
        val id = imeId(context)
        check(manager.enabledInputMethodList.any { it.id == id }) { "Enable Voice Input TV in input-method settings first." }
        val previous = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        if (previous != id && !previous.isNullOrBlank()) {
            context.getSharedPreferences("tv", Context.MODE_PRIVATE).edit().putString("typing_ime", previous).apply()
        }
        check(Settings.Secure.putString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD, id)) {
            "Cannot switch to voice input."
        }
        // No editor/keyboard appeared: do not leave a recording IME selected for
        // the next field the user opens later.
        Handler(Looper.getMainLooper()).postDelayed({
            if (TvInputMethodService.active == null) restoreSetting(context)
        }, 2000)
    }

    private fun typingIme(context: Context): String? {
        val id = context.getSharedPreferences("tv", Context.MODE_PRIVATE).getString("typing_ime", null)
        return id?.takeIf { candidate ->
            context.getSystemService(InputMethodManager::class.java).enabledInputMethodList.any { it.id == candidate }
        }
    }

    private fun restoreSetting(context: Context) {
        if (Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) != imeId(context)) return
        typingIme(context)?.let { Settings.Secure.putString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD, it) }
    }

    fun restoreKeyboard(service: InputMethodService) {
        if (Settings.Secure.getString(service.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) != imeId(service)) return
        val previous = typingIme(service)
        if (previous != null) service.switchInputMethod(previous)
        else service.switchToPreviousInputMethod()
    }
}
