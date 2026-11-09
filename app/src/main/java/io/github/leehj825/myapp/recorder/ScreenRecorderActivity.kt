package io.github.leehj825.myapp.recorder

import io.github.leehj825.myapp.R
import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch

class ScreenRecorderActivity : ComponentActivity() {

    private lateinit var status: TextView
    private lateinit var toggle: Button

    // Shows the system "Start recording?" dialog. The result (code + intent) is the capture permission.
    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            RecordingService.start(this, result.resultCode, data)
        } else {
            Toast.makeText(this, R.string.screen_capture_denied, Toast.LENGTH_SHORT).show()
        }
    }

    // Notifications are optional: without them recording still works, only the notification is hidden.
    private val notificationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { askForCapturePermission() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_screen_recorder)
        status = findViewById(R.id.status)
        toggle = findViewById(R.id.toggle)

        toggle.setOnClickListener {
            if (RecordingService.isRecording) {
                RecordingService.stop(this)
            } else {
                requestNotificationPermissionThenStart()
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                RecordingService.recording.collect { render(it) }
            }
        }
    }

    private fun requestNotificationPermissionThenStart() {
        val needsNotifications = Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        if (needsNotifications) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            askForCapturePermission()
        }
    }

    private fun askForCapturePermission() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }

    private fun render(recording: Boolean) {
        status.setText(if (recording) R.string.status_recording else R.string.status_idle)
        toggle.setText(if (recording) R.string.stop else R.string.start)
    }
}
