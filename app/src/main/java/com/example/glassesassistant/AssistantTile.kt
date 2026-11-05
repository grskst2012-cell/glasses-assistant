package com.example.glassesassistant

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Плитка в шторке: запускает ассистента, а если он уже работает — начинает слушать. */
class AssistantTile : TileService() {
    override fun onStartListening() { refresh() }

    override fun onClick() {
        val i = Intent(this, TriggerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 11, i, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION") startActivityAndCollapse(i)
        }
    }

    private fun refresh() {
        val t = qsTile ?: return
        val on = AssistantService.running
        t.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.label = getString(R.string.tile_label)
        t.subtitle = getString(if (on) R.string.tile_running else R.string.tile_stopped)
        t.updateTile()
    }
}
