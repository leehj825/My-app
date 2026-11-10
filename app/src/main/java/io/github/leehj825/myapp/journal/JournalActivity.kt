package io.github.leehj825.myapp.journal

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.format.DateFormat
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.TimePicker
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import io.github.leehj825.myapp.R
import io.github.leehj825.myapp.notes.NoteEditActivity
import io.github.leehj825.myapp.notes.NoteStore
import io.github.leehj825.myapp.notes.dayKey
import java.util.Locale

/** The daily journal: one entry per day, and an optional reminder at a set time each day. */
class JournalActivity : ComponentActivity() {

    private lateinit var status: TextView
    private lateinit var reminderOff: Button
    private lateinit var list: LinearLayout
    private lateinit var empty: TextView
    private var pendingTime: Pair<Int, Int>? = null

    private val notificationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // The reminder is set even if notifications are refused; it just cannot show.
        pendingTime?.let { (hour, minute) -> JournalReminder.schedule(this, hour, minute) }
        pendingTime = null
        renderReminder()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_journal)
        status = findViewById(R.id.journal_reminder_status)
        reminderOff = findViewById(R.id.journal_reminder_off)
        list = findViewById(R.id.journal_list)
        empty = findViewById(R.id.journal_empty)

        findViewById<Button>(R.id.journal_today).setOnClickListener {
            startActivity(todayIntent(this))
        }
        findViewById<Button>(R.id.journal_set_time).setOnClickListener { pickTime() }
        reminderOff.setOnClickListener {
            JournalReminder.cancel(this)
            renderReminder()
        }
    }

    override fun onResume() {
        super.onResume()
        renderReminder()
        render()
    }

    private fun pickTime() {
        android.app.TimePickerDialog(
            this,
            { _: TimePicker, hour: Int, minute: Int -> enableReminder(hour, minute) },
            JournalReminder.hour(this),
            JournalReminder.minute(this),
            DateFormat.is24HourFormat(this)
        ).show()
    }

    private fun enableReminder(hour: Int, minute: Int) {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingTime = hour to minute
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            JournalReminder.schedule(this, hour, minute)
            renderReminder()
        }
    }

    private fun renderReminder() {
        val on = JournalReminder.isEnabled(this)
        status.text = if (on) {
            getString(
                R.string.journal_reminder_on,
                String.format(Locale.US, "%02d:%02d", JournalReminder.hour(this), JournalReminder.minute(this))
            )
        } else {
            getString(R.string.journal_reminder_none)
        }
        reminderOff.visibility = if (on) View.VISIBLE else View.GONE
    }

    private fun render() {
        val entries = NoteStore.load(this).filter { it.day != null }.sortedByDescending { it.day }
        list.removeAllViews()
        empty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        for (entry in entries) {
            val item = layoutInflater.inflate(R.layout.item_note, list, false)
            item.findViewById<TextView>(R.id.note_time).text = entry.day
            item.findViewById<TextView>(R.id.note_text).text = entry.text
            item.setOnClickListener { startActivity(NoteEditActivity.intentFor(this, entry)) }
            list.addView(item)
        }
    }

    companion object {
        /** Opens today's entry, or starts a new one for today. */
        fun todayIntent(context: Context): Intent {
            val today = dayKey(System.currentTimeMillis())
            val entry = NoteStore.load(context).find { it.day == today }
            return NoteEditActivity.intentFor(context, entry, day = today)
        }
    }
}
