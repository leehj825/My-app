package io.github.leehj825.myapp.voice

import android.app.Activity
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import io.github.leehj825.myapp.R

/** Chooses the language and the voice for reading aloud. Tapping a voice plays a short sample. */
class VoiceSettingsActivity : Activity() {

    private lateinit var languages: LinearLayout
    private lateinit var voices: LinearLayout
    private lateinit var status: TextView
    private var tts: TextToSpeech? = null
    private var ready = false

    private val languageChoices = listOf(
        "" to R.string.lang_system,
        "ko-KR" to R.string.lang_korean,
        "en-US" to R.string.lang_english,
        "ja-JP" to R.string.lang_japanese,
        "zh-CN" to R.string.lang_chinese,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_voice_settings)
        languages = findViewById(R.id.voice_languages)
        voices = findViewById(R.id.voice_list)
        status = findViewById(R.id.voice_status)

        for ((tag, label) in languageChoices) {
            languages.addView(Button(this).apply {
                setText(label)
                setOnClickListener {
                    VoicePrefs.setLanguageTag(this@VoiceSettingsActivity, tag)
                    showVoices()
                }
            })
        }

        tts = TextToSpeech(this) { result ->
            ready = result == TextToSpeech.SUCCESS
            if (ready) showVoices() else status.setText(R.string.voice_no_engine)
        }
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }

    private fun showVoices() {
        val engine = tts ?: return
        if (!ready) return
        val locale = VoicePrefs.locale(this)
        val options: List<Voice> = engine.voices
            ?.filter { it.locale.language == locale.language }
            ?.sortedBy { it.name }
            ?: emptyList()
        voices.removeAllViews()
        if (options.isEmpty()) {
            status.text = getString(R.string.voice_none, locale.toLanguageTag())
            return
        }
        status.text = getString(R.string.voice_for, locale.toLanguageTag())
        val selected = VoicePrefs.voiceName(this)
        for (voice in options) {
            voices.addView(Button(this).apply {
                text = (if (voice.name == selected) "✓ " else "") +
                    voice.name +
                    (if (voice.isNetworkConnectionRequired) " (online)" else "")
                setOnClickListener {
                    VoicePrefs.setVoiceName(this@VoiceSettingsActivity, voice.name)
                    speakSample(voice)
                    showVoices()
                }
            })
        }
    }

    private fun speakSample(voice: Voice) {
        val engine = tts ?: return
        engine.setLanguage(voice.locale)
        engine.voice = voice
        val sample = if (voice.locale.language == "ko") {
            "안녕하세요. 음성 테스트입니다."
        } else {
            "Hello. This is a voice test."
        }
        engine.speak(sample, TextToSpeech.QUEUE_FLUSH, null, "sample")
    }
}
