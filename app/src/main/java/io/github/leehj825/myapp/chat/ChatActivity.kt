package io.github.leehj825.myapp.chat

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import io.github.leehj825.myapp.R
import io.github.leehj825.myapp.notes.formatNoteTime
import io.github.leehj825.myapp.voice.VoicePrefs
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Chat with a GGUF model on the phone, or with Gemini in the cloud when that is ticked.
 * Chats are saved on the phone; New chat starts a blank one, and Chats opens a saved one.
 * The "Memory" setting decides how many earlier messages are sent with each new message.
 * With "Search the web" on, the question is searched with Tavily first, and the sources are listed under the reply.
 */
class ChatActivity : ComponentActivity() {

    private lateinit var status: TextView
    private lateinit var progress: TextView
    private lateinit var log: LinearLayout
    private lateinit var logScroll: ScrollView
    private lateinit var newChatButton: Button
    private lateinit var openChatButton: Button
    private lateinit var memorySpinner: Spinner
    private lateinit var geminiCheck: CheckBox
    private lateinit var geminiMemoryLabel: TextView
    private lateinit var geminiMemory: Spinner
    private lateinit var webSearch: CheckBox
    private lateinit var input: EditText
    private lateinit var sendButton: Button
    private lateinit var stopButton: Button
    private lateinit var loadModel: Button
    private lateinit var mic: Button

    // All model calls and web searches run on this one thread; the UI is updated through [main].
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    @Volatile private var modelReady = false
    private var reply: TextView? = null

    // The open chat. Changed only on the main thread.
    private var chatId = ""
    private var messages: MutableList<ChatMessage> = mutableListOf()

    // Progress of the reply in progress.
    @Volatile private var generating = false
    @Volatile private var searching = false
    @Volatile private var stopRequested = false
    @Volatile private var tokens = 0
    private var startedAt = 0L

    /** Updates the progress line once a second while a reply is in progress. */
    private val ticker = object : Runnable {
        override fun run() {
            if (!generating) return
            val seconds = (System.currentTimeMillis() - startedAt) / 1000
            progress.text = when {
                searching -> getString(R.string.chat_searching, seconds)
                tokens == 0 -> getString(R.string.chat_thinking, seconds)
                else -> getString(R.string.chat_writing, tokens, seconds)
            }
            main.postDelayed(this, 1000)
        }
    }

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
        progress = findViewById(R.id.chat_progress)
        log = findViewById(R.id.chat_log)
        logScroll = findViewById(R.id.chat_scroll)
        newChatButton = findViewById(R.id.chat_new)
        openChatButton = findViewById(R.id.chat_open)
        memorySpinner = findViewById(R.id.chat_memory)
        geminiCheck = findViewById(R.id.chat_gemini)
        geminiMemoryLabel = findViewById(R.id.chat_gemini_memory_label)
        geminiMemory = findViewById(R.id.chat_gemini_memory)
        webSearch = findViewById(R.id.chat_web_search)
        input = findViewById(R.id.chat_input)
        sendButton = findViewById(R.id.chat_send)
        stopButton = findViewById(R.id.chat_stop)
        loadModel = findViewById(R.id.chat_load_model)
        mic = findViewById(R.id.chat_mic)

