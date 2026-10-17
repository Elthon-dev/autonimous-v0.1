package com.elthon.broexe.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchRankerTest {

    private fun result(title: String, url: String, snippet: String = "", domain: String = "") =
        SearchResult(title, url, snippet, domain.ifEmpty { DuckDuckGoParser.hostOf(url) })

    @Test
    fun dropsDuplicatePagesRegardlessOfSchemeWwwQueryOrTrailingSlash() {
        val hits = listOf(
            result("Paris", "https://www.example.com/paris?utm_source=a"),
            result("Paris again", "http://example.com/paris/"),
            result("Paris third", "https://EXAMPLE.com/paris#section"),
            result("Other", "https://other.test/x"),
        )
        val out = SearchRanker.rank(hits, "paris", limit = 5)
        assertEquals(2, out.size)
        assertEquals("Paris", out[0].title)
        assertEquals("Other", out[1].title)
    }

    @Test
    fun dropsSameDomainAndTitleEvenWhenUrlDiffers() {
        val hits = listOf(
            result("Cebu - Wikipedia", "https://en.wikipedia.org/wiki/Cebu"),
            result("Cebu - Wikipedia", "https://en.wikipedia.org/wiki/Cebu_City"),
        )
        assertEquals(1, SearchRanker.rank(hits, "cebu", limit = 5).size)
    }

    @Test
    fun prefersHitsWhoseTitleAndDomainContainTheQueryTerms() {
        val hits = listOf(
            result("Random page", "https://news.example/x", "unrelated text about sports"),
            result("Eiffel Tower facts", "https://toureiffel.paris/eiffel-tower", "an iron tower"),
            result("Travel blog", "https://blog.example/eiffel", "I visited the Eiffel Tower last year"),
        )
        val out = SearchRanker.rank(hits, "eiffel tower", limit = 5)
        assertEquals("Eiffel Tower facts", out[0].title)
        assertEquals(3, out.size)
    }

    @Test
    fun keepsOriginalOrderForEqualScores() {
        val hits = listOf(
            result("A", "https://a.test/1", "no match"),
            result("B", "https://b.test/1", "no match"),
            result("C", "https://c.test/1", "no match"),
        )
        assertEquals(listOf("A", "B", "C"), SearchRanker.rank(hits, "nothing", limit = 5).map { it.title })
    }

    @Test
    fun appliesLimitAfterRankingNotBeforeIt() {
        val hits = listOf(
            result("noise", "https://n.test/1", "irrelevant"),
            result("Cebu guide", "https://cebu.test/1", "Cebu travel guide"),
            result("noise2", "https://n2.test/1", "irrelevant"),
        )
        val out = SearchRanker.rank(hits, "cebu", limit = 1)
        assertEquals(1, out.size)
        assertEquals("Cebu guide", out[0].title)
    }

    @Test
    fun emptyQueryKeepsOrderAndJustDedups() {
        val hits = listOf(
            result("A", "https://a.test/1"),
            result("A", "https://www.a.test/1/"),
            result("B", "https://b.test/1"),
        )
        assertEquals(listOf("A", "B"), SearchRanker.rank(hits, "  ", limit = 5).map { it.title })
    }

    @Test
    fun dedupKeyIgnoresSchemeQueryAndFragment() {
        assertEquals("example.com/page", SearchRanker.dedupKey("https://WWW.Example.com/page/?utm=1#x"))
        assertEquals(SearchRanker.dedupKey("https://example.com/page"), SearchRanker.dedupKey("http://example.com/page/"))
    }

    @Test
    fun blankOrUnparseableUrlsAreDroppedNotCrashed() {
        val hits = listOf(
            result("empty url", ""),
            result("good", "https://good.test/1"),
        )
        val out = SearchRanker.rank(hits, "good", limit = 5)
        assertTrue(out.any { it.title == "good" })
        assertTrue(out.none { it.title == "empty url" })
    }
}
