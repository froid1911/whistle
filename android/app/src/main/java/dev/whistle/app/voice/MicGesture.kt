package dev.whistle.app.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Why the recognizer stopped without a result. */
enum class SpeechErrorKind {
    /** Nothing recognised / speech timeout: not a failure. */
    NoSpeech,

    /** Client-side abort (typically stop right after start). */
    Client,

    /** Network, permission, busy, …: worth telling the user. */
    Real,
}

/** A client error within this long after the recognizer started is the "stopped too soon" case. */
const val SHORT_HOLD_MS = 1_500L

/** Errors that go back to idle silently: "nothing heard" always, a client abort after a short hold. */
fun isQuietSpeechError(kind: SpeechErrorKind, heldMs: Long): Boolean = when (kind) {
    SpeechErrorKind.NoSpeech -> true
    SpeechErrorKind.Client -> heldMs < SHORT_HOLD_MS
    SpeechErrorKind.Real -> false
}

enum class MicPhase {
    Idle,

    /** Finger down, hold threshold not reached yet (a tap so far). */
    Pressed,

    /** Held: the recognizer runs while the finger stays down. */
    Recording,

    /** Slid up: recording continues hands-free until Stop or Cancel. */
    Locked,
}

/** What the composer has to do in response to a gesture step. */
enum class MicEffect { Start, Finish, Cancel, TapHint, Haptic }

/**
 * Hold-to-talk gesture, WhatsApp style but for dictation (never sends): a tap only hints, a hold
 * past [holdMs] starts recording, release finishes into the draft, sliding left past a third of
 * the bar cancels, sliding up past [lockPx] locks. Pure: times and offsets come from the caller.
 */
class MicGesture(
    private val lockPx: Float,
    private val holdMs: Long = HOLD_MS,
    private val cancelFraction: Float = 1f / 3f,
) {
    private val _phase = MutableStateFlow(MicPhase.Idle)
    val phase: StateFlow<MicPhase> = _phase.asStateFlow()
    private var downAt = 0L

    /** Finger down on the mic. In locked mode the mic is the Stop button. */
    fun down(now: Long): List<MicEffect> = when (_phase.value) {
        MicPhase.Locked -> {
            _phase.value = MicPhase.Idle
            listOf(MicEffect.Finish)
        }
        MicPhase.Idle -> {
            downAt = now
            _phase.value = MicPhase.Pressed
            emptyList()
        }
        else -> emptyList()
    }

    /** Called while still pressed; starts recording once the hold threshold is reached. */
    fun hold(now: Long): List<MicEffect> {
        if (_phase.value != MicPhase.Pressed || now - downAt < holdMs) return emptyList()
        _phase.value = MicPhase.Recording
        return listOf(MicEffect.Haptic, MicEffect.Start)
    }

    /** Finger moved by ([dx], [dy]) from where it went down; [barWidth] is the composer bar's width. */
    fun drag(dx: Float, dy: Float, barWidth: Float): List<MicEffect> {
        if (_phase.value != MicPhase.Recording) return emptyList()
        return when {
            -dx > barWidth * cancelFraction -> {
                _phase.value = MicPhase.Idle
                listOf(MicEffect.Haptic, MicEffect.Cancel)
            }
            -dy > lockPx -> {
                _phase.value = MicPhase.Locked
                listOf(MicEffect.Haptic)
            }
            else -> emptyList()
        }
    }

    fun up(now: Long): List<MicEffect> = when (_phase.value) {
        MicPhase.Pressed -> {
            _phase.value = MicPhase.Idle
            if (now - downAt < holdMs) listOf(MicEffect.TapHint) else emptyList()
        }
        MicPhase.Recording -> {
            _phase.value = MicPhase.Idle
            listOf(MicEffect.Finish)
        }
        else -> emptyList() // locked keeps recording; idle (cancelled) has nothing to do
    }

    /** Stop button of the locked bar. */
    fun stop(): List<MicEffect> = if (_phase.value == MicPhase.Locked) {
        _phase.value = MicPhase.Idle
        listOf(MicEffect.Finish)
    } else emptyList()

    /** Cancel button of the locked bar. */
    fun cancel(): List<MicEffect> = when (_phase.value) {
        MicPhase.Recording, MicPhase.Locked -> {
            _phase.value = MicPhase.Idle
            listOf(MicEffect.Cancel)
        }
        else -> emptyList()
    }

    /** The recognizer ended on its own (error, unavailable): forget the gesture. */
    fun reset() {
        _phase.value = MicPhase.Idle
    }

    companion object {
        const val HOLD_MS = 250L
    }
}
