package io.github.sennheiser1986.gpstrack.tiles

import android.Manifest
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import io.github.sennheiser1986.gpstrack.MainActivity
import io.github.sennheiser1986.gpstrack.record.LocationRecordingService
import io.github.sennheiser1986.gpstrack.record.RecordPreferences
import io.github.sennheiser1986.gpstrack.record.RecordingState

/**
 * Quick Settings tile that starts or stops track recording without opening the app. Starting
 * uses the activity type last chosen on the Record tab. When location permission has never been
 * granted the tile opens the app instead, where the permission flow lives.
 */
class RecordTileService : TileService() {

    /** Refreshes the tile to match the current recording state. */
    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    /** Toggles recording, or opens the app when permission is missing. */
    override fun onClick() {
        super.onClick()
        if (RecordingState.isRecording) {
            LocationRecordingService.stop(this)
        } else if (hasLocationPermission()) {
            LocationRecordingService.start(
                this,
                trackName = null,
                activityType = RecordPreferences(this).lastActivityType(),
            )
        } else {
            openApp()
            return
        }
        // The service flips RecordingState asynchronously; a short optimistic update keeps the
        // tile responsive, and onStartListening corrects it next time the shade opens.
        updateTile(optimisticActive = !RecordingState.isRecording)
    }

    /**
     * Paints the tile.
     *
     * @param optimisticActive overrides the live state right after a tap, or null to read it.
     */
    private fun updateTile(optimisticActive: Boolean? = null) {
        val tile = qsTile ?: return
        val active = optimisticActive ?: RecordingState.isRecording
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = if (active) "Recording…" else "Record track"
        tile.updateTile()
    }

    /** Opens [MainActivity], collapsing the shade. */
    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this, 0, intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }

    /**
     * Reports whether at least coarse location permission is held.
     *
     * @return true when recording can start.
     */
    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
}
