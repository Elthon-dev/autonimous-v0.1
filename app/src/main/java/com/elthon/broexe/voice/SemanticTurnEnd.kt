package com.elthon.broexe.voice

/**
 * Heuristic "is this transcript a finished thought?" test used for early end-of-turn.
 *
 * The streaming transducer emits lowercase text with no punctuation, so we cannot lean
 * on ".", "?" or "!". Instead we watch for the opposite: a transcript that *trails off*
 * on a word that cannot end a sentence (a conjunction, preposition, article or filler)
 * is treated as unfinished. Anything else with at least [MIN_WORDS] words counts as
 * complete, which lets a clear request be answered shortly after the user stops instead
 * of waiting out the full pause tolerance.
 *
 * Pure: no audio, no clock — every case is unit-testable.
 */
object SemanticTurnEnd {

    /** Transcripts shorter than this are too ambiguous to call complete. */
    const val MIN_WORDS = 2

    /** Words that leave a sentence hanging when they are the last thing heard. */
    private val DANGLING = setOf(
        "a", "an", "the", "and", "or", "but", "so", "because", "if", "when", "while",
        "to", "of", "in", "on", "at", "for", "with", "from", "by", "as", "than",
        "is", "are", "was", "were", "am", "be", "do", "does", "did",
        "can", "could", "would", "should", "will", "shall", "may", "might", "must",
        "about", "into", "over", "under", "between", "that", "which", "who", "whom",
        "whose", "um", "uh", "er", "hmm",
    )

    fun looksComplete(partial: String): Boolean {
        val words = partial.trim().lowercase()
            .split(Regex("[^a-z0-9']+"))
            .filter { it.isNotEmpty() }
        if (words.size < MIN_WORDS) return false
        val last = words.last().trim('\'')
        return last.isNotEmpty() && last !in DANGLING
    }
}
