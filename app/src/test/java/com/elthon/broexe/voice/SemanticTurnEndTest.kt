package com.elthon.broexe.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticTurnEndTest {

    @Test
    fun aFullRequestLooksComplete() {
        assertTrue(SemanticTurnEnd.looksComplete("what is the capital of France"))
        assertTrue(SemanticTurnEnd.looksComplete("set a timer for five minutes"))
        assertTrue(SemanticTurnEnd.looksComplete("tell me a joke"))
    }

    @Test
    fun trailingFunctionWordsLeaveItHanging() {
        assertFalse(SemanticTurnEnd.looksComplete("what is the"))
        assertFalse(SemanticTurnEnd.looksComplete("remind me to"))
        assertFalse(SemanticTurnEnd.looksComplete("search for restaurants in"))
        assertFalse(SemanticTurnEnd.looksComplete("is it going to"))
        assertFalse(SemanticTurnEnd.looksComplete("tell me about"))
    }

    @Test
    fun tooShortToJudge() {
        assertFalse(SemanticTurnEnd.looksComplete(""))
        assertFalse(SemanticTurnEnd.looksComplete("   "))
        assertFalse(SemanticTurnEnd.looksComplete("bro"))
    }

    @Test
    fun trailingPunctuationAndCaseAreIgnored() {
        assertTrue(SemanticTurnEnd.looksComplete("WHAT IS THE CAPITAL OF FRANCE?"))
        assertTrue(SemanticTurnEnd.looksComplete("what's the weather like..."))
    }

    @Test
    fun fillerEndingsAreNotComplete() {
        assertFalse(SemanticTurnEnd.looksComplete("so I was going to say um"))
    }
}
