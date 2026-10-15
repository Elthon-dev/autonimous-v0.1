package com.elthon.broexe.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioFrameTest {

    @Test
    fun pcm16MapsFullScaleEndpoints() {
        val f = pcm16ToFloat(shortArrayOf(0, 16384, -32768, 32767), 4)
        assertEquals(0f, f[0], 0.0001f)
        assertEquals(0.5f, f[1], 0.0001f)
        assertEquals(-1f, f[2], 0.0001f)
        assertEquals(32767f / 32768f, f[3], 0.0001f)
    }

    @Test
    fun pcm16HonoursRequestedCount() {
        val f = pcm16ToFloat(shortArrayOf(1, 2, 3, 4), 2)
        assertEquals(2, f.size)
    }

    @Test
    fun levelOfEmptyAndSilenceIsZero() {
        assertEquals(0f, audioLevel(FloatArray(0)), 0f)
        assertEquals(0f, audioLevel(FloatArray(64)), 0f)
    }

    @Test
    fun levelOfFullScaleConstantIsOne() {
        assertEquals(1f, audioLevel(FloatArray(8) { 1f }), 0.0001f)
    }

    @Test
    fun peakDetectsLargestMagnitude() {
        assertEquals(0.75f, audioPeak(floatArrayOf(-0.5f, 0.25f, 0.75f)), 0.0001f)
        assertEquals(0f, audioPeak(FloatArray(0)), 0f)
    }
}
