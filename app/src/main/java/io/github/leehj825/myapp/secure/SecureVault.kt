package io.github.leehj825.myapp.secure

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import io.github.leehj825.myapp.R
import io.github.leehj825.myapp.notes.Note
import io.github.leehj825.myapp.notes.NoteStore
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Secure notes. Their text is one file, encrypted with AES-GCM. The key is in the Android Keystore and
 * every use needs a fingerprint: opening a note asks for it, and so does saving.
 *
 * The list shows only the date and time of each note, kept in a separate index file, so the text stays hidden.
 */
object SecureVault {
    private const val TAG = "SecureVault"
    // A new key name: the key from the earlier version (unlock for 5 minutes) is not used.
    private const val KEY_ALIAS = "myapp_secure_notes_v2"
    private const val FILE = "secure_notes.bin"
    private const val INDEX = "secure_index.json"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    fun isDeviceSecure(context: Context): Boolean =
        context.getSystemService(KeyguardManager::class.java).isDeviceSecure

    /** True if a fingerprint (or other strong biometric) is enrolled. Each use needs one. */
    fun canUseFingerprint(context: Context): Boolean =
        BiometricManager.from(context).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS

    /** The id and time of each secure note, newest first. Reads no note text, so it needs no fingerprint. */
    fun index(context: Context): List<Pair<Long, Long>> {
        val file = File(context.filesDir, INDEX)
        if (!file.exists()) return emptyList()
        return try {
            val array = JSONArray(file.readText())
            (0 until array.length())
                .map { i ->
                    val o = array.getJSONObject(i)
                    o.getLong("id") to o.getLong("createdAt")
                }
                .sortedByDescending { it.second }
        } catch (e: JSONException) {
            emptyList()
        }
    }

    /** Asks for the fingerprint, then gives the decrypted notes. [onFail] runs if the user stops or it fails. */
    fun read(activity: FragmentActivity, onNotes: (MutableList<Note>) -> Unit, onFail: () -> Unit) {
        val file = File(activity.filesDir, FILE)
        if (!file.exists()) {
            onNotes(mutableListOf())
            return
        }
        val bytes = file.readBytes()
        val cipher = Cipher.getInstance(TRANSFORM)
        try {
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, bytes.copyOfRange(0, IV_BYTES)))
        } catch (e: Exception) {
            Log.e(TAG, "The secure notes key could not be used", e)
            onFail()
            return
        }
        authenticate(activity, BiometricPrompt.CryptoObject(cipher), onFail) { result ->
            try {
                val c = result.cryptoObject?.cipher ?: throw IllegalStateException("no cipher")
                val plain = c.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES)
                onNotes(NoteStore.parse(String(plain, Charsets.UTF_8)))
            } catch (e: Exception) {
                Log.e(TAG, "The secure notes could not be decrypted", e)
                onFail()
            }
        }
    }

    /** Asks for the fingerprint, then encrypts and saves all the notes. [onFail] runs if the user stops or it fails. */
    fun write(activity: FragmentActivity, notes: List<Note>, onSaved: () -> Unit, onFail: () -> Unit) {
        val cipher = Cipher.getInstance(TRANSFORM)
        try {
            cipher.init(Cipher.ENCRYPT_MODE, key())
        } catch (e: Exception) {
            Log.e(TAG, "The secure notes key could not be used", e)
            onFail()
            return
        }
        val iv = cipher.iv
        authenticate(activity, BiometricPrompt.CryptoObject(cipher), onFail) { result ->
            try {
                val c = result.cryptoObject?.cipher ?: throw IllegalStateException("no cipher")
                val encrypted = c.doFinal(NoteStore.encode(notes).toByteArray(Charsets.UTF_8))
                File(activity.filesDir, FILE).writeBytes(iv + encrypted)
                File(activity.filesDir, INDEX).writeText(indexJson(notes))
                onSaved()
            } catch (e: Exception) {
                Log.e(TAG, "The secure notes could not be saved", e)
                onFail()
            }
        }
    }

    private fun indexJson(notes: List<Note>): String {
        val array = JSONArray()
        for (n in notes) array.put(JSONObject().put("id", n.id).put("createdAt", n.createdAt))
        return array.toString()
    }

    private fun authenticate(
        activity: FragmentActivity,
        crypto: BiometricPrompt.CryptoObject,
        onFail: () -> Unit,
        onSuccess: (BiometricPrompt.AuthenticationResult) -> Unit,
    ) {
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess(result)
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onFail()
        }
        BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), callback).authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(activity.getString(R.string.secure_title))
                .setNegativeButtonText(activity.getString(R.string.secure_cancel))
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .build(),
            crypto,
        )
    }

    /** A key that needs a fingerprint for every use (no unlock time). */
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // 0 seconds: authentication is needed every time, and only a fingerprint (or other strong biometric) counts.
            spec.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(spec.build())
        return generator.generateKey()
    }
}
