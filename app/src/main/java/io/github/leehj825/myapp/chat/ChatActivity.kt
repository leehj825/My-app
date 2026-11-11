package io.github.leehj825.myapp.chat

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import io.github.leehj825.myapp.R
import io.github.leehj825.myapp.voice.VoicePrefs
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Chat with a GGUF model on the phone. Load your model file once; it is copied into the app's storage.
 * Type a message or dictate it, and the reply is written and read aloud in your voice settings.
 * Nothing is sent off the phone.
 */
class ChatActivity : ComponentActivity() {

    private lateinit var status: TextView
    private lateinit var log: LinearLayout
    private lateinit var logScroll: ScrollView
    private lateinit var input: EditText
    private lateinit var send: Button
    private lateinit var loadModel: Button
    private lateinit var mic: Button

    // All model calls run on this one thread; the UI is updated through [main].
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    @Volatile private var modelReady = false
    private var reply: TextView? = null

    private val pickModel = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) loadFrom(uri)
    }

    private val speechLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val heard = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (result.resultCode == Activity.RESULT_OK && !heard.isNullOrBlank()) input.setText(heard)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)
        status = findViewById(R.id.chat_status)
        log = findViewById(R.id.chat_log)
        logScroll = findViewById(R.id.chat_scroll)
        input = findViewById(R.id.chat_input)
        send = findViewById(R.id.chat_send)
        loadModel = findViewById(R.id.chat_load_model)
        mic = findViewById(R.id.chat_mic)

        loadModel.setOnClickListener { pickModel.launch(arrayOf("*/*")) }
        send.setOnClickListener {
            val text = input.text.toString().trim()
            if (text.isNotEmpty()) {
                input.setText("")
                send(text)
            }
        }
        mic.setOnClickListener { dictate() }

        tts = TextToSpeech(this) { result -> ttsReady = result == TextToSpeech.SUCCESS }

        worker.execute {
            LlmEngine.init()
            val file = modelFile()
            if (file.exists()) initModel(file) else showStatus(R.string.chat_no_model)
        }
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }

    private fun modelFile(): File = File(filesDir, "llm/model.gguf")

    /** Copies the picked model into the app's storage, then loads it. Runs on the worker thread. */
    private fun loadFrom(uri: Uri) {
        showStatus(R.string.chat_copying)
        worker.execute {
            try {
                val target = modelFile()
                target.parentFile?.mkdirs()
                if (modelReady) {
                    LlmEngine.unload()
                    modelReady = false
                }
                val input = contentResolver.openInputStream(uri) ?: throw IllegalStateException("cannot open the file")
                input.use { source -> target.outputStream().use { source.copyTo(it) } }
                initModel(target)
            } catch (e: Exception) {
                Log.e(TAG, "Could not copy the model", e)
                showStatus(R.string.chat_failed)
            }
        }
    }

    /** Loads the model file and sets the assistant's instructions. Runs on the worker thread. */
    private fun initModel(file: File) {
        showStatus(R.string.chat_loading)
        val ok = LlmEngine.load(file.absolutePath) == 0 &&
            LlmEngine.prepare() == 0 &&
            LlmEngine.processSystemPrompt(SYSTEM_PROMPT) == 0
        modelReady = ok
        showStatus(if (ok) R.string.chat_ready else R.string.chat_failed)
    }

    private fun send(text: String) {
        if (!modelReady) {
            Toast.makeText(this, R.string.chat_not_ready, Toast.LENGTH_SHORT).show()
            return
        }
        addLine(getString(R.string.chat_you), text)
        reply = addLine(getString(R.string.chat_assistant), "")
        // Small models often ignore the system instruction on language, so the reply language is stated in every message.
        val prompt = "$text\n${replyLanguageHint(text)}"
        worker.execute {
            val answer = StringBuilder()
            try {
                if (LlmEngine.processUserPrompt(prompt, MAX_REPLY_TOKENS) != 0) {
                    throw IllegalStateException("the prompt could not be processed")
                }
                while (true) {
                    val piece = LlmEngine.generateNextToken() ?: break
                    answer.append(piece)
                    showReply(answer.toString())
                }
            } catch (e: Exception) {
                Log.e(TAG, "Reply failed", e)
                showStatus(R.string.chat_failed)
            }
            speak(answer.toString())
        }
    }

    /** The language to reply in: Korean if the message has Hangul, otherwise the language set in Voice settings or the phone's. */
    private fun replyLanguageHint(text: String): String {
        val language = if (VoicePrefs.containsHangul(text)) {
            "Korean"
        } else {
            VoicePrefs.locale(this).getDisplayLanguage(Locale.ENGLISH)
        }
        return "(Reply only in $language.)"
    }

    private fun addLine(who: String, text: String): TextView {
        val view = TextView(this).apply {
            this.text = "$who: $text"
            textSize = 16f
            setPadding(0, 8, 0, 8)
        }
        log.addView(view)
        main.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
        return view
    }

    private fun showReply(answer: String) {
        val prefix = getString(R.string.chat_assistant) + ": "
        main.post {
            reply?.text = prefix + answer
            logScroll.fullScroll(ScrollView.FOCUS_DOWN)
        }
    }

    private fun showStatus(resId: Int) {
        main.post { status.setText(resId) }
    }

    /** Reads the reply aloud in the chosen voice. Korean replies are read in Korean. */
    private fun speak(text: String) {
        main.post {
            val engine = tts ?: return@post
            if (!ttsReady || text.isBlank()) return@post
            val locale = if (VoicePrefs.containsHangul(text)) Locale.KOREAN else VoicePrefs.locale(this)
            if (engine.setLanguage(locale) < TextToSpeech.LANG_AVAILABLE) return@post
            VoicePrefs.voiceName(this)?.let { name ->
                engine.voices?.firstOrNull { it.name == name && it.locale.language == locale.language }?.let {
                    engine.voice = it
                }
            }
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "chat")
        }
    }

    private fun dictate() {
        val speech = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, VoicePrefs.dictationLanguage(this@ChatActivity))
            putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.chat_hint))
        }
        if (speech.resolveActivity(packageManager) == null) {
            Toast.makeText(this, R.string.note_no_speech, Toast.LENGTH_LONG).show()
        } else {
            speechLauncher.launch(speech)
        }
    }

    companion object {
        private const val TAG = "ChatActivity"
        private const val MAX_REPLY_TOKENS = 256
        private const val SYSTEM_PROMPT =
            "You are a helpful assistant. Reply briefly, in the language the user writes in. Never reply in Chinese unless the user writes in Chinese."
    }
}
