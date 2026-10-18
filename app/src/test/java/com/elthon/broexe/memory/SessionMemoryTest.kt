package com.elthon.broexe.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionMemoryTest {

    @Test
    fun keepsOnlyTheMostRecentTurns() {
        val memory = SessionMemory(maxTurns = 2)
        memory.recordTurn("one", "a")
        memory.recordTurn("two", "b")
        memory.recordTurn("three", "c")
        val turns = memory.recentTurns()
        assertEquals(2, turns.size)
        assertEquals("two", turns[0].user)
        assertEquals("three", turns[1].user)
    }

    @Test
    fun ignoresEmptyExchanges() {
        val memory = SessionMemory()
        memory.recordTurn("", "   ")
        assertTrue(memory.recentTurns().isEmpty())
    }

    @Test
    fun factsAreDedupedAndBounded() {
        val memory = SessionMemory(maxFacts = 2)
        memory.noteFact("I like tea")
        memory.noteFact("I like tea")
        assertEquals(listOf("I like tea"), memory.sessionFacts())
        memory.noteFact("I live in Cebu")
        memory.noteFact("I like coffee")
        assertEquals(2, memory.sessionFacts().size)
        assertFalse(memory.sessionFacts().contains("I like tea"))
    }

    @Test
    fun relevantFactsFilteredByQuestion() {
        val memory = SessionMemory()
        memory.noteFact("I like tea")
        memory.noteFact("I live in Cebu")
        assertEquals(listOf("I like tea"), memory.relevantFacts("do I like tea"))
        assertTrue(memory.relevantFacts("what is the weather").isEmpty())
    }

    @Test
    fun clearResetsEverything() {
        val memory = SessionMemory()
        memory.recordTurn("hi", "hello")
        memory.noteFact("I like tea")
        memory.clear()
        assertTrue(memory.recentTurns().isEmpty())
        assertTrue(memory.sessionFacts().isEmpty())
    }
}
