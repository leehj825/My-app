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
 * A red REC button drawn over other apps. Tap it to start a recording from any app.
 *
 * It hides itself while recording, so the button is not in the video. Stop from the notification
 * shade (the Stop action) or the Quick Settings tile, which are not in the recording.
 */
class FloatingButtonService : Service() {

    private var windowManager: WindowManager? = null
    private var bubble: TextView? = null
    private var scope: CoroutineScope? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.floating_channel), NotificationManager.IMPORTANCE_MIN)
        )
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP || !Settings.canDrawOverlays(this)) {
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

        // Hidden while recording, so it is not in the video.
        scope = MainScope().apply {
            launch {
                RecordingService.recording.collect { recording ->
                    view.visibility = if (recording) View.GONE else View.VISIBLE
                }
            }
        }
    }

    /** Drag the button anywhere on screen; a tap without a drag starts a recording. */
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

    private fun onBubbleTapped() {
        if (!RecordingService.isRecording) {
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

    private fun buildNotification(): Notification {
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, FloatingButtonService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle(getString(R.string.floating_title))
            .setContentText(getString(R.string.floating_text))
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.floating_turn_off), stopIntent)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "floating_button"
        private const val NOTIFICATION_ID = 2
        private const val ACTION_STOP = "io.github.leehj825.myapp.FLOATING_STOP"
        private const val BUBBLE_DP = 56

        private val _running = MutableStateFlow(false)

        /** True while the floating button is on screen. */
        val running: StateFlow<Boolean> = _running.asStateFlow()

        val isRunning: Boolean get() = _running.value

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, FloatingButtonService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, FloatingButtonService::class.java).setAction(ACTION_STOP))
        }
    }
}
