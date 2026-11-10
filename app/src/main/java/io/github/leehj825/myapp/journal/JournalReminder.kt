package io.github.leehj825.myapp.journal

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import io.github.leehj825.myapp.R
import java.util.Calendar

/** The daily journal reminder: an alarm at a set time each day, which shows a notification. */
object JournalReminder {
    private const val PREFS = "journal"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_HOUR = "hour"
    private const val KEY_MINUTE = "minute"
    private const val REQUEST_CODE = 41

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)
    fun hour(context: Context): Int = prefs(context).getInt(KEY_HOUR, 21)
    fun minute(context: Context): Int = prefs(context).getInt(KEY_MINUTE, 0)

    fun schedule(context: Context, hour: Int, minute: Int) {
        prefs(context).edit().putBoolean(KEY_ENABLED, true).putInt(KEY_HOUR, hour).putInt(KEY_MINUTE, minute).apply()
        // Inexact: Android may move the reminder by a few minutes to save battery.
        context.getSystemService(AlarmManager::class.java).setInexactRepeating(
            AlarmManager.RTC_WAKEUP, nextTrigger(hour, minute), AlarmManager.INTERVAL_DAY, pendingIntent(context)
        )
    }

    fun cancel(context: Context) {
        prefs(context).edit().putBoolean(KEY_ENABLED, false).apply()
        context.getSystemService(AlarmManager::class.java).cancel(pendingIntent(context))
    }

    /** Alarms are cleared on restart, so set them again if the reminder was on. */
    fun restoreAfterBoot(context: Context) {
        if (isEnabled(context)) schedule(context, hour(context), minute(context))
    }

    private fun nextTrigger(hour: Int, minute: Int): Long {
        val time = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }
        return time.timeInMillis
    }

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, REQUEST_CODE,
        Intent(context, JournalReminderReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/** Shows the reminder. Tapping it opens today's journal entry. */
class JournalReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.journal_channel), NotificationManager.IMPORTANCE_DEFAULT)
        )
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val open = PendingIntent.getActivity(
            context, 0,
            JournalActivity.todayIntent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentTitle(context.getString(R.string.journal_notification_title))
            .setContentText(context.getString(R.string.journal_notification_text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    private companion object {
        const val CHANNEL_ID = "journal_reminder"
        const val NOTIFICATION_ID = 3
    }
}

/** Sets the reminder again after the phone restarts. */
class JournalBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) JournalReminder.restoreAfterBoot(context)
    }
}
