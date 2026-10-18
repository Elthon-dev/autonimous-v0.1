package com.elthon.broexe.voice

/**
 * One capability Bro can invoke.
 *
 * The set is deliberately tiny and described in the model's system prompt so the
 * 0.5B stops inventing abilities it does not have ("I can access databases, web
 * pages…"). OpenAI's own guidance and Google's on-device function-calling work both
 * land on the same rule: keep the initially-available tool list small and describe
 * each one plainly, or accuracy falls off a cliff.
 */
data class Tool(
    val name: String,
    val does: String,
    val useWhen: String,
)

/**
 * The fixed toolset, and the single source of truth for what Bro can do.
 *
 * Routing itself stays deterministic and pre-LLM (a 0.5B is not trusted to emit a
 * tool-call token) — this object exists to *tell the model the truth* about its
 * capabilities, which is what stops the capability hallucination seen on-device.
 */
object Toolbox {

    val webSearch = Tool(
        name = "web_search",
        does = "look things up on the web",
        useWhen = "the answer needs current, live or recent information you were not trained on",
    )

    val clock = Tool(
        name = "clock",
        does = "read the current date and time",
        useWhen = "the user asks the time or the day, or a question depends on today's date",
    )

    val memory = Tool(
        name = "memory",
        does = "save and recall things the user tells you about themselves",
        useWhen = "the user shares a personal fact to keep, or asks what you remember",
    )

    val all: List<Tool> = listOf(webSearch, clock, memory)

    /**
     * Stable, cache-friendly description of the toolset. This text must not change
     * between turns: it is the prefix of the system prompt that the native layer keeps
     * in its KV cache.
     */
    fun promptBlock(): String = buildString {
        append("You have exactly these tools, and no others:\n")
        for (tool in all) {
            append("- ").append(tool.name).append(": ").append(tool.does)
            append(", used when ").append(tool.useWhen).append(".\n")
        }
        append("Never claim or invent any other ability (no databases, files, images or code). ")
        append("Only mention web results when you are actually given some.")
    }
}
