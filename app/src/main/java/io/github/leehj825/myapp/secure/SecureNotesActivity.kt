package io.github.leehj825.myapp.secure

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

/**
 * The secure notes list. It shows only the date and time of each note; the text is hidden.
 * Opening a note asks for the fingerprint.
 */
class SecureNotesActivity : FragmentActivity() {

    private lateinit var list: LinearLayout
    private lateinit var empty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!SecureVault.isDeviceSecure(this) || !SecureVault.canUseFingerprint(this)) {
            Toast.makeText(this, R.string.secure_needs_fingerprint, Toast.LENGTH_LONG).show()
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
        render()
    }

    private fun render() {
        val entries = SecureVault.index(this)
        list.removeAllViews()
        empty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        for ((id, createdAt) in entries) {
            val item = layoutInflater.inflate(R.layout.item_note, list, false)
            item.findViewById<TextView>(R.id.note_time).text = formatNoteTime(createdAt)
            item.findViewById<TextView>(R.id.note_text).setText(R.string.secure_locked)
            item.setOnClickListener {
                startActivity(NoteEditActivity.intentFor(this, Note(id, "", createdAt), secure = true))
            }
            list.addView(item)
        }
    }
}
