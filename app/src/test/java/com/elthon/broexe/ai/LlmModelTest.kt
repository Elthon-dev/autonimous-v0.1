package com.elthon.broexe.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmModelTest {

    @Test
    fun defaultIsTheBundledQwenAndPointsAtThePackagedAsset() {
        val m = LlmCatalog.default
        assertEquals("qwen2.5-0.5b-instruct-q4_k_m", m.id)
        assertEquals("qwen2", m.arch)
        assertEquals("voice/models/qwen2.5-0.5b-instruct-q4_k_m.gguf", m.bundledAsset)
        assertEquals("qwen2.5-0.5b-instruct-q4_k_m.gguf", m.fileName)
        assertEquals(491_400_032L, m.sizeBytes)
        assertEquals(64, m.sha256.length)
        assertTrue("sha must be 64 lowercase hex", m.sha256.matches(Regex("[0-9a-f]{64}")))
        assertEquals("Apache-2.0", m.license)
    }

    @Test
    fun thereIsExactlyOneModelSoNothingCompetes() {
        assertEquals(1, LlmCatalog.all.size)
        assertEquals(LlmCatalog.default, LlmCatalog.all.single())
    }

    @Test
    fun byIdResolvesDefaultAndRejectsUnknown() {
        assertEquals(LlmCatalog.default, LlmCatalog.byId(LlmCatalog.default.id))
        assertNull(LlmCatalog.byId("does-not-exist"))
        assertNull(LlmCatalog.byId(null))
        assertNull(LlmCatalog.byId("lfm2.5-350m-q4_k_m"))
    }

    @Test
    fun defaultIdMatchesTheOnlyModel() {
        assertEquals(LlmCatalog.default.id, LlmCatalog.DEFAULT_ID)
    }

    @Test
    fun licenseUrlIsPresentForRedistributionCompliance() {
        assertNotNull(LlmCatalog.default.licenseUrl)
        assertTrue(LlmCatalog.default.licenseUrl!!.startsWith("https://"))
    }

    @Test
    fun sizeLabelIsHumanReadable() {
        assertEquals("469 MB", LlmCatalog.QWEN25_0_5B_INSTR.sizeLabel())
    }
}
