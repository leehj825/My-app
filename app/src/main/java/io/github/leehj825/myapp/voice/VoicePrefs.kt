package io.github.leehj825.myapp.voice

import android.content.Context
import java.util.Locale

/**
 * The language and voice used for reading aloud and dictation. Empty language = the phone's language.
 * The voice is applied only when its language matches the text being read.
 */
object VoicePrefs {
    private const val PREFS = "voice"
    private const val KEY_LANGUAGE = "language"
    private const val KEY_VOICE = "voice"

    fun languageTag(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LANGUAGE, "").orEmpty()

    fun setLanguageTag(context: Context, tag: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_LANGUAGE, tag).apply()
    }

    fun voiceName(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_VOICE, null)

    fun setVoiceName(context: Context, name: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_VOICE, name).apply()
    }

    /** The chosen language, or the phone's language if none is chosen. */
    fun locale(context: Context): Locale =
        languageTag(context).takeIf { it.isNotEmpty() }?.let { Locale.forLanguageTag(it) } ?: Locale.getDefault()

    /** The language tag for dictation, for example ko-KR. */
    fun dictationLanguage(context: Context): String = locale(context).toLanguageTag()

    /** True if the text has Korean letters (Hangul). */
    fun containsHangul(text: String): Boolean =
        text.any { it.code in 0xAC00..0xD7A3 || it.code in 0x3131..0x318E }
}
