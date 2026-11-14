package io.github.leehj825.myapp.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptWriterTest {

    @Test
    fun removesALabelAndQuotes() {
        assertEquals("a misty mountain at dawn", PromptWriter.clean("Prompt: \"a misty mountain at dawn\""))
        assertEquals("a misty mountain at dawn", PromptWriter.clean("Improved prompt:  a misty mountain at dawn"))
    }

    @Test
    fun putsTheReplyOnOneLine() {
        assertEquals("a cat, soft light, detailed", PromptWriter.clean("  a cat,\n\nsoft   light,\tdetailed \n"))
    }

    @Test
    fun leavesAShortPromptAlone() {
        assertEquals("a red car", PromptWriter.clean("a red car"))
    }

    @Test
    fun anEmptyReplyStaysEmpty() {
        assertEquals("", PromptWriter.clean("  \n "))
    }

    @Test
    fun aLongReplyIsCutAtACommaThatFits() {
        val phrase = "a detailed mountain landscape with soft golden light"
        val long = (1..20).joinToString(", ") { phrase }
        val cleaned = PromptWriter.clean(long)
        assertTrue(cleaned.length <= 400)
        assertFalse(cleaned.endsWith(","))
        assertTrue(cleaned.endsWith("light"))
    }
}
