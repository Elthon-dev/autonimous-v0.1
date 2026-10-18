package com.elthon.broexe.voice

/**
 * Narrow, deterministic detection of a web-search request in a transcribed
 * utterance. The 0.5B local model is not trusted to reliably emit a tool-call
 * token, so an explicit spoken phrase routes to the web tool directly. Everything
 * else stays local.
 *
 * Kept conservative — a missed search is recovered by the user asking again,
 * whereas a spurious search burns data and adds latency — but it now also catches
 * how people *actually* phrase it: a leading verb ("search for X"), a mid-sentence
 * request ("can you search X"), a web-qualified clause ("search the web for X"),
 * and a trailing instruction whose object is the surrounding question
 * ("What is the latest ChatGPT model? Use your web search tool.").
 *
 * Returns the query to search for, or null to answer locally.
 */
object SearchTrigger {

    private val POLITE = "(?:please\\s+|can\\s+you\\s+|could\\s+you\\s+)?"

    /** Leading verb + object: "search for X", "look up X", "google X", "find me X". */
    private val patterns = listOf(
        Regex("^\\s*$POLITE" + "search(?:\\s+the\\s+web|\\s+online|\\s+the\\s+internet)?\\s+for\\s+(.+)$", RegexOption.IGNORE_CASE),
        Regex("^\\s*$POLITE" + "search(?:\\s+the\\s+web|\\s+online|\\s+the\\s+internet)?\\s+(.+)$", RegexOption.IGNORE_CASE),
        Regex("^\\s*$POLITE" + "look\\s+up\\s+(.+)$", RegexOption.IGNORE_CASE),
        Regex("^\\s*$POLITE" + "google\\s+(.+)$", RegexOption.IGNORE_CASE),
        Regex("^\\s*$POLITE" + "find\\s+me\\s+(?:information\\s+(?:about|on)\\s+|info\\s+(?:about|on)\\s+|details\\s+(?:about|on)\\s+)?(.+)$", RegexOption.IGNORE_CASE),
        Regex("^\\s*$POLITE" + "find\\s+(?:information|info|details)\\s+(?:about|on)\\s+(.+)$", RegexOption.IGNORE_CASE),
    )

    /**
     * A bare instruction to use the search tool, e.g. "use your web search tool",
     * "use the search engine". It carries no object of its own, so the request is
     * whatever *other* sentence the user said.
     */
    private val USE_SEARCH = Regex(
        "\\buse\\s+(?:your\\s+|the\\s+)?(?:web\\s+|internet\\s+)?search(?:\\s+(?:tool|engine))?\\b",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Mid-sentence request behind an auxiliary cue, e.g. "… to search X",
     * "can you search X", "please search X". The cue requirement is what keeps
     * noun uses ("why did the search fail") out.
     */
    private val MID_SEARCH = Regex(
        "\\b(?:to\\s+|can\\s+you\\s+|could\\s+you\\s+|would\\s+you\\s+|please\\s+|just\\s+|also\\s+|and\\s+|then\\s+)" +
            "search\\s+(?:the\\s+web\\s+|online\\s+|the\\s+internet\\s+)?(?:for\\s+)?(.{2,})",
        RegexOption.IGNORE_CASE,
    )

    /** A web-qualified clause anywhere: "… search the web (for) X". */
    private val WEB_QUALIFIED = Regex(
        "\\bsearch\\s+(?:the\\s+web|online|the\\s+internet)\\b(?:\\s+for)?\\s*(.*)",
        RegexOption.IGNORE_CASE,
    )

    fun detect(utterance: String): String? {
        val text = utterance.trim().trimEnd('.', '!', '?')

        // 1. Explicit leading verb with its object.
        for (pattern in patterns) {
            val match = pattern.find(text) ?: continue
            val query = clean(match.groupValues.last())
            if (isMeaningful(query)) return query
        }

        // 2. A standalone "use your search tool" instruction: the request is the
        //    other sentence ("…? Use your web search tool.").
        if (USE_SEARCH.containsMatchIn(text)) {
            val query = questionFromDirective(text)
            return if (query != null && isMeaningful(query)) query else null
        }

        // 3. Mid-sentence request behind a cue ("… to search X").
        MID_SEARCH.find(text)?.let {
            val query = clean(it.groupValues[1])
            if (isMeaningful(query)) return query
        }

        // 4. Web-qualified clause anywhere ("… search the web for X").
        WEB_QUALIFIED.find(text)?.let {
            val query = clean(it.groupValues[1])
            if (isMeaningful(query)) return query
        }
        return null
    }

    /** Strip the bare directive and keep the longest remaining sentence as the request. */
    private fun questionFromDirective(text: String): String? =
        USE_SEARCH.replace(text, " ")
            .split(Regex("[.?!]+"))
            .map { it.trim().trim(',', ':', ';', '-', ' ') }
            .filter { it.isNotEmpty() }
            .maxByOrNull { it.split(Regex("\\s+")).size }

    private fun clean(query: String): String =
        query.trim().trimEnd('.', '!', '?').trim().removePrefix("for ").trim()

    private fun isMeaningful(query: String): Boolean {
        val candidate = query.lowercase().removePrefix("for ").trim()
        return candidate.length >= 2 && candidate !in FILLER
    }

    private val FILLER = setOf("", "it", "that", "this", "something", "stuff", "things")
}
