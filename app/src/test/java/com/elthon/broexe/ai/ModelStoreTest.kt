package com.elthon.broexe.ai

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ModelStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class FakeDownloader(
        private val payload: ByteArray,
        private val failWith: Throwable? = null,
    ) : ModelDownloader {
        var calls = 0
        override suspend fun download(url: String, dest: File, onProgress: (Long, Long) -> Unit) {
            calls++
            failWith?.let { throw it }
            dest.writeBytes(payload)
            onProgress(payload.size.toLong(), payload.size.toLong())
        }
    }

    private fun model(size: Long, sha: String?) = LlmModel(
        id = "test", displayName = "Test", fileName = "t.gguf",
        url = "https://example.test/t.gguf", sizeBytes = size, sha256 = sha,
        license = "X", licenseUrl = null, arch = "test",
        source = ModelSource.DOWNLOADED, summary = "",
    )

    private fun store(downloader: ModelDownloader, available: Long = Long.MAX_VALUE) =
        ModelStore(File(tmp.root, "root"), downloader, { available })

    private fun payload(size: Int) = ByteArray(size) { (it % 251).toByte() }

    private fun shaOf(bytes: ByteArray): String {
        val f = tmp.newFile("sha-source.bin").apply { writeBytes(bytes) }
        return ModelStore.sha256(f)
    }

    @Test
    fun downloadsVerifiesAndMarksInstalled() = runTest {
        val bytes = payload(4096)
        val m = model(bytes.size.toLong(), shaOf(bytes))
        val dl = FakeDownloader(bytes)
        val st = store(dl)

        val dest = st.download(m)

        assertEquals(1, dl.calls)
        assertTrue(st.isInstalled(m))
        assertArrayEquals(bytes, dest.readBytes())
        assertFalse(File(tmp.root, "root/t.gguf.part").exists())
    }

    @Test
    fun rejectsSizeMismatchAndLeavesNothingInstalled() = runTest {
        val m = model(4096, null)
        val st = store(FakeDownloader(payload(100)))

        val ex = runCatching { st.download(m) }.exceptionOrNull()

        assertTrue(ex is ModelDownloadException)
        assertFalse(st.isInstalled(m))
        assertFalse(File(tmp.root, "root/t.gguf").exists())
        assertFalse(File(tmp.root, "root/t.gguf.part").exists())
    }

    @Test
    fun rejectsShaMismatchEvenWhenSizeMatches() = runTest {
        val bytes = payload(4096)
        val m = model(bytes.size.toLong(), "0".repeat(64))
        val st = store(FakeDownloader(bytes))

        val ex = runCatching { st.download(m) }.exceptionOrNull()

        assertTrue(ex is ModelDownloadException)
        assertFalse(st.isInstalled(m))
    }

    @Test
    fun refusesUpFrontWhenStorageIsTight() = runTest {
        val bytes = payload(4096)
        val m = model(bytes.size.toLong(), shaOf(bytes))
        val dl = FakeDownloader(bytes)
        val st = store(dl, available = 1024)

        val ex = runCatching { st.download(m) }.exceptionOrNull()

        assertTrue(ex is InsufficientModelStorageException)
        assertEquals("must not touch the network when storage is refused", 0, dl.calls)
        assertFalse(st.isInstalled(m))
    }

    @Test
    fun secondDownloadIsANoOpWhenAlreadyInstalled() = runTest {
        val bytes = payload(2048)
        val m = model(bytes.size.toLong(), shaOf(bytes))
        val dl = FakeDownloader(bytes)
        val st = store(dl)

        st.download(m)
        st.download(m)

        assertEquals(1, dl.calls)
    }

    @Test
    fun failedDownloadLeavesNoPartialAndDropsMarker() = runTest {
        val m = model(4096, null)
        val st = store(FakeDownloader(ByteArray(0), failWith = RuntimeException("boom")))

        val ex = runCatching { st.download(m) }.exceptionOrNull()

        assertTrue(ex is RuntimeException)
        assertFalse(st.isInstalled(m))
        assertFalse(File(tmp.root, "root/t.gguf.part").exists())
        assertFalse(File(tmp.root, "root/t.gguf.ok").exists())
    }

    @Test
    fun tamperedFileIsNotConsideredInstalled() = runTest {
        val bytes = payload(4096)
        val m = model(bytes.size.toLong(), shaOf(bytes))
        val st = store(FakeDownloader(bytes))
        st.download(m)
        assertTrue(st.isInstalled(m))

        // Shrink the file behind the store's back: the marker no longer matches.
        File(tmp.root, "root/t.gguf").writeBytes(payload(10))

        assertFalse(st.isInstalled(m))
    }

    @Test
    fun deleteRemovesFileAndMarker() = runTest {
        val bytes = payload(4096)
        val m = model(bytes.size.toLong(), shaOf(bytes))
        val st = store(FakeDownloader(bytes))
        st.download(m)

        assertTrue(st.delete(m))
        assertFalse(st.isInstalled(m))
        assertFalse(File(tmp.root, "root/t.gguf").exists())
        assertFalse(File(tmp.root, "root/t.gguf.ok").exists())
    }
}
