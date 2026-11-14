package io.github.leehj825.myapp.image

import android.app.AlertDialog
import android.content.ClipData
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.format.Formatter
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import io.github.leehj825.myapp.R
import io.github.leehj825.myapp.chat.GeminiClient
import io.github.leehj825.myapp.chat.LlmEngine
import io.github.leehj825.myapp.notes.formatNoteTime
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.random.Random

/**
 * Makes a picture from a description. Either on the phone, with a single-file GGUF image model you load
 * (Stable Diffusion style), or with Google's image model when "Use Gemini" is ticked.
 * The picture is shown and saved to Pictures/MyApp, and listed in History with what it was made from.
 * On the phone, progress is shown and a picture can be cancelled, and a loaded ESRGAN upscaler can make the last
 * picture larger. With Gemini, the description is sent to Google.
 */
class ImageActivity : ComponentActivity() {

    private lateinit var prompt: EditText
    private lateinit var geminiCheck: CheckBox
    private lateinit var loadModel: Button
    private lateinit var removeModel: Button
    private lateinit var chooseModel: Button
    private lateinit var improveButton: Button
    private lateinit var startPick: Button
    private lateinit var startLast: Button
    private lateinit var startClear: Button
    private lateinit var strengthField: EditText
    private lateinit var allowAllFiles: Button
    private lateinit var loadUpscaler: Button
    private lateinit var presetSpinner: Spinner
    private lateinit var stepsField: EditText
    private lateinit var threadsField: EditText
    private lateinit var negativeField: EditText
    private lateinit var seedField: EditText
    private lateinit var gpuCheck: CheckBox
    private lateinit var sizeSpinner: Spinner
    private lateinit var samplerSpinner: Spinner
    private lateinit var generate: Button
    private lateinit var cancelButton: Button
    private lateinit var upscaleButton: Button
    private lateinit var historyButton: Button
    private lateinit var progress: TextView
    private lateinit var preview: ImageView
    private lateinit var status: TextView

    // All model calls run on this one thread; the UI is updated through [main].
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var modelReady = false
    // The settings for the loaded model: the preset (guidance) and the number of steps it is run with.
    @Volatile private var preset = ImagePreset.SD
    @Volatile private var steps = ImagePreset.SD.steps
    // How many CPU threads the model uses. It is set when the model is loaded, so changing it reloads the model.
    @Volatile private var threads = DEFAULT_THREADS
    // Whether to use the GPU. Matters only in a build with the Vulkan backend; changing it reloads the model.
    @Volatile private var useGpu = true
    // The picture size (a square, in pixels) and sampler for the loaded model.
    @Volatile private var imageSize = ImagePreset.SD.size
    @Volatile private var sampler = ImagePreset.SD.sampler
    // From the negative prompt and seed boxes, read when Generate is tapped. No seed means a random one each time.
    @Volatile private var negative = ""
    @Volatile private var seedValue: Long? = null
    // A start picture, if one is set: the next pictures are made as changes of it, by [strength].
    @Volatile private var startBitmap: Bitmap? = null
    @Volatile private var strength = DEFAULT_STRENGTH

    // True while a picture is being made or made larger. Model changes wait until it is over.
    @Volatile private var busy = false
    @Volatile private var cancelRequested = false
    // True while the progress line is being updated from the model's progress.
    @Volatile private var trackProgress = false
    private var startedAt = 0L

    // The last picture made on the phone, kept so the upscaler can make it larger.
    @Volatile private var lastRgb: ByteArray? = null
    @Volatile private var lastRgbSize = 0
    @Volatile private var lastEntry: HistoryEntry? = null
    @Volatile private var upscalerReady = false

    /** Updates the progress line twice a second while a picture is being made on the phone. */
    private val ticker = object : Runnable {
        override fun run() {
            if (!busy || !trackProgress) return
            val seconds = ((SystemClock.elapsedRealtime() - startedAt) / 1000).toInt()
            val step = LocalImage.progressStep()
            val total = LocalImage.progressSteps()
            progress.text = when {
                cancelRequested -> getString(R.string.image_cancelling)
                total > 0 && step > 0 -> getString(R.string.image_step_progress, step, total, seconds)
                else -> getString(R.string.image_preparing, seconds)
            }
            main.postDelayed(this, 500)
        }
    }

