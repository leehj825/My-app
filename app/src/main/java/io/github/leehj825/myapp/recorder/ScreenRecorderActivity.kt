package io.github.leehj825.myapp.recorder

import io.github.leehj825.myapp.R
import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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
    private lateinit var controlsToggle: Button
    private lateinit var floatingToggle: Button

    // Shows the system "Start recording?" dialog. The result (code + intent) is the capture permission.
    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            RecordingService.start(this, result.resultCode, data, micGranted())
        } else {
            Toast.makeText(this, R.string.screen_capture_denied, Toast.LENGTH_SHORT).show()
        }
    }

    // Notifications are optional: without them recording still works, only the notification is hidden.
    private val notificationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { requestMicThenCapture() }

    // The microphone is optional: if refused, the recording is video only.
    private val micLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) Toast.makeText(this, R.string.mic_denied, Toast.LENGTH_SHORT).show()
        askForCapturePermission()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_screen_recorder)
        status = findViewById(R.id.status)
        toggle = findViewById(R.id.toggle)
        controlsToggle = findViewById(R.id.controls_toggle)
        floatingToggle = findViewById(R.id.floating_toggle)

        toggle.setOnClickListener {
            if (RecordingService.isRecording) {
                RecordingService.stop(this)
            } else {
                requestNotificationPermissionThenStart()
            }
        }
        controlsToggle.setOnClickListener {
            if (FloatingButtonService.isRunning) {
                FloatingButtonService.stop(this)
            } else {
                FloatingButtonService.start(this, bubble = false)
            }
        }
        floatingToggle.setOnClickListener { onFloatingToggle() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                RecordingService.recording.collect { render(it) }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                FloatingButtonService.running.collect {
                    controlsToggle.setText(if (it) R.string.hide_controls else R.string.show_controls)
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                FloatingButtonService.bubbleOn.collect {
                    floatingToggle.setText(if (it) R.string.hide_floating else R.string.show_floating)
                }
            }
        }
    }

    private fun onFloatingToggle() {
        when {
            // Hiding the button keeps the notification controls.
            FloatingButtonService.isBubbleOn -> FloatingButtonService.start(this, bubble = false)
            Settings.canDrawOverlays(this) -> FloatingButtonService.start(this, bubble = true)
            else -> {
                Toast.makeText(this, R.string.overlay_permission_needed, Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
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
            requestMicThenCapture()
        }
    }

    private fun requestMicThenCapture() {
        if (micGranted()) askForCapturePermission() else micLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun micGranted(): Boolean =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun askForCapturePermission() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }

    private fun render(recording: Boolean) {
        status.setText(if (recording) R.string.status_recording else R.string.status_idle)
        toggle.setText(if (recording) R.string.stop else R.string.start)
    }
}
