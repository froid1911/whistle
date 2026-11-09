package dev.whistle.app.widget

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.whistle.app.MainActivity
import dev.whistle.app.container

/** "Herd: 2 working · 1 blocked"; tap opens the app. Reads the last snapshot, never connects. */
class HerdTileService : TileService() {
    override fun onStartListening() {
        val s = applicationContext.container.snapshots.read()
        qsTile?.apply {
            label = tileLabel(s)
            state = if (s.synced && s.counts.working + s.counts.blocked > 0) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            if (Build.VERSION.SDK_INT >= 29) subtitle = staleLabel(s.counts, s.synced, noMachines = s.noMachines)
            updateTile()
        }
    }

    @Suppress("DEPRECATION")
    override fun onClick() {
        val s = applicationContext.container.snapshots.read()
        val b = s.onlyBlocked
        val intent = if (b != null) MainActivity.openPane(this, b.machineId, b.paneId)
        else Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            )
        } else {
            startActivityAndCollapse(intent)
        }
    }
}
