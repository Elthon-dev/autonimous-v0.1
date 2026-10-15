package com.elthon.broexe.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DuckDuckGoParserTest {

    private val sample = """
        <div class="result results_links web-result">
          <h2 class="result__title">
            <a rel="nofollow" class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fwww.example.com%2Fpage&amp;rut=abc">Example <b>Page</b></a>
          </h2>
          <a class="result__snippet" href="//duckduckgo.com/l/?uddg=x">This is a <b>sample</b> snippet about Example &amp; friends.</a>
        </div>
        <div class="result results_links web-result">
          <h2 class="result__title">
            <a rel="nofollow" class="result__a" href="https://en.wikipedia.org/wiki/Paris">Paris - Wikipedia</a>
          </h2>
          <a class="result__snippet" href="https://en.wikipedia.org/wiki/Paris">Paris is the capital of France.</a>
        </div>
    """.trimIndent()

    @Test
    fun parsesTitlesUrlsSnippetsAndDomains() {
        val results = DuckDuckGoParser.parse(sample)
        assertEquals(2, results.size)
        assertEquals("Example Page", results[0].title)
        assertEquals("https://www.example.com/page", results[0].url)
        assertEquals("example.com", results[0].domain)
        assertEquals("This is a sample snippet about Example & friends.", results[0].snippet)
        assertEquals("Paris - Wikipedia", results[1].title)
        assertEquals("https://en.wikipedia.org/wiki/Paris", results[1].url)
        assertEquals("en.wikipedia.org", results[1].domain)
    }

    @Test
    fun decodesRelativeAndRedirectUrls() {
        assertEquals("https://duckduckgo.com/html/", DuckDuckGoParser.decodeUrl("/html/"))
        assertEquals("https://x.test/a", DuckDuckGoParser.decodeUrl("//duckduckgo.com/l/?uddg=https%3A%2F%2Fx.test%2Fa"))
        assertEquals("https://plain.test/", DuckDuckGoParser.decodeUrl("https://plain.test/"))
    }

    @Test
    fun emptyHtmlYieldsNoResults() {
        assertTrue(DuckDuckGoParser.parse("").isEmpty())
        assertTrue(DuckDuckGoParser.parse("<html><body>nope</body></html>").isEmpty())
    }
}
