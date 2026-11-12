package io.github.leehj825.myapp

import android.app.Activity
import androidx.annotation.StringRes
import io.github.leehj825.myapp.chat.ChatActivity
import io.github.leehj825.myapp.chat.GeminiClient
import io.github.leehj825.myapp.chat.LlmEngine
import io.github.leehj825.myapp.image.ImageActivity
import io.github.leehj825.myapp.journal.JournalActivity
import io.github.leehj825.myapp.notes.NotesActivity
import io.github.leehj825.myapp.recorder.ScreenRecorderActivity
import io.github.leehj825.myapp.secure.SecureNotesActivity
import io.github.leehj825.myapp.voice.VoiceSettingsActivity

/**
 * One tool in the app: its name and description on the home screen, and the activity that opens it.
 * [available] says whether this phone or build can use the tool; unavailable tools are not shown.
 */
data class Tool(
    @StringRes val name: Int,
    @StringRes val description: Int,
    val activity: Class<out Activity>,
    val available: () -> Boolean = { true },
)

/** All tools, in the order they appear on the home screen. To add one, write its activity and list it here. */
object Tools {
    val all = listOf(
        Tool(
            name = R.string.tool_screen_recorder,
            description = R.string.tool_screen_recorder_desc,
            activity = ScreenRecorderActivity::class.java,
        ),
        Tool(
            name = R.string.tool_notes,
            description = R.string.tool_notes_desc,
            activity = NotesActivity::class.java,
        ),
        Tool(
            name = R.string.tool_journal,
            description = R.string.tool_journal_desc,
            activity = JournalActivity::class.java,
        ),
        Tool(
            name = R.string.tool_secure_notes,
            description = R.string.tool_secure_notes_desc,
            activity = SecureNotesActivity::class.java,
        ),
        Tool(
            name = R.string.tool_voice,
            description = R.string.tool_voice_desc,
            activity = VoiceSettingsActivity::class.java,
        ),
        Tool(
            name = R.string.tool_chat,
            description = R.string.tool_chat_desc,
            activity = ChatActivity::class.java,
            available = { LlmEngine.libraryLoaded },
        ),
        Tool(
            name = R.string.tool_image,
            description = R.string.tool_image_desc,
            activity = ImageActivity::class.java,
            available = { GeminiClient.available },
        ),
    )

    /** The tools this phone and build can use. */
    fun available(): List<Tool> = all.filter { it.available() }
}