        // Shown only in builds that have a Gemini key.
        geminiCheck.visibility = if (GeminiClient.available) View.VISIBLE else View.GONE
        geminiCheck.setOnCheckedChangeListener { _, checked ->
            if (checked) showStatusText(getString(R.string.chat_gemini_selected))
            updateGeminiMemoryVisibility()
        }
        geminiMemory.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            resources.getStringArray(R.array.chat_gemini_memory_options)
        )
        geminiMemory.setSelection(GEMINI_MEMORY_VALUES.indexOf(ChatStore.geminiMemoryTurns(this)).coerceAtLeast(0))
        geminiMemory.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                ChatStore.setGeminiMemoryTurns(this@ChatActivity, GEMINI_MEMORY_VALUES[position])
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        updateGeminiMemoryVisibility()

        chatId = ChatStore.currentId(this)
        messages = ChatStore.load(this, chatId)
        renderLog()

        memorySpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            resources.getStringArray(R.array.chat_memory_options)
        )
        memorySpinner.setSelection(MEMORY_VALUES.indexOf(ChatStore.memoryTurns(this)).coerceAtLeast(0))
        memorySpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                ChatStore.setMemoryTurns(this@ChatActivity, MEMORY_VALUES[position])
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        newChatButton.setOnClickListener { startNewChat() }
        openChatButton.setOnClickListener { chooseChat() }
        loadModel.setOnClickListener { pickModel.launch(arrayOf("*/*")) }
        sendButton.setOnClickListener {
            val text = input.text.toString().trim()
            if (text.isNotEmpty()) {
                input.setText("")
                send(text)
            }
        }
        stopButton.setOnClickListener { stopRequested = true }
        mic.setOnClickListener { dictate() }
        webSearch.setOnCheckedChangeListener { _, checked ->
            if (checked && WebSearch.apiKey(this) == null) askForApiKey()
        }

        tts = TextToSpeech(this) { result -> ttsReady = result == TextToSpeech.SUCCESS }

        worker.execute {
            LlmEngine.init()
            val file = modelFile()
            if (file.exists()) initModel(file) else showStatus(R.string.chat_no_model)
        }
    }

    override fun onDestroy() {
        generating = false
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }

    private fun modelFile(): File = File(filesDir, "llm/model.gguf")

    /** Starts a blank chat. The one being written is already saved. */
    private fun startNewChat() {
        if (generating) {
            Toast.makeText(this, R.string.chat_busy, Toast.LENGTH_SHORT).show()
            return
        }
        chatId = ChatStore.newChat(this)
        messages = mutableListOf()
        reply = null
        progress.text = ""
        renderLog()
    }

    /** Lists the saved chats. Picking one offers to open it or delete it. */
    private fun chooseChat() {
        if (generating) {
            Toast.makeText(this, R.string.chat_busy, Toast.LENGTH_SHORT).show()
            return
        }
        val chats = ChatStore.list(this)
        if (chats.isEmpty()) {
            Toast.makeText(this, R.string.chat_none, Toast.LENGTH_SHORT).show()
            return
        }
        val labels = chats.map { "${formatNoteTime(it.modified)}  ${it.title}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.chat_open)
            .setItems(labels) { _, which -> chatActions(chats[which]) }
            .setNegativeButton(R.string.note_close, null)
            .show()
    }

    private fun chatActions(chat: ChatSummary) {
        AlertDialog.Builder(this)
            .setTitle(chat.title)
            .setItems(arrayOf(getString(R.string.chat_action_open), getString(R.string.chat_action_delete))) { _, which ->
                if (which == 0) openChat(chat.id) else confirmDelete(chat)
            }
            .setNegativeButton(R.string.chat_key_cancel, null)
            .show()
    }

    private fun openChat(id: String) {
        chatId = id
        ChatStore.setCurrent(this, chatId)
        messages = ChatStore.load(this, chatId)
        reply = null
        progress.text = ""
        renderLog()
    }

    private fun confirmDelete(chat: ChatSummary) {
        AlertDialog.Builder(this)
            .setMessage(R.string.chat_delete_confirm)
            .setPositiveButton(R.string.chat_action_delete) { _, _ -> deleteChat(chat.id) }
            .setNegativeButton(R.string.chat_key_cancel, null)
            .show()
    }

    /** Deletes a saved chat. If it was the open one, a new blank chat is started. */
    private fun deleteChat(id: String) {
        if (generating) {
            Toast.makeText(this, R.string.chat_busy, Toast.LENGTH_SHORT).show()
            return
        }
        ChatStore.delete(this, id)
        if (id == chatId) {
            startNewChat()
        }
        Toast.makeText(this, R.string.chat_deleted, Toast.LENGTH_SHORT).show()
    }

    /** The Gemini memory setting is shown only when Gemini is ticked. */
    private fun updateGeminiMemoryVisibility() {
        val show = geminiCheck.visibility == View.VISIBLE && geminiCheck.isChecked
        val visibility = if (show) View.VISIBLE else View.GONE
        geminiMemoryLabel.visibility = visibility
        geminiMemory.visibility = visibility
    }

    /** Shows the messages of the open chat. */
    private fun renderLog() {
        log.removeAllViews()
        for (m in messages) {
            addLine(getString(if (m.role == "user") R.string.chat_you else R.string.chat_assistant), m.text)
        }
        main.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    /** Asks for the Tavily key once. If cancelled, web search is turned off again. */
    private fun askForApiKey() {
        val field = EditText(this).apply {
            hint = getString(R.string.chat_key_hint)
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.chat_key_title)
            .setMessage(R.string.chat_key_note)
            .setView(field)
            .setPositiveButton(R.string.chat_key_save) { _, _ ->
                val key = field.text.toString().trim()
                if (key.isEmpty()) {
                    webSearch.isChecked = false
                } else {
                    WebSearch.saveApiKey(this, key)
                }
            }
            .setNegativeButton(R.string.chat_key_cancel) { _, _ -> webSearch.isChecked = false }
            .setOnCancelListener { webSearch.isChecked = false }
            .show()
    }

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
        if (ok) {
            showStatusText(getString(R.string.chat_ready_with, LlmEngine.modelInfo()))
        } else {
            showStatus(R.string.chat_failed)
        }
    }

    /**
     * The earlier messages to send with a new one: the last [turns] (or all if -1).
     * If [maxChars] is set, the newest part is kept within that size.
     */
    private fun memoryFor(all: List<ChatMessage>, turns: Int, maxChars: Int = Int.MAX_VALUE): List<ChatMessage> {
        val picked = if (turns < 0) all else all.takeLast(turns)
        var size = 0
        val kept = ArrayList<ChatMessage>()
        for (m in picked.asReversed()) {
            size += m.text.length
            if (size > maxChars) break
            kept.add(0, m)
        }
        return kept
    }

    /** Adds a message from the user to the open chat and saves it. */
    private fun addUserMessage(text: String) {
        messages.add(ChatMessage("user", text))
        ChatStore.save(this, chatId, messages)
        addLine(getString(R.string.chat_you), text)
    }

    /** Adds the assistant's reply to the open chat and saves it. Runs on the main thread. */
    private fun addAssistantMessage(answer: String) {
        if (answer.isBlank()) return
        messages.add(ChatMessage("assistant", answer))
        ChatStore.save(this, chatId, messages)
    }

    private fun send(text: String) {
        if (geminiCheck.visibility == View.VISIBLE && geminiCheck.isChecked) {
            sendToGemini(text)
            return
        }
        if (!modelReady) {
            Toast.makeText(this, R.string.chat_not_ready, Toast.LENGTH_SHORT).show()
            return
        }
        val history = memoryFor(messages, ChatStore.memoryTurns(this), MAX_HISTORY_CHARS)
        addUserMessage(text)
        reply = addLine(getString(R.string.chat_assistant), "")
        val apiKey = if (webSearch.isChecked) WebSearch.apiKey(this) else null

        startedAt = System.currentTimeMillis()
        tokens = 0
        stopRequested = false
        searching = apiKey != null
        generating = true
        stopButton.visibility = View.VISIBLE
        sendButton.isEnabled = false
        main.post(ticker)

        worker.execute {
            val answer = StringBuilder()
            var sources: List<WebResult> = emptyList()
            try {
                if (apiKey != null) {
                    try {
                        sources = WebSearch.search(apiKey, text)
                    } catch (e: Exception) {
                        Log.e(TAG, "Web search failed", e)
                        showStatus(R.string.chat_search_failed)
                    }
                }
                searching = false

                // The model keeps no memory of its own: the chat sends what the memory setting allows.
                val prompt = buildPrompt(history, text, sources)
                if (LlmEngine.processSystemPrompt(SYSTEM_PROMPT) != 0) {
                    throw IllegalStateException("the conversation could not be reset")
                }
                if (LlmEngine.processUserPrompt(prompt, MAX_REPLY_TOKENS) != 0) {
                    throw IllegalStateException("the prompt could not be processed")
                }
                while (!stopRequested) {
                    val piece = LlmEngine.generateNextToken() ?: break
                    answer.append(piece)
                    tokens++
                    showReply(answer.toString())
                }
            } catch (e: Exception) {
                Log.e(TAG, "Reply failed", e)
                showStatus(R.string.chat_failed)
            } finally {
                searching = false
                main.post {
                    addAssistantMessage(answer.toString())
                    finishReply()
                }
            }
            showSources(sources)
            speak(answer.toString())
        }
    }

    /** Sends the chat (as the memory setting allows) to Gemini in the cloud, and shows the reply. */
    private fun sendToGemini(text: String) {
        val conversation = memoryFor(messages, ChatStore.geminiMemoryTurns(this)) + ChatMessage("user", text)
        addUserMessage(text)
        reply = addLine(getString(R.string.chat_assistant), "")
        startedAt = System.currentTimeMillis()
        tokens = 0
        searching = false
        generating = true
        stopButton.visibility = View.GONE
        sendButton.isEnabled = false
        main.post(ticker)
        worker.execute {
            var answer = ""
            try {
                answer = GeminiClient.generate(conversation)
                tokens = answer.length
                showReply(answer)
                showStatusText(getString(R.string.chat_gemini_model, GeminiClient.lastModel.orEmpty()))
            } catch (e: Exception) {
                Log.e(TAG, "Gemini failed", e)
                // Show the reason (for example the HTTP code) so the cause can be found.
                showStatusText(getString(R.string.chat_gemini_failed) + " (" + (e.message ?: e.javaClass.simpleName) + ")")
            } finally {
                val finished = answer
                main.post {
                    addAssistantMessage(finished)
                    finishReply()
                }
            }
            speak(answer)
        }
    }

    /** The message to the model: the earlier conversation, the new message, and any web results. */
    private fun buildPrompt(history: List<ChatMessage>, question: String, sources: List<WebResult>): String {
        val conversation = if (history.isEmpty()) {
            question
        } else {
            buildString {
                append("Conversation so far:\n")
                for (m in history) {
                    append(if (m.role == "user") "User: " else "Assistant: ")
                    append(m.text).append('\n')
                }
                append("\nNew message from the user: ").append(question)
            }
        }
        if (sources.isEmpty()) return conversation
        val results = sources.mapIndexed { i, r -> "[${i + 1}] ${r.title}\n${r.content.take(700)}" }
            .joinToString("\n\n")
        return "Use these web search results to answer the question. Cite the results you use as [1], [2]. " +
            "If they do not answer it, say so.\n\nResults:\n$results\n\n$conversation"
    }

    /** Lists the sources under the reply, with their links. */
    private fun showSources(sources: List<WebResult>) {
        if (sources.isEmpty()) return
        val body = sources.mapIndexed { i, r -> "[${i + 1}] ${r.title}\n${r.url}" }.joinToString("\n")
        main.post {
            log.addView(TextView(this).apply {
                text = getString(R.string.chat_sources) + "\n" + body
                textSize = 13f
                setPadding(0, 0, 0, 8)
            })
            logScroll.fullScroll(ScrollView.FOCUS_DOWN)
        }
    }

    /** Ends the progress display after a reply, successful or not. */
    private fun finishReply() {
        generating = false
        stopButton.visibility = View.GONE
        sendButton.isEnabled = true
        val seconds = (System.currentTimeMillis() - startedAt) / 1000
        progress.text = getString(R.string.chat_done, tokens, seconds)
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

    private fun showStatusText(text: String) {
        main.post { status.text = text }
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
        private const val MAX_REPLY_TOKENS = 512
        // The memory choices, in the order of the dropdown. -1 is the whole chat.
        private val MEMORY_VALUES = listOf(0, 4, 10, -1)
        // The Gemini memory choices, in the order of their dropdown.
        private val GEMINI_MEMORY_VALUES = listOf(0, 2, 4, 10, 20, -1)
        // Earlier messages sent to the on-phone model are cut to this many characters, to fit its context.
        private const val MAX_HISTORY_CHARS = 3000
        private const val SYSTEM_PROMPT =
            "You are a helpful assistant. Reply in the language the user writes in."
    }
}
