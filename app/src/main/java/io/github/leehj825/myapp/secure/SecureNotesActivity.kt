package io.github.leehj825.myapp.secure

import android.security.keystore.UserNotAuthenticatedException
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import io.github.leehj825.myapp.R
import io.github.leehj825.myapp.notes.Note
import io.github.leehj825.myapp.notes.NoteEditActivity
import io.github.leehj825.myapp.notes.formatNoteTime

/** The secure notes list. Opening it, and coming back after a few minutes, asks for the unlock. */
class SecureNotesActivity : FragmentActivity() {

    private lateinit var list: LinearLayout
    private lateinit var empty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!SecureVault.isDeviceSecure(this)) {
            Toast.makeText(this, R.string.secure_needs_lock, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        setContentView(R.layout.activity_notes)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        list = findViewById(R.id.notes_list)
        empty = findViewById(R.id.notes_empty)
        findViewById<Button>(R.id.notes_new).setOnClickListener {
            startActivity(NoteEditActivity.intentFor(this, secure = true))
        }
    }

    override fun onResume() {
        super.onResume()
        unlockAndRender()
    }

    private fun unlockAndRender() {
        try {
            render(SecureVault.load(this))
        } catch (e: UserNotAuthenticatedException) {
            SecureVault.authenticate(this, onSuccess = { unlockAndRender() }, onCancel = { finish() })
        } catch (e: Exception) {
            Toast.makeText(this, R.string.secure_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun render(notes: List<Note>) {
        val sorted = notes.sortedByDescending { it.createdAt }
        list.removeAllViews()
        empty.visibility = if (sorted.isEmpty()) View.VISIBLE else View.GONE
        for (note in sorted) {
            val item = layoutInflater.inflate(R.layout.item_note, list, false)
            item.findViewById<TextView>(R.id.note_time).text = formatNoteTime(note.createdAt)
            item.findViewById<TextView>(R.id.note_text).text = note.text
            item.setOnClickListener { startActivity(NoteEditActivity.intentFor(this, note, secure = true)) }
            list.addView(item)
        }
    }
}
