package io.github.leehj825.myapp.notes

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import io.github.leehj825.myapp.R

/**
 * Writes or dictates one note. The note is saved when you leave the screen (Done, Back or switching apps),
 * with the time it was created. Dictation uses the phone's speech recognition; no audio is kept.
 */
class NoteEditActivity : ComponentActivity() {

    private lateinit var time: TextView
    private lateinit var body: EditText
    private lateinit var delete: Button
    private var noteId = NEW_NOTE
    private var createdAt = 0L
    private var deleted = false

    private val speechLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val heard = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (result.resultCode == Activity.RESULT_OK && !heard.isNullOrBlank()) insertAtCursor(heard)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_note_edit)
        time = findViewById(R.id.note_time)
        body = findViewById(R.id.note_body)
        delete = findViewById(R.id.note_delete)

        val id = intent.getLongExtra(EXTRA_ID, NEW_NOTE)
        val existing = if (id == NEW_NOTE) null else NoteStore.load(this).find { it.id == id }
        noteId = existing?.id ?: System.currentTimeMillis()
        createdAt = existing?.createdAt ?: noteId
        body.setText(existing?.text.orEmpty())
        time.text = formatNoteTime(createdAt)
        delete.visibility = if (existing != null) View.VISIBLE else View.GONE

        findViewById<Button>(R.id.note_dictate).setOnClickListener { dictate() }
        findViewById<Button>(R.id.note_done).setOnClickListener { finish() }
        delete.setOnClickListener { deleteNote() }
    }

    override fun onPause() {
        super.onPause()
        persist()
    }

    /** Saves the text. An empty note is not kept. */
    private fun persist() {
        if (deleted) return
        val text = body.text.toString().trim()
        val notes = NoteStore.load(this)
        notes.removeAll { it.id == noteId }
        if (text.isNotEmpty()) notes.add(Note(noteId, text, createdAt))
        NoteStore.save(this, notes)
    }

    private fun deleteNote() {
        deleted = true
        val notes = NoteStore.load(this)
        notes.removeAll { it.id == noteId }
        NoteStore.save(this, notes)
        finish()
    }

    private fun dictate() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.note_dictate_prompt))
        }
        if (intent.resolveActivity(packageManager) == null) {
            Toast.makeText(this, R.string.note_no_speech, Toast.LENGTH_LONG).show()
        } else {
            speechLauncher.launch(intent)
        }
    }

    /** Puts the spoken words where the cursor is, with a space before them if needed. */
    private fun insertAtCursor(heard: String) {
        val start = body.selectionStart.coerceAtLeast(0)
        val end = body.selectionEnd.coerceAtLeast(start)
        val before = if (start > 0) body.text[start - 1] else ' '
        val spacer = if (before.isWhitespace()) "" else " "
        body.text.replace(start, end, spacer + heard)
    }

    companion object {
        const val EXTRA_ID = "note_id"
        private const val NEW_NOTE = -1L
    }
}
