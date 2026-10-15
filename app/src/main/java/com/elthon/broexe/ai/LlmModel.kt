package com.elthon.broexe.ai

/** Where a model's weights come from. */
enum class ModelSource {
    /** Shipped inside the APK as an (uncompressed) asset and extracted on first run. */
    BUNDLED,

    /** Fetched over HTTPS on the user's explicit request, then kept in app storage. */
    DOWNLOADED,
}

/**
 * A single, fully-described chat model. Every field the UI, the downloader and the
 * loader need is here, so there is exactly one source of truth for "which GGUF".
 *
 * [sha256] is the authoritative LFS object hash from the hosting repo. It is verified
 * before a freshly downloaded file is ever marked usable, and re-checked on the
 * installed file so a truncated or swapped file is never loaded.
 */
data class LlmModel(
    val id: String,
    val displayName: String,
    val fileName: String,
    val url: String?,
    val sizeBytes: Long,
    val sha256: String?,
    val license: String,
    val licenseUrl: String?,
    val arch: String,
    val source: ModelSource,
    val summary: String,
    /** Path under `assets/` for [ModelSource.BUNDLED], e.g. `voice/models/x.gguf`. */
    val bundledAsset: String? = null,
) {
    /** True when this model can be fetched on demand. */
    val isDownloadable: Boolean get() = source == ModelSource.DOWNLOADED && !url.isNullOrBlank()

    /** Human-readable size, e.g. "537 MB". */
    fun sizeLabel(): String {
        val mb = sizeBytes / (1024.0 * 1024.0)
        return if (mb >= 1024) "%.2f GB".format(mb / 1024.0) else "%.0f MB".format(mb)
    }
}

/**
 * The curated model set. Two entries on purpose:
 *
 *  - the recommended downloadable model (better answers than the 350M), and
 *  - the bundled 350M kept as an offline fallback / rollback path.
 *
 * Nothing here auto-downloads; [LlmCatalog.recommended] is only *offered* in the UI.
 */
object LlmCatalog {

    /**
     * Recommended: Qwen3.5-0.8B (Q4_0). Architecture `qwen35` is supported by the
     * pinned llama.cpp revision (see LLM_ARCH_QWEN35 in llama-arch.cpp). Apache-2.0.
     */
    val QWEN35_0_8B = LlmModel(
        id = "qwen3.5-0.8b-q4_0",
        displayName = "Qwen3.5 0.8B (Q4_0)",
        fileName = "Qwen3.5-0.8B-Q4_0.gguf",
        url = "https://huggingface.co/ggml-org/Qwen3.5-0.8B-GGUF/resolve/main/Qwen3.5-0.8B-Q4_0.gguf",
        sizeBytes = 563_036_064L,
        sha256 = "57d1997790d1744fba5b40a7317df71ea5e2acee28c47e78f0cce39c0703f8cf",
        license = "Apache-2.0",
        licenseUrl = "https://huggingface.co/ggml-org/Qwen3.5-0.8B-GGUF/blob/main/README.md",
        arch = "qwen35",
        source = ModelSource.DOWNLOADED,
        summary = "Sharper answers and better instruction-following than the bundled 350M. \u2248 537 MB, on-device only.",
    )

    /** Bundled fallback / rollback: the original LFM2.5-350M Q4_K_M. */
    val LFM25_350M = LlmModel(
        id = "lfm2.5-350m-q4_k_m",
        displayName = "LFM2.5 350M (Q4_K_M)",
        fileName = "lfm2.5-350m-q4_k_m.gguf",
        url = null,
        sizeBytes = 229_312_224L,
        sha256 = "7e6f72643caafc9a68256686638c4d7916f2cec76d1df478d4c3ddcd95a6aed4",
        license = "LFM Open License v1.0",
        licenseUrl = "https://huggingface.co/LiquidAI/LFM2.5-350M-GGUF",
        arch = "lfm2",
        source = ModelSource.BUNDLED,
        summary = "Ships with the app. Works offline; kept as a safe rollback.",
        bundledAsset = "voice/models/lfm2.5-350m-q4_k_m.gguf",
    )

    val all: List<LlmModel> = listOf(QWEN35_0_8B, LFM25_350M)

    val recommended: LlmModel get() = QWEN35_0_8B

    val fallback: LlmModel get() = LFM25_350M

    /** The id selected on a fresh install. */
    val DEFAULT_ID: String get() = recommended.id

    fun byId(id: String?): LlmModel? = all.firstOrNull { it.id == id }
}
