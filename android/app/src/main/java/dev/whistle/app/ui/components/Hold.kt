package dev.whistle.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.whistle.app.ui.theme.Whistle
import dev.whistle.app.ui.theme.WhistleShapes
import dev.whistle.app.ui.theme.WhistleType
import kotlinx.coroutines.launch

/** How a press on a hold-to-confirm control ended. */
enum class HoldResult {
    /** Held for the full time: the action ran (at completion, not at release). */
    Completed,
    /** Let go early: nothing happens; tell the user to hold. */
    TooShort,
}

/**
 * Press-and-hold logic, pure (times in ms from any monotonic clock, e.g. the frame clock).
 * [tick] returns true exactly once, when the hold reaches [holdMs] while still pressed.
 */
class HoldTimer(val holdMs: Long = HOLD_MS) {
    private var pressedAt: Long? = null
    private var fired = false

    fun press(now: Long) {
        pressedAt = now
        fired = false
    }

    /** 0..1 of the way to completion; 0 when not pressed. */
    fun progress(now: Long): Float {
        val at = pressedAt ?: return 0f
        return ((now - at).toFloat() / holdMs).coerceIn(0f, 1f)
    }

    /** true once: the hold just completed. */
    fun tick(now: Long): Boolean {
        val at = pressedAt ?: return false
        if (fired || now - at < holdMs) return false
        fired = true
        pressedAt = null
        return true
    }

    /** The finger went up. */
    fun release(): HoldResult {
        val result = if (fired) HoldResult.Completed else HoldResult.TooShort
        pressedAt = null
        fired = false
        return result
    }

    /** The press turned into a scroll or was cancelled: no result, no hint. */
    fun cancel() {
        pressedAt = null
        fired = false
    }

    companion object {
        const val HOLD_MS = 600L
    }
}

/**
 * A [PillButton] that must be held for [HoldTimer.HOLD_MS]: the pill fills from the left while held,
 * and [onConfirm] runs (with a haptic tick) when the fill completes. A quick tap calls [onTooShort].
 * Accessibility: the long-press action (TalkBack double-tap-and-hold) confirms.
 */
@Composable
fun HoldPillButton(
    text: String,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    style: PillStyle = PillStyle.Outline,
    enabled: Boolean = true,
    onTooShort: () -> Unit = {},
) {
    val c = Whistle.colors
    val (bg, fg) = when (style) {
        PillStyle.Lamp -> c.lamp to c.onLamp
        PillStyle.Wool -> c.wool to c.night
        PillStyle.Outline -> Color.Transparent to c.wool
        PillStyle.Quiet -> c.cardHi to c.wool
    }
    val fill = if (style == PillStyle.Outline || style == PillStyle.Quiet) c.lamp.copy(alpha = 0.35f) else fg.copy(alpha = 0.28f)
    var progress by remember { mutableFloatStateOf(0f) }
    val timer = remember { HoldTimer() }
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val confirm by rememberUpdatedState(onConfirm)
    val tooShort by rememberUpdatedState(onTooShort)
    Box(
        modifier
            .defaultMinSize(minHeight = 36.dp)
            .clip(WhistleShapes.Pill)
            .background(if (enabled) bg else bg.copy(alpha = bg.alpha * 0.4f))
            .then(if (style == PillStyle.Outline) Modifier.border(1.dp, c.line, WhistleShapes.Pill) else Modifier)
            .drawBehind { if (progress > 0f) drawRect(fill, size = Size(size.width * progress, size.height)) }
            .semantics(mergeDescendants = true) {
                role = Role.Button
                stateDescription = "Hold to answer"
                if (enabled) onLongClick("Answer") { confirm(); true } else disabled()
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(onPress = {
                    val hold = scope.launch {
                        timer.press(withFrameMillis { it })
                        while (true) {
                            val now = withFrameMillis { it }
                            progress = timer.progress(now)
                            if (timer.tick(now)) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                confirm()
                                break
                            }
                        }
                    }
                    val released = tryAwaitRelease()
                    hold.cancel()
                    if (released) {
                        if (timer.release() == HoldResult.TooShort) tooShort()
                    } else {
                        timer.cancel()
                    }
                    progress = 0f
                })
            }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = WhistleType.secondaryStrong,
            color = if (enabled) fg else fg.copy(alpha = 0.5f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
