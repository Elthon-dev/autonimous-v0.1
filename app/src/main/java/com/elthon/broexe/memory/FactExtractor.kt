package com.elthon.broexe.memory

/**
 * Pulls durable, first-person facts out of ordinary speech, so the user never has to
 * phrase them as "remember this".
 *
 * High precision beats recall: a wrong fact is worse than a missed one, so the rules
 * are narrow, the extracted object is length- and shape-checked, and anything that
 * points at the conversation itself ("I like that idea") is dropped. Purely lexical —
 * no model call, so it costs nothing on the hot path. Pure and unit-tested.
 */
object FactExtractor {

    private class Rule(val regex: Regex, val build: (String) -> String)

    /**
     * A fact object: up to four words, letters/digits/hyphens only, and it stops dead
     * at a conjunction so "I like tea and I like coffee" yields one fact ("tea"), not
     * the whole sentence.
     */
    private val OBJ =
        """([\p{L}0-9][\p{L}'\-0-9]*(?: (?!and |or |but |because |so |then |that )[\p{L}0-9][\p{L}'\-0-9]*){0,3})"""

    private val RULES: List<Rule> = listOf(
        rule("""\bmy name is $OBJ""") { "My name is $it" },
        rule("""\bi(?:'m| am) allergic to $OBJ""") { "I'm allergic to $it" },
        rule("""\bi live in $OBJ""") { "I live in $it" },
        rule("""\bi(?:'m| am) based in $OBJ""") { "I'm based in $it" },
        rule("""\bi(?:'m| am) from $OBJ""") { "I'm from $it" },
        rule("""\bi work (?:at|for) $OBJ""") { "I work at $it" },
        rule("""\bi work as (?:an? )?$OBJ""") { "I work as $it" },
        rule("""\bi (?:study|am studying) $OBJ""") { "I study $it" },
        rule("""\bmy birthday is $OBJ""") { "My birthday is $it" },
        rule("""\bi (?:really )?(?:like|love|enjoy|prefer) $OBJ""") { "I like $it" },
        rule("""\bi (?:really )?(?:don't|do not) (?:like|love|enjoy) $OBJ""") { "I don't like $it" },
        rule("""\bi (?:hate|dislike) $OBJ""") { "I dislike $it" },
    )

    private fun rule(regex: String, build: (String) -> String) =
        Rule(Regex(regex, RegexOption.IGNORE_CASE), build)

    /** Durable facts stated in [utterance], normalised and de-duplicated. */
    fun extract(utterance: String): List<String> {
        val text = utterance.trim()
        if (text.length < 6) return emptyList()
        val out = LinkedHashSet<String>()
        for (r in RULES) {
            val match = r.regex.find(text) ?: continue
            val obj = clean(match.groupValues[1])
            if (!isDurable(obj)) continue
            out.add(r.build(obj).replace(Regex("\\s+"), " ").trim())
        }
        return out.toList()
    }

    private fun clean(raw: String): String =
        raw.trim().trimEnd('.', '!', '?', ',', ';').trim()

    private fun isDurable(obj: String): Boolean {
        if (obj.length < 2 || obj.length > 40) return false
        val tokens = obj.lowercase().split(Regex("\\s+"))
        if (tokens.firstOrNull() in STOP_LEAD) return false
        // An object that talks about the listener is not a fact about the user.
        if (tokens.any { it in SECOND_PERSON }) return false
        return true
    }

    private val STOP_LEAD = setOf(
        "that", "it", "this", "these", "those", "how", "when", "where", "why", "what", "who",
        "so", "if", "and", "but", "or", "not", "really", "very", "just", "being", "doing",
        "to", "on",
    )

    private val SECOND_PERSON = setOf("you", "your", "yours", "yourself", "me", "my", "mine")
}
