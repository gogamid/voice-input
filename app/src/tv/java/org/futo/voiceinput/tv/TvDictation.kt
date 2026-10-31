package org.futo.voiceinput.tv

import android.content.Context
import android.os.SystemClock
import com.konovalov.vad.Vad
import com.konovalov.vad.config.FrameSize
import com.konovalov.vad.config.Mode
import com.konovalov.vad.config.Model
import com.konovalov.vad.config.SampleRate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.withContext
import org.futo.voiceinput.CrashLoggingApplication
import org.futo.voiceinput.loadRecognizerSettings
import org.futo.voiceinput.shared.types.InferenceState
import org.futo.voiceinput.shared.types.Language
import org.futo.voiceinput.shared.types.ModelInferenceCallback
import org.futo.voiceinput.shared.whisper.MultiModelRunner
import org.futo.voiceinput.shared.whisper.isBlankResult
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** One foreground dictation. Audio exists only in bounded, in-memory buffers. */
class TvDictation(private val context: Context) {
    private val finish = AtomicBoolean(false)
    private var runner: MultiModelRunner? = null

    fun finishRecording() { finish.set(true) }
    fun cancel() { finish.set(true); runner?.cancelAll() }

    suspend fun run(status: (String) -> Unit): String = withContext(Dispatchers.IO) {
        val settings = context.loadRecognizerSettings()
        val models = listOf(settings.modelRunConfiguration.primaryModel) + settings.modelRunConfiguration.languageSpecificModels.values
        if (models.any { !it.exists(context) }) throw IOException("Model missing. Open Voice Input TV setup and download the model.")
        UsbAudio.checkPrivacy(context)
        status("Loading offline model…")
        val modelRunner = MultiModelRunner(CrashLoggingApplication.getModelManager(context)!!)
        runner = modelRunner
        modelRunner.preload(settings.modelRunConfiguration)
        currentCoroutineContext().ensureActive()
        if (finish.get()) return@withContext ""

        val chunks = ArrayList<FloatArray>()
        var speechSeen = false
        val start = SystemClock.elapsedRealtime()
        var lastSpeech = start
        var lastPrivacyCheck = start
        val frame = ShortArray(480)
        var framePosition = 0
        val samples = ShortArray(1600)
        UsbAudio.open(context).use { input ->
            Vad.builder().setModel(Model.WEB_RTC_GMM).setMode(Mode.VERY_AGGRESSIVE)
                .setFrameSize(FrameSize.FRAME_SIZE_480).setSampleRate(SampleRate.SAMPLE_RATE_16K)
                .setSpeechDurationMs(150).setSilenceDurationMs(300).build().use { vad ->
                    status("Listening · USB remote only\nSpeak near the remote. Waveform again: finish · Back: cancel")
                    while (!finish.get()) {
                        currentCoroutineContext().ensureActive()
                        val now = SystemClock.elapsedRealtime()
                        if (now - start >= 20_000 || (!speechSeen && now - start >= 8_000)
                            || (speechSeen && now - lastSpeech >= 2_000)) break
                        if (now - lastPrivacyCheck >= 500) {
                            UsbAudio.checkPrivacy(context)
                            lastPrivacyCheck = now
                        }
                        val count = input.read(samples)
                        chunks.add(FloatArray(count) { samples[it] / 32768.0f })
                        for (i in 0 until count) {
                            frame[framePosition++] = samples[i]
                            if (framePosition == frame.size) {
                                if (vad.isSpeech(frame)) { speechSeen = true; lastSpeech = SystemClock.elapsedRealtime() }
                                framePosition = 0
                            }
                        }
                    }
                }
        }
        currentCoroutineContext().ensureActive()
        if (!speechSeen) return@withContext ""
        status("Transcribing offline…\nThe USB microphone is now closed.")
        val result = modelRunner.run(chunks.asFlow(), settings.modelRunConfiguration, settings.decodingConfiguration,
            object : ModelInferenceCallback {
                override fun updateStatus(state: InferenceState) { }
                override fun languageDetected(language: Language) { }
                override fun partialResult(string: String) { }
            })
        if (isBlankResult(result)) "" else result.trim()
    }
}
