package com.elthon.broexe.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceActivityDetectorTest {

    @Test
    fun silenceProducesNoEvents() {
        val vad = VoiceActivityDetector()
        assertEquals(VadEvent.NONE, vad.process(0.0f, 0L))
        assertFalse(vad.isSpeaking())
    }

    @Test
    fun loudFrameStartsSpeech() {
        val vad = VoiceActivityDetector()
        assertEquals(VadEvent.SPEECH_START, vad.process(0.5f, 0L))
        assertTrue(vad.isSpeaking())
    }

    @Test
    fun speechEndsAfterEnoughSilence() {
        val vad = VoiceActivityDetector(minSilenceMs = 600L)
        assertEquals(VadEvent.SPEECH_START, vad.process(0.5f, 0L))
        assertEquals(VadEvent.NONE, vad.process(0.0f, 200L))
        // Silence is measured from the last voiced frame (t=0), so 600 ms later ends it.
        assertEquals(VadEvent.SPEECH_END, vad.process(0.0f, 600L))
        assertFalse(vad.isSpeaking())
    }

    @Test
    fun briefSilenceDoesNotEndSpeech() {
        val vad = VoiceActivityDetector(minSilenceMs = 600L)
        vad.process(0.5f, 0L)
        vad.process(0.5f, 100L)
        assertEquals(VadEvent.NONE, vad.process(0.0f, 300L))
        assertTrue(vad.isSpeaking())
    }

    @Test
    fun speechDurationTracksVoicedSpan() {
        val vad = VoiceActivityDetector()
        vad.process(0.5f, 1_000L)
        vad.process(0.5f, 1_400L)
        assertEquals(400L, vad.speechDurationMs(1_800L))
    }

    @Test
    fun adaptiveFloorRaisesTheBar() {
        val vad = VoiceActivityDetector()
        // Quiet, but not silent: the floor should rise to just under this level.
        repeat(30) { vad.process(0.01f, it * 100L) }
        val threshold = vad.startThreshold
        assertTrue("floor adapted upward", threshold > VoiceActivityDetector.DEFAULT_START_RMS)
        // A frame just under the adapted threshold must not start speech.
        assertEquals(VadEvent.NONE, vad.process(threshold - 0.001f, 5_000L))
    }

    @Test
    fun floorRecoversQuicklyWhenTheRoomGoesQuietAgain() {
        val vad = VoiceActivityDetector()
        // First non-speech frame seeds the floor at the ambient level (just under the
        // start threshold, so it is not mistaken for speech).
        vad.process(0.019f, 0L)
        assertTrue("floor seeded near ambient", vad.floor > 0.015f)
        // The room goes silent: the asymmetric fall must drop the floor most of the way
        // within a handful of frames (the old symmetric EMA would still be ~0.014).
        repeat(10) { vad.process(0.0f, 100L + it * 100L) }
        assertTrue("floor fell back toward silence", vad.floor < 0.005f)
    }

    @Test
    fun nonAdaptiveKeepsFixedThreshold() {
        val vad = VoiceActivityDetector(adaptNoiseFloor = false)
        repeat(30) { vad.process(0.01f, it * 100L) }
        assertEquals(VoiceActivityDetector.DEFAULT_START_RMS, vad.startThreshold, 0.0001f)
        assertEquals(VadEvent.SPEECH_START, vad.process(0.05f, 5_000L))
    }

    @Test
    fun resetClearsState() {
        val vad = VoiceActivityDetector()
        vad.process(0.5f, 0L)
        assertTrue(vad.isSpeaking())
        vad.reset()
        assertFalse(vad.isSpeaking())
        assertEquals(0L, vad.speechDurationMs(1_000L))
    }
}
