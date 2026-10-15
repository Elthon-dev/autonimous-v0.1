package com.elthon.broexe.memory

import java.util.Locale

/** What the user is asking us to do with memory this turn. */
sealed interface MemoryCommand {
    /** "remember that ..." — store [fact]. */
    data class Remember(val fact: String) : MemoryCommand

    /** "forget that ..." / "forget ..." — remove entries matching [query]. */
    data class Forget(val query: String) : MemoryCommand

    /** "forget everything" / "clear your memory". */
    data object ForgetAll : MemoryCommand

    /** "what do you remember" / "do you remember ...". */
    data object Recall : MemoryCommand

    /** Not a memory command; handle the utterance normally. */
    data object None : MemoryCommand
}

/**
 * Classifies a transcript as a memory command, purely and without Android.
 *
 * Longest-prefix matching means "remember that" wins over "remember", and
 * "forget everything" is recognised before a plain "forget <thing>".
 */
object MemoryExtractor {

    private val REMEMBER_PREFIXES = listOf(
        "please remember that ", "please remember ",
        "remember that ", "remember ",
        "note that ", "note ",
        "keep in mind that ", "keep in mind ",
    ).sortedByDescending { it.length }

    private val FORGET_ALL_PHRASES = listOf(
        "forget everything you know",
        "forget everything", "forget all",
        "clear your memory", "clear your memories",
        "wipe your memory", "wipe your memories",
        "erase your memory", "erase your memories",
    )

    private val RECALL_PHRASES = listOf(
        "what do you remember",
        "what do you know about me",
        "do you remember",
        "what have i told you",
    )

    private val FORGET_PREFIXES = listOf(
        "forget that ", "forget about ", "forget ",
    ).sortedByDescending { it.length }

    fun parse(utterance: String): MemoryCommand {
        val text = utterance.trim()
        if (text.isEmpty()) return MemoryCommand.None
        val lower = text.lowercase(Locale.ROOT).trimEnd('.', '!', '?', ' ')

        for (p in FORGET_ALL_PHRASES) {
            if (lower == p || lower.startsWith(p)) return MemoryCommand.ForgetAll
        }
        for (p in RECALL_PHRASES) {
            if (lower.startsWith(p)) return MemoryCommand.Recall
        }
        for (p in REMEMBER_PREFIXES) {
            if (lower.startsWith(p)) {
                val fact = strip(text.substring(p.length))
                return if (fact.isEmpty()) MemoryCommand.None else MemoryCommand.Remember(fact)
            }
        }
        for (p in FORGET_PREFIXES) {
            if (lower.startsWith(p)) {
                val query = strip(text.substring(p.length))
                return if (query.isEmpty()) MemoryCommand.ForgetAll else MemoryCommand.Forget(query)
            }
        }
        return MemoryCommand.None
    }

    private fun strip(s: String): String = s.trim().trimEnd('.', '!', '?').trim()
}
