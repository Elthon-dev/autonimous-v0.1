package com.elthon.broexe.memory

/** One exchange in the current voice session. Text only — never microphone audio. */
data class SessionTurn(val user: String, val assistant: String)

/**
 * Rolling, in-memory view of the *current* conversation — the "compaction" half of the
 * memory story.
 *
 * Deliberately NOT summarisation. The 2026 literature on agent memory is blunt that
 * summarize-and-compact loses facts and constraints ("the compaction cliff"), so Bro
 * instead keeps a small working memory (the last few turns) plus a deduped scratchpad
 * of facts seen this session, and hands the model only what is relevant to the current
 * question — relevance-filtered, never rewritten. That matches the robust BEAM/H-MEM
 * pattern (working memory + a salient-fact scratchpad) without an extra model call.
 *
 * Bounded by construction: at most [maxTurns] turns and [maxFacts] facts. Never
 * persisted, never microphone audio.
 */
class SessionMemory(
    private val maxTurns: Int = 6,
    private val maxFacts: Int = 12,
) {
    private val turns = ArrayDeque<SessionTurn>()
    private val facts = LinkedHashSet<String>()

    fun recordTurn(user: String, assistant: String) {
        val u = user.trim()
        val a = assistant.trim()
        if (u.isEmpty() && a.isEmpty()) return
        turns.addLast(SessionTurn(u, a))
        while (turns.size > maxTurns) turns.removeFirst()
    }

    /** Remember a salient fact for the rest of the session (most-recent wins on eviction). */
    fun noteFact(fact: String) {
        val f = fact.trim()
        if (f.isEmpty()) return
        facts.remove(f) // re-insert at the tail so the newest facts survive eviction
        facts.add(f)
        while (facts.size > maxFacts) facts.remove(facts.first())
    }

    fun recentTurns(): List<SessionTurn> = turns.toList()

    fun sessionFacts(): List<String> = facts.toList()

    /** The session facts most relevant to [question], best first. */
    fun relevantFacts(question: String, limit: Int = 3): List<String> {
        if (facts.isEmpty()) return emptyList()
        val asMemory = facts.map { Memory(id = it, text = it, createdAtMs = 0L) }
        return MemoryRetriever.relevant(question, asMemory, limit).map { it.text }
    }

    fun clear() {
        turns.clear()
        facts.clear()
    }
}
