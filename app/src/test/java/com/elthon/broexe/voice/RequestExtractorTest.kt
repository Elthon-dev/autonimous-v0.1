package com.elthon.broexe.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestExtractorTest {

    @Test
    fun stripsLeadingTriggerAndPunctuation() {
        assertEquals(
            "what is two plus two?",
            RequestExtractor.extract("Hey Bro, what is two plus two?"),
        )
    }

    @Test
    fun keepsWordsThatPrecedeTheTrigger() {
        assertEquals(
            "set a timer",
            RequestExtractor.extract("set a timer hey bro"),
        )
    }

    @Test
    fun stripsLeadingFiller() {
        assertEquals(
            "what's up",
            RequestExtractor.extract("um hey bro what's up"),
        )
    }

    @Test
    fun handlesMultipleTriggerStyles() {
        assertEquals("tell me a joke", RequestExtractor.extract("Yo bro, tell me a joke"))
        assertEquals("are you there", RequestExtractor.extract("Okay bro are you there"))
        assertEquals("are you there", RequestExtractor.extract("bro are you there"))
    }

    @Test
    fun bareTriggerYieldsEmpty() {
        assertEquals("", RequestExtractor.extract("Hey Bro"))
        assertEquals("", RequestExtractor.extract("Bro"))
    }

    @Test
    fun noTriggerPassesThrough() {
        assertEquals("what is two plus two", RequestExtractor.extract("what is two plus two"))
    }

    @Test
    fun triggerInsideAnotherWordDoesNotMatch() {
        assertFalse(RequestExtractor.containsTrigger("give me a brownie"))
        assertEquals("give me a brownie", RequestExtractor.extract("give me a brownie"))
    }

    @Test
    fun containsTriggerIsWordBoundaryAware() {
        assertTrue(RequestExtractor.containsTrigger("hey bro can you hear me"))
        assertFalse(RequestExtractor.containsTrigger("hello there"))
    }
}
