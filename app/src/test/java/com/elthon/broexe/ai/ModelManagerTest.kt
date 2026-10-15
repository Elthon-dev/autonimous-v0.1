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

    private class FakeSelection(var id: String? = null) : ModelSelection {
        override fun selectedId(): String? = id
        override fun select(id: String) {
            this.id = id
        }
    }

    private val noDownloader = object : ModelDownloader {
        override suspend fun download(url: String, dest: File, onProgress: (Long, Long) -> Unit) =
            error("network must not be used in this test")
    }

    private fun store() = ModelStore(File(tmp.root, "store"), noDownloader, { Long.MAX_VALUE })

    private fun bundledFile(): File =
        File(tmp.root, "voice/models/lfm2.5-350m-q4_k_m.gguf").apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(16))
        }

    private fun install(model: LlmModel, root: File) {
        root.mkdirs()
        RandomAccessFile(File(root, model.fileName), "rw").use { it.setLength(model.sizeBytes) }
        File(root, model.fileName + ModelStore.MARKER_SUFFIX).writeText("${model.sizeBytes}:${model.sha256.orEmpty()}")
    }

    @Test
    fun fallsBackToBundledModelWithAVisibleNoteWhenRecommendedNotDownloaded() {
        val st = store()
        val manager = ModelManager(FakeSelection(LlmCatalog.recommended.id), st, bundledFile())

        val active = manager.activeModel()

        assertEquals(LlmCatalog.fallback.id, active!!.model.id)
        assertEquals(ModelOrigin.BUNDLED_FALLBACK, active.origin)
        assertTrue("fallback must be explained, never silent", active.note.isNotEmpty())
    }

    @Test
    fun usesDownloadedRecommendedModelWhenInstalled() {
        val st = store()
        val root = File(tmp.root, "store")
        install(LlmCatalog.recommended, root)
        val manager = ModelManager(FakeSelection(LlmCatalog.recommended.id), st, bundledFile())

        val active = manager.activeModel()

        assertEquals(LlmCatalog.recommended.id, active!!.model.id)
        assertEquals(ModelOrigin.DOWNLOADED, active.origin)
        assertTrue(active.note.isEmpty())
    }

    @Test
    fun selectedBundledModelHasNoNote() {
        val manager = ModelManager(FakeSelection(LlmCatalog.fallback.id), store(), bundledFile())

        val active = manager.activeModel()

        assertEquals(LlmCatalog.fallback.id, active!!.model.id)
        assertTrue(active.note.isEmpty())
    }

    @Test
    fun noModelWhenNothingUsable() {
        val manager = ModelManager(FakeSelection(LlmCatalog.recommended.id), store(), File(tmp.root, "missing.gguf"))
        assertNull(manager.activeModel())
    }

    @Test
    fun statusReportsDownloadabilityAndInstallation() {
        val st = store()
        install(LlmCatalog.recommended, File(tmp.root, "store"))
        val manager = ModelManager(FakeSelection(LlmCatalog.recommended.id), st, bundledFile())

        val recommended = manager.status(LlmCatalog.recommended)
        assertTrue(recommended.installed)
        assertFalse("already installed, nothing to download", recommended.canDownload)
        assertTrue(recommended.isActive)

        val fallback = manager.status(LlmCatalog.fallback)
        assertTrue(fallback.installed)
        assertFalse("bundled model is never downloadable", fallback.canDownload)
    }

    @Test
    fun selectionPersistsThroughManager() {
        val selection = FakeSelection(null)
        val manager = ModelManager(selection, store(), bundledFile())

        manager.select(LlmCatalog.fallback)

        assertEquals(LlmCatalog.fallback.id, selection.selectedId())
    }
}
