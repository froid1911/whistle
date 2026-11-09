package dev.whistle.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * "Night pasture" palette (spec 2026-11-09). Dark is the primary theme; [LightWhistle] follows the
 * system setting. [lamp] is the only saturated warm color and means exactly one thing: needs you.
 */
@Immutable
data class WhistleColors(
    val isDark: Boolean,
    /** App background. */
    val night: Color,
    /** Cards, composer, sheets. */
    val card: Color,
    /** Your own messages, pressed/selected surfaces. */
    val cardHi: Color,
    /** Terminal strips, code. */
    val inset: Color,
    /** Text on [inset]. */
    val insetText: Color,
    /** Primary text. */
    val wool: Color,
    /** Secondary text. */
    val sage: Color,
    /** Hairline outlines (sparingly). */
    val line: Color,
    /** Needs you (blocked / approval). */
    val lamp: Color,
    val onLamp: Color,
    /** Working. */
    val sky: Color,
    /** Done (finished, not yet looked at). */
    val heather: Color,
    /** Idle / unknown. */
    val moss: Color,
    /** Errors (failed sends, git errors). */
    val ember: Color,
    /** Glyph color on a status-colored orb. */
    val onStatus: Color,
    val diffAddBg: Color,
    val diffAddFg: Color,
    val diffDelBg: Color,
    val diffDelFg: Color,
)

val DarkWhistle = WhistleColors(
    isDark = true,
    night = Color(0xFF161D1A),
    card = Color(0xFF1F2924),
    cardHi = Color(0xFF2C3A33),
    inset = Color(0xFF0F1412),
    insetText = Color(0xFFBFD3C6),
    wool = Color(0xFFE8E3D6),
    sage = Color(0xFF93A39A),
    line = Color(0xFF3A4741),
    lamp = Color(0xFFF5A524),
    onLamp = Color(0xFF1B1206),
    sky = Color(0xFF8CC4E8),
    heather = Color(0xFFB9A6E8),
    moss = Color(0xFF5F7268),
    ember = Color(0xFFE5684C),
    onStatus = Color(0xFF161D1A),
    diffAddBg = Color(0xFF2E4A36),
    diffAddFg = Color(0xFF9FD8A8),
    diffDelBg = Color(0xFF4A2A2A),
    diffDelFg = Color(0xFFF0A8A0),
)

/** Light variant: mist background (not the cream cliché), status colors darkened for ≥ 4.5:1. */
val LightWhistle = WhistleColors(
    isDark = false,
    night = Color(0xFFEEF2EF),
    card = Color(0xFFFFFFFF),
    cardHi = Color(0xFFDCE5DF),
    inset = Color(0xFF1E2622),
    insetText = Color(0xFFCFDCD4),
    wool = Color(0xFF1E2622),
    sage = Color(0xFF5B6B62),
    line = Color(0xFFC9D3CD),
    lamp = Color(0xFFB86E00),
    onLamp = Color(0xFF1B1206),
    sky = Color(0xFF2B6E99),
    heather = Color(0xFF6A55B0),
    moss = Color(0xFF5F7268),
    ember = Color(0xFFB33A22),
    onStatus = Color(0xFFFFFFFF),
    diffAddBg = Color(0xFFD8EED9),
    diffAddFg = Color(0xFF1F5A2A),
    diffDelBg = Color(0xFFF6DAD5),
    diffDelFg = Color(0xFF8A2516),
)

/** Defaults to the dark palette so un-themed previews and tests still use brand colors. */
val LocalWhistleColors = staticCompositionLocalOf { DarkWhistle }
