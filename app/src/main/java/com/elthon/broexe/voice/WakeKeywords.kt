package com.elthon.broexe.voice

/**
 * One wake trigger: the BPE tokens sherpa needs, plus the per-keyword boost score
 * and trigger threshold that live in the `keywords.txt` file.
 *
 * sherpa's keywords.txt grammar (see `EncodeBase` in sherpa-onnx `utils.cc`) is:
 *
 *     [tokens...] :<score> #<threshold> @<phrase>
 *
 *   - each space-separated word must be a literal token from `tokens.txt`
 *   - `:score`     boosts a keyword (higher = easier to fire)
 *   - `#threshold` is the firing probability (lower = easier to fire)
 *   - `@phrase`    is the string sherpa reports back; it must NOT contain spaces
 */
data class WakeKeyword(
    val tokens: String,
    val phrase: String,
    val score: Float,
    val threshold: Float,
) {
    /** Friendly, human-readable name for the UI. */
    val label: String get() = WakeKeywords.friendly(phrase)

    /** A single sherpa `keywords.txt` line. */
    fun line(): String = "$tokens :$score #$threshold @${phrase.filterNot { it == ' ' }}"
}

/**
 * Parsing/rendering for the wake-word keyword set. Pure and android-free so the
 * grammar (and every trigger decision) is unit-testable without a device.
 */
object WakeKeywords {

    /** The one token row we know is valid for the bundled model, kept as a last resort. */
    private val FALLBACK = WakeKeyword("▁HE Y ▁B RO", "HEYBRO", 2.0f, 0.25f)

    private val FRIENDLY = mapOf(
        "HEYBRO" to "Hey Bro",
        "BRO" to "Bro",
        "YOBRO" to "Yo Bro",
        "OKAYBRO" to "Okay Bro",
    )

    fun friendly(phrase: String): String =
        FRIENDLY[phrase.filterNot { it == ' ' }.uppercase()] ?: phrase

    /**
     * Parse a sherpa `keywords.txt`. Blank lines and `#` comments are ignored.
     * Lines where a literal token is missing (e.g. only metadata) are skipped.
     */
    fun parse(text: String): List<WakeKeyword> {
        val out = ArrayList<WakeKeyword>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            var tokens = StringBuilder()
            var score = Float.NaN
            var threshold = Float.NaN
            var phrase = ""
            for (word in line.split(Regex("\\s+"))) {
                if (word.isEmpty()) continue
                when (word[0]) {
                    ':' -> score = word.substring(1).toFloatOrNull() ?: score
                    '#' -> threshold = word.substring(1).toFloatOrNull() ?: threshold
                    '@' -> phrase = word.substring(1)
                    else -> {
                        if (tokens.isNotEmpty()) tokens.append(' ')
                        tokens.append(word)
                    }
                }
            }
            if (tokens.isEmpty()) continue
            out.add(
                WakeKeyword(
                    tokens = tokens.toString(),
                    phrase = phrase.ifBlank { derivePhrase(tokens.toString()) },
                    score = if (score.isNaN()) 2.0f else score,
                    threshold = if (threshold.isNaN()) 0.25f else threshold,
                ),
            )
        }
        return out
    }

    /** Render an active keyword string for `KeywordSpotter.createStream(keywords)`. */
    fun render(keywords: List<WakeKeyword>): String =
        keywords.joinToString("/") { it.line() }

    /** Single-trigger fallback used only if `keywords.txt` is unreadable. */
    fun fallback(): List<WakeKeyword> = listOf(FALLBACK)

    /** Best-effort label from raw tokens when no `@phrase` is present. */
    private fun derivePhrase(tokens: String): String =
        tokens.replace("▁", " ").replace(" ", "").uppercase()
}
