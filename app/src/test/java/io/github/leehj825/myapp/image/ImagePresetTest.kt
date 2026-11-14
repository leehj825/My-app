package io.github.leehj825.myapp.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImagePresetTest {

    @Test
    fun sdxsFileStartsOnTheFastOneStepSettings() {
        assertEquals(ImagePreset.SDXS, ImagePreset.guess("sdxs-512-Q8_0.gguf"))
    }

    @Test
    fun fastSdxlNamesAreRecognized() {
        assertEquals(ImagePreset.SDXL_FAST, ImagePreset.guess("sdxl-lightning-4step.gguf"))
        assertEquals(ImagePreset.SDXL_FAST, ImagePreset.guess("DMD2_SDXL_Q8.gguf"))
        assertEquals(ImagePreset.SDXL_FAST, ImagePreset.guess("hyper-sdxl-8steps.gguf"))
    }

    @Test
    fun plainSdxlIsNotTakenForTheFastOne() {
        assertEquals(ImagePreset.SDXL, ImagePreset.guess("sd_xl_base_1.0_SDXL_Q8.gguf"))
    }

    @Test
    fun anythingElseIsStableDiffusion() {
        assertEquals(ImagePreset.SD, ImagePreset.guess("v1-5-pruned-emaonly-Q8_0.gguf"))
        assertEquals(ImagePreset.SD, ImagePreset.guess("model.gguf"))
    }

    @Test
    fun everyPresetStaysInsideWhatTheScreenAccepts() {
        for (preset in ImagePreset.values()) {
            assertTrue("${preset.name} steps", preset.steps in 1..50)
            assertTrue("${preset.name} size", preset.size in listOf(512, 768, 1024))
            assertTrue("${preset.name} guidance", preset.guidance > 0f)
        }
    }

    @Test
    fun samplerCodesAreDistinctAndFollowTheListOrder() {
        val codes = Sampler.values().map { it.code }
        assertEquals(codes.size, codes.toSet().size)
        assertEquals(Sampler.values().indices.toList(), codes)
    }
}
