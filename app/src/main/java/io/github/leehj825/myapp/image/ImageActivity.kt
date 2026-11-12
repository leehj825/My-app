package io.github.leehj825.myapp.image

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
    private lateinit var generate: Button
    private lateinit var progress: TextView
    private lateinit var preview: ImageView
    private lateinit var status: TextView

    // All model calls run on this one thread; the UI is updated through [main].
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var modelReady = false
    // The settings for the loaded model: the steps and guidance it is run with.
    @Volatile private var preset = ImagePreset.SD

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
        presetSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val chosen = ImagePreset.values()[position]
                if (chosen != preset) {
                    preset = chosen
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putString(presetKey(modelName()), chosen.name).apply()
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        loadModel.setOnClickListener { pickModel.launch(arrayOf("*/*")) }
        generate.setOnClickListener { onGenerate() }

        if (LocalImage.libraryLoaded) {
            worker.execute {
                val file = modelFile()
                when {
                    // Still loaded from an earlier visit to this tool: no need to load it again.
                    LocalImage.isLoaded() -> {
                        modelReady = true
                        showPreset(presetFor(modelName()))
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
    }

    private fun presetKey(name: String): String = "preset:$name"

    /** The settings remembered for this model. A file named like SDXS starts on the fast settings. */
    private fun presetFor(name: String): ImagePreset {
        val saved = getSharedPreferences(PREFS, MODE_PRIVATE).getString(presetKey(name), null)
        return ImagePreset.values().firstOrNull { it.name == saved }
            ?: if (name.contains("sdxs", ignoreCase = true)) ImagePreset.SDXS else ImagePreset.SD
    }

    /** Sets the settings for the loaded model and shows them in the dropdown. Any thread. */
    private fun showPreset(chosen: ImagePreset) {
        preset = chosen
        main.post { presetSpinner.setSelection(chosen.ordinal) }
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
        if (useGemini()) {
            generateWithGemini(text)
        } else {
            if (!modelReady) {
                Toast.makeText(this, R.string.image_model_not_ready, Toast.LENGTH_SHORT).show()
                return
            }
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
                val chosen = preset
                val rgb = LocalImage.generate(text, "", SIZE, SIZE, chosen.steps, chosen.guidance, Random.nextLong(1L shl 31))
                if (rgb == null || rgb.size != SIZE * SIZE * 3) {
                    show(getString(R.string.image_local_failed), "")
                } else {
                    val bitmap = rgbToBitmap(rgb, SIZE, SIZE)
                    val png = ByteArrayOutputStream().use { out ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                        out.toByteArray()
                    }
                    saveToPictures(png)
                    val model = modelName()
                    main.post {
                        preview.setImageBitmap(bitmap)
                        status.text = getString(R.string.image_saved, model)
                        progress.text = ""
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "On-phone image failed", e)
                show(getString(R.string.image_local_failed), "")
            } finally {
                main.post { generate.isEnabled = true }
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
        val threads = (Runtime.getRuntime().availableProcessors() - 2).coerceIn(2, 4)
        val ok = LocalImage.load(file.absolutePath, threads) == 0
        modelReady = ok
        if (ok) {
            showPreset(presetFor(modelName()))
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
        // Pictures are made at this size.
        private const val SIZE = 512
    }
}

/** The settings a picture is made with, for the kind of model loaded. Order is the dropdown's order. */
enum class ImagePreset(val labelRes: Int, val steps: Int, val guidance: Float) {
    /** Stable Diffusion 1.5 and SDXL style models: slower, with guidance. */
    SD(R.string.image_preset_sd, 20, 7.0f),

    /** SDXS, a distilled model that makes a picture in one step with little or no guidance. */
    SDXS(R.string.image_preset_sdxs, 1, 1.0f),
}
