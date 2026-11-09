package dev.whistle.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HoldTimerTest {
    @Test
    fun firesOnceWhenHeldForTheFullTime() {
        val t = HoldTimer(600)
        t.press(1_000)
        assertEquals(0f, t.progress(1_000))
        assertEquals(0.5f, t.progress(1_300))
        assertFalse(t.tick(1_599))
        assertTrue(t.tick(1_600)) // completion fires while still pressed
        assertFalse(t.tick(1_700)) // once
        assertEquals(HoldResult.Completed, t.release())
    }

    @Test
    fun quickTapIsTooShort() {
        val t = HoldTimer(600)
        t.press(0)
        assertFalse(t.tick(100))
        assertEquals(HoldResult.TooShort, t.release())
        assertEquals(0f, t.progress(10_000))
        assertFalse(t.tick(10_000)) // a released press never fires later
    }

    @Test
    fun releaseBeforeTheFirstFrameIsTooShortEvenAfterACompletedHold() {
        val t = HoldTimer(600)
        t.press(0)
        assertTrue(t.tick(600))
        assertEquals(HoldResult.Completed, t.release())
        assertEquals(HoldResult.TooShort, t.release()) // next tap, released before press() ran
    }

    @Test
    fun cancelledPressNeverFires() {
        val t = HoldTimer(600)
        t.press(0)
        t.cancel() // turned into a scroll
        assertFalse(t.tick(1_000))
        assertEquals(1f, HoldTimer(600).also { it.press(0) }.progress(5_000))
        assertEquals(600L, HoldTimer.HOLD_MS)
    }
}
