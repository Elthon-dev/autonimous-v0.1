package com.elthon.broexe.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationSessionTest {

    @Test
    fun startsIdle() {
        val s = ConversationSession()
        assertEquals(SessionPhase.IDLE, s.currentPhase())
        assertFalse(s.isActive())
    }

    @Test
    fun activationOpensSession() {
        val s = ConversationSession()
        s.activate(100L)
        assertEquals(SessionPhase.LISTENING, s.currentPhase())
        assertTrue(s.isActive())
        assertEquals(1, s.turns())
    }

    @Test
    fun turnLifecycleWalksThroughPhases() {
        val s = ConversationSession()
        s.activate(0L)
        s.assistantThinking(10L)
        assertEquals(SessionPhase.THINKING, s.currentPhase())
        s.assistantSpeaking(20L)
        assertEquals(SessionPhase.SPEAKING, s.currentPhase())
        s.assistantDone(30L)
        assertEquals(SessionPhase.LISTENING, s.currentPhase())
        assertEquals(2, s.turns())
    }

    @Test
    fun tickClosesIdleSessionAfterTimeout() {
        val s = ConversationSession(idleTimeoutMs = 30_000L)
        s.activate(0L)
        assertFalse(s.tick(29_999L))
        assertTrue(s.tick(30_000L))
        assertEquals(SessionPhase.IDLE, s.currentPhase())
        assertFalse(s.isActive())
    }

    @Test
    fun tickDoesNotCloseWhileSpeaking() {
        val s = ConversationSession(idleTimeoutMs = 1_000L)
        s.activate(0L)
        s.assistantSpeaking(0L)
        assertFalse(s.tick(100_000L))
        assertEquals(SessionPhase.SPEAKING, s.currentPhase())
    }

    @Test
    fun userSpeechRefreshesActivity() {
        val s = ConversationSession(idleTimeoutMs = 10_000L)
        s.activate(0L)
        s.userSpoke(9_000L)
        assertFalse(s.tick(15_000L))
        assertTrue(s.tick(19_000L))
    }

    @Test
    fun followUpWindowTracksLastReply() {
        val s = ConversationSession(followUpWindowMs = 8_000L)
        s.activate(0L)
        s.assistantSpeaking(1_000L)
        assertTrue(s.inFollowUpWindow(5_000L))
        assertFalse(s.inFollowUpWindow(10_000L))
    }

    @Test
    fun followUpWindowFalseBeforeAnyReply() {
        val s = ConversationSession()
        s.activate(0L)
        assertFalse(s.inFollowUpWindow(100L))
    }

    @Test
    fun msSinceAssistantSpokeIsMaxBeforeReply() {
        val s = ConversationSession()
        s.activate(0L)
        assertEquals(Long.MAX_VALUE, s.msSinceAssistantSpoke(500L))
        s.assistantSpeaking(1_000L)
        assertEquals(2_000L, s.msSinceAssistantSpoke(3_000L))
    }
}
