package com.elthon.broexe.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class EhsTest {

    // --- analyzer: acoustic discrimination ----------------------------------

    @Test
    fun silenceIsNearSilentFloor() {
        val f = analyzer.analyze(FloatArray(FRAME) { 0f })
        assertTrue("rms=${f.rmsDbfs}", f.rmsDbfs < -100f)
        assertEquals(0, f.clarity)
    }

    @Test
    fun lowToneKeepsEnergyBelowHighBand() {
        val f = analyzer.analyze(sine(500f, amplitude = 0.15f))
        // A 500 Hz voice-like tone: energy sits well under the ~2.5 kHz high-pass, so
        // very little leaks into the high-frequency fraction.
        assertTrue("hfRatio=${f.hfRatio}", f.hfRatio < 0.25f)
        assertTrue("zcr=${f.zcr}", f.zcr in 0.0f..0.15f)
        assertTrue("rms=${f.rmsDbfs}", f.rmsDbfs > -30f)
    }

    @Test
    fun noiseCrossesFarMoreAndLeaksHighFrequency() {
        val f = analyzer.analyze(pseudoNoise())
        // White noise: high crossing rate and a lot of energy above 2.5 kHz.
        assertTrue("zcr=${f.zcr}", f.zcr > 0.3f)
        assertTrue("hfRatio=${f.hfRatio}", f.hfRatio > EhsConfig().noiseHfRatio)
        assertTrue("clarity=${f.clarity}", f.clarity < 40)
    }

    // --- gate: policy over features -----------------------------------------

    @Test
    fun belowSilenceIsQuiet() {
        assertEquals(EhsDecision.QUIET, gate.decide(features(rms = -60f, peak = -50f), true, true))
    }

    @Test
    fun belowBandIsQuiet() {
        assertEquals(EhsDecision.QUIET, gate.decide(features(rms = -40f, peak = -20f), true, true))
    }

    @Test
    fun tooFaintPeakIsQuiet() {
        assertEquals(EhsDecision.QUIET, gate.decide(features(rms = -16f, peak = -20f), true, true))
    }

    @Test
    fun hissyFrameIsNoise() {
        assertEquals(
            EhsDecision.NOISE,
            gate.decide(features(rms = -18f, peak = -8f, hfRatio = 0.75f, clarity = 55), true, true),
        )
    }

    @Test
    fun lowClarityIsNoise() {
        assertEquals(
            EhsDecision.NOISE,
            gate.decide(features(rms = -18f, peak = -8f, hfRatio = 0.2f, clarity = 20), true, true),
        )
    }

    @Test
    fun sileroRejectIsNoise() {
        assertEquals(
            EhsDecision.NOISE,
            gate.decide(features(rms = -18f, peak = -8f, clarity = 60), sileroSpeech = false, coreWord = true),
        )
    }

    @Test
    fun clearSpeechWithoutCoreWordIsSpeech() {
        assertEquals(
            EhsDecision.SPEECH,
            gate.decide(features(rms = -18f, peak = -8f, clarity = 60), sileroSpeech = true, coreWord = false),
        )
    }

    @Test
    fun clearSpeechWithCoreWordIsTarget() {
        assertEquals(
            EhsDecision.TARGET,
            gate.decide(features(rms = -18f, peak = -8f, clarity = 60), sileroSpeech = true, coreWord = true),
        )
    }

    @Test
    fun coreWordNotRequiredStillTargetsOnSpeech() {
        val permissive = EhsGate(EhsConfig(coreWordRequired = false))
        assertEquals(
            EhsDecision.TARGET,
            permissive.decide(features(rms = -18f, peak = -8f, clarity = 60), true, coreWord = false),
        )
    }

    // --- helpers ------------------------------------------------------------

    private val analyzer = EhsAnalyzer()
    private val gate = EhsGate()

    private fun features(
        rms: Float,
        peak: Float,
        zcr: Float = 0.05f,
        hfRatio: Float = 0.15f,
        clarity: Int = 60,
    ) = EhsFeatures(rms, peak, zcr, hfRatio, clarity)

    private fun sine(freq: Float, amplitude: Float): FloatArray = FloatArray(FRAME) { i ->
        (amplitude * sin(2.0 * PI * freq * i / SAMPLE_RATE)).toFloat()
    }

    /** Deterministic xorshift white noise (~0.15 RMS) so the test never flakes on randomness. */
    private fun pseudoNoise(): FloatArray {
        var s = 0x9E3779B9
        return FloatArray(FRAME) {
            s = s xor (s shl 13)
            s = s xor (s ushr 17)
            s = s xor (s shl 5)
            (0.15 * s / 2147483648.0).toFloat()
        }
    }

    private companion object {
        /** One 100 ms frame at 16 kHz. */
        const val FRAME = 1600
    }
}
