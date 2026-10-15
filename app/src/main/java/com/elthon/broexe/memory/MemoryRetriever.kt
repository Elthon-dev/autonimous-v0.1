package com.elthon.broexe.memory

import java.util.Locale

/**
 * Ranks remembered facts against a question by shared content words.
 *
 * Deliberately lexical (token overlap) rather than embedding-based: it needs no
 * extra model, runs instantly, and is fully unit-testable. Ties break toward the
 * most recently learned fact so newer information wins.
 */
object MemoryRetriever {

    private val STOPWORDS = setOf(
        "the", "a", "an", "and", "or", "of", "to", "in", "on", "at", "for", "with",
        "is", "are", "was", "were", "be", "been", "am", "do", "does", "did",
        "i", "you", "he", "she", "it", "we", "they", "me", "my", "mine", "your", "yours",
        "this", "that", "these", "those", "what", "which", "who", "whom", "whose",
        "how", "when", "where", "why", "can", "could", "would", "should", "will",
        "about", "know", "remember", "tell", "say", "said", "please", "bro", "hey",
    )

    /**
     * The most relevant memories for [query], best first, at most [limit].
     * Returns an empty list when nothing overlaps or the query has no content words.
     */
    fun relevant(query: String, memories: List<Memory>, limit: Int = 3): List<Memory> {
        val q = tokens(query)
        if (q.isEmpty() || memories.isEmpty()) return emptyList()
        return memories
            .map { it to overlap(q, tokens(it.text)) }
            .filter { it.second > 0 }
            .sortedWith(
                compareByDescending<Pair<Memory, Int>> { it.second }
                    .thenByDescending { it.first.createdAtMs },
            )
            .take(limit)
            .map { it.first }
    }

    private fun overlap(query: Set<String>, fact: Set<String>): Int = query.count { it in fact }

    private fun tokens(text: String): Set<String> =
        text.lowercase(Locale.ROOT)
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 2 && it !in STOPWORDS }
            .toSet()
}
