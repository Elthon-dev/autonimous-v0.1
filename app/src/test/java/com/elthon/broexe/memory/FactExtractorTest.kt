package com.elthon.broexe.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FactExtractorTest {

    @Test
    fun extractsName() {
        assertEquals(listOf("My name is Alex"), FactExtractor.extract("my name is Alex"))
    }

    @Test
    fun extractsLocationAndOrigin() {
        assertTrue(FactExtractor.extract("I live in Cebu").contains("I live in Cebu"))
        assertTrue(FactExtractor.extract("I'm from the Philippines").contains("I'm from the Philippines"))
    }

    @Test
    fun normalisesPreferences() {
        assertEquals(listOf("I like tea"), FactExtractor.extract("I really love tea"))
        assertEquals(listOf("I don't like spam"), FactExtractor.extract("I don't like spam"))
        assertEquals(listOf("I dislike Mondays"), FactExtractor.extract("I hate Mondays"))
    }

    @Test
    fun extractsAllergiesAndBirthday() {
        assertTrue(FactExtractor.extract("I'm allergic to peanuts").contains("I'm allergic to peanuts"))
        assertTrue(FactExtractor.extract("my birthday is March 5").contains("My birthday is March 5"))
    }

    @Test
    fun ignoresFactsAboutTheListener() {
        assertEquals(emptyList<String>(), FactExtractor.extract("I like that idea"))
        assertEquals(emptyList<String>(), FactExtractor.extract("I like the way you talk"))
        assertEquals(emptyList<String>(), FactExtractor.extract("I like to think so"))
    }

    @Test
    fun ignoresShortOrEmptyInput() {
        assertEquals(emptyList<String>(), FactExtractor.extract(""))
        assertEquals(emptyList<String>(), FactExtractor.extract("hey"))
        assertEquals(emptyList<String>(), FactExtractor.extract("what time is it"))
    }

    @Test
    fun deDuplicatesRepeatedFacts() {
        assertEquals(listOf("I like tea"), FactExtractor.extract("I like tea and I like tea"))
    }
}
