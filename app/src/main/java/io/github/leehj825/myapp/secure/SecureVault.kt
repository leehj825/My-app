package io.github.leehj825.myapp.secure

import android.app.KeyguardManager
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import io.github.leehj825.myapp.R
import io.github.leehj825.myapp.notes.Note
import io.github.leehj825.myapp.notes.NoteStore
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Secure notes: all of them are kept in one file, encrypted with AES-GCM. The key lives in the Android
 * Keystore and needs the fingerprint or the screen lock. After an unlock the key works for a few minutes.
 *
 * The phone must have a screen lock; without one, the key cannot be made.
 */
object SecureVault {
    private const val KEY_ALIAS = "myapp_secure_notes"
    private const val FILE = "secure_notes.bin"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val UNLOCK_SECONDS = 300

    fun isDeviceSecure(context: Context): Boolean =
        context.getSystemService(KeyguardManager::class.java).isDeviceSecure

    /** Reads and decrypts the notes. Throws UserNotAuthenticatedException if the phone must be unlocked first. */
    fun load(context: Context): MutableList<Note> {
        val file = File(context.filesDir, FILE)
        if (!file.exists()) return mutableListOf()
        val bytes = file.readBytes()
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, bytes.copyOfRange(0, IV_BYTES)))
        val json = String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
        return NoteStore.parse(json)
    }

    /** Encrypts and writes the notes. Throws UserNotAuthenticatedException if the phone must be unlocked first. */
    fun save(context: Context, notes: List<Note>) {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(NoteStore.encode(notes).toByteArray(Charsets.UTF_8))
        File(context.filesDir, FILE).writeBytes(cipher.iv + encrypted)
    }

    @Suppress("DEPRECATION")
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(true)
                .setUserAuthenticationValidityDurationSeconds(UNLOCK_SECONDS)
                .build()
        )
        return generator.generateKey()
    }

    /** Asks for the fingerprint or the screen lock. [onCancel] runs if the user gives up or it fails. */
    fun authenticate(activity: FragmentActivity, onSuccess: () -> Unit, onCancel: () -> Unit) {
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onCancel()
        }
        BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), callback).authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(activity.getString(R.string.secure_title))
                .setAllowedAuthenticators(
                    BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
                )
                .build()
        )
    }
}
