package io.github.leehj825.myapp.notes

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
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
import io.github.leehj825.myapp.ai.AiSupport
import io.github.leehj825.myapp.secure.SecureVault
import io.github.leehj825.myapp.voice.VoicePrefs
import java.util.Locale

/**
 * Writes, dictates or reads aloud one note. Used for plain notes, journal entries and secure notes.
 *
 * Plain notes are saved when you leave the screen. Secure notes need a fingerprint: the text is shown only
 * after one, and they are saved when you tap Done or Back (leaving the app by itself does not save them).
 */
class NoteEditActivity : FragmentActivity() {

    private lateinit var time: TextView
    private lateinit var body: EditText
    private lateinit var delete: Button
    private var noteId = NEW_NOTE
    private var createdAt = 0L
    private var day: String? = null
    private var secure = false
    private var loaded = false
    private var secureNotes: MutableList<Note> = mutableListOf()
    private var deleted = false
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private val speechLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val heard: List<String> = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS) ?: emptyList()
        when {
            result.resultCode != Activity.RESULT_OK || heard.isEmpty() -> Unit
            heard.size == 1 -> insertAtCursor(heard[0])
            // Several guesses: let the user pick the one that is right.
            else -> AlertDialog.Builder(this)
                .setTitle(R.string.note_pick_heard)
                .setItems(heard.toTypedArray()) { _, which -> insertAtCursor(heard[which]) }
                .show()
        }
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
        time.text = formatNoteTime(createdAt)
        delete.visibility = if (isNew) View.GONE else View.VISIBLE

        findViewById<Button>(R.id.note_dictate).setOnClickListener { dictate() }
        findViewById<Button>(R.id.note_read).setOnClickListener { readAloud() }
        // Summarize uses the phone's on-device Gemini Nano. The button is shown only if this phone supports it.
        findViewById<Button>(R.id.note_summarize).apply {
            visibility = if (AiSupport.cachedAvailable(this@NoteEditActivity)) View.VISIBLE else View.GONE
            setOnClickListener { summarizeNote() }
        }
        findViewById<Button>(R.id.note_done).setOnClickListener { persist(allowPrompt = true) { finish() } }
        onBackPressedDispatcher.addCallback(this) { persist(allowPrompt = true) { finish() } }
        delete.setOnClickListener { deleteNote() }

        tts = TextToSpeech(this) { status -> ttsReady = status == TextToSpeech.SUCCESS }

        if (secure) {
            // The text is hidden until the fingerprint is accepted.
            body.isEnabled = false
            body.hint = getString(R.string.secure_locked)
            SecureVault.read(
                this,
                onNotes = { notes ->
                    secureNotes = notes
                    loaded = true
                    body.setText(notes.find { it.id == noteId }?.text.orEmpty())
                    body.isEnabled = true
                    body.hint = getString(R.string.note_hint)
                },
                onFail = {
                    Toast.makeText(this, R.string.secure_not_opened, Toast.LENGTH_LONG).show()
                    finish()
                },
            )
        } else {
            body.setText(intent.getStringExtra(EXTRA_TEXT).orEmpty())
        }
    }

    override fun onPause() {
        super.onPause()
        persist(allowPrompt = false)
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }

    /** Saves the text. An empty note is removed. [then] runs once it is saved (or when there is nothing to save). */
    private fun persist(allowPrompt: Boolean, then: () -> Unit = {}) {
        if (deleted) {
            then()
            return
        }
        val text = body.text.toString().trim()
        if (secure) {
            if (!allowPrompt || !loaded) {
                then()
                return
            }
            val updated = secureNotes.toMutableList().apply {
                removeAll { it.id == noteId }
                if (text.isNotEmpty()) add(Note(noteId, text, createdAt, day))
            }
            SecureVault.write(
                this,
                updated,
                onSaved = {
                    secureNotes = updated
                    then()
                },
                onFail = { Toast.makeText(this, R.string.note_save_failed, Toast.LENGTH_LONG).show() },
            )
            return
        }
        try {
            val notes = NoteStore.load(this)
            notes.removeAll { it.id == noteId }
            if (text.isNotEmpty()) notes.add(Note(noteId, text, createdAt, day))
            NoteStore.save(this, notes)
            then()
        } catch (e: Exception) {
            Log.e(TAG, "Could not save the note", e)
            Toast.makeText(this, R.string.note_save_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun deleteNote() {
        if (secure) {
            if (!loaded) return
            val updated = secureNotes.filter { it.id != noteId }.toMutableList()
            SecureVault.write(
                this,
                updated,
                onSaved = {
                    deleted = true
                    finish()
                },
                onFail = { Toast.makeText(this, R.string.note_save_failed, Toast.LENGTH_LONG).show() },
            )
            return
        }
        try {
            val notes = NoteStore.load(this)
            notes.removeAll { it.id == noteId }
            NoteStore.save(this, notes)
            deleted = true
            finish()
        } catch (e: Exception) {
            Log.e(TAG, "Could not delete the note", e)
            Toast.makeText(this, R.string.note_save_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun dictate() {
        val language = VoicePrefs.dictationLanguage(this)
        val speech = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.note_dictate_prompt))
        }
        if (speech.resolveActivity(packageManager) == null) {
            Toast.makeText(this, R.string.note_no_speech, Toast.LENGTH_LONG).show()
        } else {
            speechLauncher.launch(speech)
        }
    }

    /** Reads the note in the chosen voice. Korean text is always read in Korean. */
    private fun readAloud() {
        val text = body.text.toString().trim()
        val engine = tts
        if (text.isEmpty()) return
        if (!ttsReady || engine == null) {
            Toast.makeText(this, R.string.note_tts_unavailable, Toast.LENGTH_LONG).show()
            return
        }
        val locale = if (VoicePrefs.containsHangul(text)) Locale.KOREAN else VoicePrefs.locale(this)
        if (engine.setLanguage(locale) < TextToSpeech.LANG_AVAILABLE) {
            Toast.makeText(this, R.string.note_tts_language_missing, Toast.LENGTH_LONG).show()
            return
        }
        // Set after the language, because changing the language can reset the voice.
        VoicePrefs.voiceName(this)?.let { name ->
            engine.voices?.firstOrNull { it.name == name && it.locale.language == locale.language }?.let {
                engine.voice = it
            }
        }
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "note")
    }

    /** Shows an on-device summary of the text. The user chooses whether to insert it. */
    private fun summarizeNote() {
        val text = body.text.toString().trim()
        if (text.isEmpty()) return
        AiSupport.summarize(this, text) { summary ->
            if (summary.isNullOrBlank()) {
                Toast.makeText(this, R.string.note_ai_failed, Toast.LENGTH_LONG).show()
            } else {
                AlertDialog.Builder(this)
                    .setTitle(R.string.note_summary_title)
                    .setMessage(summary)
                    .setPositiveButton(R.string.note_summary_insert) { _, _ -> insertAtCursor(summary) }
                    .setNegativeButton(R.string.note_close, null)
                    .show()
            }
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
