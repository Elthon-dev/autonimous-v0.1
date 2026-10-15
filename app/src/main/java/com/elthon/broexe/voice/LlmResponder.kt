package com.elthon.broexe.voice

import android.content.Context
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import java.io.File

/**
 * Thin voice-oriented wrapper over the `:llama` inference engine.
 *
 * The engine is stateless per turn (each prompt re-decodes system + user), so the
 * system prompt is installed once after loading and every call to [answer] is an
 * independent question — exactly what a wake-word assistant wants.
 */
class LlmResponder(context: Context) {

    private val engine: InferenceEngine = AiChat.getInferenceEngine(context)

    suspend fun load(model: File, systemPrompt: String = SYSTEM_PROMPT) {
        awaitInitialized()
        engine.loadModel(model.absolutePath)
        engine.setSystemPrompt(systemPrompt)
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
     * engine reaches a loadable state so [load] does not race initialization.
     */
    private suspend fun awaitInitialized() {
        if (engine.state.value is InferenceEngine.State.Initialized ||
            engine.state.value is InferenceEngine.State.ModelReady
        ) return
        val reached = withTimeout(60_000) {
            runCatching {
                engine.state.first {
                    it is InferenceEngine.State.Initialized ||
                        it is InferenceEngine.State.ModelReady ||
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

    fun unload() = runCatching { engine.cleanUp() }

    private fun clean(raw: String): String =
        raw.replace(Regex("[ \\t]+"), " ")
            .replace(Regex("\\n{2,}"), "\n")
            .trim()

    companion object {
        const val MAX_TOKENS = 160

        /**
         * Spoken-voice contract: short, plain sentences. Kept short so the 350M
         * model stays on-topic and the TTS reply does not drone on.
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
