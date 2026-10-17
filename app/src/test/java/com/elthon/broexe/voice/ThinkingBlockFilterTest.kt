package com.elthon.broexe.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for the streaming reasoning splitter. The markers are assembled
 * from parts on purpose: a literal think tag in source has a history of being
 * mangled in transit, and we do not want the test to silently stop matching.
 */
class ThinkingBlockFilterTest {

    private val openTag = "<" + "think" + ">"
    private val closeTag = "<" + "/" + "think" + ">"

    private fun feedAll(vararg chunks: String): String {
        val filter = ThinkingBlockFilter()
        val out = StringBuilder()
        for (c in chunks) out.append(filter.feed(c))
        out.append(filter.finish())
        return out.toString()
    }

    @Test
    fun plainAnswerPassesThroughUntouched() {
        assertEquals("Hello world", feedAll("Hello world"))
    }

    @Test
    fun leadingThinkBlockIsRemovedFromSpeech() {
        val filter = ThinkingBlockFilter()
        val out = StringBuilder()
        out.append(filter.feed(openTag + "secret chain" + closeTag + "Hi there"))
        out.append(filter.finish())

        assertEquals("Hi there", out.toString())
        assertEquals("secret chain", filter.reasoningText)
        assertTrue(filter.sawThinking)
    }

    @Test
    fun textBeforeAThinkBlockIsKept() {
        assertEquals("Pre. Answer", feedAll("Pre. " + openTag + "hmm" + closeTag + "Answer"))
    }

    @Test
    fun markerSplitAcrossChunksNeverLeaks() {
        val filter = ThinkingBlockFilter()
        var visible = ""
        visible += filter.feed("A ")
        visible += filter.feed(openTag.substring(0, 4))
        visible += filter.feed(openTag.substring(4) + "hidden" + closeTag.substring(0, 3))
        visible += filter.feed(closeTag.substring(3) + "B")
        visible += filter.finish()

        assertEquals("A B", visible)
        assertFalse("no reasoning text may reach the speaker", visible.contains("hidden"))
        assertEquals("hidden", filter.reasoningText)
    }

    @Test
    fun strayCloseMarkerDropsThePrecedingReasoning() {
        assertEquals("Answer", feedAll("was thinking out loud" + closeTag + "Answer"))
    }

    @Test
    fun truncatedThinkBlockIsDroppedEntirely() {
        val filter = ThinkingBlockFilter()
        val out = StringBuilder()
        out.append(filter.feed("Sure. " + openTag + "half a thought"))
        out.append(filter.finish())

        assertEquals("Sure. ", out.toString())
        assertTrue(filter.sawThinking)
    }

    @Test
    fun aLoneLessThanIsSpokenVerbatim() {
        assertEquals("a < b", feedAll("a < b"))
    }

    @Test
    fun aTrailingLessThanIsHeldThenReleased() {
        val filter = ThinkingBlockFilter()
        val first = filter.feed("a<")
        val second = filter.feed("b")
        assertEquals("a", first)
        assertEquals("<b", second)
    }

    @Test
    fun startsInThinkingKeepsOnlyTextAfterTheFirstClose() {
        val filter = ThinkingBlockFilter(startsInThinking = true)
        val out = StringBuilder()
        out.append(filter.feed("hidden prefill reasoning"))
        out.append(filter.feed(closeTag + "the answer"))
        out.append(filter.finish())

        assertEquals("the answer", out.toString())
        assertEquals("hidden prefill reasoning", filter.reasoningText)
        assertTrue(filter.sawThinking)
    }

    @Test
    fun finishReleasesATrailingPartialMarker() {
        val filter = ThinkingBlockFilter()
        assertEquals("done", filter.feed("done<"))
        assertEquals("<", filter.finish())
    }
}
