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
     * Reasoning discarded by the last [answer] call, if the model thought before replying.
     * Diagnostics only; it is never spoken and never fed back into the next turn.
     */
    @Volatile
    var lastReasoning: String? = null
        private set

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

    /**
     * Ask the resident model and return only the speakable answer.
     *
     * Set [thinking] when the model's chat template prefills an open `" thinking"` block
     * (Qwen3.5-style): the stream then starts inside reasoning and the first
     * `"</think>"` closes it. The shipped Qwen2.5-Instruct is not a reasoning model, so
     * the default is `false`. Either way a `" thinking ... </think>"` block emitted by the
     * model is stripped, so its private reasoning can never reach the TTS engine.
     */
    suspend fun answer(
        question: String,
        maxTokens: Int = MAX_TOKENS,
        thinking: Boolean = false,
    ): String {
        val filter = ThinkingBlockFilter(startsInThinking = thinking)
        val visible = StringBuilder()
        engine.sendUserPrompt(question, maxTokens).collect { chunk ->
            visible.append(filter.feed(chunk))
        }
        visible.append(filter.finish())
        lastReasoning = if (filter.sawThinking) filter.reasoningText.trim().ifEmpty { null } else null
        return clean(visible.toString())
    }

    private fun clean(raw: String): String =
        raw.replace(SPECIAL_TOKEN, " ")
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex("\\n{2,}"), "\n")
            .trim()

    companion object {
        const val MAX_TOKENS = 160

        /**
         * Last-resort scrub for control tokens that should never be spoken, e.g.
         * `<|im_start|>`, `<|im_end|>`, `<|endoftext|>`. The native stop set normally
         * halts generation before these; this catches anything that still slips through.
         */
        private val SPECIAL_TOKEN = Regex("<\\|[^|>]*\\|>")

        /**
         * The one, stable system prompt.
         *
         * Stable is the whole point: the native layer caches this text's KV prefix and
         * only re-decodes the variable user turn, so the moment this string changes the
         * cache is thrown away. It therefore carries only invariant content — persona,
         * the spoken-voice contract, and the real toolset ([Toolbox]) — never the time or
         * the retrieved notes. Per OpenAI/Meta guidance, the stable prefix goes first and
         * anything volatile goes in the user turn.
         */
        val SYSTEM_PROMPT: String = buildString {
            append("You are Bro, a friendly, concise voice assistant. ")
            append("Answer in one or two short spoken sentences. ")
            append("Do not use markdown, lists, or emoji. ")
            append("If you do not know or are unsure, say so briefly instead of guessing. ")
            append(Toolbox.promptBlock())
        }

        /**
         * Appended to the *user* turn when web notes are supplied, so the system prompt
         * can stay byte-for-byte identical (and cacheable) whether or not a search ran.
         */
        const val NOTES_INSTRUCTION =
            "Answer using only the web notes above for anything current; " +
                "if they do not cover it, say so."

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
