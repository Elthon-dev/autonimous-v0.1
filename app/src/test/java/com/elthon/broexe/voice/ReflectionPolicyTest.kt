package com.elthon.broexe.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReflectionPolicyTest {

    @Test
    fun trivialTurnsAreAnsweredInOnePass() {
        assertFalse(ReflectionPolicy.shouldReflect("hello bro", hasSearch = false, hasFacts = false))
        assertFalse(ReflectionPolicy.shouldReflect("what time is it", false, false))
        assertFalse(ReflectionPolicy.shouldReflect("tell me a joke", false, false))
    }

    @Test
    fun groundedTurnsAlwaysGetTheReviewPass() {
        assertTrue(ReflectionPolicy.shouldReflect("anything", hasSearch = true, hasFacts = false))
        assertTrue(ReflectionPolicy.shouldReflect("anything", hasSearch = false, hasFacts = true))
    }

    @Test
    fun complexityCuesTriggerTheReviewPass() {
        assertTrue(ReflectionPolicy.shouldReflect("why is the sky blue", false, false))
        assertTrue(ReflectionPolicy.shouldReflect("compare cats and dogs", false, false))
        assertTrue(ReflectionPolicy.shouldReflect("explain how a battery works", false, false))
    }

    @Test
    fun longQuestionsTriggerTheReviewPass() {
        val long = "please help me understand what the main differences are between these two things"
        assertTrue(long.split(" ").size >= ReflectionPolicy.COMPLEX_WORDS)
        assertTrue(ReflectionPolicy.shouldReflect(long, false, false))
    }

    @Test
    fun reviewPromptCarriesQuestionAndDraftAndAsksForOnlyTheAnswer() {
        val p = ReflectionPolicy.reviewPrompt("what is 2 plus 2", "It is four.")
        assertTrue(p.contains("what is 2 plus 2"))
        assertTrue(p.contains("It is four."))
        assertTrue(p.contains("only the final answer"))
    }
}
