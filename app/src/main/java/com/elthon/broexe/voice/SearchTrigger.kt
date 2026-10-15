package com.elthon.broexe.voice

/**
 * Narrow, deterministic detection of an explicit web-search request in a
 * transcribed utterance. The 350M local model is not trusted to reliably emit a
 * tool-call token, so an unambiguous spoken phrase ("search for …", "look up …",
 * "google …") routes to the web tool directly. Everything else stays local.
 *
 * Returns the query to search for, or null to answer locally.
 */
object SearchTrigger {

    private val patterns = listOf(
        Regex("^\\s*(?:please\\s+)?search(?:\\s+the\\s+web|\\s+online|\\s+the\\s+internet)?\\s+for\\s+(.+)$", RegexOption.IGNORE_CASE),
        Regex("^\\s*(?:please\\s+)?search\\s+(.+)$", RegexOption.IGNORE_CASE),
        Regex("^\\s*(?:please\\s+)?look\\s+up\\s+(.+)$", RegexOption.IGNORE_CASE),
        Regex("^\\s*(?:please\\s+)?google\\s+(.+)$", RegexOption.IGNORE_CASE),
        Regex("^\\s*(?:please\\s+)?find\\s+(?:me\\s+)?(?:information\\s+(?:about|on)\\s+)?(.+)$", RegexOption.IGNORE_CASE),
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
