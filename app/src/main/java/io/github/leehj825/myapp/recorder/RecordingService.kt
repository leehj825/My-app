package io.github.leehj825.myapp.recorder

import io.github.leehj825.myapp.R
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Records the whole screen to an MP4 file in Movies/ScreenRecordings (shared storage, visible in Gallery).
 *
 * Runs as a foreground service: Android requires that for MediaProjection, and it keeps the recording alive
 * while the app is in the background. The capture permission (result code + intent) comes from ScreenRecorderActivity.
 */
class RecordingService : Service() {

    private var projection: MediaProjection? = null
    private var recorder: MediaRecorder? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var outputDescriptor: ParcelFileDescriptor? = null
    private var outputUri: Uri? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        when {
            intent != null && action == ACTION_START -> handleStart(intent)
            action == ACTION_STOP -> teardown(keep = true)
            !_recording.value -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun handleStart(intent: Intent) {
        // Must be called right away after startForegroundService, before anything slow.
        startAsForeground()
        if (_recording.value) return
        val data = intent.projectionData()
        val code = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        if (data != null && code == Activity.RESULT_OK) {
            startRecording(code, data)
        } else {
            teardown(keep = false)
        }
    }

    private fun startAsForeground() {
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, RecordingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, ScreenRecorderActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setContentIntent(openIntent)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.stop), stopIntent)
            .build()
        // Android 14+ requires the type; the system checks it against the MediaProjection permission.
        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
    }

    private fun startRecording(resultCode: Int, data: Intent) {
        val mp = getSystemService(MediaProjectionManager::class.java).getMediaProjection(resultCode, data)
        if (mp == null) {
            teardown(keep = false)
            return
        }
        projection = mp
        // Android 14+ requires a callback before creating the virtual display. It fires when the user stops
        // capture from the system bar, so the recording is finished and saved.
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() = teardown(keep = true)
        }, Handler(Looper.getMainLooper()))

        try {
            val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, pendingValues())
                ?: error("MediaStore refused the new video")
            outputUri = uri
            val descriptor = contentResolver.openFileDescriptor(uri, "rw")
                ?: error("Could not open $uri for writing")
            outputDescriptor = descriptor

            val spec = captureSpec()
            val mr = createRecorder()
            recorder = mr
            mr.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mr.setOutputFile(descriptor.fileDescriptor)
            mr.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            mr.setVideoSize(spec.width, spec.height)
            mr.setVideoFrameRate(FRAME_RATE)
            mr.setVideoEncodingBitRate(bitRateFor(spec.width, spec.height))
            mr.setOnErrorListener { _, what, extra ->
                Log.e(TAG, "MediaRecorder error $what/$extra")
                teardown(keep = true)
            }
            mr.prepare()

            virtualDisplay = mp.createVirtualDisplay(
                "ScreenRecorder",
                spec.width, spec.height, spec.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                mr.surface, null, null
            )
            mr.start()
            _recording.value = true
        } catch (e: Exception) {
            Log.e(TAG, "Could not start the recording", e)
            teardown(keep = false)
        }
    }

    /**
     * Stops everything and releases it. [keep] = true keeps the file (the recording finished normally);
     * false deletes it (the recording never started or failed right away).
     * Safe to call more than once: each resource is taken out of its field before it is released.
     */
    private fun teardown(keep: Boolean) {
        val mr = recorder; recorder = null
        val vd = virtualDisplay; virtualDisplay = null
        val mp = projection; projection = null
        val fd = outputDescriptor; outputDescriptor = null
        val uri = outputUri; outputUri = null

        var recorded = false
        if (mr != null) {
            try {
                mr.stop()
                recorded = true
            } catch (e: RuntimeException) {
                // stop() throws when nothing was recorded (for example, stopped right after starting).
                Log.w(TAG, "Recording had no usable video", e)
            }
            mr.release()
        }
        vd?.release()
        mp?.stop()
        fd?.close()

        if (uri != null) {
            if (keep && recorded) {
                // Mark the file as complete so other apps can see it.
                contentResolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            } else {
                contentResolver.delete(uri, null, null)
            }
        }

        _recording.value = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun pendingValues() = ContentValues().apply {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        put(MediaStore.Video.Media.DISPLAY_NAME, "ScreenRecording_$stamp.mp4")
        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
        put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/ScreenRecordings")
        // The file is hidden from other apps until the recording is finished (IS_PENDING = 0 in teardown).
        put(MediaStore.Video.Media.IS_PENDING, 1)
    }

    @Suppress("DEPRECATION")
    private fun createRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()

    private class CaptureSpec(val width: Int, val height: Int, val densityDpi: Int)

    /** The screen size, scaled down so the long side is at most [MAX_LONG_SIDE], with even sides (H.264 needs them). */
    @Suppress("DEPRECATION")
    private fun captureSpec(): CaptureSpec {
        val windowManager = getSystemService(WindowManager::class.java)
        val (rawWidth, rawHeight) = if (Build.VERSION.SDK_INT >= 30) {
            val bounds = windowManager.currentWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            val metrics = DisplayMetrics()
            windowManager.defaultDisplay.getRealMetrics(metrics)
            metrics.widthPixels to metrics.heightPixels
        }
        val scale = min(1f, MAX_LONG_SIDE.toFloat() / max(rawWidth, rawHeight))
        return CaptureSpec(
            width = even(rawWidth * scale),
            height = even(rawHeight * scale),
            densityDpi = resources.displayMetrics.densityDpi
        )
    }

    private fun even(value: Float): Int = max(2, (value.toInt() / 2) * 2)

    /** About 4 bits per pixel per frame at 30 fps, clamped to a sane range. */
    private fun bitRateFor(width: Int, height: Int): Int =
        (width.toLong() * height * 4).coerceIn(2_000_000L, 20_000_000L).toInt()

    private fun Intent.projectionData(): Intent? =
        if (Build.VERSION.SDK_INT >= 33) {
            getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(EXTRA_DATA)
        }

    companion object {
        private const val TAG = "RecordingService"
        private const val CHANNEL_ID = "screen_recording"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_START = "io.github.leehj825.myapp.START"
        const val ACTION_STOP = "io.github.leehj825.myapp.STOP"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_DATA = "data"
        private const val FRAME_RATE = 30
        private const val MAX_LONG_SIDE = 1920

        private val _recording = MutableStateFlow(false)

        /** True while the screen is being recorded. */
        val recording: StateFlow<Boolean> = _recording.asStateFlow()

        val isRecording: Boolean get() = _recording.value

        /** Starts recording with the permission from the system dialog. */
        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, RecordingService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_DATA, data)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, RecordingService::class.java).setAction(ACTION_STOP))
        }
    }
}
