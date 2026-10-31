package org.futo.voiceinput.tv

import android.graphics.Color
import android.inputmethodservice.InputMethodService
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.*

class TvInputMethodService : InputMethodService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var dictation: TvDictation? = null
    private var status: TextView? = null
    private var stop: Button? = null
    private var recording = false

    companion object {
        var active: TvInputMethodService? = null
            private set

        fun privateField(inputType: Int): Boolean {
            val kind = inputType and InputType.TYPE_MASK_CLASS
            val variation = inputType and InputType.TYPE_MASK_VARIATION
            return kind == InputType.TYPE_CLASS_TEXT && variation in setOf(
                InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
                || kind == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
    }

    override fun onCreateInputView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 20)
            setBackgroundColor(Color.rgb(20, 24, 30))
        }
        root.addView(TextView(this).apply { text = "Voice Input TV · unofficial FUTO nightly"; textSize = 23f; setTextColor(Color.WHITE) })
        status = TextView(this).apply { textSize = 20f; setTextColor(Color.WHITE); setPadding(0, 12, 0, 16) }
        root.addView(status)
        val buttons = LinearLayout(this)
        stop = Button(this).apply { text = "Finish and transcribe"; setOnClickListener { finishRecording() } }
        buttons.addView(stop, LinearLayout.LayoutParams(0, -2, 1f))
        buttons.addView(Button(this).apply { text = "Cancel"; setOnClickListener { cancelAndReturn() } }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(buttons)
        return root
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        if (job?.isActive == true) return
        active = this
        if (privateField(info.inputType) || info.inputType == InputType.TYPE_NULL) {
            status?.text = "Voice input is disabled for password or non-text fields."
            returnToKeyboard()
            return
        }
        val connection = currentInputConnection ?: run { returnToKeyboard(); return }
        val session = TvDictation(this)
        dictation = session
        recording = true
        stop?.isEnabled = true
        stop?.requestFocus()
        job = scope.launch {
            try {
                val result = session.run { message -> scope.launch { status?.text = message } }
                recording = false
                stop?.isEnabled = false
                if (result.isNotBlank() && currentInputConnection === connection) {
                    connection.commitText(result, 1)
                    returnToKeyboard()
                } else {
                    status?.text = "No speech detected. Back: return to keyboard."
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                recording = false
                stop?.isEnabled = false
                status?.text = error.message ?: "USB voice input failed."
            } finally {
                dictation = null
            }
        }
    }

    fun finishRecording() {
        if (recording) {
            recording = false
            dictation?.finishRecording()
            stop?.isEnabled = false
        }
    }

    private fun cancelAndReturn() {
        dictation?.cancel()
        job?.cancel()
        returnToKeyboard()
    }

    private fun returnToKeyboard() {
        TvShortcut.restoreKeyboard(this)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) { cancelAndReturn(); return true }
        if (keyCode == KeyEvent.KEYCODE_VOICE_ASSIST || keyCode == KeyEvent.KEYCODE_ASSIST) { finishRecording(); return true }
        return super.onKeyDown(keyCode, event)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        dictation?.cancel()
        job?.cancel()
        active = null
        returnToKeyboard()
        super.onFinishInputView(finishingInput)
    }

    override fun onEvaluateFullscreenMode() = false

    override fun onDestroy() {
        dictation?.cancel()
        scope.cancel()
        if (active === this) active = null
        super.onDestroy()
    }
}
