package com.elthon.broexe.memory

/**
 * One durable fact the user told Bro about themselves.
 *
 * Written either because the user asked Bro to remember it ("remember that ...") or
 * because a short, first-person fact was recognised while they spoke ("I'm from Cebu").
 * Memory stays narrow — only short user-stated facts. Microphone audio and raw ambient
 * conversation are never written to it: only the normalised fact text is. There is no
 * code path that stores audio.
 */
data class Memory(
    val id: String,
    val text: String,
    val createdAtMs: Long,
)
