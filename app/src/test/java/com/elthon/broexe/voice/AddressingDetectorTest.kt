package com.elthon.broexe.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behavioural tests for the open-vocabulary addressing classifier.
 *
 * These are the "held-out" examples: none of the positive cases are in the algorithm
 * as literal phrases — they exercise the linguistic rules. Band boundaries are checked
 * against [ActivationDecider]'s defaults so the tests describe real activation.
 */
class AddressingDetectorTest {

    private val decider = ActivationDecider()

    private fun score(text: String, ctx: DetectionContext = DetectionContext()): Float =
        AddressingDetector.evaluate(text, ctx).score

    private fun band(text: String, ctx: DetectionContext = DetectionContext()): ConfidenceBand =
        decider.bandFor(score(text, ctx))

    // --- explicit address should activate --------------------------------------

    @Test
    fun standaloneBroIsHighConfidence() {
        assertEquals(ConfidenceBand.HIGH, band("Bro"))
        assertEquals(ConfidenceBand.HIGH, band("bruh"))
        assertEquals(ConfidenceBand.HIGH, band("Brah"))
    }

    @Test
    fun standaloneBroReportsItsReason() {
        val signal = AddressingDetector.evaluate("bro", DetectionContext())
        assertTrue(signal.reasonString().contains("standalone"))
    }

    @Test
    fun openerPlusVocativeIsHighConfidence() {
        assertEquals(ConfidenceBand.HIGH, band("Yo bro"))
        assertEquals(ConfidenceBand.HIGH, band("Okay bro"))
        assertEquals(ConfidenceBand.HIGH, band("Hey bro"))
    }

    @Test
    fun leadingVocativeWithImperativeIsHighConfidence() {
        assertEquals(ConfidenceBand.HIGH, band("Bro, listen"))
        assertEquals(ConfidenceBand.HIGH, band("Bro turn on the lights"))
    }

    @Test
    fun addressFramesAreHighConfidence() {
        assertEquals(ConfidenceBand.HIGH, band("Can you help me?"))
        assertEquals(ConfidenceBand.HIGH, band("Could you tell me the time"))
        assertEquals(ConfidenceBand.HIGH, band("Are you there?"))
        assertEquals(ConfidenceBand.HIGH, band("Remind me to call mom"))
    }

    @Test
    fun metaRequestsAreHighConfidence() {
        assertEquals(ConfidenceBand.HIGH, band("I have a question"))
        assertEquals(ConfidenceBand.HIGH, band("Can I ask you something"))
        assertEquals(ConfidenceBand.HIGH, band("Quick question"))
    }

    @Test
    fun vocativePlusQuestionIsHighConfidence() {
        assertEquals(ConfidenceBand.HIGH, band("Hey bro, what is two plus two"))
    }

    // --- third-person must not activate ----------------------------------------

    @Test
    fun possessiveBroIsRejected() {
        assertEquals(ConfidenceBand.LOW, band("my bro said he's coming"))
    }

    @Test
    fun broAsSubjectIsRejected() {
        assertEquals(ConfidenceBand.LOW, band("bro is cool"))
        assertEquals(ConfidenceBand.LOW, band("bro said hello yesterday"))
    }

    @Test
    fun thirdPersonReportsItsReason() {
        val signal = AddressingDetector.evaluate("my bro told me", DetectionContext())
        assertTrue(signal.reasonString().contains("third-person"))
    }

    @Test
    fun unrelatedSpeechIsRejected() {
        assertEquals(ConfidenceBand.LOW, band("there's a question about the report"))
        assertEquals(ConfidenceBand.LOW, band("the weather looks nice today"))
    }

    // --- "bro" anywhere in the utterance ---------------------------------------

    @Test
    fun medialVocativeCounts() {
        val signal = AddressingDetector.evaluate("tell me bro what's the time", DetectionContext())
        assertTrue("medial 'bro' is credited", signal.reasonString().contains("medial"))
        assertTrue("medial vocative plus intent activates", signal.score >= 0.5f)
    }

    @Test
    fun medialVocativeStillDoesNotRescueThirdPerson() {
        val signal = AddressingDetector.evaluate("I told my bro the truth", DetectionContext())
        assertTrue(signal.reasonString().contains("third-person"))
        assertEquals(ConfidenceBand.LOW, decider.bandFor(signal.score))
    }

    // --- ambiguous middle band --------------------------------------------------

    @Test
    fun bareQuestionIsAmbiguousNotHigh() {
        assertEquals(ConfidenceBand.AMBIGUOUS, band("what's the weather?"))
    }

    @Test
    fun bareImperativeIsAmbiguousNotHigh() {
        assertEquals(ConfidenceBand.AMBIGUOUS, band("set a timer"))
    }

    // --- context hysteresis -----------------------------------------------------

    @Test
    fun inSessionLiftsABareQuestionToHigh() {
        val idle = band("what is two plus two")
        assertEquals(ConfidenceBand.LOW, idle)
        val inSession = band("what is two plus two", DetectionContext(inSession = true))
        assertEquals(ConfidenceBand.HIGH, inSession)
    }

    @Test
    fun recentReplyCountsAsContext() {
        val ctx = DetectionContext(msSinceAssistantSpoke = 2000L, followUpWindowMs = 8000L)
        assertEquals(ConfidenceBand.HIGH, band("what is two plus two", ctx))
    }

    // --- configurability --------------------------------------------------------

    @Test
    fun standaloneBroCanBeDisabled() {
        val ctx = DetectionContext(standaloneBro = false)
        assertEquals(ConfidenceBand.LOW, band("Bro", ctx))
        // A vocative with other content still counts.
        assertEquals(ConfidenceBand.HIGH, band("Bro, listen", ctx))
    }

    @Test
    fun veryShortUtteranceIsDiscounted() {
        val full = score("Can you help me?")
        val short = score("Can you help me?", DetectionContext(speechMs = 50L))
        assertTrue("short utterance scores lower", short < full)
    }

    @Test
    fun keywordHintAddsEvidence() {
        val plain = score("what's the weather?")
        val hinted = score("what's the weather?", DetectionContext(keywordHit = "BRO"))
        assertTrue("hint raises the score", hinted > plain)
    }

    @Test
    fun emptyTranscriptScoresZero() {
        assertEquals(0f, score(""), 0.0001f)
        assertEquals(0f, score("   "), 0.0001f)
    }

    @Test
    fun scoresStayWithinUnitRange() {
        val texts = listOf(
            "bro", "hey bro can you please help me right now", "", "my bro and his bro said hi",
            "what is the meaning of life?", "OKAY BRO LISTEN TO ME PLEASE",
        )
        for (t in texts) {
            val s = score(t)
            assertTrue("score in range for \u201c$t\u201d", s in 0f..1f)
        }
    }
}
