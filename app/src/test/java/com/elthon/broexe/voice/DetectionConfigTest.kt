package com.elthon.broexe.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectionConfigTest {

    @Test
    fun defaultsAreSensible() {
        val d = DetectionConfig.DEFAULT
        assertTrue(d.standaloneBro)
        assertTrue(d.bargeIn)
        assertTrue(!d.ambiguousClarify)
        assertTrue(d.ehsEnabled)
        assertTrue(d.highThreshold >= d.ambiguousThreshold)
    }

    @Test
    fun sanitizeClampsOutOfRangeThresholds() {
        val d = DetectionConfig(highThreshold = 5f, ambiguousThreshold = 9f).sanitized()
        assertTrue(d.highThreshold <= 0.99f)
        assertTrue(d.ambiguousThreshold <= d.highThreshold)
    }

    @Test
    fun sanitizeClampsNegativeThresholds() {
        val d = DetectionConfig(highThreshold = -1f, ambiguousThreshold = -2f).sanitized()
        assertTrue(d.highThreshold >= 0.05f)
        assertTrue(d.ambiguousThreshold >= 0.05f)
    }

    @Test
    fun sanitizeClampsTimeouts() {
        val d = DetectionConfig(followUpMs = -5L, idleTimeoutMs = 999_999_999L).sanitized()
        assertTrue(d.followUpMs >= 0L)
        assertTrue(d.idleTimeoutMs <= 600_000L)
    }

    @Test
    fun sanitizeLeavesValidConfigUnchanged() {
        val d = DetectionConfig(highThreshold = 0.6f, ambiguousThreshold = 0.4f)
        assertEquals(d, d.sanitized())
    }
}
