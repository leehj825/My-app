package io.github.leehj825.myapp.image

import android.content.ContentValues
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import io.github.leehj825.myapp.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Makes a picture from a description with Google's image model. The picture is shown and saved to
 * Pictures/MyApp. The description is sent to Google.
 */
class ImageActivity : ComponentActivity() {

    private lateinit var prompt: EditText
    private lateinit var generate: Button
    private lateinit var progress: TextView
    private lateinit var preview: ImageView
    private lateinit var status: TextView

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_image)
        prompt = findViewById(R.id.image_prompt)
        generate = findViewById(R.id.image_generate)
        progress = findViewById(R.id.image_progress)
        preview = findViewById(R.id.image_preview)
        status = findViewById(R.id.image_status)
        generate.setOnClickListener { onGenerate() }
    }

    private fun onGenerate() {
        val text = prompt.text.toString().trim()
        if (text.isEmpty()) {
            Toast.makeText(this, R.string.image_empty, Toast.LENGTH_SHORT).show()
            return
        }
        generate.isEnabled = false
        progress.setText(R.string.image_working)
        status.text = ""
        worker.execute {
            try {
                val result = GoogleImage.generate(text)
                val bytes = result.bytes
                if (bytes == null) {
                    show(getString(R.string.image_none), null, result.text)
                } else {
                    val uri = saveToPictures(bytes)
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    main.post {
                        preview.setImageBitmap(bitmap)
                        status.text = getString(R.string.image_saved, result.model)
                        progress.text = result.text
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Image failed", e)
                show(getString(R.string.image_failed, e.message ?: e.javaClass.simpleName), null, "")
            } finally {
                main.post { generate.isEnabled = true }
            }
        }
    }

    /** Shows a message (and any text the model sent) in place of a picture. */
    private fun show(message: String, unused: Unit?, text: String) {
        main.post {
            preview.setImageDrawable(null)
            status.text = message
            progress.text = text
        }
    }

    /** Saves the picture to Pictures/MyApp, where it appears in the Gallery. */
    private fun saveToPictures(bytes: ByteArray): android.net.Uri {
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
    }
}
