package com.elthon.broexe.memory

/** What a single voice turn should do after consulting memory. */
sealed interface MemoryDirective {
    /** Answer directly with [text]; no LLM call is needed. */
    data class Reply(val text: String) : MemoryDirective

    /** Ask the LLM [question], optionally primed with relevant [facts]. */
    data class Ask(val question: String, val facts: List<Memory>) : MemoryDirective
}

/**
 * Turns one utterance into a memory action using a [MemoryStore].
 *
 * Pure apart from the store, so the whole remember / forget / recall / augment
 * behaviour is unit-testable with an [InMemoryMemoryStore].
 */
object MemoryFlow {

    fun handle(
        store: MemoryStore,
        utterance: String,
        atMs: Long,
        recallLimit: Int = 3,
    ): MemoryDirective = when (val cmd = MemoryExtractor.parse(utterance)) {
        is MemoryCommand.Remember -> {
            store.add(cmd.fact, atMs)
            MemoryDirective.Reply("Okay, I'll remember that.")
        }

        is MemoryCommand.Forget -> {
            val removed = store.forget(cmd.query)
            MemoryDirective.Reply(
                if (removed > 0) "Okay, I've forgotten that."
                else "I don't have anything like that remembered.",
            )
        }

        MemoryCommand.ForgetAll -> {
            store.clear()
            MemoryDirective.Reply("Okay, I've cleared everything I remembered.")
        }

        MemoryCommand.Recall -> MemoryDirective.Reply(recallText(store.all()))

        MemoryCommand.None ->
            MemoryDirective.Ask(utterance, MemoryRetriever.relevant(utterance, store.all(), recallLimit))
    }

    /** Spoken summary of everything remembered, or an honest "nothing yet". */
    fun recallText(memories: List<Memory>, maxListed: Int = 8): String {
        if (memories.isEmpty()) return "I don't remember anything about you yet."
        val ordered = memories.sortedBy { it.createdAtMs }
        val shown = ordered.take(maxListed)
        val listed = shown.joinToString("; ") { it.text.trimEnd('.', '!', '?') }
        val more = ordered.size - shown.size
        val tail = if (more > 0) "; and $more more" else ""
        return "I remember that $listed$tail."
    }
}
