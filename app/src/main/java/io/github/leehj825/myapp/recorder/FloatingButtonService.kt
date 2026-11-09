package io.github.leehj825.myapp.recorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.github.leehj825.myapp.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Shows the REC button over other apps and a persistent notification with Start / Stop.
 *
 * Tapping the button starts a recording, or stops it if one is running. The button stays visible
 * while recording, so it appears in the video. The notification's Stop is not captured unless you
 * open the shade during the recording.
 */
class FloatingButtonService : Service() {

    private var windowManager: WindowManager? = null
    private var bubble: TextView? = null
    private var scope: CoroutineScope? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.controls_channel), NotificationManager.IMPORTANCE_LOW)
        )
        startForeground(NOTIFICATION_ID, buildControls(RecordingService.isRecording))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_HIDE || !Settings.canDrawOverlays(this)) {
            stopSelf()
        } else if (bubble == null) {
            addBubble()
        }
        return START_NOT_STICKY
    }

    private fun addBubble() {
        val wm = getSystemService(WindowManager::class.java)
        val size = (BUBBLE_DP * resources.displayMetrics.density).toInt()
        val view = TextView(this).apply {
            text = getString(R.string.bubble_label)
            setTextColor(Color.WHITE)
            textSize = 12f
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.bubble)
        }
        val params = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = resources.displayMetrics.heightPixels / 3
        }
        view.setOnTouchListener(dragOrTap(params, wm))
        wm.addView(view, params)
        windowManager = wm
        bubble = view
        _running.value = true

        // Keep the button label and the notification in step with the recording.
        scope = MainScope().apply {
            launch {
                RecordingService.recording.collect { recording ->
                    view.text = getString(if (recording) R.string.bubble_stop else R.string.bubble_label)
                    getSystemService(NotificationManager::class.java)
                        .notify(NOTIFICATION_ID, buildControls(recording))
                }
            }
        }
    }

    /** Drag the button anywhere on screen; a tap without a drag starts or stops a recording. */
    private fun dragOrTap(params: WindowManager.LayoutParams, wm: WindowManager): View.OnTouchListener {
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false
        return View.OnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchX
                    val dy = event.rawY - touchY
                    if (abs(dx) > slop || abs(dy) > slop) moved = true
                    if (moved) {
                        params.x = startX + dx.toInt()
                        params.y = startY + dy.toInt()
                        wm.updateViewLayout(view, params)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) onBubbleTapped()
                }
            }
            true
        }
    }

    /** Force stop when a recording is running; otherwise ask for permission and start one. */
    private fun onBubbleTapped() {
        if (RecordingService.isRecording) {
            RecordingService.stop(this)
        } else {
            startActivity(Intent(this, CaptureRequestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    override fun onDestroy() {
        scope?.cancel()
        scope = null
        bubble?.let { windowManager?.removeView(it) }
        bubble = null
        _running.value = false
        super.onDestroy()
    }

    /** The notification shade controls: one Start or Stop button, plus Hide. */
    private fun buildControls(recording: Boolean): Notification {
        val hideIntent = PendingIntent.getService(
            this, 0,
            Intent(this, FloatingButtonService::class.java).setAction(ACTION_HIDE),
            PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle(getString(if (recording) R.string.controls_title_recording else R.string.controls_title_idle))
            .setContentText(getString(R.string.controls_text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (recording) {
            val stopIntent = PendingIntent.getService(
                this, 1,
                Intent(this, RecordingService::class.java).setAction(RecordingService.ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.stop), stopIntent)
        } else {
            // Starting needs the system capture prompt, which only an activity can show.
            val startIntent = PendingIntent.getActivity(
                this, 2,
                Intent(this, CaptureRequestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(android.R.drawable.ic_media_play, getString(R.string.start), startIntent)
        }
        builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.controls_hide), hideIntent)
        return builder.build()
    }

    companion object {
        private const val CHANNEL_ID = "recorder_controls"
        private const val NOTIFICATION_ID = 2
        private const val ACTION_HIDE = "io.github.leehj825.myapp.HIDE_CONTROLS"
        private const val BUBBLE_DP = 56

        private val _running = MutableStateFlow(false)

        /** True while the floating button and the notification controls are shown. */
        val running: StateFlow<Boolean> = _running.asStateFlow()

        val isRunning: Boolean get() = _running.value

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, FloatingButtonService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, FloatingButtonService::class.java).setAction(ACTION_HIDE))
        }
    }
}
