package io.github.leehj825.myapp.recorder

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * A Quick Settings tile (the panel you open by pulling down from the top). Start or stop a recording
 * from there. The panel is not part of the recording unless you open it while recording.
 */
class ScreenRecorderTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    @Suppress("DEPRECATION")
    override fun onClick() {
        if (RecordingService.isRecording) {
            RecordingService.stop(this)
        } else {
            val intent = Intent(this, CaptureRequestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
            } else {
                startActivityAndCollapse(intent)
            }
        }
        refreshTile()
    }

    private fun refreshTile() {
        qsTile?.apply {
            state = if (RecordingService.isRecording) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            updateTile()
        }
    }
}
