package com.elthon.broexe.voice

/**
 * Decides when a second, self-review pass is worth its latency, and builds the review
 * prompt. Bounded by construction: this only ever authorises ONE extra pass, so the
 * worst case is two model decodes, never a loop.
 *
 * A greeting or a trivial question is answered in a single pass; only web-grounded,
 * memory-grounded or genuinely complex questions get the draft-then-review treatment.
 * Pure and unit-tested.
 */
object ReflectionPolicy {

    /** A question at least this many words long is treated as complex. */
    const val COMPLEX_WORDS = 12

    private val CUES = listOf(
        "compare", "difference between", "why", "explain", "pros and cons",
        "step by step", "calculate", "how much", "how many", "trade-off", "tradeoff",
        "summarise", "summarize", "in detail",
    )

    fun shouldReflect(question: String, hasSearch: Boolean, hasFacts: Boolean): Boolean {
        if (hasSearch || hasFacts) return true
        val q = question.lowercase()
        if (q.split(Regex("\\s+")).count { it.isNotBlank() } >= COMPLEX_WORDS) return true
        return CUES.any { q.contains(it) }
    }

    /** The prompt that asks the model to check and tighten its own draft. */
    fun reviewPrompt(question: String, draft: String): String = buildString {
        append("Question: ").append(question).append('\n')
        append("Your draft answer: ").append(draft).append('\n')
        append("Check the draft for factual mistakes and unnecessary words. ")
        append("If it is already correct and concise, repeat it unchanged. ")
        append("Reply with only the final answer, in one or two short plain sentences.")
    }
}
