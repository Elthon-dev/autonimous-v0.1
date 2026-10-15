package com.elthon.broexe.voice

/** What the engine should do with one utterance's [AddressingSignal]. */
enum class ActivationDecision {
    /** Strong evidence: open a conversation and act on the utterance. */
    ACTIVATE,

    /** Not enough evidence yet; keep listening silently (default for the middle band). */
    WAIT,

    /** Middle band and the user opted in: ask a short, non-intrusive clarification. */
    CLARIFY,

    /** Low band: definitely not addressed to Bro; stay idle. */
    IGNORE,
}

data class ActivationResult(
    val decision: ActivationDecision,
    val score: Float,
    val band: ConfidenceBand,
    val reason: String,
)

enum class ConfidenceBand { HIGH, AMBIGUOUS, LOW }

/**
 * Turns an [AddressingSignal] into an action, applying the policy the user configured:
 * two confidence thresholds, an optional clarification path, a refractory window that
 * stops one burst of speech from activating twice, and a per-utterance dedup guard so
 * a re-finalized transcript can never double-activate.
 *
 * Hysteresis lives here: once a session is open, the middle band counts as activation,
 * so follow-up turns do not have to re-earn a high score.
 */
class ActivationDecider(
    private val highThreshold: Float = DEFAULT_HIGH,
    private val ambiguousThreshold: Float = DEFAULT_AMBIGUOUS,
    private val ambiguousClarify: Boolean = false,
    private val refractoryMs: Long = DEFAULT_REFRACTORY_MS,
    private val clarifyCooldownMs: Long = DEFAULT_CLARIFY_COOLDOWN_MS,
) {
    private var lastActivateAt = Long.MIN_VALUE
    private var lastClarifyAt = Long.MIN_VALUE
    private var lastHandledUtteranceId: String? = null

    init {
        require(ambiguousThreshold <= highThreshold) {
            "ambiguousThreshold ($ambiguousThreshold) must not exceed highThreshold ($highThreshold)"
        }
    }

    fun reset() {
        lastActivateAt = Long.MIN_VALUE
        lastClarifyAt = Long.MIN_VALUE
        lastHandledUtteranceId = null
    }

    fun bandFor(score: Float): ConfidenceBand = when {
        score >= highThreshold -> ConfidenceBand.HIGH
        score >= ambiguousThreshold -> ConfidenceBand.AMBIGUOUS
        else -> ConfidenceBand.LOW
    }

    fun decide(
        signal: AddressingSignal,
        ctx: DetectionContext,
        utteranceId: String,
        atMs: Long,
    ): ActivationResult {
        val band = bandFor(signal.score)

        if (utteranceId == lastHandledUtteranceId) {
            return ActivationResult(
                ActivationDecision.WAIT,
                signal.score,
                band,
                "duplicate utterance",
            )
        }

        val decision: ActivationDecision
        val reason: String
        when (band) {
            ConfidenceBand.HIGH -> {
                if (lastActivateAt != Long.MIN_VALUE && atMs - lastActivateAt < refractoryMs) {
                    decision = ActivationDecision.WAIT
                    reason = "refractory"
                } else {
                    decision = ActivationDecision.ACTIVATE
                    reason = "high confidence"
                }
            }
            ConfidenceBand.AMBIGUOUS -> {
                if (ctx.inSession) {
                    decision = ActivationDecision.ACTIVATE
                    reason = "ambiguous but in session"
                } else if (ambiguousClarify &&
                    (lastClarifyAt == Long.MIN_VALUE || atMs - lastClarifyAt >= clarifyCooldownMs)
                ) {
                    decision = ActivationDecision.CLARIFY
                    reason = "ambiguous, clarification enabled"
                } else {
                    decision = ActivationDecision.WAIT
                    reason = "ambiguous, waiting for context"
                }
            }
            ConfidenceBand.LOW -> {
                decision = ActivationDecision.IGNORE
                reason = "low confidence"
            }
        }

        when (decision) {
            ActivationDecision.ACTIVATE -> {
                lastActivateAt = atMs
                lastHandledUtteranceId = utteranceId
            }
            ActivationDecision.CLARIFY -> {
                lastClarifyAt = atMs
                lastHandledUtteranceId = utteranceId
            }
            else -> Unit
        }

        return ActivationResult(decision, signal.score, band, reason)
    }

    /** Record an activation decided elsewhere (e.g. an explicit UI tap). */
    fun markActivated(atMs: Long) {
        lastActivateAt = atMs
    }

    companion object {
        const val DEFAULT_HIGH = 0.50f
        const val DEFAULT_AMBIGUOUS = 0.35f
        const val DEFAULT_REFRACTORY_MS = 1200L
        const val DEFAULT_CLARIFY_COOLDOWN_MS = 20000L
    }
}
