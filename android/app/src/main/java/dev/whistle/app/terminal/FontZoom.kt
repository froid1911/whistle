package dev.whistle.app.terminal

import kotlin.math.roundToInt

/**
 * Pinch-zoom math for the terminal: a pinch changes xterm's fontSize (the terminal stays at the
 * pane's cols×rows) instead of scaling the rendered page. Sizes are clamped to [MIN]..[MAX] px and
 * snapped to [STEP] px so a pinch re-lays out the terminal only when the size really changes.
 */
object FontZoom {
    const val MIN = 6f
    const val MAX = 20f
    const val STEP = 0.5f

    /** Clamps [px] to [MIN]..[MAX] and snaps it to [STEP]; NaN/infinite fall back to [fallback]. */
    fun clamp(px: Float, fallback: Float = 13f): Float {
        val v = if (px.isFinite()) px else fallback
        val snapped = (v / STEP).roundToInt() * STEP
        return snapped.coerceIn(MIN, MAX)
    }

    /** The font size for a pinch that started at [base] px and has scaled by [scale] in total. */
    fun scaled(base: Float, scale: Float): Float =
        clamp(base * (if (scale.isFinite() && scale > 0f) scale else 1f), base)
}

/** Font sizes the user chose per pane (process lifetime), keyed by machine and pane. */
object PaneFontSizes {
    private val sizes = HashMap<String, Float>()

    @Synchronized fun get(machineId: String, paneId: String): Float? = sizes["$machineId\u0000$paneId"]

    @Synchronized fun put(machineId: String, paneId: String, px: Float) {
        sizes["$machineId\u0000$paneId"] = FontZoom.clamp(px)
    }

    @Synchronized internal fun clear() = sizes.clear()
}
