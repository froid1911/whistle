package dev.whistle.app.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Speech recognition behind an interface so the state logic needs no Android speech service. */
interface SpeechEngine {
    interface Listener {
        fun onPartial(text: String)
        fun onFinal(text: String)

        /** [kind]: whether this is "nothing heard", a client-side abort, or a real failure. */
        fun onError(message: String, kind: SpeechErrorKind)
    }

    val available: Boolean

    /** Starts listening (system default language); results go to [listener]. */
    fun start(listener: Listener)

    /** The user let go: finish and deliver the final result. */
    fun stop()

    /** Abort without a result. */
    fun cancel()

    fun destroy()
}

sealed interface VoiceState {
    data object Idle : VoiceState
    data object Listening : VoiceState

    /** Finger lifted; waiting for the recognizer's final result. */
    data object Finishing : VoiceState
    data class Unavailable(val message: String) : VoiceState
    data class Failed(val message: String) : VoiceState
}

const val NO_RECOGNIZER_MESSAGE = "Speech recognition isn't available on this device."

/**
 * Hold-to-talk logic. [begin] with the composer's current text; partial results are written
 * through [onText] as base + partial, the final result replaces the partial and becomes the new
 * base. Never sends anything.
 */
class VoiceController(
    private val engine: SpeechEngine,
    private val onText: (String) -> Unit,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val _state = MutableStateFlow<VoiceState>(VoiceState.Idle)
    val state: StateFlow<VoiceState> = _state.asStateFlow()
    private val _partial = MutableStateFlow("")

    /** What has been heard in this recording so far (for the recording bar). */
    val partial: StateFlow<String> = _partial.asStateFlow()
    private var base = ""
    private var beganAt = 0L

    private val active get() = _state.value == VoiceState.Listening || _state.value == VoiceState.Finishing

    fun begin(currentText: String) {
        if (active) return
        if (!engine.available) {
            _state.value = VoiceState.Unavailable(NO_RECOGNIZER_MESSAGE)
            return
        }
        base = currentText
        beganAt = now()
        _partial.value = ""
        _state.value = VoiceState.Listening
        engine.start(object : SpeechEngine.Listener {
            override fun onPartial(text: String) {
                if (!active) return
                _partial.value = text
                onText(join(base, text))
            }

            override fun onFinal(text: String) {
                if (!active) return
                base = join(base, text)
                onText(base)
                _partial.value = ""
                _state.value = VoiceState.Idle
            }

            override fun onError(message: String, kind: SpeechErrorKind) {
                if (!active) return
                onText(base) // drop the partial
                _partial.value = ""
                _state.value = if (isQuietSpeechError(kind, now() - beganAt)) VoiceState.Idle else VoiceState.Failed(message)
            }
        })
    }

    /** Finger lifted: the engine delivers the final result (state stays Listening until then). */
    fun end() {
        if (_state.value == VoiceState.Listening) {
            _state.value = VoiceState.Finishing
            engine.stop()
        }
    }

    /** Page left or permission lost: abort, keeping the text as it was before the partial. */
    fun cancel() {
        if (active) {
            engine.cancel()
            onText(base)
        }
        _partial.value = ""
        _state.value = VoiceState.Idle
    }

    fun consumeMessage() {
        if (_state.value is VoiceState.Failed || _state.value is VoiceState.Unavailable) _state.value = VoiceState.Idle
    }

    fun destroy() {
        engine.destroy()
    }

    companion object {
        fun join(base: String, spoken: String): String {
            if (spoken.isBlank()) return base
            return if (base.isEmpty() || base.last().isWhitespace()) base + spoken else "$base $spoken"
        }
    }
}

/** [SpeechRecognizer] backed engine; create and use on the main thread. */
class AndroidSpeechEngine(private val context: Context) : SpeechEngine {
    private var recognizer: SpeechRecognizer? = null

    override val available: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    override fun start(listener: SpeechEngine.Listener) {
        recognizer?.destroy()
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onPartialResults(partialResults: Bundle?) {
                first(partialResults)?.let(listener::onPartial)
            }

            override fun onResults(results: Bundle?) {
                listener.onFinal(first(results).orEmpty())
            }

            override fun onError(error: Int) {
                val kind = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> SpeechErrorKind.NoSpeech
                    SpeechRecognizer.ERROR_CLIENT -> SpeechErrorKind.Client
                    else -> SpeechErrorKind.Real
                }
                listener.onError(errorText(error), kind)
            }

            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        r.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true),
        ) // no EXTRA_LANGUAGE: the system default
    }

    override fun stop() {
        recognizer?.stopListening()
    }

    override fun cancel() {
        recognizer?.cancel()
    }

    override fun destroy() {
        recognizer?.destroy()
        recognizer = null
    }

    private fun first(b: Bundle?) = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    private fun errorText(code: Int) = when (code) {
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech recognition needs a network connection."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is missing."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognizer is busy, try again."
        else -> "Speech recognition failed (code $code)."
    }
}