    private val pickModel = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) loadFrom(uri)
    }

    private val pickStart = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) loadStartPicture(uri)
    }

    private val pickUpscaler = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) loadUpscalerFrom(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_image)
        prompt = findViewById(R.id.image_prompt)
        geminiCheck = findViewById(R.id.image_gemini)
        loadModel = findViewById(R.id.image_load_model)
        removeModel = findViewById(R.id.image_remove_model)
        chooseModel = findViewById(R.id.image_choose_model)
        improveButton = findViewById(R.id.image_improve)
        startPick = findViewById(R.id.image_start_pick)
        startLast = findViewById(R.id.image_start_last)
        startClear = findViewById(R.id.image_start_clear)
        strengthField = findViewById(R.id.image_strength)
        allowAllFiles = findViewById(R.id.image_allow_all_files)
        loadUpscaler = findViewById(R.id.image_load_upscaler)
        presetSpinner = findViewById(R.id.image_preset)
        stepsField = findViewById(R.id.image_steps)
        threadsField = findViewById(R.id.image_threads)
        negativeField = findViewById(R.id.image_negative)
        seedField = findViewById(R.id.image_seed)
        gpuCheck = findViewById(R.id.image_gpu)
        sizeSpinner = findViewById(R.id.image_size)
        samplerSpinner = findViewById(R.id.image_sampler)
        generate = findViewById(R.id.image_generate)
        cancelButton = findViewById(R.id.image_cancel)
        upscaleButton = findViewById(R.id.image_upscale)
        historyButton = findViewById(R.id.image_history)
        progress = findViewById(R.id.image_progress)
        preview = findViewById(R.id.image_preview)
        status = findViewById(R.id.image_status)

        // Gemini is offered only in builds that have a Gemini key. Without the on-phone library it is the only choice.
        geminiCheck.visibility = if (GeminiClient.available) View.VISIBLE else View.GONE
        if (!LocalImage.libraryLoaded) geminiCheck.isChecked = true
        geminiCheck.setOnCheckedChangeListener { _, _ -> updateModelControls() }
        updateModelControls()

        presetSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            ImagePreset.values().map { getString(it.labelRes) }
        )
        sizeSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            SIZES.map { "$it x $it" }
        )
        samplerSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            Sampler.values().map { getString(it.labelRes) }
        )
        negative = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_NEGATIVE, "") ?: ""
        negativeField.setText(negative)

        // Choosing a model type sets its steps, size and sampler, which can then be changed one by one.
        presetSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val chosen = ImagePreset.values()[position]
                if (chosen != preset) {
                    preset = chosen
                    steps = chosen.steps
                    imageSize = chosen.size
                    sampler = chosen.sampler
                    stepsField.setText(steps.toString())
                    sizeSpinner.setSelection(SIZES.indexOf(imageSize).coerceAtLeast(0))
                    samplerSpinner.setSelection(sampler.ordinal)
                    saveSettings()
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        sizeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (SIZES[position] != imageSize) {
                    imageSize = SIZES[position]
                    saveSettings()
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        samplerSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val chosen = Sampler.values()[position]
                if (chosen != sampler) {
                    sampler = chosen
                    saveSettings()
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        threads = getSharedPreferences(PREFS, MODE_PRIVATE).getInt(KEY_THREADS, DEFAULT_THREADS)
        threadsField.setText(threads.toString())
        useGpu = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_GPU, true)
        gpuCheck.isChecked = useGpu

        loadModel.setOnClickListener {
            if (busy) toastBusy() else pickModel.launch(arrayOf("*/*"))
        }
        removeModel.setOnClickListener { confirmRemoveModel() }
        chooseModel.setOnClickListener { showModels() }
        improveButton.setOnClickListener { improvePrompt() }
        startPick.setOnClickListener { pickStart.launch(arrayOf("image/*")) }
        startLast.setOnClickListener {
            val rgb = lastRgb
            if (rgb == null) {
                Toast.makeText(this, R.string.image_upscale_none, Toast.LENGTH_SHORT).show()
            } else {
                setStartPicture(rgbToBitmap(rgb, lastRgbSize, lastRgbSize))
            }
        }
        startClear.setOnClickListener { setStartPicture(null) }
        strength = getSharedPreferences(PREFS, MODE_PRIVATE).getFloat(KEY_STRENGTH, DEFAULT_STRENGTH)
        strengthField.setText(strength.toString())
        allowAllFiles.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
                )
            }
        }
        loadUpscaler.setOnClickListener {
            if (busy) toastBusy() else pickUpscaler.launch(arrayOf("*/*"))
        }
        generate.setOnClickListener { onGenerate() }
        cancelButton.setOnClickListener { onCancel() }
        upscaleButton.setOnClickListener { onUpscale() }
        historyButton.setOnClickListener { showHistory() }

        if (LocalImage.libraryLoaded) {
            worker.execute {
                migrateOldModel()
                val active = activeModel()
                when {
                    // Still loaded from an earlier visit to this tool: no need to load it again.
                    LocalImage.isLoaded() -> {
                        modelReady = true
                        showSettings(modelName())
                        showStatusText(getString(R.string.image_model_ready, modelName(), modelSizeText()))
                    }
                    active != null -> initModel(File(active.path))
                    else -> showStatus(R.string.image_model_none)
                }
                restoreUpscaler()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Coming back from the All files access screen: show or hide its button.
        updateModelControls()
    }

    /** True when pictures are made in the cloud. Otherwise they are made on the phone. */
    private fun useGemini(): Boolean = GeminiClient.available && (!LocalImage.libraryLoaded || geminiCheck.isChecked)

    /** The model file and its settings are shown only when pictures are made on the phone. */
    private fun updateModelControls() {
        val visibility = if (LocalImage.libraryLoaded && !useGemini()) View.VISIBLE else View.GONE
        loadModel.visibility = visibility
        removeModel.visibility = visibility
        chooseModel.visibility = visibility
        improveButton.visibility = if (visibility == View.VISIBLE && LlmEngine.libraryLoaded) View.VISIBLE else View.GONE
        startPick.visibility = visibility
        allowAllFiles.visibility =
            if (visibility == View.VISIBLE && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !hasAllFilesAccess()) {
                View.VISIBLE
            } else {
                View.GONE
            }
        loadUpscaler.visibility = visibility
        presetSpinner.visibility = visibility
        stepsField.visibility = visibility
        threadsField.visibility = visibility
        negativeField.visibility = visibility
        seedField.visibility = visibility
        // The GPU choice is offered only when this build of the library has the GPU backend.
        gpuCheck.visibility = if (visibility == View.VISIBLE && LocalImage.libraryLoaded && LocalImage.hasVulkan()) {
            View.VISIBLE
        } else {
            View.GONE
        }
        sizeSpinner.visibility = visibility
        samplerSpinner.visibility = visibility
        updateUpscaleButton()
    }

    /** Start picture controls: the strength box and Clear show once a start picture is set. */
    private fun updateStartControls() {
        val local = LocalImage.libraryLoaded && !useGemini()
        val hasStart = startBitmap != null
        startLast.visibility = if (local && lastRgb != null) View.VISIBLE else View.GONE
        strengthField.visibility = if (local && hasStart) View.VISIBLE else View.GONE
        startClear.visibility = if (local && hasStart) View.VISIBLE else View.GONE
    }

    /** Sets (or with null, clears) the start picture and shows it. Main thread. */
    private fun setStartPicture(bitmap: Bitmap?) {
        startBitmap = bitmap
        if (bitmap != null) {
            preview.setImageBitmap(bitmap)
            status.setText(R.string.image_start_set)
        }
        updateStartControls()
    }

    /** Opens a picked picture to use as the start picture. */
    private fun loadStartPicture(uri: Uri) {
        worker.execute {
            val bitmap = decodeScaled(uri)
            main.post {
                if (bitmap == null) {
                    Toast.makeText(this, R.string.image_start_failed, Toast.LENGTH_LONG).show()
                } else {
                    setStartPicture(bitmap)
                }
            }
        }
    }

    /** The picture in the middle of [source], cut square and scaled to [size] pixels, as RGB bytes. */
    private fun bitmapToRgb(source: Bitmap, size: Int): ByteArray {
        val side = minOf(source.width, source.height)
        val square = Bitmap.createBitmap(source, (source.width - side) / 2, (source.height - side) / 2, side, side)
        val scaled = Bitmap.createScaledBitmap(square, size, size, true)
        val pixels = IntArray(size * size)
        scaled.getPixels(pixels, 0, size, 0, 0, size, size)
        val rgb = ByteArray(size * size * 3)
        for (i in pixels.indices) {
            val p = pixels[i]
            rgb[3 * i] = (p shr 16).toByte()
            rgb[3 * i + 1] = (p shr 8).toByte()
            rgb[3 * i + 2] = p.toByte()
        }
        return rgb
    }

    /** The Upscale button shows when pictures are made on the phone, an upscaler is loaded and there is a picture. */
    private fun updateUpscaleButton() {
        val show = LocalImage.libraryLoaded && !useGemini() && upscalerReady && lastRgb != null
        upscaleButton.visibility = if (show) View.VISIBLE else View.GONE
        updateStartControls()
    }

    private fun presetKey(name: String): String = "preset:$name"

    private fun stepsKey(name: String): String = "steps:$name"

    private fun sizeKey(name: String): String = "size:$name"

    private fun samplerKey(name: String): String = "sampler:$name"

    /** Remembers the model type, steps, size and sampler for the loaded model. */
    private fun saveSettings() {
        val name = modelName()
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString(presetKey(name), preset.name)
            .putInt(stepsKey(name), steps)
            .putInt(sizeKey(name), imageSize)
            .putString(samplerKey(name), sampler.name)
            .apply()
    }

    /** The settings remembered for this model. A new file starts on the type its name suggests. */
    private fun presetFor(name: String): ImagePreset {
        val saved = getSharedPreferences(PREFS, MODE_PRIVATE).getString(presetKey(name), null)
        ImagePreset.values().firstOrNull { it.name == saved }?.let { return it }
        val lower = name.lowercase(Locale.ROOT)
        return when {
            "sdxs" in lower -> ImagePreset.SDXS
            "sdxl" in lower && listOf("lightning", "hyper", "dmd", "turbo", "lcm").any { it in lower } ->
                ImagePreset.SDXL_FAST
            "sdxl" in lower -> ImagePreset.SDXL
            else -> ImagePreset.SD
        }
    }

    /** Sets the settings for the loaded model and shows them. Any thread. */
    private fun showSettings(name: String) {
        val chosen = presetFor(name)
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val savedSteps = prefs.getInt(stepsKey(name), chosen.steps)
        val savedSize = prefs.getInt(sizeKey(name), chosen.size).takeIf { it in SIZES } ?: chosen.size
        val savedSampler = Sampler.values().firstOrNull { it.name == prefs.getString(samplerKey(name), null) }
            ?: chosen.sampler
        preset = chosen
        steps = savedSteps
        imageSize = savedSize
        sampler = savedSampler
        main.post {
            presetSpinner.setSelection(chosen.ordinal)
            stepsField.setText(savedSteps.toString())
            sizeSpinner.setSelection(SIZES.indexOf(savedSize))
            samplerSpinner.setSelection(savedSampler.ordinal)
        }
    }

    /** Reads the negative prompt, seed and GPU boxes and remembers them. Main thread. */
    private fun readOptions() {
        useGpu = gpuCheck.isChecked
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_GPU, useGpu).apply()
        negative = negativeField.text.toString().trim()
        seedValue = seedField.text.toString().trim().toLongOrNull()
        strength = strengthField.text.toString().toFloatOrNull()?.coerceIn(0.05f, 1.0f) ?: DEFAULT_STRENGTH
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putFloat(KEY_STRENGTH, strength).apply()
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_NEGATIVE, negative).apply()
    }

    /** Reads the steps box, keeps it within 1 to MAX_STEPS and remembers it for the loaded model. Main thread. */
    private fun readSteps() {
        val value = (stepsField.text.toString().toIntOrNull() ?: preset.steps).coerceIn(1, MAX_STEPS)
        steps = value
        stepsField.setText(value.toString())
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt(stepsKey(modelName()), value).apply()
    }

    /** Reads the threads box (1 to MAX_THREADS) and remembers it. Main thread. */
    private fun readThreads() {
        val value = (threadsField.text.toString().toIntOrNull() ?: DEFAULT_THREADS).coerceIn(1, MAX_THREADS)
        threads = value
        threadsField.setText(value.toString())
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt(KEY_THREADS, value).apply()
    }

    /** Whether the GPU is wanted and this build can use it. */
    private fun wantGpu(): Boolean = useGpu && LocalImage.hasVulkan()

    /** Loads the model again if the threads or GPU choice changed since it was loaded. Worker thread. True if ready. */
    private fun ensureThreads(): Boolean {
        if (modelReady && (loadedThreads != threads || (LocalImage.hasVulkan() && loadedGpuWanted != wantGpu()))) {
            LocalImage.unload()
            modelReady = false
            initModel(modelFile())
        }
        return modelReady
    }

    // ---- Models ----

    /** The model in use, if its file is still there. */
    private fun activeModel(): ModelRef? {
        val name = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_ACTIVE_MODEL, null) ?: return null
        return ModelStore.find(this, name)?.takeIf { File(it.path).exists() }
    }

    private fun modelFile(): File = File(activeModel()?.path ?: File(ModelStore.dir(this), "model.gguf").path)

    /** The name of the model in use. The settings for each model are saved under it. */
    private fun modelName(): String = activeModel()?.name ?: "model.gguf"

    private fun safeFileName(name: String): String = name.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()

    /** The real path of a file picked from the phone's own storage, if this app may read it there. Else null. */
    private fun inPlacePath(uri: Uri): String? {
        if (!hasAllFilesAccess()) return null
        if (!DocumentsContract.isDocumentUri(this, uri) || uri.authority != "com.android.externalstorage.documents") {
            return null
        }
        val parts = DocumentsContract.getDocumentId(uri).split(":", limit = 2)
        if (parts.size != 2) return null
        val root = if (parts[0] == "primary") "/storage/emulated/0" else "/storage/${parts[0]}"
        return File(root, parts[1]).takeIf { it.canRead() }?.absolutePath
    }

    /** Moves the model copied by an earlier version of the app into the list of models. Worker thread. */
    private fun migrateOldModel() {
        val old = File(filesDir, "image/model.gguf")
        if (!old.exists()) return
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val name = prefs.getString(KEY_MODEL_NAME, null) ?: "model.gguf"
        val target = File(ModelStore.dir(this), safeFileName(name))
        target.parentFile?.mkdirs()
        if (old.renameTo(target)) {
            ModelStore.add(this, ModelRef(name, target.absolutePath, true))
            prefs.edit().putString(KEY_ACTIVE_MODEL, name).remove(KEY_MODEL_NAME).apply()
        }
    }

    /** Lists the models, and loads the one that is picked. */
    private fun showModels() {
        if (busy) {
            toastBusy()
            return
        }
        val models = ModelStore.list(this).filter { File(it.path).exists() }
        if (models.isEmpty()) {
            Toast.makeText(this, R.string.image_models_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val labels = models.map {
            val size = Formatter.formatFileSize(this, File(it.path).length())
            val where = getString(if (it.copied) R.string.image_model_in_app else R.string.image_model_on_phone)
            "${it.name}  ($size, $where)"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.image_choose_model)
            .setItems(labels) { _, which -> switchTo(models[which]) }
            .setNegativeButton(R.string.note_close, null)
            .show()
    }

    private fun switchTo(ref: ModelRef) {
        showStatus(R.string.image_loading)
        worker.execute {
            if (LocalImage.isLoaded()) LocalImage.unload()
            modelReady = false
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_ACTIVE_MODEL, ref.name).apply()
            initModel(File(ref.path))
        }
    }

    private fun modelSizeText(): String = Formatter.formatFileSize(this, modelFile().length())

    private fun toastBusy() {
        Toast.makeText(this, R.string.image_busy, Toast.LENGTH_SHORT).show()
    }

    private fun onGenerate() {
        val text = prompt.text.toString().trim()
        if (text.isEmpty()) {
            Toast.makeText(this, R.string.image_empty, Toast.LENGTH_SHORT).show()
            return
        }
        readOptions()
        if (useGemini()) {
            generateWithGemini(text)
        } else {
            if (!modelReady) {
                Toast.makeText(this, R.string.image_model_not_ready, Toast.LENGTH_SHORT).show()
                return
            }
            readSteps()
            readThreads()
            generateOnPhone(text)
        }
    }

    /** Rewrites the prompt as a detailed description with the chat's on-phone model. */
    private fun improvePrompt() {
        if (busy) {
            toastBusy()
            return
        }
        val text = prompt.text.toString().trim()
        if (text.isEmpty()) {
            Toast.makeText(this, R.string.image_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val chatModel = File(filesDir, "llm/model.gguf")
        if (!LlmEngine.libraryLoaded || !chatModel.exists()) {
            Toast.makeText(this, R.string.image_improve_no_model, Toast.LENGTH_LONG).show()
            return
        }
        busy = true
        generate.isEnabled = false
        improveButton.isEnabled = false
        progress.setText(R.string.image_improving)
        status.text = ""
        worker.execute {
            var improved = ""
            try {
                improved = PromptWriter.improve(chatModel.absolutePath, text)
            } catch (e: Exception) {
                Log.e(TAG, "Improving the prompt failed", e)
            } finally {
                finishBusy()
            }
            main.post {
                improveButton.isEnabled = true
                if (improved.isBlank()) {
                    progress.text = ""
                    status.setText(R.string.image_improve_failed)
                } else {
                    prompt.setText(improved)
                    progress.text = ""
                    status.setText(R.string.image_improved)
                }
            }
        }
    }

    /** Stops the picture being made on the phone. */
    private fun onCancel() {
        if (!busy || cancelRequested) return
        cancelRequested = true
        progress.setText(R.string.image_cancelling)
        LocalImage.cancel()
    }

    private fun setBusy() {
        busy = true
        cancelRequested = false
        generate.isEnabled = false
        upscaleButton.isEnabled = false
        progress.setText(R.string.image_working)
        status.text = ""
    }

    /** Ends the busy state after a picture, made or not. Any thread. */
    private fun finishBusy() {
        busy = false
        trackProgress = false
        main.post {
            generate.isEnabled = true
            upscaleButton.isEnabled = true
            cancelButton.visibility = View.GONE
            updateUpscaleButton()
        }
    }

    /** Starts showing the model's progress, with a Cancel button. Any thread. */
    private fun beginLocalTracking() {
        cancelRequested = false
        trackProgress = true
        main.post {
            startedAt = SystemClock.elapsedRealtime()
            cancelButton.visibility = View.VISIBLE
            main.removeCallbacks(ticker)
            main.post(ticker)
        }
    }

    private fun generateWithGemini(text: String) {
        setBusy()
        worker.execute {
            val began = SystemClock.elapsedRealtime()
            try {
                val result = GoogleImage.generate(text)
                val bytes = result.bytes
                if (bytes == null) {
                    show(getString(R.string.image_none), result.text)
                } else {
                    val uri = saveToPictures(bytes)
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ImageHistory.add(
                        this,
                        HistoryEntry(
                            uri = uri.toString(), time = System.currentTimeMillis(), kind = "gemini",
                            prompt = text, negative = "", model = result.model, backend = "Google",
                            steps = 0, size = bitmap?.width ?: 0, seed = 0L, sampler = "", guidance = 0f,
                            seconds = (SystemClock.elapsedRealtime() - began) / 1000.0,
                        )
                    )
                    main.post {
                        preview.setImageBitmap(bitmap)
                        status.text = getString(R.string.image_saved, result.model)
                        progress.text = result.text
                    }
                }
            } catch (e: GoogleImage.QuotaExceeded) {
                // Google has no picture quota left: make the picture on the phone if a model is loaded.
                if (modelReady) {
                    makeOnPhone(text, getString(R.string.image_quota_fallback))
                } else {
                    show(getString(R.string.image_quota), "")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Image failed", e)
                show(getString(R.string.image_failed, e.message ?: e.javaClass.simpleName), "")
            } finally {
                finishBusy()
            }
        }
    }

    private fun generateOnPhone(text: String) {
        setBusy()
        worker.execute {
            try {
                if (ensureThreads()) makeOnPhone(text, "") else show(getString(R.string.image_model_failed), "")
            } finally {
                finishBusy()
            }
        }
    }

    /** Makes the picture with the loaded model. Runs on the worker thread. [note] is shown under the picture. */
    private fun makeOnPhone(text: String, note: String) {
        beginLocalTracking()
        try {
            val usedSteps = steps
            val usedSize = imageSize
            val usedThreads = loadedThreads
            val usedGpu = loadedGpu
            val usedSeed = seedValue ?: Random.nextLong(1L shl 31)
            val usedNegative = negative
            val usedSampler = sampler
            val usedGuidance = preset.guidance
            // The start picture, cut square and scaled to the size of the new picture.
            val startRgb = startBitmap?.let { bitmapToRgb(it, usedSize) }
            val startedAtMs = SystemClock.elapsedRealtime()
            val rgb = LocalImage.generate(
                text, usedNegative, usedSize, usedSize, usedSteps, usedGuidance, usedSampler.code, usedSeed,
                startRgb, strength
            )
            val seconds = (SystemClock.elapsedRealtime() - startedAtMs) / 1000.0
            Log.i(TAG, "On-phone picture: $seconds s, $usedSteps steps, $usedThreads threads, $usedSize px, seed $usedSeed, ${usedSampler.name}")
            if (rgb == null || rgb.size != usedSize * usedSize * 3) {
                if (cancelRequested) {
                    show(getString(R.string.image_cancelled), "")
                } else {
                    val reason = LocalImage.lastError()
                    Log.e(TAG, "No picture was made: $reason")
                    show(
                        if (reason.isBlank()) {
                            getString(R.string.image_local_failed)
                        } else {
                            getString(R.string.image_local_failed_reason, reason)
                        },
                        ""
                    )
                }
                return
            }
            val bitmap = rgbToBitmap(rgb, usedSize, usedSize)
            val uri = saveToPictures(toPng(bitmap))
            val model = modelName()
            val backend = getString(if (usedGpu) R.string.image_backend_gpu else R.string.image_backend_cpu)
            val entry = HistoryEntry(
                uri = uri.toString(), time = System.currentTimeMillis(), kind = "phone",
                prompt = text, negative = usedNegative, model = model, backend = backend,
                steps = usedSteps, size = usedSize, seed = usedSeed, sampler = usedSampler.name,
                guidance = usedGuidance, seconds = seconds,
            )
            ImageHistory.add(this, entry)
            lastRgb = rgb
            lastRgbSize = usedSize
            lastEntry = entry
            main.post {
                preview.setImageBitmap(bitmap)
                status.text = getString(R.string.image_saved, model)
                progress.text = (note + " " + getString(
                    R.string.image_timing, seconds, usedSteps, usedThreads, usedSize, usedSeed, backend
                )).trim()
                updateUpscaleButton()
            }
        } catch (e: Exception) {
            Log.e(TAG, "On-phone image failed", e)
            show(getString(R.string.image_local_failed), "")
        } finally {
            trackProgress = false
        }
    }

    /** Makes the last picture larger with the loaded upscaler. */
    private fun onUpscale() {
        if (busy) {
            toastBusy()
            return
        }
        val rgb = lastRgb ?: return
        val size = lastRgbSize
        val factor = getSharedPreferences(PREFS, MODE_PRIVATE).getInt(KEY_UPSCALER_SCALE, 0)
        if (factor <= 0 || !upscalerReady) return
        if (size * factor > MAX_UPSCALED_SIDE) {
            Toast.makeText(this, R.string.image_upscale_too_large, Toast.LENGTH_LONG).show()
            return
        }
        setBusy()
        progress.setText(R.string.image_upscaling)
        val source = lastEntry
        worker.execute {
            try {
                val startedAtMs = SystemClock.elapsedRealtime()
                val out = LocalImage.upscale(rgb, size, size, factor)
                val seconds = (SystemClock.elapsedRealtime() - startedAtMs) / 1000.0
                val side = size * factor
                if (out == null || out.size != side * side * 3) {
                    val reason = LocalImage.lastError()
                    Log.e(TAG, "Upscale failed: $reason")
                    show(getString(R.string.image_upscale_failed), reason)
                } else {
                    val bitmap = rgbToBitmap(out, side, side)
                    val uri = saveToPictures(toPng(bitmap))
                    ImageHistory.add(
                        this,
                        HistoryEntry(
                            uri = uri.toString(), time = System.currentTimeMillis(), kind = "upscaled",
                            prompt = source?.prompt.orEmpty(), negative = source?.negative.orEmpty(),
                            model = source?.model.orEmpty(), backend = source?.backend.orEmpty(),
                            steps = source?.steps ?: 0, size = side, seed = source?.seed ?: 0L,
                            sampler = source?.sampler.orEmpty(), guidance = source?.guidance ?: 0f,
                            seconds = seconds,
                        )
                    )
                    main.post {
                        preview.setImageBitmap(bitmap)
                        status.text = getString(R.string.image_upscaled, factor, side, seconds)
                        progress.text = ""
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Upscale failed", e)
                show(getString(R.string.image_upscale_failed), "")
            } finally {
                finishBusy()
            }
        }
    }

    /** Shows a message (and any text the model sent) in place of a picture. */
    private fun show(message: String, text: String) {
        main.post {
            preview.setImageDrawable(null)
            status.text = message
            progress.text = text
        }
    }

    private fun showStatus(resId: Int) {
        main.post { status.setText(resId) }
    }

    private fun showStatusText(text: String) {
        main.post { status.text = text }
    }

    /**
     * Adds the picked model to the list and loads it. It is read where it is if the app may (All files access),
     * otherwise copied into the app's storage first. Runs on the worker thread.
     */
    private fun loadFrom(uri: Uri) {
        val name = displayName(uri)
        val inPlace = inPlacePath(uri)
        showStatus(if (inPlace != null) R.string.image_loading else R.string.image_copying)
        worker.execute {
            try {
                // Let go of the loaded model first: the file may be the one being replaced.
                if (LocalImage.isLoaded()) LocalImage.unload()
                modelReady = false
                val ref = if (inPlace != null) {
                    ModelRef(name, inPlace, false)
                } else {
                    val target = File(ModelStore.dir(this), safeFileName(name))
                    target.parentFile?.mkdirs()
                    val input = contentResolver.openInputStream(uri) ?: throw IllegalStateException("cannot open the file")
                    input.use { source -> target.outputStream().use { source.copyTo(it) } }
                    ModelRef(name, target.absolutePath, true)
                }
                ModelStore.add(this, ref)
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_ACTIVE_MODEL, name).apply()
                initModel(File(ref.path))
            } catch (e: Exception) {
                Log.e(TAG, "Could not copy the image model", e)
                showStatus(R.string.image_model_failed)
            }
        }
    }

    /** Loads the model file. Runs on the worker thread. */
    private fun initModel(file: File) {
        showStatus(R.string.image_loading)
        val startedAtMs = SystemClock.elapsedRealtime()
        val gpu = wantGpu()
        var ok = LocalImage.load(file.absolutePath, threads, gpu) == 0
        var gpuUsed = gpu && ok
        if (!ok && gpu) {
            // The GPU could not run this model: use the processor instead.
            Log.w(TAG, "Loading on the GPU failed; trying the CPU")
            ok = LocalImage.load(file.absolutePath, threads, false) == 0
            gpuUsed = false
        }
        Log.i(TAG, "Model load: ${SystemClock.elapsedRealtime() - startedAtMs} ms, $threads threads, gpu=$gpuUsed, ok=$ok")
        Log.i(TAG, "Devices:\n${LocalImage.devices()}")
        modelReady = ok
        loadedThreads = if (ok) threads else 0
        loadedGpu = gpuUsed
        loadedGpuWanted = gpu
        if (ok) {
            showSettings(modelName())
            showStatusText(getString(R.string.image_model_ready, modelName(), modelSizeText()))
        } else {
            // The library's own message, so the reason shows on screen and in the log.
            val reason = LocalImage.lastError()
            Log.e(TAG, "Model did not load: $reason")
            showStatusText(
                if (reason.isBlank()) {
                    getString(R.string.image_model_failed)
                } else {
                    getString(R.string.image_model_failed_reason, reason)
                }
            )
        }
    }

    /**
     * Asks before removing the model in use. A model the app copied is deleted (it is several GB); a model read
     * in place is only taken off the list, and the user's file is kept.
     */
    private fun confirmRemoveModel() {
        if (busy) {
            toastBusy()
            return
        }
        val ref = activeModel()
        if (ref == null) {
            showStatus(R.string.image_model_none)
            return
        }
        val message = if (ref.copied) {
            getString(R.string.image_remove_confirm, Formatter.formatFileSize(this, File(ref.path).length()))
        } else {
            getString(R.string.image_remove_confirm_listed, ref.name)
        }
        AlertDialog.Builder(this)
            .setMessage(message)
            .setPositiveButton(R.string.image_remove_confirm_yes) { _, _ -> removeModelFile(ref) }
            .setNegativeButton(R.string.chat_key_cancel, null)
            .show()
    }

    private fun removeModelFile(ref: ModelRef) {
        worker.execute {
            if (LocalImage.isLoaded()) LocalImage.unload()
            modelReady = false
            loadedThreads = 0
            if (ref.copied) File(ref.path).delete()
            ModelStore.remove(this, ref.name)
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(KEY_ACTIVE_MODEL).apply()
            showStatus(R.string.image_model_removed)
        }
    }

    // ---- Upscaler ----

    private fun upscalerFile(): File? =
        getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_UPSCALER_FILE, null)?.let { File(filesDir, "image/$it") }

    /** Makes the upscaler from an earlier visit ready again. Runs on the worker thread. */
    private fun restoreUpscaler() {
        val file = upscalerFile()
        val scale = getSharedPreferences(PREFS, MODE_PRIVATE).getInt(KEY_UPSCALER_SCALE, 0)
        if (LocalImage.upscalerLoaded()) {
            upscalerReady = true
        } else if (file != null && file.exists() && scale > 0) {
            upscalerReady = LocalImage.upscalerLoad(file.absolutePath, threads) == 0
        }
        main.post { updateUpscaleButton() }
    }

    /** Copies the picked upscaler into the app's storage, checks it and loads it. Runs on the worker thread. */
    private fun loadUpscalerFrom(uri: Uri) {
        showStatus(R.string.image_copying)
        val name = displayName(uri, "upscaler")
        // The library recognizes the format from the file, but keeping the extension helps it.
        val fileName = "upscaler." + name.substringAfterLast('.', "bin")
        worker.execute {
            try {
                val target = File(filesDir, "image/$fileName")
                target.parentFile?.mkdirs()
                if (upscalerReady) {
                    LocalImage.upscalerUnload()
                    upscalerReady = false
                }
                val input = contentResolver.openInputStream(uri) ?: throw IllegalStateException("cannot open the file")
                input.use { source -> target.outputStream().use { source.copyTo(it) } }
                val scale = LocalImage.upscalerScale(target.absolutePath)
                if (scale <= 0) {
                    target.delete()
                    showStatusText(getString(R.string.image_upscaler_failed, getString(R.string.image_upscaler_not_esrgan)))
                    return@execute
                }
                if (LocalImage.upscalerLoad(target.absolutePath, threads) != 0) {
                    val reason = LocalImage.lastError().ifBlank { "unknown" }
                    showStatusText(getString(R.string.image_upscaler_failed, reason))
                    return@execute
                }
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString(KEY_UPSCALER_FILE, fileName)
                    .putInt(KEY_UPSCALER_SCALE, scale)
                    .apply()
                upscalerReady = true
                showStatusText(getString(R.string.image_upscaler_ready, name, scale))
                main.post { updateUpscaleButton() }
            } catch (e: Exception) {
                Log.e(TAG, "Could not load the upscaler", e)
                showStatusText(getString(R.string.image_upscaler_failed, e.message ?: e.javaClass.simpleName))
            }
        }
    }

    // ---- History ----

    private fun showHistory() {
        val entries = ImageHistory.load(this)
        if (entries.isEmpty()) {
            Toast.makeText(this, R.string.image_history_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val labels = entries.map { "${formatNoteTime(it.time)}  ${it.prompt.take(40)}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.image_history)
            .setItems(labels) { _, which -> showEntry(entries[which]) }
            .setNegativeButton(R.string.note_close, null)
            .show()
    }

    /** Shows the picture and what it was made from, with Share, Use settings and Remove. */
    private fun showEntry(entry: HistoryEntry) {
        showPicture(Uri.parse(entry.uri))
        val details = getString(
            R.string.image_history_details,
            formatNoteTime(entry.time), entry.prompt, entry.negative.ifBlank { "-" },
            entry.model, entry.backend, entry.steps, entry.size, entry.seed,
            entry.sampler.ifBlank { "-" }, entry.seconds
        )
        val dialog = AlertDialog.Builder(this)
            .setTitle(entry.prompt.take(40))
            .setMessage(details)
            .setPositiveButton(R.string.image_history_share) { _, _ -> share(Uri.parse(entry.uri)) }
            .setNegativeButton(R.string.image_history_remove) { _, _ ->
                ImageHistory.remove(this, entry)
                Toast.makeText(this, R.string.image_history_removed, Toast.LENGTH_LONG).show()
            }
        if (entry.kind != "gemini") {
            dialog.setNeutralButton(R.string.image_history_use) { _, _ -> useSettings(entry) }
        }
        dialog.show()
    }

    /** Opens a picture. A big one is opened at a smaller size, so it does not use too much memory. Null if it fails. */
    private fun decodeScaled(uri: Uri): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / sample > MAX_PREVIEW_SIDE) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    } catch (e: Exception) {
        Log.w(TAG, "Could not open the picture", e)
        null
    }

    /** Puts a saved picture in the preview. */
    private fun showPicture(uri: Uri) {
        worker.execute {
            val bitmap = decodeScaled(uri)
            main.post {
                if (bitmap == null) {
                    Toast.makeText(this, R.string.image_history_missing, Toast.LENGTH_LONG).show()
                } else {
                    preview.setImageBitmap(bitmap)
                }
            }
        }
    }

    private fun share(uri: Uri) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, getString(R.string.image_share_title)))
    }

    /** Puts a picture's prompt and settings back in the boxes, so it can be made again or changed. */
    private fun useSettings(entry: HistoryEntry) {
        prompt.setText(entry.prompt)
        negativeField.setText(entry.negative)
        if (entry.kind == "phone") seedField.setText(entry.seed.toString())
        if (entry.steps > 0) {
            steps = entry.steps.coerceIn(1, MAX_STEPS)
            stepsField.setText(steps.toString())
        }
        val size = if (entry.kind == "upscaled") lastRgbSize.takeIf { it in SIZES } ?: imageSize else entry.size
        if (size in SIZES) {
            imageSize = size
            sizeSpinner.setSelection(SIZES.indexOf(size))
        }
        Sampler.values().firstOrNull { it.name == entry.sampler }?.let {
            sampler = it
            samplerSpinner.setSelection(it.ordinal)
        }
        saveSettings()
        Toast.makeText(this, R.string.image_history_restored, Toast.LENGTH_SHORT).show()
    }

    private fun displayName(uri: Uri, fallback: String = "model.gguf"): String =
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && column >= 0) cursor.getString(column) else null
        } ?: fallback

    /** Turns the model's RGB pixels (top row first) into a bitmap. */
    private fun rgbToBitmap(rgb: ByteArray, width: Int, height: Int): Bitmap {
        val pixels = IntArray(width * height)
        for (i in pixels.indices) {
            val r = rgb[3 * i].toInt() and 0xff
            val g = rgb[3 * i + 1].toInt() and 0xff
            val b = rgb[3 * i + 2].toInt() and 0xff
            pixels[i] = (0xff shl 24) or (r shl 16) or (g shl 8) or b
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun toPng(bitmap: Bitmap): ByteArray = ByteArrayOutputStream().use { out ->
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        out.toByteArray()
    }

    /** Saves the picture to Pictures/MyApp, where it appears in the Gallery. */
    private fun saveToPictures(bytes: ByteArray): Uri {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "Image_$stamp.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/MyApp")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("could not create the picture file")
        contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            ?: throw IllegalStateException("could not write the picture file")
        contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        return uri
    }

    companion object {
        private const val TAG = "ImageActivity"
        private const val PREFS = "image"
        // The name of the model in use. KEY_MODEL_NAME is what earlier versions saved, moved on first start.
        private const val KEY_ACTIVE_MODEL = "active_model"
        private const val KEY_MODEL_NAME = "model_name"
        private const val KEY_THREADS = "threads"
        private const val KEY_NEGATIVE = "negative"
        private const val KEY_GPU = "gpu"
        private const val KEY_STRENGTH = "strength"
        // How much a start picture changes, if the strength box is empty.
        private const val DEFAULT_STRENGTH = 0.6f
        private const val KEY_UPSCALER_FILE = "upscaler_file"
        private const val KEY_UPSCALER_SCALE = "upscaler_scale"
        // The picture sizes offered (squares, in pixels). Bigger is slower.
        private val SIZES = listOf(512, 768, 1024)
        // The Galaxy Z Fold8 (Snapdragon 8 Elite Gen 5) has 2 prime and 6 performance cores and no slow ones.
        private const val DEFAULT_THREADS = 6
        private const val MAX_THREADS = 8
        // The threads the loaded model was started with. It outlives the screen, as the model does. 0 if none.
        @Volatile private var loadedThreads = 0
        // Whether the loaded model runs on the GPU.
        @Volatile private var loadedGpu = false
        // What was asked for when it was loaded. It differs from [loadedGpu] when the GPU failed and the CPU was used.
        @Volatile private var loadedGpuWanted = false
        // The most steps the steps box accepts.
        private const val MAX_STEPS = 50
        // The longest side of an upscaled picture. Bigger ones would not fit in the app's memory.
        private const val MAX_UPSCALED_SIDE = 3072
        // A picture opened from storage is shown no wider than this, so a big one does not use too much memory.
        private const val MAX_PREVIEW_SIDE = 2048
    }
}

