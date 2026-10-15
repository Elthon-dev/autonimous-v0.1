package com.elthon.broexe.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryRetrieverTest {

    private fun mem(text: String, at: Long, id: String = text) = Memory(id, text, at)

    @Test
    fun matchesOnSharedContentWord() {
        val memories = listOf(mem("My dog is named Rex", 1), mem("I like green tea", 2))
        val result = MemoryRetriever.relevant("what is my dog's name", memories)
        assertEquals(listOf("My dog is named Rex"), result.map { it.text })
    }

    @Test
    fun unrelatedQuestionReturnsNothing() {
        val memories = listOf(mem("I like tea", 1))
        assertTrue(MemoryRetriever.relevant("what is the capital of France", memories).isEmpty())
    }

    @Test
    fun stopwordsAloneDoNotMatch() {
        val memories = listOf(mem("I like tea", 1))
        assertTrue(MemoryRetriever.relevant("what is it that you know about me", memories).isEmpty())
    }

    @Test
    fun respectsLimit() {
        val memories = listOf(
            mem("I like tea", 1),
            mem("I drink tea daily", 2),
            mem("tea is great", 3),
            mem("more tea please", 4),
        )
        assertEquals(2, MemoryRetriever.relevant("tea", memories, limit = 2).size)
    }

    @Test
    fun mostRecentWinsTies() {
        val older = mem("I like tea", 1, id = "older")
        val newer = mem("I like tea", 2, id = "newer")
        val result = MemoryRetriever.relevant("tea", listOf(older, newer))
        assertEquals("newer", result[0].id)
    }

    @Test
    fun blankQueryReturnsNothing() {
        val memories = listOf(mem("I like tea", 1))
        assertTrue(MemoryRetriever.relevant("   ", memories).isEmpty())
    }
}
