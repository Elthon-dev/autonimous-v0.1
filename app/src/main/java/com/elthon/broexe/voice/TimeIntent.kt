package com.elthon.broexe.voice

/**
 * Detects a plain "what is the time/date" request so the clock tool can answer it
 * directly — instantly, and without the model being able to hallucinate the time.
 *
 * Deliberately narrow: anything with a place qualifier ("what time is it in Tokyo")
 * is left for web search, because the local clock only knows the device's zone. Pure
 * and unit-tested.
 */
object TimeIntent {

    private val PATTERNS = listOf(
        Regex("\\bwhat(?:'s| is)? the time\\b"),
        Regex("\\bwhat time is it\\b"),
        Regex("\\bwhat(?:'s| is)? (?:the )?(?:date|day)\\b"),
        Regex("\\btoday'?s date\\b"),
        Regex("\\bcurrent (?:time|date|day)\\b"),
        Regex("\\bwhat day (?:is it|is today)\\b"),
        Regex("\\btell me the time\\b"),
    )

    fun matches(utterance: String): Boolean {
        val text = utterance.lowercase().trim()
        if (text.isEmpty()) return false
        // "in <place>" needs a web lookup, not the device clock.
        if (Regex("\\b(?:time|date|day)\\b[^.?!]*\\bin\\b").containsMatchIn(text)) return false
        return PATTERNS.any { it.containsMatchIn(text) }
    }
}
