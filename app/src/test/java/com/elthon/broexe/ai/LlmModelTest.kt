package com.elthon.broexe.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmModelTest {

    @Test
    fun recommendedIsDownloadableWithPinnedHash() {
        val m = LlmCatalog.recommended
        assertTrue(m.isDownloadable)
        assertNotNull(m.url)
        assertTrue(m.url!!.startsWith("https://"))
        assertNotNull(m.sha256)
        assertTrue("sha must be 64 lowercase hex", m.sha256!!.matches(Regex("[0-9a-f]{64}")))
        assertTrue(m.sizeBytes > 0)
        assertEquals("qwen35", m.arch)
    }

    @Test
    fun fallbackIsBundledAndNotDownloadable() {
        val m = LlmCatalog.fallback
        assertFalse(m.isDownloadable)
        assertEquals(ModelSource.BUNDLED, m.source)
        assertNotNull(m.bundledAsset)
        assertEquals(64, m.sha256!!.length)
    }

    @Test
    fun byIdResolvesAndRejectsUnknown() {
        assertEquals(LlmCatalog.recommended, LlmCatalog.byId(LlmCatalog.recommended.id))
        assertEquals(LlmCatalog.fallback, LlmCatalog.byId(LlmCatalog.fallback.id))
        assertNull(LlmCatalog.byId("does-not-exist"))
        assertNull(LlmCatalog.byId(null))
    }

    @Test
    fun defaultSelectionIsTheRecommendedModel() {
        assertEquals(LlmCatalog.recommended.id, LlmCatalog.DEFAULT_ID)
    }

    @Test
    fun sizeLabelIsHumanReadable() {
        assertEquals("537 MB", LlmCatalog.QWEN35_0_8B.sizeLabel())
    }
}
