package com.elthon.broexe.ai

/**
 * A single, fully-described chat model. The app ships exactly one — Qwen2.5-0.5B-Instruct
 * (Q6_K) — embedded in the APK and extracted into app storage on first launch. There is
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
    /**
     * True when the model's chat template prefills an open `" thinking"` block, so generation
     * begins inside reasoning and the first `"</think>"` closes it (Qwen3.5-style). Drives
     * [com.elthon.broexe.voice.ThinkingBlockFilter]'s start mode. `false` for the shipped
     * Qwen2.5-Instruct, which is not a reasoning model. Either way an explicit
     * `" thinking ... </think>"` block is stripped from spoken output.
     */
    val thinking: Boolean = false,
) {
    /** Human-readable size, e.g. "620 MB". */
    fun sizeLabel(): String {
        val mb = sizeBytes / (1024.0 * 1024.0)
        return if (mb >= 1024) "%.2f GB".format(mb / 1024.0) else "%.0f MB".format(mb)
    }
}

/**
 * The curated model set: exactly one entry.
 *
 * Qwen2.5-0.5B-Instruct (Q6_K) is a small, pure-ChatML, non-reasoning model that passes the
 * native smoke gate cleanly (no think-token leakage into spoken output) and is RAM-safe on
 * the ~3 GB target device. Apache-2.0, so redistributing the weights inside the APK is fine.
 */
object LlmCatalog {

    val QWEN25_0_5B_INSTR = LlmModel(
        id = "qwen2.5-0.5b-instruct-q6_k",
        displayName = "Qwen2.5 0.5B Instruct (Q6_K)",
        fileName = "qwen2.5-0.5b-instruct-q6_k.gguf",
        sizeBytes = 650_379_104L,
        sha256 = "2f82233630c349ccf6b8daccf48f9a7865713d9f08a2eadfa456cebe9b97c7f5",
        license = "Apache-2.0",
        licenseUrl = "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF",
        arch = "qwen2",
        summary = "Ships inside the APK. Runs fully on-device; no download needed.",
        bundledAsset = "voice/models/qwen2.5-0.5b-instruct-q6_k.gguf",
        thinking = false,
    )

    /** Every chat model the app knows about. There is exactly one on purpose. */
    val all: List<LlmModel> = listOf(QWEN25_0_5B_INSTR)

    /** The sole default (and only) model. */
    val default: LlmModel get() = QWEN25_0_5B_INSTR

    /** The id used everywhere the selected model is referenced. */
    val DEFAULT_ID: String get() = default.id

    fun byId(id: String?): LlmModel? = all.firstOrNull { it.id == id }
}
