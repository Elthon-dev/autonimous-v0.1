package com.elthon.broexe.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SearchTriggerTest {

    @Test
    fun detectsExplicitSearchPhrases() {
        assertEquals("the capital of France", SearchTrigger.detect("search for the capital of France"))
        assertEquals("weather in Manila", SearchTrigger.detect("Search the web for weather in Manila."))
        assertEquals("the Eiffel Tower", SearchTrigger.detect("look up the Eiffel Tower"))
        assertEquals("best pizza near me", SearchTrigger.detect("Google best pizza near me!"))
        assertEquals("Cebu", SearchTrigger.detect("find me information about Cebu"))
        assertEquals("current time in Japan", SearchTrigger.detect("search online for current time in Japan"))
    }

    @Test
    fun toleratesPoliteOpeners() {
        assertEquals("the weather", SearchTrigger.detect("can you search for the weather"))
        assertEquals("Cebu", SearchTrigger.detect("could you look up Cebu"))
        assertEquals("a good restaurant", SearchTrigger.detect("find me a good restaurant"))
        assertEquals("the Eiffel Tower", SearchTrigger.detect("find information about the Eiffel Tower"))
    }

    @Test
    fun leavesOrdinaryQuestionsLocal() {
        assertNull(SearchTrigger.detect("what is the capital of France"))
        assertNull(SearchTrigger.detect("tell me a joke"))
        assertNull(SearchTrigger.detect("hey bro"))
    }

    @Test
    fun bareFindIsNoLongerASearch() {
        assertNull(SearchTrigger.detect("find the bug in my code"))
        assertNull(SearchTrigger.detect("I need to find my keys"))
    }

    @Test
    fun rejectsFillerOnlyMatches() {
        assertNull(SearchTrigger.detect("search"))
        assertNull(SearchTrigger.detect("search for it"))
        assertNull(SearchTrigger.detect("look up that"))
    }
}
