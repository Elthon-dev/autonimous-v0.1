package com.elthon.broexe.ai

import android.content.Context
import android.os.StatFs
import java.io.File
import java.security.MessageDigest

/** Not enough free space to stage the download safely. */
class InsufficientModelStorageException(
    val requiredBytes: Long,
    val availableBytes: Long,
) : ModelDownloadException(
    "Need \u2248 ${requiredBytes / (1024 * 1024)} MB free, only ${availableBytes / (1024 * 1024)} MB available",
)

/**
 * Owns the downloaded-model directory. All the safety rules for a half-gigabyte
 * file live here:
 *
 *  - writes go to `<name>.part` and are finalised with an atomic rename, so the
 *    real file only ever appears once it is complete;
 *  - the finished file is validated (exact size + pinned SHA-256) before a `.ok`
 *    marker is written;
 *  - [isInstalled] trusts a file only when the marker matches, so a partial or
 *    swapped file is never loaded;
 *  - a download is refused up front when the device lacks room for it.
 *
 * The class is deliberately free of Android types except the [forContext] factory,
 * so every rule above is unit-testable on the JVM.
 */
class ModelStore(
    private val root: File,
    private val downloader: ModelDownloader,
    private val availableBytes: () -> Long,
) {

    fun fileFor(model: LlmModel): File = File(root, model.fileName)

    private fun markerFor(model: LlmModel): File = File(root, model.fileName + MARKER_SUFFIX)

    private fun expectedMarker(model: LlmModel): String = "${model.sizeBytes}:${model.sha256.orEmpty()}"

    /** Bytes on disk for this model's file, or 0 when absent. */
    fun installedSize(model: LlmModel): Long = fileFor(model).let { if (it.isFile) it.length() else 0L }

    /**
     * True only for a complete, checksum-verified file: the size matches, and the
     * `.ok` marker matches the size+hash we expect for this model.
     */
    fun isInstalled(model: LlmModel): Boolean {
        val f = fileFor(model)
        if (!f.isFile || f.length() != model.sizeBytes) return false
        val marker = markerFor(model)
        if (!marker.isFile) return false
        return marker.readText().trim() == expectedMarker(model)
    }

    /**
     * Download, validate and install [model]. Returns the finished file.
     *
     * @throws InsufficientModelStorageException when the device is too full.
     * @throws ModelDownloadException on any transport or validation failure; in
     *         every failure path the partial file is removed and nothing is marked
     *         installed.
     */
    suspend fun download(model: LlmModel, onProgress: (Long, Long) -> Unit = { _, _ -> }): File {
        require(model.isDownloadable) { "model ${model.id} is not downloadable" }
        val dest = fileFor(model)
        if (isInstalled(model)) return dest

        root.mkdirs()
        val need = model.sizeBytes + STAGING_MARGIN_BYTES
        val free = availableBytes()
        if (free < need) throw InsufficientModelStorageException(need, free)

        val tmp = File(root, model.fileName + PART_SUFFIX)
        tmp.delete()
        try {
            downloader.download(model.url!!, tmp, onProgress)
            verify(model, tmp)
        } catch (t: Throwable) {
            tmp.delete()
            // Drop a stale marker so a leftover file can never look installed.
            markerFor(model).delete()
            throw t
        }

        // Atomic-ish finalise: the real file appears only after validation.
        dest.delete()
        if (!tmp.renameTo(dest)) {
            tmp.copyTo(dest, overwrite = true)
            tmp.delete()
        }
        markerFor(model).writeText(expectedMarker(model))
        return dest
    }

    /** Verify a candidate file against the model's pinned size and SHA-256. */
    fun verify(model: LlmModel, file: File) {
        val actualSize = file.length()
        if (actualSize != model.sizeBytes) {
            throw ModelDownloadException("size mismatch: got $actualSize want ${model.sizeBytes}")
        }
        val want = model.sha256
        if (!want.isNullOrBlank()) {
            val got = sha256(file)
            if (!got.equals(want, ignoreCase = true)) {
                throw ModelDownloadException("sha256 mismatch: got $got want $want")
            }
        }
    }

    /** Remove an installed model and its marker. Returns true if anything was removed. */
    fun delete(model: LlmModel): Boolean {
        val removedMarker = markerFor(model).delete()
        val removedFile = fileFor(model).delete()
        File(root, model.fileName + PART_SUFFIX).delete()
        return removedMarker || removedFile
    }

    companion object {
        /** Sub-directory under `filesDir` for downloaded LLM weights. */
        const val MODEL_DIR = "models/llm"

        const val PART_SUFFIX = ".part"
        const val MARKER_SUFFIX = ".ok"

        /** Head-room kept free on top of the file size while staging. */
        const val STAGING_MARGIN_BYTES = 32L * 1024L * 1024L

        fun forContext(context: Context): ModelStore {
            val dir = File(context.filesDir, MODEL_DIR)
            val filesDir = context.filesDir
            return ModelStore(
                root = dir,
                downloader = OkHttpModelDownloader(),
                availableBytes = { StatFs(filesDir.absolutePath).availableBytes },
            )
        }

        /** Lowercase hex SHA-256 of a file, streamed so large files never hit the heap. */
        fun sha256(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(1 shl 16)
                var n: Int
                while (input.read(buf).also { n = it } != -1) md.update(buf, 0, n)
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
