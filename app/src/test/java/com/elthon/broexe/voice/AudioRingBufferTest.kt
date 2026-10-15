package com.elthon.broexe.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioRingBufferTest {

    private fun frame(vararg v: Float): FloatArray = v

    @Test
    fun evictsOldestWhenFull() {
        val buf = AudioRingBuffer(3)
        buf.add(frame(1f)); buf.add(frame(2f)); buf.add(frame(3f))
        buf.add(frame(4f))
        val drained = buf.drain()
        assertEquals(3, drained.size)
        assertEquals(frame(2f).toList(), drained[0].toList())
        assertEquals(frame(3f).toList(), drained[1].toList())
        assertEquals(frame(4f).toList(), drained[2].toList())
    }

    @Test
    fun drainReturnsOldestFirstAndClears() {
        val buf = AudioRingBuffer(5)
        buf.add(frame(1f)); buf.add(frame(2f))
        val drained = buf.drain()
        assertEquals(2, drained.size)
        assertEquals(frame(1f).toList(), drained[0].toList())
        assertEquals(frame(2f).toList(), drained[1].toList())
        assertEquals(0, buf.size())
        assertTrue(buf.drain().isEmpty())
    }

    @Test
    fun ignoresEmptyFrames() {
        val buf = AudioRingBuffer(2)
        buf.add(FloatArray(0))
        assertEquals(0, buf.size())
        buf.add(floatArrayOf(1f))
        assertEquals(1, buf.size())
    }

    @Test
    fun storesDefensiveCopies() {
        val buf = AudioRingBuffer(2)
        val f = floatArrayOf(1f, 2f)
        buf.add(f)
        f[0] = 9f
        assertEquals(frame(1f, 2f).toList(), buf.drain()[0].toList())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonPositiveCapacity() {
        AudioRingBuffer(0)
    }

    @Test
    fun tracksFrameTimestamps() {
        val buf = AudioRingBuffer(4)
        buf.add(floatArrayOf(1f), 100L)
        buf.add(floatArrayOf(2f), 300L)
        val frames = buf.drainFrames()
        assertEquals(100L, frames[0].atMs)
        assertEquals(300L, frames[1].atMs)
    }

    @Test
    fun spanReflectsBufferedWindow() {
        val buf = AudioRingBuffer(4)
        buf.add(floatArrayOf(1f), 100L)
        buf.add(floatArrayOf(2f), 300L)
        assertEquals(200L, buf.spanMs())
    }

    @Test
    fun spanIsZeroForSingleFrame() {
        val buf = AudioRingBuffer(4)
        buf.add(floatArrayOf(1f), 100L)
        assertEquals(0L, buf.spanMs())
    }

    @Test
    fun peakOverBufferedFrames() {
        val buf = AudioRingBuffer(4)
        buf.add(floatArrayOf(0.1f, -0.4f))
        buf.add(floatArrayOf(0.2f, 0.3f))
        assertEquals(0.4f, buf.peak(), 0.0001f)
    }
}
