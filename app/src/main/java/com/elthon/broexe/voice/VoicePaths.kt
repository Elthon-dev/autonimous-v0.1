package com.elthon.broexe.voice

import android.content.Context
import android.content.res.AssetManager
import android.os.StatFs
import java.io.File
import java.io.IOException

private const val MB = 1024L * 1024L

/** Thrown when the device lacks room for the one-time model extraction. */
class InsufficientStorageException(val requiredBytes: Long, val availableBytes: Long) :
    IOException("Need ${requiredBytes / MB} MB free, only ${availableBytes / MB} MB available")

/**
 * Resolves every on-device voice model path and performs the one-time extraction
 * of the bundled APK assets into app-private storage.
 *
 * The assets ship compressed-free (see `androidResources.noCompress`) inside the APK.
 * Both llama.cpp and sherpa-onnx are handed real file paths, so the models must land
 * on disk once. Extraction is staged in `voice.tmp` and renamed into place, so a
 * crash midway can never leave a half-populated model directory behind.
 */
class VoicePaths private constructor(val root: File) {

    val llm: File get() = File(root, "models/lfm2.5-350m-q4_k_m.gguf")

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

    val ttsModel: File get() = File(root, "models/tts/en_US-amy-low.onnx")
    val ttsTokens: File get() = File(root, "models/tts/tokens.txt")
    val ttsDataDir: File get() = File(root, "models/tts/espeak-ng-data")

    /** Every file that must exist before the engines can start. */
    fun requiredFiles(): List<File> = listOf(
        llm, kwsEncoder, kwsDecoder, kwsJoiner, kwsTokens, keywords,
        asrEncoder, asrDecoder, asrJoiner, asrTokens,
        ttsModel, ttsTokens,
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
        /** Rough extraction footprint with headroom for the staging copy. */
        const val REQUIRED_BYTES: Long = 360L * MB

        private const val EXTRACT_VERSION = "voice-assets-v2"

        private const val ASSET_ROOT = "voice"
        private const val DIR_NAME = "voice"
        private const val TMP_NAME = "voice.tmp"
        private const val MARKER = ".extracted"

        /** Path resolution only — does not touch the disk. */
        fun forFilesDir(filesDir: File) = VoicePaths(File(filesDir, DIR_NAME))

        /**
         * Ensure the models are extracted, extracting them on first launch.
         *
         * @throws InsufficientStorageException when there is not enough free space.
         */
        fun ensureExtracted(context: Context): VoicePaths {
            val paths = forFilesDir(context.filesDir)
            val marker = File(paths.root, MARKER)
            if (marker.isFile && marker.readText() == EXTRACT_VERSION && paths.isReady()) {
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
            copyAssetTree(context.assets, ASSET_ROOT, tmp)
            File(tmp, MARKER).writeText(EXTRACT_VERSION)

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
            return paths
        }

        private fun copyAssetTree(assets: AssetManager, assetPath: String, dest: File) {
            val children = assets.list(assetPath)
            if (children.isNullOrEmpty()) {
                // Leaf: a single file to be written at [dest].
                dest.parentFile?.mkdirs()
                dest.outputStream().use { out ->
                    assets.open(assetPath).use { input -> input.copyTo(out) }
                }
                return
            }
            dest.mkdirs()
            for (child in children) {
                copyAssetTree(assets, "$assetPath/$child", File(dest, child))
            }
        }
    }
}
