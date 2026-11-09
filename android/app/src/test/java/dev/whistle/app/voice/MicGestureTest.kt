package dev.whistle.app.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class MicGestureTest {
    private val g = MicGesture(lockPx = 100f)

    @Test fun quickTapOnlyHints() {
        g.down(0)
        assertEquals(emptyList<MicEffect>(), g.hold(100))
        assertEquals(listOf(MicEffect.TapHint), g.up(200))
        assertEquals(MicPhase.Idle, g.phase.value)
    }

    @Test fun holdStartsAfterThresholdAndReleaseFinishes() {
        g.down(0)
        assertEquals(emptyList<MicEffect>(), g.hold(249))
        assertEquals(listOf(MicEffect.Haptic, MicEffect.Start), g.hold(250))
        assertEquals(MicPhase.Recording, g.phase.value)
        assertEquals(emptyList<MicEffect>(), g.drag(-50f, -20f, 900f)) // small wobble
        assertEquals(listOf(MicEffect.Finish), g.up(2_000))
        assertEquals(MicPhase.Idle, g.phase.value)
    }

    @Test fun slideLeftPastAThirdCancels() {
        g.down(0); g.hold(300)
        assertEquals(emptyList<MicEffect>(), g.drag(-299f, 0f, 900f))
        assertEquals(listOf(MicEffect.Haptic, MicEffect.Cancel), g.drag(-301f, 0f, 900f))
        assertEquals(emptyList<MicEffect>(), g.up(1_000)) // nothing left to finish
    }

    @Test fun slideUpLocksAndStopFinishes() {
        g.down(0); g.hold(300)
        assertEquals(listOf(MicEffect.Haptic), g.drag(0f, -101f, 900f))
        assertEquals(MicPhase.Locked, g.phase.value)
        assertEquals(emptyList<MicEffect>(), g.up(1_000)) // keeps recording hands-free
        assertEquals(MicPhase.Locked, g.phase.value)
        assertEquals(emptyList<MicEffect>(), g.drag(-800f, 0f, 900f)) // no cancel by slide once locked
        assertEquals(listOf(MicEffect.Finish), g.stop())
        assertEquals(MicPhase.Idle, g.phase.value)
    }

    @Test fun lockedMicTapFinishesAndCancelButtonCancels() {
        g.down(0); g.hold(300); g.drag(0f, -150f, 900f); g.up(500)
        assertEquals(listOf(MicEffect.Finish), g.down(900))
        g.up(950)
        g.down(2_000); g.hold(2_300); g.drag(0f, -150f, 900f)
        assertEquals(listOf(MicEffect.Cancel), g.cancel())
        assertEquals(MicPhase.Idle, g.phase.value)
    }

    @Test fun quietErrors() {
        assertEquals(true, isQuietSpeechError(SpeechErrorKind.NoSpeech, 10_000))
        assertEquals(true, isQuietSpeechError(SpeechErrorKind.Client, 300))
        assertEquals(false, isQuietSpeechError(SpeechErrorKind.Client, SHORT_HOLD_MS + 1))
        assertEquals(false, isQuietSpeechError(SpeechErrorKind.Real, 100))
    }
}
