package com.elthon.broexe.voice

/**
 * Turns a raw command transcript into just the user's request.
 *
 * The command recognizer is primed with pre-trigger audio, so the transcript often
 * still contains the wake phrase ("hey bro, what is two plus two"). We remove the
 * trigger phrase only — any words that came *before* it are kept, because a request
 * can legally run straight through the trigger ("set a timer hey bro").
 *
 * Pure and android-free so the trimming rules are unit-testable.
 */
object RequestExtractor {

    /** Trigger phrases, longest first so "okay bro" wins over "bro". */
    private val TRIGGERS = listOf(
        "hey bro",
        "yo bro",
        "okay bro",
        "ok bro",
        "hey brotha",
        "bro",
    ).sortedByDescending { it.length }

    private val FILLERS = setOf("um", "uh", "erm", "hmm", "ah", "er")

    /** True if the transcript contains any known trigger phrase as whole words. */
    fun containsTrigger(transcript: String): Boolean =
        matchTrigger(transcript) != null

    fun extract(transcript: String): String {
        val trimmed = transcript.trim()
        if (trimmed.isEmpty()) return ""

        val match = matchTrigger(trimmed)
        val body = if (match != null) {
            trimmed.removeRange(match.first, match.last + 1)
        } else {
            trimmed
        }
        return stripLeadingNoise(body)
    }

    /** Index range (inclusive) of the earliest trigger phrase, or null. */
    private fun matchTrigger(text: String): IntRange? {
        val lower = text.lowercase()
        var best: IntRange? = null
        for (phrase in TRIGGERS) {
            val m = Regex("\\b" + Regex.escape(phrase) + "\\b").find(lower) ?: continue
            if (best == null || m.range.first < best.first) {
                best = m.range.first until m.range.last + 1
            }
        }
        return best
    }

    private fun stripLeadingNoise(text: String): String {
        val tokens = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val start = tokens.indexOfFirst { normalize(it) !in FILLERS }
        val kept = if (start < 0) emptyList() else tokens.subList(start, tokens.size)
        return kept.joinToString(" ")
            .trim()
            .trimStart(',', '.', '!', '?', ':', ';', '-', ' ')
            .trim()
    }

    private fun normalize(token: String): String =
        token.lowercase().trim(',', '.', '!', '?', ':', ';', '-', '"', '\'')
}
