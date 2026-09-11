package io.github.sennheiser1986.gpstrack.tiles

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import io.github.sennheiser1986.gpstrack.share.LocationShareService
import io.github.sennheiser1986.gpstrack.share.SharePreferences

/**
 * Quick Settings tile that toggles the location broadcast without opening the app. It flips the
 * same persisted switch as the Share tab and starts or stops the sharing service accordingly.
 */
class BroadcastTileService : TileService() {

    /** Refreshes the tile to match the persisted broadcast switch. */
    override fun onStartListening() {
        super.onStartListening()
        updateTile(SharePreferences(this).isBroadcasting())
    }

    /** Flips the broadcast switch. */
    override fun onClick() {
        super.onClick()
        val preferences = SharePreferences(this)
        val enabled = !preferences.isBroadcasting()
        preferences.setBroadcasting(enabled)
        // The service re-evaluates its reasons to run: it starts, keeps running for watched
        // peers, or stops itself when nothing is left to do.
        LocationShareService.sync(this)
        updateTile(enabled)
    }

    /**
     * Paints the tile.
     *
     * @param broadcasting whether the position broadcast is on.
     */
    private fun updateTile(broadcasting: Boolean) {
        val tile = qsTile ?: return
        tile.state = if (broadcasting) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = if (broadcasting) "Broadcasting" else "Broadcast location"
        tile.updateTile()
    }
}
