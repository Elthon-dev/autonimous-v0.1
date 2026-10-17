package com.elthon.broexe.voice

/**
 * Everything the addressing classifier knows about the current moment besides the
 * transcript itself. Kept as plain data so tests can construct any situation.
 */
data class DetectionContext(
    /** True while a conversation is open (i.e. a follow-up window is running). */
    val inSession: Boolean = false,
    /** ms since Bro last finished speaking (Long.MAX_VALUE if never). */
    val msSinceAssistantSpoke: Long = Long.MAX_VALUE,
    val followUpWindowMs: Long = 8000L,
    /** Voiced duration of this utterance, in ms. */
    val speechMs: Long = 0L,
    /** Average ASR confidence in [0, 1] (0.5 when unknown). */
    val asrConfidence: Float = 0.5f,
    /** Whether a bare "bro" may, on its own, activate. */
    val standaloneBro: Boolean = true,
    /** Optional acoustical keyword hint (e.g. from a keyword spotter), if any. */
    val keywordHit: String? = null,
    /**
     * EHS acoustic verdict for this utterance, when the gate ran. `null` (the default)
     * means "unknown" and leaves the classifier untouched, so behaviour is unchanged
     * for callers that have not wired EHS in yet.
     */
    val ehsDecision: EhsDecision? = null,
    /** EHS clarity score (0–100) for the loudest frame, when the gate ran. */
    val ehsClarity: Int = 0,
)

/** The classifier's verdict for one utterance. */
data class AddressingSignal(
    val score: Float,
    val reasons: List<String>,
) {
    fun reasonString(): String = if (reasons.isEmpty()) "no address cues" else reasons.joinToString(", ")
}

/**
 * Open-vocabulary addressing-intent detector.
 *
 * This is the piece that replaces the finite wake-phrase list. It never checks
 * whether the transcript *equals* an approved phrase; instead it accumulates
 * weighted evidence that the utterance is directed at Bro:
 *
 *  - **vocative** — "bro" / "bruh" / "brah" in a direct-address position (alone,
 *    leading, or trailing), *not* in a third-person slot ("my bro said…");
 *  - **request intent** — meta-requests ("I have a question"), address frames
 *    ("can you…", "are you there"), imperatives ("set a timer"), questions;
 *  - **opener markers** ("hey", "yo", "okay");
 *  - **conversation context** — an open session or a recent reply makes a
 *    follow-up very likely to be addressed to Bro;
 *  - **acoustic/ASR confidence** — very short blips and low-confidence
 *    transcripts are discounted.
 *
 * The result is general: it fires on wording the implementation has never seen,
 * because the cues are linguistic categories, not a phrase list. It is pure and
 * deterministic, so every rule is unit-testable without audio or a device.
 *
 * Deliberate policy: explicit address (a vocative, an addressing frame, or a
 * meta-request) activates on its own; a bare question or bare imperative is treated
 * as *ambiguous* — addressed people usually frame commands, and over-firing on
 * background speech is the worse failure. Ambiguous utterances still activate while a
 * session is open.
 */
object AddressingDetector {

    // --- evidence weights (the tunable heart of the classifier) -----------------
    const val W_STANDALONE_VOCATIVE = 0.60f
    const val W_LEADING_VOCATIVE = 0.45f
    const val W_TRAILING_VOCATIVE = 0.34f
    const val W_MEDIAL_VOCATIVE = 0.22f
    const val THIRD_PERSON_MULTIPLIER = 0.30f

    const val W_META_REQUEST = 0.55f
    const val W_ADDRESS_FRAME = 0.40f
    const val W_EXTRA_FRAME = 0.05f
    const val W_EXTRA_FRAME_CAP = 0.15f
    const val W_PLEASE = 0.25f
    const val W_QUESTION_MARK = 0.18f
    const val W_QUESTION_WORD = 0.12f
    const val W_AUX_INTENT = 0.10f
    const val W_IMPERATIVE = 0.40f
    const val W_OPENER = 0.15f
    const val W_INTERROGATIVE = 0.10f

    const val W_IN_SESSION = 0.45f
    const val W_RECENT_REPLY = 0.30f
    const val CONTEXT_CAP = 0.50f

    const val W_KEYWORD_HINT = 0.25f

    // EHS acoustic gate: a mild nudge only, so it can never flip a decision the
    // linguistic evidence already settled. Absent (null) EHS is a no-op.
    const val W_EHS_TARGET = 0.15f
    const val W_EHS_SPEECH = 0.08f
    const val W_EHS_PENALTY = -0.10f

    const val SHORT_UTTERANCE_MS = 200L
    const val SHORT_UTTERANCE_MULTIPLIER = 0.60f
    const val MIN_USEFUL_SPEECH_MS = 300L
    const val LONG_SPEECH_BONUS = 0.05f

