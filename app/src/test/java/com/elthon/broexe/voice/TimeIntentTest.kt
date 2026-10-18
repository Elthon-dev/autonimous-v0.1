package com.elthon.broexe.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimeIntentTest {

    @Test
    fun matchesPlainTimeAndDateQuestions() {
        assertTrue(TimeIntent.matches("what time is it"))
        assertTrue(TimeIntent.matches("what's the time"))
        assertTrue(TimeIntent.matches("what is today's date"))
        assertTrue(TimeIntent.matches("hey bro what time is it"))
    }

    @Test
    fun matchesAreCaseInsensitive() {
        assertTrue(TimeIntent.matches("WHAT TIME IS IT"))
    }

    @Test
    fun rejectsOtherTimeZones() {
        assertFalse(TimeIntent.matches("what time is it in Tokyo"))
        assertFalse(TimeIntent.matches("what is the date in Japan"))
    }

    @Test
    fun ignoresUnrelatedSpeech() {
        assertFalse(TimeIntent.matches("tell me a joke"))
        assertFalse(TimeIntent.matches("set a timer for five minutes"))
        assertFalse(TimeIntent.matches(""))
    }
}
