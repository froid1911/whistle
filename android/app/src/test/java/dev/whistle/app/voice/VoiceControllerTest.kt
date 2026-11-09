package dev.whistle.app.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceControllerTest {
    private class Fake(override var available: Boolean = true) : SpeechEngine {
        var listener: SpeechEngine.Listener? = null
        var stops = 0
        var cancels = 0
        override fun start(listener: SpeechEngine.Listener) { this.listener = listener }
        override fun stop() { stops++ }
        override fun cancel() { cancels++ }
        override fun destroy() = Unit
    }

    private val fake = Fake()
    private var text = ""
    private var clock = 0L
    private val c = VoiceController(fake, { text = it }, now = { clock })

    @Test fun partialsStreamAndFinalReplacesPartial() {
        c.begin("hello")
        assertEquals(VoiceState.Listening, c.state.value)
        fake.listener!!.onPartial("wor")
        assertEquals("hello wor", text)
        fake.listener!!.onPartial("world")
        assertEquals("hello world", text)
        c.end()
        assertEquals(1, fake.stops)
        assertEquals(VoiceState.Finishing, c.state.value)
        fake.listener!!.onFinal("World!")
        assertEquals("hello World!", text)
        assertEquals(VoiceState.Idle, c.state.value)
    }

    @Test fun emptyBaseHasNoLeadingSpace() {
        c.begin("")
        fake.listener!!.onPartial("hi")
        assertEquals("hi", text)
    }

    @Test fun noRecognizerGivesMessageAndNeverStarts() {
        fake.available = false
        c.begin("x")
        assertTrue(c.state.value is VoiceState.Unavailable)
        assertEquals(null, fake.listener)
    }

    @Test fun errorDropsPartialAndReports() {
        c.begin("a")
        fake.listener!!.onPartial("b")
        fake.listener!!.onError("network", SpeechErrorKind.Real)
        assertEquals("a", text)
        assertEquals(VoiceState.Failed("network"), c.state.value)
    }

    @Test fun noSpeechIsSilent() {
        c.begin("a")
        fake.listener!!.onError("none", SpeechErrorKind.NoSpeech)
        assertEquals(VoiceState.Idle, c.state.value)
    }

    @Test fun cancelRestoresText() {
        c.begin("a")
        fake.listener!!.onPartial("b")
        c.cancel()
        assertEquals("a", text)
        assertEquals(1, fake.cancels)
        fake.listener!!.onPartial("late") // ignored
        assertEquals("a", text)
    }

    @Test fun clientErrorAfterShortHoldIsSilentButLaterReports() {
        c.begin("a")
        clock = 300
        fake.listener!!.onError("client", SpeechErrorKind.Client)
        assertEquals(VoiceState.Idle, c.state.value)
        c.begin("a")
        clock += SHORT_HOLD_MS + 1
        fake.listener!!.onError("client", SpeechErrorKind.Client)
        assertEquals(VoiceState.Failed("client"), c.state.value)
    }

    @Test fun partialIsExposedAndClearedOnFinal() {
        c.begin("")
        fake.listener!!.onPartial("hel")
        assertEquals("hel", c.partial.value)
        c.end()
        fake.listener!!.onFinal("hello")
        assertEquals("", c.partial.value)
        assertEquals("hello", text)
    }
}