    // --- vocabularies (linguistic categories, not an activation whitelist) ------
    private val FILLERS = setOf("um", "uh", "erm", "hmm", "mm", "ah", "er")
    private val BRO = setOf("bro", "bruh", "brah", "brotha", "brodie")

    /** Words that commonly open an utterance directed at someone. */
    private val OPENERS = setOf(
        "hey", "hi", "hello", "yo", "sup", "howdy",
        "okay", "ok", "so", "well", "now", "ahem",
    )

    private val QWORDS = setOf(
        "what", "what's", "whats", "who", "who's", "whos", "whose", "where",
        "when", "why", "how", "which", "how's", "hows",
    )
    private val AUX = setOf(
        "is", "are", "am", "do", "does", "did", "can", "could", "will", "would",
        "should", "may", "might", "must", "have", "has", "had",
    )
    private val IMPERATIVE = setOf(
        "tell", "show", "give", "help", "set", "remind", "play", "stop", "start",
        "open", "close", "turn", "search", "look", "find", "google", "define",
        "explain", "translate", "calculate", "compute", "add", "write", "make",
        "summarize", "list", "send", "call", "text", "mute", "unmute", "raise",
        "lower", "increase", "decrease", "sing", "say", "read", "check", "plan",
        "book", "order", "enable", "disable", "switch", "bring", "put", "reset",
        "listen", "answer",
    )

    /** Unambiguous "I am talking to you" openers. */
    private val META_REQUESTS = listOf(
        "i have a question", "i have a doubt", "i have a question for you",
        "i have something to ask", "quick question", "can i ask", "may i ask",
        "let me ask", "i want to ask", "i wanted to ask", "i'd like to ask",
    )

    /** Frames that strongly imply the listener is being addressed. */
    private val ADDRESS_FRAMES = listOf(
        "can you", "could you", "would you", "will you", "do you know",
        "don't you", "i want you to", "i need you to", "i'd like you to",
        "help me", "tell me", "show me", "give me", "remind me", "explain to me",
        "talk to me", "listen to me", "listen up", "are you there",
        "are you listening", "you there",
    )

    private val POSSESSIVE_OR_DET = setOf(
        "my", "your", "his", "her", "our", "their", "the", "that", "this", "a", "an",
        "some", "another", "any", "every", "each", "no",
    )

    private val BRO_VERB = setOf(
        "is", "was", "were", "has", "had", "said", "says", "told", "tells", "thinks",
        "thought", "wants", "wanted", "did", "does", "likes", "liked", "went",
        "came", "called", "calls", "texted", "texts", "will", "would", "can",
        "could", "should", "may", "might", "knows", "knew", "feels", "felt",
        "gets", "got", "made", "makes", "looks", "seems", "always", "never",
        "usually", "just", "also", "still",
    )

    fun evaluate(transcript: String, ctx: DetectionContext): AddressingSignal {
        val reasons = ArrayList<String>()
        val norm = normalize(transcript)
        if (norm.isBlank()) return AddressingSignal(0f, listOf("empty transcript"))

        val tokens = tokensOf(norm)
        val stripped = tokens.filter { it !in FILLERS }
        // Punctuation-free word sequence for frame/word matching, so a trailing
        // "?" or "," never hides a match ("are you there?" -> "are you there").
        val words = tokens.joinToString(" ")

        var score = 0f

        // --- vocative evidence ---
        val vocative = vocativeEvidence(stripped, ctx.standaloneBro, reasons)
        score += vocative
        if (thirdPersonBro(stripped)) {
            if (vocative > 0f) {
                // Re-scale the vocative portion: "bro" as a subject/object is not address.
                score -= vocative
                score += vocative * THIRD_PERSON_MULTIPLIER
            }
            reasons.add("third-person 'bro'")
        }

        // --- request / intent evidence ---
        val metaHit = META_REQUESTS.firstOrNull { containsFrame(words, it) }
        if (metaHit != null) {
            score += W_META_REQUEST
            reasons.add("meta-request")
        } else {
            val frames = ADDRESS_FRAMES.filter { containsFrame(words, it) }
            if (frames.isNotEmpty()) {
                score += W_ADDRESS_FRAME
                score += ((frames.size - 1) * W_EXTRA_FRAME).coerceAtMost(W_EXTRA_FRAME_CAP)
                reasons.add("address-frame")
            }
        }
        if (containsWord(words, "please")) {
            score += W_PLEASE
            reasons.add("please")
        }

        // The "intent" token is the first content word after any openers/vocatives.
        val contentTokens = stripped.filter { it !in BRO && it !in OPENERS }
        val intentToken = contentTokens.firstOrNull()
        if (intentToken in QWORDS) {
            score += W_QUESTION_WORD
            reasons.add("question-word")
        }
        if (intentToken in AUX) {
            score += W_AUX_INTENT
            reasons.add("auxiliary-intent")
        }
        if (intentToken in IMPERATIVE) {
            score += W_IMPERATIVE
            reasons.add("imperative")
        }

        val interrogative = transcript.contains('?') || intentToken in QWORDS || intentToken in AUX
        if (transcript.contains('?')) {
            score += W_QUESTION_MARK
            reasons.add("question")
        }
        if (interrogative) {
            score += W_INTERROGATIVE
            reasons.add("interrogative")
        }

        if (stripped.firstOrNull() in OPENERS) {
            score += W_OPENER
            reasons.add("opener")
        }

        // --- conversational context ---
        var context = 0f
        if (ctx.inSession) context += W_IN_SESSION
        if (ctx.msSinceAssistantSpoke in 0 until ctx.followUpWindowMs) context += W_RECENT_REPLY
        if (context > 0f) {
            score += context.coerceAtMost(CONTEXT_CAP)
            reasons.add(if (ctx.inSession) "in-session" else "recent-reply")
        }

        // --- optional acoustical keyword evidence ---
        if (!ctx.keywordHit.isNullOrBlank()) {
            score += W_KEYWORD_HINT
            reasons.add("keyword-hint")
        }

        // --- EHS acoustic gate (neutral when it did not run) ---
        when (ctx.ehsDecision) {
            EhsDecision.TARGET -> {
                score += W_EHS_TARGET
                reasons.add("ehs-target")
            }
            EhsDecision.SPEECH -> {
                score += W_EHS_SPEECH
                reasons.add("ehs-speech")
            }
            EhsDecision.NOISE, EhsDecision.QUIET -> {
                score += W_EHS_PENALTY
                reasons.add("ehs-" + ctx.ehsDecision.name.lowercase())
            }
            null -> Unit
        }

        // --- acoustic / recognition quality ---
        if (ctx.speechMs in 1 until SHORT_UTTERANCE_MS) {
            score *= SHORT_UTTERANCE_MULTIPLIER
            reasons.add("very-short")
        } else if (ctx.speechMs >= MIN_USEFUL_SPEECH_MS && score > 0f) {
            score += LONG_SPEECH_BONUS
        }
        val confAdj = ((ctx.asrConfidence - 0.5f) * 0.4f).coerceIn(-0.08f, 0.08f)
        score += confAdj

        return AddressingSignal(score.coerceIn(0f, 1f), reasons)
    }

