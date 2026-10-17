package com.elthon.broexe.voice

/**
 * Narrow, deterministic detection of an explicit web-search request in a
 * transcribed utterance. The 0.5B local model is not trusted to reliably emit a
 * tool-call token, so an unambiguous spoken phrase ("search for …", "look up …",
 * "google …") routes to the web tool directly. Everything else stays local.
 *
 * Deliberately conservative: it now only fires on a search verb plus a target, or on
 * "find (me) information …". The old bare "find (…)" form matched mundane
 * sentences ("find the bug"), so it was dropped — a missed search is recovered by the
 * user asking again, whereas a spurious search burns data and adds latency.
 *
 * Returns the query to search for, or null to answer locally.
 */
object SearchTrigger {

    private val POLITE = "(?:please\\s+|can\\s+you\\s+|could\\s+you\\s+)?"

    private val patterns = listOf(
        Regex("^\\s*$POLITE" + "search(?:\\s+the\\s+web|\\s+online|\\s+the\\s+internet)?\\s+for\\s+(.+)$", RegexOption.IGNORE_CASE),
        Regex("^\\s*$POLITE" + "search(?:\\s+the\\s+web|\\s+online|\\s+the\\s+internet)?\\s+(.+)$", RegexOption.IGNORE_CASE),
        Regex("^\\s*$POLITE" + "look\\s+up\\s+(.+)$", RegexOption.IGNORE_CASE),
        Regex("^\\s*$POLITE" + "google\\s+(.+)$", RegexOption.IGNORE_CASE),
        Regex("^\\s*$POLITE" + "find\\s+me\\s+(?:information\\s+(?:about|on)\\s+|info\\s+(?:about|on)\\s+|details\\s+(?:about|on)\\s+)?(.+)$", RegexOption.IGNORE_CASE),
        Regex("^\\s*$POLITE" + "find\\s+(?:information|info|details)\\s+(?:about|on)\\s+(.+)$", RegexOption.IGNORE_CASE),
    )

    fun detect(utterance: String): String? {
        val text = utterance.trim().trimEnd('.', '!', '?')
        for (pattern in patterns) {
            val match = pattern.find(text) ?: continue
            val query = match.groupValues.last().trim().trimEnd('.', '!', '?').trim()
            // Reject filler-only matches ("search for it", "look up that").
            val candidate = query.lowercase().removePrefix("for ").trim()
            if (candidate.length >= 2 && candidate !in FILLER) return query
        }
        return null
    }

    private val FILLER = setOf("", "it", "that", "this", "something", "stuff", "things")
}
