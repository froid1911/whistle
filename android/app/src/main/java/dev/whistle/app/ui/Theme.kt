package dev.whistle.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.whistle.app.protocol.AgentStatus
import dev.whistle.app.ui.theme.DarkWhistle
import dev.whistle.app.ui.theme.Whistle
import dev.whistle.app.ui.theme.WhistleColors
import dev.whistle.app.ui.theme.WhistleTheme
import dev.whistle.app.ui.theme.status

/** The app theme ("Night pasture"); see [WhistleTheme]. No dynamic color: the palette is the brand. */
@Composable
fun HerdrTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) = WhistleTheme(dark, content)

/** Agent status colors of the dark (primary) palette; the light theme darkens them (see [WhistleColors.status]). */
object StatusColors {
    val IDLE = DarkWhistle.moss
    val WORKING = DarkWhistle.sky
    val BLOCKED = DarkWhistle.lamp
    val DONE = DarkWhistle.heather
    val UNKNOWN = DarkWhistle.moss
}

fun statusColor(s: AgentStatus, colors: WhistleColors = DarkWhistle): Color = colors.status(s)

/** The color a [StatusDot] is drawn with (exposed for tests). */
val StatusColorKey = SemanticsPropertyKey<Color>("StatusColor")
private var SemanticsPropertyReceiver.statusColorProp by StatusColorKey

@Composable
fun StatusDot(status: AgentStatus, modifier: Modifier = Modifier, size: Dp = 12.dp) {
    val color = Whistle.colors.status(status)
    Box(
        modifier
            .semantics {
                statusColorProp = color
                contentDescription = "status ${status.wire}"
            }
            .size(size)
            .background(color, CircleShape),
    )
}
