package com.elthon.broexe.memory

import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.UUID

/**
 * Durable store of user-provided facts.
 *
 * Holds short, first-person facts — both explicit "remember ..." ones and those
 * recognised from ordinary speech. Never microphone audio and never raw conversation
 * transcripts. The store is text-only, tiny, and safe to keep across launches.
 */
interface MemoryStore {
    /** Every remembered fact, oldest first. */
    fun all(): List<Memory>

    /** Append [text] (trimmed). Returns the stored entry. */
    fun add(text: String, atMs: Long): Memory

    /** Remove entries whose text contains [query] (case-insensitive). Returns how many. */
    fun forget(query: String): Int

    /** Remove every remembered fact. */
    fun clear()
}

/** In-memory store: for tests and ephemeral use. */
class InMemoryMemoryStore(
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) : MemoryStore {
    private val entries = mutableListOf<Memory>()

    override fun all(): List<Memory> = entries.toList()

    override fun add(text: String, atMs: Long): Memory {
        val trimmed = text.trim()
        require(trimmed.isNotEmpty()) { "empty memory" }
        val mem = Memory(idFactory(), trimmed, atMs)
        entries.add(mem)
        return mem
    }

    override fun forget(query: String): Int {
        val q = query.trim()
        if (q.isEmpty()) return 0
        var n = 0
        val it = entries.iterator()
        while (it.hasNext()) {
            if (it.next().text.contains(q, ignoreCase = true)) {
                it.remove()
                n++
            }
        }
        return n
    }

    override fun clear() {
        entries.clear()
    }
}

/**
 * Line-based, dependency-free [MemoryStore].
 *
 * Format (one entry per line): `<id>|<createdAtMs>|<url-encoded text>`. The text
 * is URL-encoded so facts may contain spaces, punctuation or newlines without
 * breaking the record. Writes are non-atomic but the file is tiny and rewritten
 * whole, so a crash can at worst lose the most recent fact.
 */
class FileMemoryStore(
    private val file: File,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) : MemoryStore {
    private val lock = Any()

    override fun all(): List<Memory> = synchronized(lock) { load() }

    override fun add(text: String, atMs: Long): Memory {
        val trimmed = text.trim()
        require(trimmed.isNotEmpty()) { "empty memory" }
        synchronized(lock) {
            val current = load().toMutableList()
            val mem = Memory(idFactory(), trimmed, atMs)
            current.add(mem)
            save(current)
            return mem
        }
    }

    override fun forget(query: String): Int {
        val q = query.trim()
        if (q.isEmpty()) return 0
        synchronized(lock) {
            val current = load()
            val kept = current.filterNot { it.text.contains(q, ignoreCase = true) }
            val removed = current.size - kept.size
            if (removed > 0) save(kept)
            return removed
        }
    }

    override fun clear() {
        synchronized(lock) {
            if (file.exists()) file.delete()
        }
    }

    private fun load(): List<Memory> {
        if (!file.exists()) return emptyList()
        return file.readLines().mapNotNull(::parse)
    }

    private fun parse(line: String): Memory? {
        if (line.isBlank()) return null
        val parts = line.split('|', limit = 3)
        if (parts.size != 3) return null
        val at = parts[1].toLongOrNull() ?: return null
        val text = runCatching { URLDecoder.decode(parts[2], "UTF-8") }.getOrNull() ?: return null
        if (text.isBlank()) return null
        return Memory(parts[0], text, at)
    }

    private fun save(memories: List<Memory>) {
        file.parentFile?.mkdirs()
        val sb = StringBuilder()
        for (m in memories) {
            sb.append(m.id).append('|')
                .append(m.createdAtMs).append('|')
                .append(URLEncoder.encode(m.text, "UTF-8")).append('\n')
        }
        file.writeText(sb.toString())
    }
}
