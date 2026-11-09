package dev.whistle.app.widget

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.service.quicksettings.TileService
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.glance.appwidget.cornerRadius
import dev.whistle.app.ui.theme.DarkWhistle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import dev.whistle.app.MainActivity
import dev.whistle.app.container

// Night pasture: night background, orb-colored counts, a lamp glow while anything needs you.
private val BG = DarkWhistle.night
private val BG_LAMP = lerp(DarkWhistle.night, DarkWhistle.lamp, 0.18f)
private val FG = DarkWhistle.wool
private val DIM = DarkWhistle.sage
private val WORKING = DarkWhistle.sky
private val BLOCKED = DarkWhistle.lamp
private val DONE = DarkWhistle.heather
private val IDLE = DarkWhistle.moss

/** Counts of working / blocked / done agents; tap opens the app (or the one blocked pane's chat). */
class HerdWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snap = context.container.snapshots.read()
        provideContent { Content(context, snap) }
    }

    @androidx.compose.runtime.Composable
    private fun Content(context: Context, s: Snapshot) {
        val b = s.onlyBlocked
        val intent = if (b != null) MainActivity.openPane(context, b.machineId, b.paneId)
        else Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val label = widgetLabel(s)
        Column(
            GlanceModifier.fillMaxSize().cornerRadius(20.dp).background(if (s.counts.blocked > 0) BG_LAMP else BG)
                .padding(12.dp).clickable(actionStartActivity(intent)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(GlanceModifier.defaultWeight(), verticalAlignment = Alignment.CenterVertically) {
                stat(s.counts.blocked, "need you", if (s.counts.blocked > 0) BLOCKED else IDLE)
                stat(s.counts.working, "working", if (s.counts.working > 0) WORKING else IDLE)
                stat(s.counts.done, "done", if (s.counts.done > 0) DONE else IDLE)
            }
            Text(label.text, style = TextStyle(color = ColorProvider(if (label.stale) FG else DIM), fontSize = 11.sp))
        }
    }

    @androidx.compose.runtime.Composable
    private fun androidx.glance.layout.RowScope.stat(n: Int, label: String, color: Color) {
        Column(GlanceModifier.defaultWeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$n", style = TextStyle(color = ColorProvider(color), fontSize = 24.sp, fontWeight = FontWeight.Bold))
            Text(label, style = TextStyle(color = ColorProvider(DIM), fontSize = 11.sp))
        }
    }
}

class HerdWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = HerdWidget()
}

/** Pushes the stored snapshot to every widget and the tile. */
suspend fun refreshSurfaces(context: Context) {
    HerdWidget().updateAll(context)
    TileService.requestListeningState(context, ComponentName(context, HerdTileService::class.java))
}
