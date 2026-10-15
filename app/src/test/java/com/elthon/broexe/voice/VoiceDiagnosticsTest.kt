package com.elthon.broexe.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class VoiceDiagnosticsTest {

    @Before
    fun setUp() {
        VoiceDiagnostics.reset()
    }

    @Test
    fun framesUpdateCountersAndPeakHold() {
        VoiceDiagnostics.onFrame(0.10f, 0.20f)
        VoiceDiagnostics.onFrame(0.05f, 0.10f)
        val s = VoiceDiagnostics.snapshot.value
        assertEquals(2L, s.micFrames)
        assertEquals(0.05f, s.micLevel, 1e-6f)
        assertEquals(0.20f, s.micPeak, 1e-6f)
    }

    @Test
    fun hasSignalOnlyAboveSilenceThreshold() {
        VoiceDiagnostics.onFrame(0f, DiagnosticsSnapshot.SILENCE_PEAK)
        assertFalse(VoiceDiagnostics.snapshot.value.hasSignal)
        VoiceDiagnostics.onFrame(0f, DiagnosticsSnapshot.SILENCE_PEAK + 0.01f)
        assertTrue(VoiceDiagnostics.snapshot.value.hasSignal)
    }

    @Test
    fun beginCaptureClearsCountersButKeepsEvents() {
        VoiceDiagnostics.event("x", "y")
        VoiceDiagnostics.onFrame(0.5f, 0.5f)
        VoiceDiagnostics.beginCapture()
        val s = VoiceDiagnostics.snapshot.value
        assertEquals(0L, s.micFrames)
        assertEquals(0f, s.micPeak, 0f)
        assertTrue(VoiceDiagnostics.events.value.isNotEmpty())
    }

    @Test
    fun reportContainsKeyFields() {
        VoiceDiagnostics.onWake("HEY BRO")
        VoiceDiagnostics.onAsr("hello")
        val r = VoiceDiagnostics.report()
        assertTrue(r.contains("wakeHits=1"))
        assertTrue(r.contains("lastAsr=hello"))
        assertTrue(r.contains("confirmed"))
    }

    @Test
    fun candidatesAndRejectionsAreCounted() {
        VoiceDiagnostics.onCandidate(WakeCandidate("HEYBRO", 100L, 0.4f))
        VoiceDiagnostics.onReject(WakeCandidate("BRO", 200L, 0.01f), "low-energy")
        val s = VoiceDiagnostics.snapshot.value
        assertEquals(1, s.wakeCandidates)
        assertEquals(1, s.wakeRejected)
        assertEquals("low-energy", s.lastRejectReason)
    }

    @Test
    fun reportIncludesCrashWhenProvided() {
        val r = VoiceDiagnostics.report(crash = "type=crash-native")
        assertTrue(r.contains("-- last crash --"))
        assertTrue(r.contains("type=crash-native"))
    }

    @Test
    fun eventLogIsBounded() {
        repeat(200) { VoiceDiagnostics.event("t", "m$it") }
        assertTrue(VoiceDiagnostics.events.value.size <= 80)
    }
}
