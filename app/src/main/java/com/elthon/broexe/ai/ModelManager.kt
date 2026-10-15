package com.elthon.broexe.ai

import android.content.Context
import java.io.File

/** Persists which model the user wants to run. Kept tiny so it is trivially fakeable. */
interface ModelSelection {
    fun selectedId(): String?
    fun select(id: String)
}

/** The `SharedPreferences`-backed selection used on device. */
class PrefsModelSelection(context: Context) : ModelSelection {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    override fun selectedId(): String? = prefs.getString(KEY_SELECTED, null)
    override fun select(id: String) {
        prefs.edit().putString(KEY_SELECTED, id).apply()
    }

    companion object {
        const val PREFS = "bro_exe_model"
        const val KEY_SELECTED = "selected_model_id"
    }
}

/** How the currently-active model was obtained. Surfaced to the user verbatim. */
enum class ModelOrigin { DOWNLOADED, BUNDLED_FALLBACK }

/**
 * The model the assistant will actually load right now, plus an honest [note]
 * explaining any fallback that happened.
 */
data class ActiveModel(
    val model: LlmModel,
    val file: File,
    val origin: ModelOrigin,
    val note: String,
)

/** UI-facing status for one catalogue entry. */
data class ModelStatus(
    val model: LlmModel,
    val installed: Boolean,
    val onDiskBytes: Long,
    val canDownload: Boolean,
    val isActive: Boolean,
)

/**
 * Decides which GGUF to run. The rule is deliberately boring:
 *
 *   1. If the *selected* model is usable, use it.
 *   2. Otherwise fall back to the other catalogue entry **with a visible note**,
 *      never silently — the user always sees which model actually answered.
 *   3. If nothing is usable, there is no model.
 *
 * No Android types leak in except the [forContext] factory.
 */
class ModelManager(
    private val selection: ModelSelection,
    private val store: ModelStore,
    private val bundledLlm: File,
) {

    fun selectedModel(): LlmModel = LlmCatalog.byId(selection.selectedId()) ?: LlmCatalog.recommended

    fun select(model: LlmModel) = selection.select(model.id)

    private fun usableFile(model: LlmModel): File? = when (model.source) {
        ModelSource.DOWNLOADED -> store.fileFor(model).takeIf { store.isInstalled(model) }
        ModelSource.BUNDLED -> bundledLlm.takeIf { it.isFile && it.length() > 0L }
    }

    private fun originOf(model: LlmModel): ModelOrigin = when (model.source) {
        ModelSource.DOWNLOADED -> ModelOrigin.DOWNLOADED
        ModelSource.BUNDLED -> ModelOrigin.BUNDLED_FALLBACK
    }

    /** The model to load, or null when no weights are usable at all. */
    fun activeModel(): ActiveModel? {
        val chosen = selectedModel()
        usableFile(chosen)?.let { return ActiveModel(chosen, it, originOf(chosen), "") }

        val alt = LlmCatalog.all.firstOrNull { it.id != chosen.id } ?: return null
        val altFile = usableFile(alt) ?: return null
        return ActiveModel(
            model = alt,
            file = altFile,
            origin = originOf(alt),
            note = "${chosen.displayName} is not ready; using ${alt.displayName}",
        )
    }

    fun status(model: LlmModel): ModelStatus {
        val file = usableFile(model)
        val active = activeModel()?.model?.id == model.id
        return ModelStatus(
            model = model,
            installed = file != null,
            onDiskBytes = file?.length() ?: 0L,
            canDownload = model.isDownloadable && !store.isInstalled(model),
            isActive = active,
        )
    }

    /** Delete a downloaded model (no-op for the bundled one). */
    fun deleteDownload(model: LlmModel): Boolean =
        if (model.source == ModelSource.DOWNLOADED) store.delete(model) else false

    companion object {
        fun forContext(context: Context, bundledLlm: File): ModelManager = ModelManager(
            selection = PrefsModelSelection(context),
            store = ModelStore.forContext(context),
            bundledLlm = bundledLlm,
        )
    }
}
