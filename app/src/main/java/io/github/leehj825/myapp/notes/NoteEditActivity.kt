package io.github.leehj825.myapp.notes

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.security.keystore.UserNotAuthenticatedException
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.FragmentActivity
import io.github.leehj825.myapp.R
import io.github.leehj825.myapp.secure.SecureVault
import java.util.Locale

/**
 * Writes, dictates or reads aloud one note. Used for plain notes, journal entries and secure notes.
 *
 * Saving: Done, Back and leaving the screen. A secure note asks for the phone unlock when it needs it.
 * Dictation uses the phone's speech recognition; read-aloud uses the phone's text-to-speech. No audio is kept.
 */
class NoteEditActivity : FragmentActivity() {

    private lateinit var time: TextView
    private lateinit var body: EditText
    private lateinit var delete: Button
    private var noteId = NEW_NOTE
    private var createdAt = 0L
    private var day: String? = null
    private var secure = false
    private var deleted = false
    private var tts: TextToSpeech? = null
    private var ttsReady = false

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

        secure = intent.getBooleanExtra(EXTRA_SECURE, false)
        // Secure notes are not shown in screenshots, screen recordings or the recent apps preview.
        if (secure) window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)

        val existingId = intent.getLongExtra(EXTRA_ID, NEW_NOTE)
        val isNew = existingId == NEW_NOTE
        noteId = if (isNew) System.currentTimeMillis() else existingId
        createdAt = if (isNew) noteId else intent.getLongExtra(EXTRA_CREATED, noteId)
        day = intent.getStringExtra(EXTRA_DAY)
        body.setText(intent.getStringExtra(EXTRA_TEXT).orEmpty())
        time.text = formatNoteTime(createdAt)
        delete.visibility = if (isNew) View.GONE else View.VISIBLE

        findViewById<Button>(R.id.note_dictate).setOnClickListener { dictate() }
        findViewById<Button>(R.id.note_read).setOnClickListener { readAloud() }
        findViewById<Button>(R.id.note_done).setOnClickListener { persist(allowPrompt = true) { finish() } }
        onBackPressedDispatcher.addCallback(this) { persist(allowPrompt = true) { finish() } }
        delete.setOnClickListener { deleteNote() }

        tts = TextToSpeech(this) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) tts?.language = Locale.getDefault()
        }
    }

    override fun onPause() {
        super.onPause()
        // Leaving the screen saves without asking; a secure note that needs unlocking is then not saved.
        persist(allowPrompt = false)
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }

    /** Reads the notes store, applies [change] and writes it back. Secure notes may throw UserNotAuthenticatedException. */
    private fun update(change: (MutableList<Note>) -> Unit) {
        val notes = if (secure) SecureVault.load(this) else NoteStore.load(this)
        change(notes)
        if (secure) SecureVault.save(this, notes) else NoteStore.save(this, notes)
    }

    /** Saves the text. An empty note is removed. [then] runs once it is saved (or given up on). */
    private fun persist(allowPrompt: Boolean, then: () -> Unit = {}) {
        if (deleted) {
            then()
            return
        }
        try {
            val text = body.text.toString().trim()
            update { notes ->
                notes.removeAll { it.id == noteId }
                if (text.isNotEmpty()) notes.add(Note(noteId, text, createdAt, day))
            }
            then()
        } catch (e: UserNotAuthenticatedException) {
            if (allowPrompt) {
                SecureVault.authenticate(this, onSuccess = { persist(allowPrompt = false, then = then) }, onCancel = {})
            } else {
                then()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not save the note", e)
            Toast.makeText(this, R.string.note_save_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun deleteNote() {
        try {
            update { notes -> notes.removeAll { it.id == noteId } }
            deleted = true
            finish()
        } catch (e: UserNotAuthenticatedException) {
            SecureVault.authenticate(this, onSuccess = { deleteNote() }, onCancel = {})
        }
    }

    private fun dictate() {
        val speech = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.note_dictate_prompt))
        }
        if (speech.resolveActivity(packageManager) == null) {
            Toast.makeText(this, R.string.note_no_speech, Toast.LENGTH_LONG).show()
        } else {
            speechLauncher.launch(speech)
        }
    }

    private fun readAloud() {
        val text = body.text.toString().trim()
        if (text.isEmpty()) return
        if (!ttsReady) {
            Toast.makeText(this, R.string.note_tts_unavailable, Toast.LENGTH_LONG).show()
        } else {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "note")
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
        private const val TAG = "NoteEditActivity"
        private const val EXTRA_ID = "note_id"
        private const val EXTRA_TEXT = "note_text"
        private const val EXTRA_CREATED = "note_created"
        private const val EXTRA_DAY = "note_day"
        private const val EXTRA_SECURE = "note_secure"
        private const val NEW_NOTE = -1L

        /** An intent to open a note. [note] = null starts a new one; [day] starts a journal entry for that day. */
        fun intentFor(context: Context, note: Note? = null, secure: Boolean = false, day: String? = null): Intent =
            Intent(context, NoteEditActivity::class.java)
                .putExtra(EXTRA_ID, note?.id ?: NEW_NOTE)
                .putExtra(EXTRA_TEXT, note?.text.orEmpty())
                .putExtra(EXTRA_CREATED, note?.createdAt ?: 0L)
                .putExtra(EXTRA_DAY, note?.day ?: day)
                .putExtra(EXTRA_SECURE, secure)
    }
}
