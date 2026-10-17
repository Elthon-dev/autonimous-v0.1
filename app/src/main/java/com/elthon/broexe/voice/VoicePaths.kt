package com.elthon.broexe.voice

import android.content.Context
import android.content.res.AssetManager
import android.os.StatFs
import com.elthon.broexe.util.sha256Of
import java.io.File
import java.io.IOException

private const val MB = 1024L * 1024L

/** Thrown when the device lacks room for the one-time model extraction. */
class InsufficientStorageException(val requiredBytes: Long, val availableBytes: Long) :
    IOException("Need ${requiredBytes / MB} MB free, only ${availableBytes / MB} MB available")

/**
 * Thrown when the packaged model copied out of the APK fails its integrity check. This
 * means the APK asset is corrupt (a bad download/install), not a transient problem.
 */
class ModelIntegrityException(message: String) : IOException(message)

/**
 * Resolves every on-device voice model path and performs the one-time extraction
 * of the bundled APK assets into app-private storage.
 *
 * The assets ship compression-free (see `androidResources.noCompress`) inside the APK.
 * Both llama.cpp and sherpa-onnx are handed real file paths, so the models must land
 * on disk once. Extraction is staged in `voice.tmp` and renamed into place, so a crash
 * midway can never leave a half-populated model directory behind.
 *
 * The packaged LLM is checksum-verified against the pinned SHA-256 before the extraction
 * is ever marked ready; a failed extraction removes the staging copy and leaves no marker,
 * so a partial or corrupt model can never be loaded.
 */
class VoicePaths private constructor(val root: File) {

    val llm: File get() = File(root, LLM_REL)

    val kwsEncoder: File get() = File(root, "models/kws/encoder.int8.onnx")
    val kwsDecoder: File get() = File(root, "models/kws/decoder.int8.onnx")
    val kwsJoiner: File get() = File(root, "models/kws/joiner.int8.onnx")
    val kwsTokens: File get() = File(root, "models/kws/tokens.txt")
    val keywords: File get() = File(root, "keywords.txt")

    /** App-owned, rewritten by [WakeConfig] when triggers are toggled. */
    val activeKeywords: File get() = File(root, "keywords.active.txt")

    val asrEncoder: File get() = File(root, "models/asr/encoder.int8.onnx")
    val asrDecoder: File get() = File(root, "models/asr/decoder.int8.onnx")
    val asrJoiner: File get() = File(root, "models/asr/joiner.int8.onnx")
    val asrTokens: File get() = File(root, "models/asr/tokens.txt")

    val ttsModel: File get() = File(root, "models/tts/en_US-ryan-low.onnx")
    val ttsTokens: File get() = File(root, "models/tts/tokens.txt")
    val ttsDataDir: File get() = File(root, "models/tts/espeak-ng-data")

    /** Silero VAD, used by the EHS gate as the high-accuracy speech confirmor. */
    val vadModel: File get() = File(root, "models/vad/silero_vad.onnx")

    /** Every file that must exist before the engines can start. */
    fun requiredFiles(): List<File> = listOf(
        llm, kwsEncoder, kwsDecoder, kwsJoiner, kwsTokens, keywords,
        asrEncoder, asrDecoder, asrJoiner, asrTokens,
        ttsModel, ttsTokens,
        vadModel,
    )

    fun isReady(): Boolean = requiredFiles().all { it.isFile && it.length() > 0L } && ttsDataDir.isDirectory

    /**
     * Compare every extracted model file against the size of the bundled
     * (uncompressed) asset it came from. Returns human-readable problems; empty
     * means everything matched. This catches a truncated extraction, which would
     * otherwise reach llama.cpp as a corrupt GGUF and fail far from the cause.
     *
     * Assets for these files are stored uncompressed (`noCompress`), so
     * [AssetManager.openFd] can report the real length.
     */
    fun verifyExtracted(context: Context): List<String> {
        val problems = mutableListOf<String>()
        for (f in requiredFiles()) {
            val rel = runCatching { f.relativeTo(root).invariantSeparatorsPath }.getOrNull() ?: continue
            val expected = runCatching {
                context.assets.openFd("$ASSET_ROOT/$rel").use { it.length }
            }.getOrNull() ?: continue
            if (expected <= 0L) continue
            val actual = f.length()
            if (actual != expected) {
                problems += "$rel: extracted=$actual asset=$expected"
            }
        }
        return problems
    }

