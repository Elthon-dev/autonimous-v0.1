package com.elthon.broexe.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryFlowTest {

    private fun store() = InMemoryMemoryStore { "id-" + counter++ }

    private var counter = 0

    @Test
    fun rememberStoresFactAndReplies() {
        val store = store()
        val directive = MemoryFlow.handle(store, "remember that I like tea", 10)
        assertEquals(MemoryDirective.Reply("Okay, I'll remember that."), directive)
        assertEquals(listOf("I like tea"), store.all().map { it.text })
    }

    @Test
    fun forgetExistingReportsSuccess() {
        val store = store()
        store.add("I like tea", 1)
        val directive = MemoryFlow.handle(store, "forget that I like tea", 2)
        assertTrue(directive is MemoryDirective.Reply)
        assertTrue((directive as MemoryDirective.Reply).text.contains("forgotten"))
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun forgetMissingIsHonest() {
        val store = store()
        val directive = MemoryFlow.handle(store, "forget that I like tea", 2) as MemoryDirective.Reply
        assertTrue(directive.text.contains("don't have"))
    }

    @Test
    fun forgetEverythingClearsStore() {
        val store = store()
        store.add("a", 1)
        store.add("b", 2)
        MemoryFlow.handle(store, "clear your memory", 3)
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun recallWithNothingRemembersIsHonest() {
        val store = store()
        val directive = MemoryFlow.handle(store, "what do you remember", 1) as MemoryDirective.Reply
        assertEquals("I don't remember anything about you yet.", directive.text)
    }

    @Test
    fun recallListsRememberedFacts() {
        val store = store()
        store.add("I like tea", 1)
        store.add("my dog is Rex", 2)
        val directive = MemoryFlow.handle(store, "what do you remember", 3) as MemoryDirective.Reply
        assertTrue(directive.text.contains("I like tea"))
        assertTrue(directive.text.contains("my dog is Rex"))
    }

    @Test
    fun normalQuestionBecomesAskWithNoFacts() {
        val store = store()
        val directive = MemoryFlow.handle(store, "what is two plus two", 1)
        assertTrue(directive is MemoryDirective.Ask)
        directive as MemoryDirective.Ask
        assertEquals("what is two plus two", directive.question)
        assertTrue(directive.facts.isEmpty())
    }

    @Test
    fun normalQuestionRetrievesRelevantFacts() {
        val store = store()
        store.add("my dog is named Rex", 1)
        store.add("I like tea", 2)
        val directive = MemoryFlow.handle(store, "what is my dog's name", 3) as MemoryDirective.Ask
        assertEquals(listOf("my dog is named Rex"), directive.facts.map { it.text })
    }

    @Test
    fun recallTextCapsLongLists() {
        val memories = (1..12).map { Memory("$it", "fact $it", it.toLong()) }
        val text = MemoryFlow.recallText(memories, maxListed = 5)
        assertTrue(text.contains("fact 1"))
        assertTrue(text.contains("and 7 more"))
    }
}
