package com.elthon.broexe.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the "it heard me but ignored me" bug used to live: the capture loop used to
 * finalize on the first pause, splitting one sentence into fragments. These tests pin
 * the whole-sentence behaviour without touching audio or a device.
 */
class UtteranceAssemblerTest {

    private fun assembler(
        minSpeechMs: Long = 250L,
        maxUtteranceMs: Long = 10_000L,
        continuationGraceMs: Long = 800L,
        extendedGraceMs: Long = 1_800L,
        semanticGraceMs: Long = 450L,
    ) = UtteranceAssembler(minSpeechMs, maxUtteranceMs, continuationGraceMs, extendedGraceMs, semanticGraceMs)

    @Test
    fun notCapturingPauseIsDiscard() {
        assertEquals(HoldDecision.DISCARD, assembler().onPause(1_000L))
    }

    @Test
    fun shortPauseIsHeldThenFinalizedAfterGrace() {
        val a = assembler()
        a.begin(1_000L)
        a.onVoice(1_300L) // 300 ms of speech

        // 200 ms of silence: still within the tolerance, keep the turn open.
        assertEquals(HoldDecision.HOLD, a.onPause(1_500L))
        // 900 ms of silence: past the tolerance, and there was real speech.
        assertEquals(HoldDecision.FINALIZE, a.onPause(2_200L))
    }

    @Test
    fun blipShorterThanMinSpeechIsDiscarded() {
        val a = assembler()
        a.begin(1_000L)
        a.onVoice(1_100L) // 100 ms — a cough, not a word
        assertEquals(HoldDecision.DISCARD, a.onPause(2_000L))
    }

    @Test
    fun resumedSpeechIsMergedIntoTheSameTurn() {
        val a = assembler()
        a.begin(1_000L)
        a.onVoice(1_300L)
        assertEquals(HoldDecision.HOLD, a.onPause(1_600L))

        // User resumes before the tolerance elapses.
        a.onVoice(1_700L)
        assertEquals(700L, a.speechMs)
        assertEquals(HoldDecision.HOLD, a.onPause(2_000L))
        assertEquals(HoldDecision.FINALIZE, a.onPause(2_600L))
    }

    @Test
    fun extendGraceWidensThePauseTolerance() {
        val a = assembler(continuationGraceMs = 800L, extendedGraceMs = 1_800L)
        a.begin(0L)
        a.onVoice(300L)
        a.extendGrace()
        assertTrue(a.graceExtended)

        // 1.6 s pause would finalize normally, but the extended grace still holds.
        assertEquals(HoldDecision.HOLD, a.onPause(1_900L))
        assertEquals(HoldDecision.FINALIZE, a.onPause(2_200L))
    }

    @Test
    fun maxDurationForcesTheTurnToEnd() {
        val a = assembler(maxUtteranceMs = 10_000L)
        a.begin(0L)
        a.onVoice(100L)
        assertFalse(a.exceededMax(9_000L))
        assertTrue(a.exceededMax(10_000L))
        assertTrue(a.exceededMax(12_000L))
    }

    @Test
    fun completeSentenceFinalizesAtTheShortSemanticGrace() {
        val a = assembler(continuationGraceMs = 800L, semanticGraceMs = 450L)
        a.begin(0L)
        a.onVoice(300L)
        // 300 ms of silence: too short even for the semantic grace.
        assertEquals(HoldDecision.HOLD, a.onPause(600L, complete = true))
        // 500 ms of silence with a finished-looking partial: finalize without the
        // full 800 ms continuation tolerance.
        assertEquals(HoldDecision.FINALIZE, a.onPause(800L, complete = true))
    }

    @Test
    fun incompletePartialKeepsTheLongerGrace() {
        val a = assembler(continuationGraceMs = 800L, semanticGraceMs = 450L)
        a.begin(0L)
        a.onVoice(300L)
        // Same 400 ms pause, but the user trailed off: keep waiting.
        assertEquals(HoldDecision.HOLD, a.onPause(700L, complete = false))
        assertEquals(HoldDecision.FINALIZE, a.onPause(1_100L, complete = false))
    }

    @Test
    fun resetReturnsToIdle() {
        val a = assembler()
        a.begin(0L)
        a.onVoice(100L)
        a.extendGrace()
        a.reset()
        assertFalse(a.isCapturing)
        assertEquals(0L, a.speechMs)
        assertFalse(a.graceExtended)
        assertEquals(HoldDecision.DISCARD, a.onPause(5_000L))
    }
}
