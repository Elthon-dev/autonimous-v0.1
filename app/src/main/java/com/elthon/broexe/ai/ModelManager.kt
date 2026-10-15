package com.elthon.broexe.ai

import android.content.Context
import java.io.File

/**
 * How far along the single bundled chat model is. The UI shows exactly one of these,
 * never a list of downloadable choices.
 */
enum class ModelState {
    /** Packaged inside the APK, not written to app storage yet. */
    BUNDLED,

    /** Being copied from the APK into app-private storage (one-time). */
    INSTALLING,

    /** Extracted and size/checksum-verified; ready to load into the engine. */
    READY,

    /** Currently being loaded into the inference engine. */
    LOADING,

    /** Extraction or loading failed; [ModelStatus.detail] explains what and why. */
    ERROR,
}

/** The chat model the assistant will load right now, paired with its on-disk file. */
data class ActiveModel(
    val model: LlmModel,
    val file: File,
)

/** UI-facing status for the single bundled model. */
data class ModelStatus(
    val model: LlmModel,
    val state: ModelState,
    val onDiskBytes: Long,
    val detail: String? = null,
)

/**
 * Resolves the single bundled chat model against its extracted location.
 *
 * The APK ships exactly one GGUF; [bundledLlm] is where `VoicePaths.ensureExtracted`
 * writes it. There is deliberately no selection, download, or fallback: the app either
 * has the one model extracted and verified, or it does not — and it says so plainly
 * rather than quietly running something else.
 *
 * Kept free of Android types except the [forContext] factory so the resolution rules
 * are unit-testable on the JVM.
 */
class ModelManager(
    val model: LlmModel,
    private val bundledLlm: File,
) {

    /** True when the model file exists at exactly the expected size. */
    fun isReady(): Boolean = bundledLlm.isFile && bundledLlm.length() == model.sizeBytes

    /** The model to load, or null when the packaged weights are not extracted yet. */
    fun activeModel(): ActiveModel? =
        if (isReady()) ActiveModel(model, bundledLlm) else null

    /** Bytes already on disk for the model (0 when absent). */
    fun onDiskBytes(): Long = bundledLlm.takeIf { it.isFile }?.length() ?: 0L

    fun status(): ModelStatus = if (isReady()) {
        ModelStatus(model, ModelState.READY, bundledLlm.length())
    } else {
        ModelStatus(model, ModelState.BUNDLED, onDiskBytes())
    }

    companion object {
        fun forContext(context: Context, bundledLlm: File): ModelManager =
            ModelManager(LlmCatalog.default, bundledLlm)
    }
}
