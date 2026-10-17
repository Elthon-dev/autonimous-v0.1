package com.elthon.broexe.voice

/**
 * Streaming splitter that keeps a model's hidden reasoning out of spoken output.
 *
 * "Thinking" models (Qwen3, Qwen3.5, DeepSeek-R1, ...) emit their private reasoning
 * between `" thinking"` and `"</think>"` markers; the final answer is whatever follows
 * the last `"</think>"`. A voice assistant must never speak the reasoning or the markers,
 * but it also must not stall: text has to stream out as soon as it is known to be answer.
 *
 * Two layouts are handled, mirroring vLLM's Qwen3 reasoning parser:
 *  - the model emits `" thinking"` itself and generation starts in the answer
 *    ([startsInThinking] = `false`, the shipped Qwen2.5 case) — a leading
 *    `" thinking"` opens a block; or
 *  - the chat template *prefills* `" thinking"`, so generation begins already inside the
 *    block and the first marker seen is `"</think>"` ([startsInThinking] = `true`) —
 *    everything before that first close is reasoning.
 *
 * Guarantees:
 *  - a block that is still open when the stream ends (truncated generation) is dropped
 *    entirely — partial reasoning is never spoken;
 *  - a close marker with no matching open drops the text that preceded it (best effort;
 *    the preceding text is what the model was thinking);
 *  - a lone `<` that never becomes a marker is preserved verbatim;
 *  - a marker split across callbacks is buffered and never leaks.
 *
 * Not thread-safe; feed one answer at a time.
 */
class ThinkingBlockFilter(
    private val startsInThinking: Boolean = false,
) {

    private enum class Mode { CONTENT, THINKING }

    private val pending = StringBuilder()
    private val reasoning = StringBuilder()
    private var mode = if (startsInThinking) Mode.THINKING else Mode.CONTENT

    /** Reasoning seen so far. Diagnostics only — never spoken. */
    val reasoningText: String get() = reasoning.toString()

    /** True once any `" thinking"` / `"</think>"` marker has been observed. */
    var sawThinking: Boolean = false
        private set

    /** Feed one streamed chunk; returns the speakable text it contributed. */
    fun feed(chunk: String): String {
        if (chunk.isEmpty()) return ""
        pending.append(chunk)
        val out = StringBuilder()
        drain(out, final = false)
        return out.toString()
    }

    /** Close the stream; returns any final speakable text still buffered. */
    fun finish(): String {
        val out = StringBuilder()
        drain(out, final = true)
        return out.toString()
    }

    private fun drain(out: StringBuilder, final: Boolean) {
        while (true) {
            if (mode == Mode.THINKING) {
                val close = pending.indexOf(THINK_CLOSE)
                if (close >= 0) {
                    reasoning.append(pending, 0, close)
                    pending.delete(0, close + THINK_CLOSE.length)
                    mode = Mode.CONTENT
                    sawThinking = true
                    continue
                }
                val keep = if (final) 0 else partialMarkerLength()
                reasoning.append(pending, 0, pending.length - keep)
                pending.delete(0, pending.length - keep)
                if (final) pending.clear()
                return
            }

            val open = pending.indexOf(THINK_OPEN)
            val close = pending.indexOf(THINK_CLOSE)
            when {
                open >= 0 && (close < 0 || open < close) -> {
                    out.append(pending, 0, open)
                    pending.delete(0, open + THINK_OPEN.length)
                    mode = Mode.THINKING
                    sawThinking = true
                }

                close >= 0 -> {
                    // Stray close: whatever came before it was reasoning.
                    reasoning.append(pending, 0, close)
                    pending.delete(0, close + THINK_CLOSE.length)
                    sawThinking = true
                }

                else -> {
                    val keep = if (final) 0 else partialMarkerLength()
                    out.append(pending, 0, pending.length - keep)
                    pending.delete(0, pending.length - keep)
                    return
                }
            }
        }
    }

    /**
     * How many trailing chars of [pending] could still grow into a marker. Holding these
     * back is what stops a marker split across chunks from leaking half of itself.
     */
    private fun partialMarkerLength(): Int {
        val max = maxOf(THINK_OPEN.length, THINK_CLOSE.length) - 1
        val n = pending.length
        var best = 0
        var len = 1
        while (len <= max && len <= n) {
            val suffix = pending.substring(n - len)
            if (THINK_OPEN.startsWith(suffix) || THINK_CLOSE.startsWith(suffix)) best = len
            len++
        }
        return best
    }

    private companion object {
        // Assembled from parts on purpose: a literal "think" tag in source has a
        // history of being mangled in transit. Concatenation of const strings is
        // still a compile-time constant, so this stays a `const val`.
        const val THINK_OPEN = "<" + "think" + ">"
        const val THINK_CLOSE = "<" + "/" + "think" + ">"
    }
}
