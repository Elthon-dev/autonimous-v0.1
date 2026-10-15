package com.elthon.broexe.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MemoryStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store(): FileMemoryStore =
        FileMemoryStore(File(tmp.root, "memory.txt")) { "id" }

    @Test
    fun addThenAll() {
        val store = store()
        store.add("I like tea", 100)
        val all = store.all()
        assertEquals(1, all.size)
        assertEquals("I like tea", all[0].text)
        assertEquals(100L, all[0].createdAtMs)
    }

    @Test
    fun persistsAcrossInstances() {
        val file = File(tmp.root, "memory.txt")
        FileMemoryStore(file) { "a" }.add("my dog is Rex", 1)

        val reopened = FileMemoryStore(file) { "b" }
        assertEquals(listOf("my dog is Rex"), reopened.all().map { it.text })
    }

    @Test
    fun trimsAndRejectsEmpty() {
        val store = store()
        assertEquals("hello", store.add("  hello  ", 1).text)
        try {
            store.add("   ", 2)
            fail("expected IllegalArgumentException for blank fact")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun forgetRemovesOnlyMatching() {
        val store = store()
        store.add("I like tea", 1)
        store.add("my dog is Rex", 2)

        assertEquals(1, store.forget("tea"))
        assertEquals(listOf("my dog is Rex"), store.all().map { it.text })
    }

    @Test
    fun forgetIsCaseInsensitive() {
        val store = store()
        store.add("My Dog is Rex", 1)
        assertEquals(1, store.forget("dog"))
    }

    @Test
    fun forgetMissingReturnsZero() {
        val store = store()
        store.add("I like tea", 1)
        assertEquals(0, store.forget("quantum physics"))
    }

    @Test
    fun clearRemovesEverything() {
        val store = store()
        store.add("a", 1)
        store.add("b", 2)
        store.clear()
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun survivesTrickyCharactersAndReload() {
        val file = File(tmp.root, "m.txt")
        val tricky = "he said \"hi\" | weird\nsecond line"
        FileMemoryStore(file) { "x" }.add(tricky, 5)

        val reopened = FileMemoryStore(file)
        assertEquals(tricky, reopened.all()[0].text)
    }

    @Test
    fun inMemoryStoreBehavesLikeFileStore() {
        val store = InMemoryMemoryStore { "id" }
        store.add("a fact", 7)
        assertEquals(1, store.all().size)
        assertEquals(1, store.forget("fact"))
        assertTrue(store.all().isEmpty())
    }
}
