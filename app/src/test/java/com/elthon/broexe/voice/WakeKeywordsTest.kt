package com.elthon.broexe.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeKeywordsTest {

    @Test
    fun parsesTokensScoreThresholdAndPhrase() {
        val kws = WakeKeywords.parse("▁HE Y ▁B RO :2.0 #0.25 @HEYBRO")
        assertEquals(1, kws.size)
        val k = kws[0]
        assertEquals("▁HE Y ▁B RO", k.tokens)
        assertEquals(2.0f, k.score, 1e-6f)
        assertEquals(0.25f, k.threshold, 1e-6f)
        assertEquals("HEYBRO", k.phrase)
        assertEquals("Hey Bro", k.label)
    }

    @Test
    fun parsesMultipleLinesAndIgnoresCommentsAndBlanks() {
        val text = """
            # a comment

            ▁HE Y ▁B RO :2.0 #0.25 @HEYBRO
            ▁B RO :2.0 #0.50 @BRO
        """.trimIndent()
        val kws = WakeKeywords.parse(text)
        assertEquals(2, kws.size)
        assertEquals("Bro", kws[1].label)
    }

    @Test
    fun defaultsScoreAndThresholdWhenAbsent() {
        val k = WakeKeywords.parse("▁B RO").single()
        assertEquals(2.0f, k.score, 1e-6f)
        assertEquals(0.25f, k.threshold, 1e-6f)
        assertEquals("BRO", k.phrase)
    }

    @Test
    fun renderJoinsWithSlashAndKeepsMetadata() {
        val rendered = WakeKeywords.render(
            listOf(WakeKeyword("▁B RO", "BRO", 2.0f, 0.5f)),
        )
        assertEquals("▁B RO :2.0 #0.5 @BRO", rendered)
    }

    @Test
    fun renderStripsSpacesFromPhraseMarker() {
        val rendered = WakeKeywords.render(
            listOf(WakeKeyword("▁HE Y", "HEY BRO", 2f, 0.3f)),
        )
        assertTrue(rendered.endsWith("@HEYBRO"))
    }

    @Test
    fun fallbackIsSingleKnownTrigger() {
        val f = WakeKeywords.fallback()
        assertEquals(1, f.size)
        assertEquals("Hey Bro", f[0].label)
    }
}
