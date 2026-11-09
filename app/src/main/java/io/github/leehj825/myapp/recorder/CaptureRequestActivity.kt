package io.github.leehj825.myapp.recorder

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import io.github.leehj825.myapp.R

/**
 * An invisible activity that asks for the microphone (if needed) and the screen capture permission,
 * then starts the recording. Used when a recording starts outside the app (the floating button, the
 * notification, or the Quick Settings tile), which has no screen of its own to ask from.
 */
class CaptureRequestActivity : ComponentActivity() {

    private val micLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) Toast.makeText(this, R.string.mic_denied, Toast.LENGTH_SHORT).show()
        askForCapturePermission()
    }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            RecordingService.start(this, result.resultCode, data, micGranted())
        } else {
            Toast.makeText(this, R.string.screen_capture_denied, Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            if (micGranted()) askForCapturePermission() else micLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun micGranted(): Boolean =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun askForCapturePermission() {
        projectionLauncher.launch(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())
    }
}
