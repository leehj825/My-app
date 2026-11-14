package io.github.leehj825.myapp.image

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedListsTest {

    @Test
    fun modelListSurvivesSavingAndReading() {
        val models = listOf(
            ModelRef("sdxs-512-Q8_0.gguf", "/data/files/image/models/sdxs.gguf", true),
            ModelRef("sdxl.gguf", "/storage/emulated/0/Download/sdxl.gguf", false),
        )
        assertEquals(models, ModelStore.parse(ModelStore.serialize(models)))
    }

    @Test
    fun anOldEntryWithoutTheCopiedFlagCountsAsCopied() {
        val parsed = ModelStore.parse("""[{"name":"a.gguf","path":"/p/a.gguf"}]""")
        assertTrue(parsed.single().copied)
    }

    @Test
    fun aPictureEntrySurvivesSavingAndReading() {
        val entry = HistoryEntry(
            uri = "content://media/external/images/media/42", time = 1_700_000_000_000L, kind = "phone",
            prompt = "a misty mountain", negative = "blurry", model = "sdxl.gguf", backend = "GPU",
            steps = 10, size = 768, seed = 123_456_789L, sampler = "EULER_A", guidance = 5.0f, seconds = 12.5,
        )
        assertEquals(entry, ImageHistory.fromJson(JSONObject(ImageHistory.toJson(entry).toString())))
    }

    @Test
    fun aGeminiEntryKeepsItsEmptyFields() {
        val entry = HistoryEntry(
            uri = "content://media/external/images/media/7", time = 5L, kind = "gemini",
            prompt = "a cat", negative = "", model = "gemini-3.1-flash-image", backend = "Google",
            steps = 0, size = 1024, seed = 0L, sampler = "", guidance = 0f, seconds = 4.0,
        )
        val back = ImageHistory.fromJson(JSONObject(ImageHistory.toJson(entry).toString()))
        assertEquals("", back.sampler)
        assertFalse(back.kind == "phone")
        assertEquals(entry, back)
    }
}
