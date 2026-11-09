package dev.whistle.app.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.whistle.app.protocol.AgentStatus
import dev.whistle.app.ui.StatusColorKey
import dev.whistle.app.ui.theme.AgentGlyph
import dev.whistle.app.ui.theme.Bricolage
import dev.whistle.app.ui.theme.Whistle
import dev.whistle.app.ui.theme.rememberReducedMotion
import dev.whistle.app.ui.theme.status

/** True when the orb draws the lamp ripple ring (animated, or static under reduced motion). */
val RippleKey = SemanticsPropertyKey<Boolean>("LampRipple")
private var SemanticsPropertyReceiver.rippleProp by RippleKey
private var SemanticsPropertyReceiver.statusColorProp by StatusColorKey

/**
 * An agent: a circle in its status color with the kind's glyph. A needs-you (blocked) orb gets
 * the lamp ripple, the app's only ambient animation; under reduced motion the ring stays still.
 */
@Composable
fun AgentOrb(kind: String?, status: AgentStatus, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    val colors = Whistle.colors
    val color = colors.status(status)
    val glyph = remember(kind) { AgentGlyph.of(kind) }
    val ring = status == AgentStatus.BLOCKED
    val reduced = rememberReducedMotion()
    val phase: State<Float> = if (ring && !reduced) {
        rememberInfiniteTransition(label = "lamp").animateFloat(
            0f, 1f,
            infiniteRepeatable(tween(2_400, easing = LinearOutSlowInEasing), RepeatMode.Restart),
            label = "ripple",
        )
    } else {
        remember { mutableFloatStateOf(0.35f) }
    }
    Box(
        modifier
            .size(size)
            .semantics {
                statusColorProp = color
                rippleProp = ring
                contentDescription = "${kind ?: "agent"}, status ${status.wire}"
            }
            .drawBehind {
                if (!ring) return@drawBehind
                val p = phase.value
                // scale .8 → 1.5 of the orb (+6dp inset like the mockup), fading out.
                val scale = 0.8f + 0.7f * p
                val alpha = if (reduced) 0.45f else 0.45f * (1f - FastOutSlowInEasing.transform(p))
                drawCircle(
                    colors.lamp.copy(alpha = alpha),
                    radius = (this.size.minDimension / 2 + 6.dp.toPx()) * scale,
                    style = Stroke(2.dp.toPx()),
                )
            }
            .background(color, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        GlyphMark(glyph, colors.onStatus, size)
    }
}

@Composable
private fun GlyphMark(glyph: AgentGlyph, color: Color, size: Dp) {
    when (glyph) {
        is AgentGlyph.Letter -> Text(
            glyph.symbol,
            color = color,
            fontFamily = Bricolage,
            fontWeight = FontWeight.Bold,
            fontSize = (size.value * 0.42f).sp,
            lineHeight = (size.value * 0.42f).sp,
        )
        AgentGlyph.Spokes -> Canvas(Modifier.size(size * 0.46f).testTag("glyph:spokes")) {
            val r = this.size.minDimension / 2
            val c = center
            val w = r * 0.30f
            for (i in 0 until 4) {
                val a = Math.toRadians(i * 45.0)
                val dx = (kotlin.math.cos(a) * r).toFloat() * 0.92f
                val dy = (kotlin.math.sin(a) * r).toFloat() * 0.92f
                drawLine(color, Offset(c.x - dx, c.y - dy), Offset(c.x + dx, c.y + dy), w, StrokeCap.Round)
            }
        }
        AgentGlyph.Diamond -> Canvas(Modifier.size(size * 0.40f).testTag("glyph:diamond")) {
            val r = this.size.minDimension / 2
            val path = Path().apply {
                moveTo(center.x, center.y - r)
                lineTo(center.x + r * 0.86f, center.y)
                lineTo(center.x, center.y + r)
                lineTo(center.x - r * 0.86f, center.y)
                close()
            }
            drawPath(path, color)
        }
    }
}

/** `>_` mark of a shell pane (compact rows on the herd screen). */
@Composable
fun ShellMark(modifier: Modifier = Modifier, size: Dp = 28.dp) {
    val colors = Whistle.colors
    Box(
        modifier.size(size).background(colors.inset, androidx.compose.foundation.shape.RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(">_", color = colors.sage, style = dev.whistle.app.ui.theme.WhistleType.mono.copy(fontSize = 11.sp))
    }
}

/** Small status dot (tabs, live line); same color semantics as the orb. */
@Composable
fun Dot(color: Color, modifier: Modifier = Modifier, size: Dp = 6.dp) {
    Box(modifier.size(size).background(color, CircleShape))
}