/** The way the picture is built up step by step. [code] is the number the native code reads. */
enum class Sampler(val labelRes: Int, val code: Int) {
    EULER_A(R.string.image_sampler_euler_a, 0),
    EULER(R.string.image_sampler_euler, 1),
    LCM(R.string.image_sampler_lcm, 2),
    DPMPP2M(R.string.image_sampler_dpmpp2m, 3),
}

/**
 * The starting settings for the kind of model loaded: steps, guidance, picture size and sampler.
 * Each can then be changed on the screen. Order is the dropdown's order; saved choices are stored by name.
 */
enum class ImagePreset(val labelRes: Int, val steps: Int, val guidance: Float, val size: Int, val sampler: Sampler) {
    /** Stable Diffusion 1.5: slower, with guidance. */
    SD(R.string.image_preset_sd, 20, 7.0f, 512, Sampler.EULER_A),

    /** SDXL: made for 1024 px, so it looks better above 512 but takes longer. */
    SDXL(R.string.image_preset_sdxl, 10, 5.0f, 768, Sampler.EULER_A),

    /** SDXL models trained to work in a few steps (Lightning, Hyper-SD, DMD2). LCM sampler suits DMD2 and LCM models. */
    SDXL_FAST(R.string.image_preset_sdxl_fast, 4, 1.0f, 768, Sampler.EULER),

    /** SDXS, a distilled model that makes a picture in one step with little or no guidance. */
    SDXS(R.string.image_preset_sdxs, 1, 1.0f, 512, Sampler.EULER_A),
}
