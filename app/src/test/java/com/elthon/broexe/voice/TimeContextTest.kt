package com.elthon.broexe.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

class TimeContextTest {

    private val fixed = Instant.parse("2026-10-17T14:05:00Z").toEpochMilli()

    @Test
    fun formatsTheCurrentMomentDeterministically() {
        val line = TimeContext.nowLine(fixed, ZoneId.of("UTC"), Locale.US)
        assertEquals("The current date and time is Saturday, 17 October 2026 at 14:05.", line)
    }

    @Test
    fun respectsTheZone() {
        val line = TimeContext.nowLine(fixed, ZoneId.of("Asia/Manila"), Locale.US)
        // Manila is UTC+8, so 14:05Z is 22:05 the same day.
        assertEquals("The current date and time is Saturday, 17 October 2026 at 22:05.", line)
    }

    @Test
    fun systemPromptAppendsTheTimeToTheBase() {
        val base = "You are Bro, a friendly concise voice assistant."
        val prompt = TimeContext.systemPrompt(base, fixed)
        assertTrue(prompt.startsWith(base))
        assertTrue(prompt.contains("current date and time"))
    }
}
