package com.elthon.broexe.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeVerifierTest {

    @Test
    fun acceptsStrongCandidate() {
        val v = WakeVerifier()
        val d = v.evaluate(WakeCandidate("HEYBRO", 1_000L, 0.30f))
        assertTrue(d.accepted)
        assertEquals("confirmed", d.reason)
    }

    @Test
    fun rejectsCandidateWithTooLittleEnergy() {
        val v = WakeVerifier()
        val d = v.evaluate(WakeCandidate("BRO", 1_000L, 0.0001f))
        assertFalse(d.accepted)
        assertTrue(d.gate.contains("low-energy"))
    }

    @Test
    fun rejectsSecondCandidateInsideRefractoryWindow() {
        val v = WakeVerifier(refractoryMs = 2_000L)
        assertTrue(v.evaluate(WakeCandidate("HEYBRO", 10_000L, 0.4f)).accepted)
        val d = v.evaluate(WakeCandidate("HEYBRO", 10_500L, 0.4f))
        assertFalse(d.accepted)
        assertTrue(d.gate.contains("refractory"))
    }

    @Test
    fun acceptsAgainAfterRefractoryWindow() {
        val v = WakeVerifier(refractoryMs = 1_000L)
        assertTrue(v.evaluate(WakeCandidate("HEYBRO", 10_000L, 0.4f)).accepted)
        assertTrue(v.evaluate(WakeCandidate("HEYBRO", 12_000L, 0.4f)).accepted)
    }

    @Test
    fun resetClearsRefractoryState() {
        val v = WakeVerifier(refractoryMs = 5_000L)
        assertTrue(v.evaluate(WakeCandidate("HEYBRO", 10_000L, 0.4f)).accepted)
        v.reset()
        assertTrue(v.evaluate(WakeCandidate("HEYBRO", 10_100L, 0.4f)).accepted)
    }
}