    // --- vocative -----------------------------------------------------------------

    private fun vocativeEvidence(
        stripped: List<String>,
        standaloneEnabled: Boolean,
        reasons: MutableList<String>,
    ): Float {
        if (stripped.isEmpty()) return 0f

        // Standalone: "bro" is the only real token.
        if (stripped.size == 1 && stripped[0] in BRO) {
            if (standaloneEnabled) {
                reasons.add("standalone vocative")
                return W_STANDALONE_VOCATIVE
            }
            return 0f
        }

        // "hey bro …": an opener followed by the vocative.
        if (stripped.size >= 2 && stripped[1] in BRO && stripped[0] in OPENERS) {
            reasons.add("opener-vocative")
            return W_LEADING_VOCATIVE
        }

        // Leading: "bro …".
        if (stripped.first() in BRO) {
            reasons.add("leading vocative")
            return W_LEADING_VOCATIVE
        }

        // Trailing: "…, bro".
        if (stripped.size > 1 && stripped.last() in BRO) {
            reasons.add("trailing vocative")
            return W_TRAILING_VOCATIVE
        }

        // Medial: "tell me bro what's the time". The mission is explicit that "bro"
        // must count wherever it lands, not only at the edges.
        val medial = stripped.withIndex().any { (i, t) -> i in 1 until stripped.size - 1 && t in BRO }
        if (medial) {
            reasons.add("medial vocative")
            return W_MEDIAL_VOCATIVE
        }

        return 0f
    }

    private fun thirdPersonBro(tokens: List<String>): Boolean {
        for (i in tokens.indices) {
            if (tokens[i] !in BRO) continue
            val prev = tokens.getOrNull(i - 1)
            val next = tokens.getOrNull(i + 1)
            if (prev != null && (prev in POSSESSIVE_OR_DET || prev in BRO)) return true
            if (next != null && (next in BRO_VERB || next == "'s" || next == "s")) return true
        }
        return false
    }

    // --- text helpers -------------------------------------------------------------

    private fun normalize(s: String): String = s
        .replace('\u2019', '\'')
        .replace('\u2018', '\'')
        .replace('\u201c', '"')
        .replace('\u201d', '"')
        .lowercase()
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun tokensOf(norm: String): List<String> =
        norm.split(' ')
            .map { it.trim(*TRIM_CHARS) }
            .filter { it.isNotEmpty() }

    private fun containsFrame(norm: String, frame: String): Boolean =
        " $norm ".contains(" $frame ")

    private fun containsWord(norm: String, word: String): Boolean =
        " $norm ".contains(" $word ")

    private val TRIM_CHARS = charArrayOf(
        ',', '.', '!', '?', ':', ';', '-', '"', '\'', '\u2026',
    )
}
