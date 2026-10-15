package com.elthon.broexe.voice

import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceStateTest {

    @Test
    fun everyStateHasAHumanLabel() {
        for (state in VoiceState.entries) {
            assertTrue("state $state has a label", state.label().isNotBlank())
        }
    }

    @Test
    fun searchSystemPromptMentionsNotes() {
        assertTrue(LlmResponder.SEARCH_SYSTEM_PROMPT.contains("web notes", ignoreCase = true))
    }

    @Test
    fun rendersResultsAsNotes() {
        val notes = LlmResponder.renderResults(
            listOf(
                SearchResult("Paris", "https://en.wikipedia.org/wiki/Paris", "Capital of France.", "en.wikipedia.org"),
            )
        )
        assertTrue(notes.contains("en.wikipedia.org"))
        assertTrue(notes.contains("Capital of France."))
    }

    @Test
    fun emptyResultsRenderHonestly() {
        assertTrue(LlmResponder.renderResults(emptyList()).contains("no results", ignoreCase = true))
    }
}
