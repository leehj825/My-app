package io.github.leehj825.myapp.image

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import io.github.leehj825.myapp.R
import io.github.leehj825.myapp.chat.GeminiClient
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
 * The picture is shown and saved to Pictures/MyApp. With Gemini, the description is sent to Google.
 */
class ImageActivity : ComponentActivity() {

    private lateinit var prompt: EditText
    private lateinit var geminiCheck: CheckBox
    private lateinit var loadModel: Button
    private lateinit var presetSpinner: Spinner
    private lateinit var stepsField: EditText
    private lateinit var threadsField: EditText
    private lateinit var negativeField: EditText
    private lateinit var seedField: EditText
    private lateinit var gpuCheck: CheckBox
    private lateinit var sizeSpinner: Spinner
    private lateinit var samplerSpinner: Spinner
    private lateinit var generate: Button
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

    private val pickModel = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) loadFrom(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_image)
        prompt = findViewById(R.id.image_prompt)
        geminiCheck = findViewById(R.id.image_gemini)
        loadModel = findViewById(R.id.image_load_model)
        presetSpinner = findViewById(R.id.image_preset)
        stepsField = findViewById(R.id.image_steps)
        threadsField = findViewById(R.id.image_threads)
        negativeField = findViewById(R.id.image_negative)
        seedField = findViewById(R.id.image_seed)
        gpuCheck = findViewById(R.id.image_gpu)
        sizeSpinner = findViewById(R.id.image_size)
        samplerSpinner = findViewById(R.id.image_sampler)
        generate = findViewById(R.id.image_generate)
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

        loadModel.setOnClickListener { pickModel.launch(arrayOf("*/*")) }
        generate.setOnClickListener { onGenerate() }

        if (LocalImage.libraryLoaded) {
            worker.execute {
                val file = modelFile()
                when {
                    // Still loaded from an earlier visit to this tool: no need to load it again.
                    LocalImage.isLoaded() -> {
                        modelReady = true
                        showSettings(modelName())
                        showStatusText(getString(R.string.image_model_ready, modelName()))
                    }
                    file.exists() -> initModel(file)
                    else -> showStatus(R.string.image_model_none)
                }
            }
        }
    }

    /** True when pictures are made in the cloud. Otherwise they are made on the phone. */
    private fun useGemini(): Boolean = GeminiClient.available && (!LocalImage.libraryLoaded || geminiCheck.isChecked)

    /** The model file and its settings are shown only when pictures are made on the phone. */
    private fun updateModelControls() {
        val visibility = if (LocalImage.libraryLoaded && !useGemini()) View.VISIBLE else View.GONE
        loadModel.visibility = visibility
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

    private fun modelFile(): File = File(filesDir, "image/model.gguf")

    private fun modelName(): String =
        getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_MODEL_NAME, null) ?: "model.gguf"

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

    private fun setBusy() {
        generate.isEnabled = false
        progress.setText(R.string.image_working)
        status.text = ""
    }

    private fun generateWithGemini(text: String) {
        setBusy()
        worker.execute {
            try {
                val result = GoogleImage.generate(text)
                val bytes = result.bytes
                if (bytes == null) {
                    show(getString(R.string.image_none), result.text)
                } else {
                    saveToPictures(bytes)
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
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
                main.post { generate.isEnabled = true }
            }
        }
    }

    private fun generateOnPhone(text: String) {
        setBusy()
        worker.execute {
            try {
                if (ensureThreads()) makeOnPhone(text, "") else show(getString(R.string.image_model_failed), "")
            } finally {
                main.post { generate.isEnabled = true }
            }
        }
    }

    /** Makes the picture with the loaded model. Runs on the worker thread. [note] is shown under the picture. */
    private fun makeOnPhone(text: String, note: String) {
        try {
            val usedSteps = steps
            val usedSize = imageSize
            val usedThreads = loadedThreads
            val usedGpu = loadedGpu
            val usedSeed = seedValue ?: Random.nextLong(1L shl 31)
            val startedAt = SystemClock.elapsedRealtime()
            val rgb = LocalImage.generate(
                text, negative, usedSize, usedSize, usedSteps, preset.guidance, sampler.code, usedSeed
            )
            val seconds = (SystemClock.elapsedRealtime() - startedAt) / 1000.0
            Log.i(TAG, "On-phone picture: $seconds s, $usedSteps steps, $usedThreads threads, $usedSize px, seed $usedSeed, ${sampler.name}")
            if (rgb == null || rgb.size != usedSize * usedSize * 3) {
                show(getString(R.string.image_local_failed), "")
            } else {
                val bitmap = rgbToBitmap(rgb, usedSize, usedSize)
                val png = ByteArrayOutputStream().use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    out.toByteArray()
                }
                saveToPictures(png)
                val model = modelName()
                main.post {
                    preview.setImageBitmap(bitmap)
                    status.text = getString(R.string.image_saved, model)
                    progress.text = (note + " " + getString(
                        R.string.image_timing, seconds, usedSteps, usedThreads, usedSize, usedSeed,
                        getString(if (usedGpu) R.string.image_backend_gpu else R.string.image_backend_cpu)
                    )).trim()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "On-phone image failed", e)
            show(getString(R.string.image_local_failed), "")
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

    /** Copies the picked model into the app's storage, then loads it. Runs on the worker thread. */
    private fun loadFrom(uri: Uri) {
        showStatus(R.string.image_copying)
        val name = displayName(uri)
        worker.execute {
            try {
                val target = modelFile()
                target.parentFile?.mkdirs()
                if (modelReady) {
                    LocalImage.unload()
                    modelReady = false
                }
                val input = contentResolver.openInputStream(uri) ?: throw IllegalStateException("cannot open the file")
                input.use { source -> target.outputStream().use { source.copyTo(it) } }
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_MODEL_NAME, name).apply()
                initModel(target)
            } catch (e: Exception) {
                Log.e(TAG, "Could not copy the image model", e)
                showStatus(R.string.image_model_failed)
            }
        }
    }

    /** Loads the model file. Runs on the worker thread. */
    private fun initModel(file: File) {
        showStatus(R.string.image_loading)
        val startedAt = SystemClock.elapsedRealtime()
        val gpu = wantGpu()
        var ok = LocalImage.load(file.absolutePath, threads, gpu) == 0
        var gpuUsed = gpu && ok
        if (!ok && gpu) {
            // The GPU could not run this model: use the processor instead.
            Log.w(TAG, "Loading on the GPU failed; trying the CPU")
            ok = LocalImage.load(file.absolutePath, threads, false) == 0
            gpuUsed = false
        }
        Log.i(TAG, "Model load: ${SystemClock.elapsedRealtime() - startedAt} ms, $threads threads, gpu=$gpuUsed, ok=$ok")
        Log.i(TAG, "Devices:\n${LocalImage.devices()}")
        modelReady = ok
        loadedThreads = if (ok) threads else 0
        loadedGpu = gpuUsed
        loadedGpuWanted = gpu
        if (ok) {
            showSettings(modelName())
            showStatusText(getString(R.string.image_model_ready, modelName()))
        } else {
            showStatus(R.string.image_model_failed)
        }
    }

    private fun displayName(uri: Uri): String =
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && column >= 0) cursor.getString(column) else null
        } ?: "model.gguf"

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

    /** Saves the picture to Pictures/MyApp, where it appears in the Gallery. */
    private fun saveToPictures(bytes: ByteArray): Uri {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
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
        private const val KEY_MODEL_NAME = "model_name"
        private const val KEY_THREADS = "threads"
        private const val KEY_NEGATIVE = "negative"
        private const val KEY_GPU = "gpu"
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