    companion object {
        private const val LLM_REL = "models/qwen2.5-0.5b-instruct-q6_k.gguf"

        /**
         * Rough extraction footprint with headroom for the staging copy. The staged tree
         * is ~730 MB (the 620 MB GGUF dominates); a re-extract briefly holds the old tree
         * plus the new one, so we ask for more than the tree alone.
         */
        const val REQUIRED_BYTES: Long = 950L * MB

        private const val EXTRACT_VERSION = "voice-assets-v5"

        private const val ASSET_ROOT = "voice"
        private const val DIR_NAME = "voice"
        private const val TMP_NAME = "voice.tmp"
        private const val MARKER = ".extracted"

        /** Serialises concurrent extraction (app launch and Voice Mode can both ask). */
        private val LOCK = Any()

        /** Path resolution only — does not touch the disk. */
        fun forFilesDir(filesDir: File) = VoicePaths(File(filesDir, DIR_NAME))

        /**
         * Ensure the models are extracted, extracting them on first launch.
         *
         * @param llmSha256 when non-null, the copied GGUF is verified against this hash
         *   before the extraction is marked ready. The hash is recorded in the marker, so
         *   later launches trust the already-verified copy without re-hashing 620 MB.
         * @param onProgress optional truthful progress callback (copied bytes, total bytes)
         *   invoked while copying the asset tree.
         * @throws InsufficientStorageException when there is not enough free space.
         * @throws ModelIntegrityException when the packaged model fails its checksum.
         */
        fun ensureExtracted(
            context: Context,
            llmSha256: String? = null,
            onProgress: ((copied: Long, totalBytes: Long) -> Unit)? = null,
        ): VoicePaths = synchronized(LOCK) {
            val paths = forFilesDir(context.filesDir)
            val marker = File(paths.root, MARKER)
            val expectedMarker = markerValue(llmSha256)
            if (marker.isFile && marker.readText().trim() == expectedMarker && paths.isReady()) {
                val problems = paths.verifyExtracted(context)
                VoiceDiagnostics.event(
                    "paths",
                    if (problems.isEmpty()) "extraction verified (all model sizes match assets)"
                    else "extraction WARNING: ${problems.joinToString("; ")}",
                )
                return paths
            }

            val stat = StatFs(context.filesDir.absolutePath)
            val available = stat.availableBytes
            if (available < REQUIRED_BYTES) {
                throw InsufficientStorageException(REQUIRED_BYTES, available)
            }

            val tmp = File(context.filesDir, TMP_NAME)
            tmp.deleteRecursively()
            tmp.mkdirs()
            val baseTotal = assetTreeBytes(context.assets, ASSET_ROOT)
            var copied = 0L
            onProgress?.invoke(0L, baseTotal)
            copyAssetTree(context.assets, ASSET_ROOT, tmp) { n ->
                copied += n
                onProgress?.invoke(copied, maxOf(baseTotal, copied))
            }

            if (llmSha256 != null) {
                val copiedLlm = File(tmp, LLM_REL)
                val got = sha256Of(copiedLlm)
                if (!got.equals(llmSha256, ignoreCase = true)) {
                    tmp.deleteRecursively()
                    throw ModelIntegrityException(
                        "packaged model checksum mismatch: got $got want $llmSha256",
                    )
                }
            }
            File(tmp, MARKER).writeText(expectedMarker)

            paths.root.deleteRecursively()
            if (!tmp.renameTo(paths.root)) {
                // Rename can fail across odd mounts; fall back to a copy.
                tmp.copyRecursively(paths.root, overwrite = true)
                tmp.deleteRecursively()
            }

            val post = paths.verifyExtracted(context)
            VoiceDiagnostics.event(
                "paths",
                if (post.isEmpty()) "extraction verified (all model sizes match assets)"
                else "extraction WARNING: ${post.joinToString("; ")}",
            )
            paths
        }

        private fun markerValue(llmSha256: String?): String =
            if (llmSha256.isNullOrBlank()) EXTRACT_VERSION else "$EXTRACT_VERSION:$llmSha256"

        /** Sum of the uncompressed asset lengths under [assetPath] (0 for compressed leaves). */
        private fun assetTreeBytes(assets: AssetManager, assetPath: String): Long {
            val children = assets.list(assetPath)
            if (children.isNullOrEmpty()) {
                return runCatching { assets.openFd(assetPath).use { it.length } }.getOrDefault(0L)
            }
            var sum = 0L
            for (child in children) sum += assetTreeBytes(assets, "$assetPath/$child")
            return sum
        }

        private fun copyAssetTree(
            assets: AssetManager,
            assetPath: String,
            dest: File,
            onBytes: (Long) -> Unit,
        ) {
            val children = assets.list(assetPath)
            if (children.isNullOrEmpty()) {
                // Leaf: a single file to be written at [dest].
                dest.parentFile?.mkdirs()
                var written = 0L
                dest.outputStream().use { out ->
                    assets.open(assetPath).use { input ->
                        val buf = ByteArray(1 shl 16)
                        var n: Int
                        while (input.read(buf).also { n = it } != -1) {
                            out.write(buf, 0, n)
                            written += n
                        }
                    }
                }
                onBytes(written)
                return
            }
            dest.mkdirs()
            for (child in children) {
                copyAssetTree(assets, "$assetPath/$child", File(dest, child), onBytes)
            }
        }
    }
}
