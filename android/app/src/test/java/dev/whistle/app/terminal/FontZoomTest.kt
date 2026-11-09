package dev.whistle.app.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FontZoomTest {
    @Test fun clampsToRange() {
        assertEquals(6f, FontZoom.clamp(2f))
        assertEquals(20f, FontZoom.clamp(64f))
        assertEquals(13f, FontZoom.clamp(13f))
    }

    @Test fun snapsToHalfPixels() {
        assertEquals(12.5f, FontZoom.clamp(12.4f))
        assertEquals(12f, FontZoom.clamp(12.2f))
    }

    @Test fun nonFiniteFallsBack() {
        assertEquals(10f, FontZoom.clamp(Float.NaN, 10f))
        assertEquals(10f, FontZoom.clamp(Float.POSITIVE_INFINITY, 10f))
    }

    @Test fun scalesFromPinchBase() {
        assertEquals(20f, FontZoom.scaled(10f, 2f))
        assertEquals(6f, FontZoom.scaled(10f, 0.1f))
        assertEquals(15f, FontZoom.scaled(10f, 1.5f))
        assertEquals(10f, FontZoom.scaled(10f, 0f)) // degenerate scale keeps the size
        assertEquals(10f, FontZoom.scaled(10f, Float.NaN))
    }

    @Test fun storesPerPane() {
        PaneFontSizes.clear()
        PaneFontSizes.put("m", "w1:p1", 30f)
        assertEquals(20f, PaneFontSizes.get("m", "w1:p1"))
        assertNull(PaneFontSizes.get("m", "w1:p2"))
        assertNull(PaneFontSizes.get("other", "w1:p1"))
    }
}
