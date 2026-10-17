package com.elthon.broexe.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile

class ModelManagerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val model = LlmCatalog.default

    /** A file that exists at exactly the model's expected size, without writing 620 MB. */
    private fun sizedFile(length: Long): File =
        File(tmp.root, model.fileName).apply {
            RandomAccessFile(this, "rw").use { it.setLength(length) }
        }

    @Test
    fun noActiveModelWhenNotExtractedYet() {
        val manager = ModelManager(model, File(tmp.root, "missing.gguf"))

        assertNull(manager.activeModel())
        assertFalse(manager.isReady())
        assertEquals(ModelState.BUNDLED, manager.status().state)
    }

    @Test
    fun activeModelWhenExtractedAtExpectedSize() {
        val manager = ModelManager(model, sizedFile(model.sizeBytes))

        val active = manager.activeModel()
        assertEquals(model, active!!.model)
        assertTrue(manager.isReady())
        assertEquals(ModelState.READY, manager.status().state)
    }

    @Test
    fun wrongSizeIsNotReady() {
        val manager = ModelManager(model, sizedFile(model.sizeBytes - 1))

        assertNull(manager.activeModel())
        assertFalse(manager.isReady())
        assertEquals(ModelState.BUNDLED, manager.status().state)
    }

    @Test
    fun statusReportsOnDiskBytes() {
        val manager = ModelManager(model, sizedFile(model.sizeBytes))
        assertEquals(model.sizeBytes, manager.status().onDiskBytes)
    }

    @Test
    fun managerResolvesTheSoleDefaultModel() {
        assertEquals(LlmCatalog.default, ModelManager(model, File(tmp.root, "x")).model)
    }
}
