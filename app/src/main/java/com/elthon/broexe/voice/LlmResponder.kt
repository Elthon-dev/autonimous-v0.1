package com.elthon.broexe.voice

import android.content.Context
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import com.arm.aichat.isModelLoaded
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import java.io.File

/** What [LlmResponder.ensureLoaded] had to do, for honest diagnostics. */
enum class LoadOutcome {
    /** Weights were read and the native model built. */
    LOADED,

    /** Same model and prompt already live — nothing was touched. */
    REUSED,

    /** Same weights; only the system prompt string was swapped (no re-read). */
    PROMPT_UPDATED,
}

/**
 * Voice-oriented wrapper over the `:llama` inference engine.
 *
 * The engine keeps one model resident once loaded, so this class must be reused
 * across turns instead of rebuilt per utterance — otherwise every question pays a
 * full GGUF read + `llama_model_load` (the old "loading model..." every turn).
 *
 * [ensureLoaded] is idempotent: it reuses the resident model when the path matches
 * and only swaps the system prompt when that differs. Callers keep one instance for
 * the whole session and [release] it when done.
 */
class LlmResponder internal constructor(
    private val engine: InferenceEngine,
) {

    private var loadedPath: String? = null
    private var loadedSystemPrompt: String? = null

    /**
     * Ensure `model` is resident and the engine is primed with `systemPrompt`.
     * Returns what actually happened so the caller can report it truthfully.
     */
    suspend fun ensureLoaded(model: File, systemPrompt: String = SYSTEM_PROMPT): LoadOutcome {
        awaitInitialized()
        val path = model.absolutePath
        val current = engine.state.value

        if (current.isModelLoaded && loadedPath == path) {
            if (loadedSystemPrompt == systemPrompt) return LoadOutcome.REUSED
            engine.setSystemPrompt(systemPrompt)
            loadedSystemPrompt = systemPrompt
            return LoadOutcome.PROMPT_UPDATED
        }

        // Different weights, an error, or nothing loaded: reset then load.
        when (current) {
            is InferenceEngine.State.ModelReady, is InferenceEngine.State.Error ->
                runCatching { engine.cleanUp() }
            else -> Unit
        }
        engine.loadModel(path)
        engine.setSystemPrompt(systemPrompt)
        loadedPath = path
        loadedSystemPrompt = systemPrompt
        return LoadOutcome.LOADED
    }

    /** Force a reload on the next [ensureLoaded] even if the path is unchanged. */
    fun invalidate() {
        loadedPath = null
        loadedSystemPrompt = null
    }

    /** Free the native model. Safe to call more than once. */
    fun release() {
        runCatching { engine.cleanUp() }
        invalidate()
    }

    /** Ground-truth list of ggml backends/devices, or why they could not be read. */
    suspend fun backendReport(): String = runCatching {
        awaitInitialized()
        engine.backendReport()
    }.getOrElse { "(no backends: ${it.javaClass.simpleName})" }

    /** llama.cpp compile-time system info, or why it could not be read. */
    suspend fun systemInfo(): String = runCatching {
        awaitInitialized()
        engine.systemInfo()
    }.getOrElse { "(unavailable)" }

    /**
     * The native library loads itself asynchronously; block (bounded) until the
     * engine reaches a loadable state so [ensureLoaded] does not race initialization.
     */
    private suspend fun awaitInitialized() {
        if (engine.state.value is InferenceEngine.State.Initialized ||
            engine.state.value.isModelLoaded
        ) return
        val reached = withTimeout(60_000) {
            runCatching {
                engine.state.first {
                    it is InferenceEngine.State.Initialized ||
                        it.isModelLoaded ||
                        it is InferenceEngine.State.Error
                }
            }.getOrNull()
        }
        when (reached) {
            is InferenceEngine.State.Error ->
                throw IllegalStateException(reached.exception.message ?: "Native engine failed to start")
            null -> throw IllegalStateException("Native engine did not start within 60s")
            else -> Unit
        }
    }

    suspend fun answer(question: String, maxTokens: Int = MAX_TOKENS): String {
        val text = StringBuilder()
        engine.sendUserPrompt(question, maxTokens).collect { text.append(it) }
        return clean(text.toString())
    }

    private fun clean(raw: String): String =
        raw.replace(Regex("[ \\t]+"), " ")
            .replace(Regex("\\n{2,}"), "\n")
            .trim()

    companion object {
        const val MAX_TOKENS = 160

        /**
         * Spoken-voice contract: short, plain sentences. Kept short so the model
         * stays on-topic and the TTS reply does not drone on.
         */
        const val SYSTEM_PROMPT =
            "You are Bro, a friendly concise voice assistant. " +
                "Reply in one or two short plain sentences. " +
                "Do not use markdown, lists, or emoji. " +
                "If you do not know, say so briefly."

        /** Used when web results are supplied: answer only from the given notes. */
        const val SEARCH_SYSTEM_PROMPT =
            "You are Bro, a friendly concise voice assistant. " +
                "Answer the user's question using ONLY the web notes provided. " +
                "Reply in one or two short plain sentences. " +
                "Do not use markdown, lists, or emoji. " +
                "If the notes do not contain the answer, say you could not find it."

        @Volatile
        private var instance: LlmResponder? = null

        /**
         * The process-wide responder. The native engine is itself a singleton, so
         * one responder per process is the correct scope: the model stays resident
         * across turns and even across voice sessions until [release] is called.
         */
        fun get(context: Context): LlmResponder =
            instance ?: synchronized(this) {
                instance ?: LlmResponder(AiChat.getInferenceEngine(context)).also { instance = it }
            }

        /** Render fetched results as compact, prompt-safe notes. */
        fun renderResults(results: List<SearchResult>): String {
            if (results.isEmpty()) return "(no results found)"
            return results.joinToString("\n") { r ->
                val text = r.snippet.ifBlank { r.title }
                "- ${r.title} (${r.domain}): $text"
            }
        }
    }
}
