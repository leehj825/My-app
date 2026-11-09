package io.github.leehj825.myapp.notes

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import io.github.leehj825.myapp.R

/** The list of notes, newest first. Tap a note to open it; New note starts a blank one. */
class NotesActivity : Activity() {

    private lateinit var list: LinearLayout
    private lateinit var empty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_notes)
        list = findViewById(R.id.notes_list)
        empty = findViewById(R.id.notes_empty)
        findViewById<Button>(R.id.notes_new).setOnClickListener {
            startActivity(Intent(this, NoteEditActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val notes = NoteStore.load(this).sortedByDescending { it.createdAt }
        list.removeAllViews()
        empty.visibility = if (notes.isEmpty()) View.VISIBLE else View.GONE
        for (note in notes) {
            val item = layoutInflater.inflate(R.layout.item_note, list, false)
            item.findViewById<TextView>(R.id.note_time).text = formatNoteTime(note.createdAt)
            item.findViewById<TextView>(R.id.note_text).text = note.text
            item.setOnClickListener {
                startActivity(Intent(this, NoteEditActivity::class.java).putExtra(NoteEditActivity.EXTRA_ID, note.id))
            }
            list.addView(item)
        }
    }
}
