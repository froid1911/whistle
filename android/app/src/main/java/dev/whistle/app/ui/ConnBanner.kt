package dev.whistle.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import dev.whistle.app.ui.components.Dot
import dev.whistle.app.ui.theme.Whistle
import dev.whistle.app.ui.theme.WhistleShapes
import dev.whistle.app.ui.theme.WhistleType
import dev.whistle.app.protocol.ConnState

object BannerText {
    const val SYNCING = "Syncing…"
    const val OFFLINE = "Offline — showing cached"
    const val HERDR_OFFLINE = "herdr offline"

    /** null when nothing needs saying (synced). */
    fun of(state: ConnState): String? = when (state) {
        ConnState.Connecting, ConnState.Syncing -> SYNCING
        ConnState.Offline -> OFFLINE
        ConnState.HerdrOffline -> HERDR_OFFLINE
        ConnState.Synced -> null
    }
}

/**
 * Connection state as a slim pill under the screen title (not a full-width bar); hidden when
 * synced. Syncing is sky, offline sage, herdr offline ember.
 */
@Composable
fun ConnBanner(state: ConnState, modifier: Modifier = Modifier) {
    // Keep showing the last unsynced state while the exit animation runs (plain holder, not state).
    val last = remember { arrayOf(state) }
    if (state != ConnState.Synced) last[0] = state
    val shownState = last[0]
    AnimatedVisibility(state != ConnState.Synced, modifier, enter = expandVertically(), exit = shrinkVertically()) {
        val shown = BannerText.of(shownState) ?: return@AnimatedVisibility
        val c = Whistle.colors
        val fg = when (shownState) {
            ConnState.HerdrOffline -> c.ember
            ConnState.Offline -> c.sage
            else -> c.sky
        }
        Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            Row(
                Modifier
                    .clip(WhistleShapes.Pill)
                    .background(lerp(c.card, fg, 0.12f))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (shownState == ConnState.Connecting || shownState == ConnState.Syncing) {
                    CircularProgressIndicator(Modifier.size(10.dp), strokeWidth = 1.5.dp, color = fg)
                } else {
                    Dot(fg)
                }
                Spacer(Modifier.width(6.dp))
                Text(shown, style = WhistleType.small, color = fg)
            }
        }
    }
}

/**
 * The machine refuses this phone (unpaired, removed, or a bridge with the wrong key): an ember card
 * under the title; the client has stopped reconnecting. Tapping opens Machines (pair again).
 */
@Composable
fun AuthBanner(text: String, modifier: Modifier = Modifier, onClick: () -> Unit = {}) {
    val c = Whistle.colors
    dev.whistle.app.ui.components.WhistleCard(
        modifier.padding(horizontal = 16.dp, vertical = 4.dp).testTag("authBanner"),
        onClick = onClick,
        color = lerp(c.card, c.ember, 0.12f),
    ) {
        Text(text, style = WhistleType.body, color = c.ember)
    }
}
