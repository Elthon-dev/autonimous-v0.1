package com.elthon.broexe.memory

/**
 * One durable fact the user explicitly asked Bro to remember.
 *
 * Memory is deliberately narrow: it holds only short, user-stated facts
 * ("I like tea", "my dog is Rex"). Microphone audio and ambient conversation are
 * never written to it — there is no code path that stores either.
 */
data class Memory(
    val id: String,
    val text: String,
    val createdAtMs: Long,
)
