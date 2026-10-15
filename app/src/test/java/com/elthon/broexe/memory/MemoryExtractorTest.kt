package com.elthon.broexe.memory

import org.junit.Assert.assertEquals
import org.junit.Test

class MemoryExtractorTest {

    @Test
    fun remembersAfterRememberThat() {
        assertEquals(
            MemoryCommand.Remember("I like tea"),
            MemoryExtractor.parse("remember that I like tea"),
        )
    }

    @Test
    fun remembersAfterPleaseRemember() {
        assertEquals(
            MemoryCommand.Remember("my dog is Rex"),
            MemoryExtractor.parse("please remember my dog is Rex"),
        )
    }

    @Test
    fun remembersAfterNoteThat() {
        assertEquals(
            MemoryCommand.Remember("the wifi password is hunter2"),
            MemoryExtractor.parse("note that the wifi password is hunter2"),
        )
    }

    @Test
    fun stripsTrailingPunctuationAndCapitalisation() {
        assertEquals(
            MemoryCommand.Remember("I take my coffee black"),
            MemoryExtractor.parse("Remember that I take my coffee black."),
        )
    }

    @Test
    fun bareRememberIsNotACommand() {
        assertEquals(MemoryCommand.None, MemoryExtractor.parse("remember"))
    }

    @Test
    fun forgetSpecificFact() {
        assertEquals(
            MemoryCommand.Forget("I like tea"),
            MemoryExtractor.parse("forget that I like tea"),
        )
    }

    @Test
    fun forgetAbout() {
        assertEquals(
            MemoryCommand.Forget("my dog"),
            MemoryExtractor.parse("forget about my dog"),
        )
    }

    @Test
    fun forgetEverything() {
        assertEquals(MemoryCommand.ForgetAll, MemoryExtractor.parse("forget everything"))
    }

    @Test
    fun clearYourMemory() {
        assertEquals(MemoryCommand.ForgetAll, MemoryExtractor.parse("clear your memory"))
    }

    @Test
    fun recallWhatDoYouRemember() {
        assertEquals(MemoryCommand.Recall, MemoryExtractor.parse("what do you remember"))
    }

    @Test
    fun recallDoYouRemember() {
        assertEquals(MemoryCommand.Recall, MemoryExtractor.parse("do you remember my name"))
    }

    @Test
    fun plainQuestionIsNotACommand() {
        assertEquals(MemoryCommand.None, MemoryExtractor.parse("what is two plus two"))
    }

    @Test
    fun blankInputIsNotACommand() {
        assertEquals(MemoryCommand.None, MemoryExtractor.parse("   "))
    }
}
