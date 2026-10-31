package org.futo.voiceinput.tv

import android.app.assist.AssistContent
import android.app.assist.AssistStructure
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.widget.Toast

/** Native assistant entry point only. No hotword detector or background audio. */
class TvVoiceService : VoiceInteractionService()

class TvVoiceSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = TvVoiceSession(this)
}

private class TvVoiceSession(context: Context) : VoiceInteractionSession(context) {
    private val handler = Handler(Looper.getMainLooper())
    private var awaitingEditor = false
    private val noEditor = Runnable {
        if (awaitingEditor) {
            awaitingEditor = false
            Toast.makeText(context, "Open a text field and its keyboard first.", Toast.LENGTH_SHORT).show()
            hide()
        }
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        setUiEnabled(false)
        val input = TvInputMethodService.active
        if (input != null && input.isInputViewShown) {
            input.finishRecording()
            hide()
            return
        }
        awaitingEditor = true
        handler.removeCallbacks(noEditor)
        handler.postDelayed(noEditor, 1500)
    }

    private fun editable(node: AssistStructure.ViewNode): Boolean {
        if (node.isFocused && node.inputType != 0 && !TvInputMethodService.privateField(node.inputType)) return true
        for (i in 0 until node.childCount) if (editable(node.getChildAt(i))) return true
        return false
    }

    override fun onHandleAssist(data: Bundle?, structure: AssistStructure?, content: AssistContent?) {
        if (!awaitingEditor) return
        val hasEditor = structure != null && (0 until structure.windowNodeCount).any {
            editable(structure.getWindowNodeAt(it).rootViewNode)
        }
        if (!hasEditor) { noEditor.run(); return }
        awaitingEditor = false
        handler.removeCallbacks(noEditor)
        try { TvShortcut.start(context) }
        catch (e: Exception) { Toast.makeText(context, e.message, Toast.LENGTH_LONG).show() }
        hide()
    }

    override fun onHide() { awaitingEditor = false; handler.removeCallbacks(noEditor); super.onHide() }
    override fun onDestroy() { handler.removeCallbacks(noEditor); super.onDestroy() }
}

/** VoiceInteractionService metadata requires this service. No implicit recording. */
class TvRecognitionService : RecognitionService() {
    override fun onStartListening(intent: Intent, listener: Callback) { listener.error(SpeechRecognizer.ERROR_CLIENT) }
    override fun onStopListening(listener: Callback) { }
    override fun onCancel(listener: Callback) { }
}
