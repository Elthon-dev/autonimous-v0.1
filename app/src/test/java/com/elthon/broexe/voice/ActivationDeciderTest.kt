package com.elthon.broexe.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class ActivationDeciderTest {

    private fun signal(score: Float) = AddressingSignal(score, listOf("test"))

    private val idle = DetectionContext(inSession = false)
    private val session = DetectionContext(inSession = true)

    @Test
    fun highBandActivates() {
        val d = ActivationDecider()
        val r = d.decide(signal(0.80f), idle, "u1", 0L)
        assertEquals(ActivationDecision.ACTIVATE, r.decision)
        assertEquals(ConfidenceBand.HIGH, r.band)
    }

    @Test
    fun lowBandIsIgnored() {
        val d = ActivationDecider()
        val r = d.decide(signal(0.10f), idle, "u1", 0L)
        assertEquals(ActivationDecision.IGNORE, r.decision)
        assertEquals(ConfidenceBand.LOW, r.band)
    }

    @Test
    fun ambiguousBandWaitsByDefault() {
        val d = ActivationDecider()
        val r = d.decide(signal(0.40f), idle, "u1", 0L)
        assertEquals(ActivationDecision.WAIT, r.decision)
        assertEquals(ConfidenceBand.AMBIGUOUS, r.band)
    }

    @Test
    fun ambiguousBandClarifiesWhenEnabled() {
        val d = ActivationDecider(ambiguousClarify = true)
        val r = d.decide(signal(0.40f), idle, "u1", 0L)
        assertEquals(ActivationDecision.CLARIFY, r.decision)
    }

    @Test
    fun ambiguousBandActivatesInsideASession() {
        val d = ActivationDecider()
        val r = d.decide(signal(0.40f), session, "u1", 0L)
        assertEquals(ActivationDecision.ACTIVATE, r.decision)
    }

    @Test
    fun clarifyIsRateLimited() {
        val d = ActivationDecider(ambiguousClarify = true, clarifyCooldownMs = 20_000L)
        assertEquals(ActivationDecision.CLARIFY, d.decide(signal(0.40f), idle, "u1", 0L).decision)
        assertEquals(ActivationDecision.WAIT, d.decide(signal(0.40f), idle, "u2", 1_000L).decision)
        assertEquals(ActivationDecision.CLARIFY, d.decide(signal(0.40f), idle, "u3", 25_000L).decision)
    }

    @Test
    fun duplicateUtteranceIsNeverActivatedTwice() {
        val d = ActivationDecider()
        assertEquals(ActivationDecision.ACTIVATE, d.decide(signal(0.8f), idle, "u1", 0L).decision)
        assertEquals(ActivationDecision.WAIT, d.decide(signal(0.8f), idle, "u1", 10_000L).decision)
    }

    @Test
    fun refractorySuppressesRapidRepeatActivations() {
        val d = ActivationDecider(refractoryMs = 1_200L)
        assertEquals(ActivationDecision.ACTIVATE, d.decide(signal(0.8f), idle, "u1", 0L).decision)
        assertEquals(ActivationDecision.WAIT, d.decide(signal(0.8f), idle, "u2", 500L).decision)
        assertEquals(ActivationDecision.ACTIVATE, d.decide(signal(0.8f), idle, "u3", 2_000L).decision)
    }

    @Test
    fun bandBoundariesMatchThresholds() {
        val d = ActivationDecider(highThreshold = 0.50f, ambiguousThreshold = 0.35f)
        assertEquals(ConfidenceBand.HIGH, d.bandFor(0.50f))
        assertEquals(ConfidenceBand.AMBIGUOUS, d.bandFor(0.499f))
        assertEquals(ConfidenceBand.AMBIGUOUS, d.bandFor(0.35f))
        assertEquals(ConfidenceBand.LOW, d.bandFor(0.349f))
    }

    @Test(expected = IllegalArgumentException::class)
    fun ambiguousThresholdCannotExceedHighThreshold() {
        ActivationDecider(highThreshold = 0.30f, ambiguousThreshold = 0.50f)
    }
}
