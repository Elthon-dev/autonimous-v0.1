package com.elthon.broexe.ai

/**
 * A single, fully-described chat model. The app ships exactly one — Qwen2.5-0.5B-Instruct
 * (Q4_K_M) — embedded in the APK and extracted into app storage on first launch. There is
 * deliberately no second candidate and no download path, so there is exactly one source of
 * truth for "which GGUF".
 *
 * [sha256] is the authoritative LFS object hash from the hosting repo. It is checked against
 * the packaged asset before extraction is ever trusted, and the extracted file is re-checked
 * so a truncated or swapped file can never reach llama.cpp.
 */
data class LlmModel(
    val id: String,
    val displayName: String,
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String,
    val license: String,
    val licenseUrl: String?,
    val arch: String,
    val summary: String,
    /** Path under `assets/`, e.g. `voice/models/x.gguf`. Always packaged inside the APK. */
    val bundledAsset: String,
) {
    /** Human-readable size, e.g. "469 MB". */
    fun sizeLabel(): String {
        val mb = sizeBytes / (1024.0 * 1024.0)
        return if (mb >= 1024) "%.2f GB".format(mb / 1024.0) else "%.0f MB".format(mb)
    }
}

/**
 * The curated model set: exactly one entry.
 *
 * Qwen2.5-0.5B-Instruct (Q4_K_M) is a small, pure-ChatML, non-reasoning model that passes the
 * native smoke gate cleanly (no think-token leakage into spoken output) and is RAM-safe on
 * the ~3 GB target device. Apache-2.0, so redistributing the weights inside the APK is fine.
 */
object LlmCatalog {

    val QWEN25_0_5B_INSTR = LlmModel(
        id = "qwen2.5-0.5b-instruct-q4_k_m",
        displayName = "Qwen2.5 0.5B Instruct (Q4_K_M)",
        fileName = "qwen2.5-0.5b-instruct-q4_k_m.gguf",
        sizeBytes = 491_400_032L,
        sha256 = "74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db",
        license = "Apache-2.0",
        licenseUrl = "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF",
        arch = "qwen2",
        summary = "Ships inside the APK. Runs fully on-device; no download needed.",
        bundledAsset = "voice/models/qwen2.5-0.5b-instruct-q4_k_m.gguf",
    )

    /** Every chat model the app knows about. There is exactly one on purpose. */
    val all: List<LlmModel> = listOf(QWEN25_0_5B_INSTR)

    /** The sole default (and only) model. */
    val default: LlmModel get() = QWEN25_0_5B_INSTR

    /** The id used everywhere the selected model is referenced. */
    val DEFAULT_ID: String get() = default.id

    fun byId(id: String?): LlmModel? = all.firstOrNull { it.id == id }
}
