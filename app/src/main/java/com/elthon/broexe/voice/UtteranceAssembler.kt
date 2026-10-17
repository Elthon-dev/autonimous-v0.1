package com.elthon.broexe.voice

/** What the capture loop should do after the requested pause has elapsed. */
enum class HoldDecision {
    /** Keep the turn open and keep reading frames. */
    HOLD,

    /** The turn is complete and should be transcribed. */
    FINALIZE,

    /** There was not enough speech; drop the turn and keep listening. */
    DISCARD,
}

/**
 * Decides when a spoken turn is actually over.
 *
 * The energy VAD marks the end of *voicing*, but people pause mid-sentence. If the
 * capture loop finalized on the first pause, "what is the ... capital of France"
 * would be split into two turns, and the fragment without "bro" would be silently
 * dropped — the exact "it heard me but ignored me" failure. This assembler keeps a
 * turn open across short pauses ([continuationGraceMs]) and merges resumed speech
 * into the same stream, so the recognizer sees the whole sentence.
 *
 * It also:
 *  - drops blips shorter than [minSpeechMs] (coughs, clicks, a chair creak);
 *  - force-closes a turn longer than [maxUtteranceMs] so a stuck microphone can
 *    never pin the mic forever;
 *  - accepts [extendGrace] once a partial transcript already looks addressed, giving
 *    the user extra time to finish the sentence they began with "bro".
 *
 * Pure and clock-driven: every transition is unit-testable without audio or a device.
 */
class UtteranceAssembler(
    private val minSpeechMs: Long = DEFAULT_MIN_SPEECH_MS,
    private val maxUtteranceMs: Long = DEFAULT_MAX_UTTERANCE_MS,
    private val continuationGraceMs: Long = DEFAULT_CONTINUATION_GRACE_MS,
    private val extendedGraceMs: Long = DEFAULT_EXTENDED_GRACE_MS,
    private val semanticGraceMs: Long = DEFAULT_SEMANTIC_GRACE_MS,
) {
    private var startedAt = Long.MIN_VALUE
    private var lastVoiceAt = Long.MIN_VALUE
    private var extended = false

    val isCapturing: Boolean get() = startedAt != Long.MIN_VALUE

    /** Total voiced span so far (ms), including intra-utterance pauses. */
    val speechMs: Long
        get() = if (!isCapturing) 0L else (lastVoiceAt - startedAt).coerceAtLeast(0L)

    /** Wall-clock length of the open turn so far (ms). */
    fun elapsedMs(now: Long): Long =
        if (!isCapturing) 0L else (now - startedAt).coerceAtLeast(0L)

    /** True once [extendGrace] has widened the pause tolerance for this turn. */
    val graceExtended: Boolean get() = extended

    /** Begin a new turn at [now] with the first voiced frame. */
    fun begin(now: Long) {
        startedAt = now
        lastVoiceAt = now
        extended = false
    }

    /** A voiced frame arrived; extend the turn's voiced span (also on resumed speech). */
    fun onVoice(now: Long) {
        if (isCapturing) lastVoiceAt = now
    }

    /**
     * The pause after the last voiced frame is being evaluated; decide whether to keep
     * the turn open. Safe to call on every silent frame.
     *
     * @param complete when the in-flight partial transcript already looks like a finished
     *   sentence (see [SemanticTurnEnd]), the shorter [semanticGraceMs] applies so a
     *   clearly-finished request is answered without the full pause tolerance.
     */
    fun onPause(now: Long, complete: Boolean = false): HoldDecision {
        if (!isCapturing) return HoldDecision.DISCARD
        val grace = when {
            complete -> semanticGraceMs
            extended -> extendedGraceMs
            else -> continuationGraceMs
        }
        if (now - lastVoiceAt < grace) return HoldDecision.HOLD
        return if (speechMs >= minSpeechMs) HoldDecision.FINALIZE else HoldDecision.DISCARD
    }

    /** Hard cap reached: the turn must end even if the user is still talking. */
    fun exceededMax(now: Long): Boolean = isCapturing && elapsedMs(now) >= maxUtteranceMs

    /**
     * Widen the pause tolerance for the rest of this turn — called once a partial
     * transcript already looks addressed to Bro, so the user gets extra time to finish
     * the sentence they started with "bro".
     */
    fun extendGrace() {
        extended = true
    }

    /** Reset to idle after a [HoldDecision.FINALIZE] or [HoldDecision.DISCARD]. */
    fun reset() {
        startedAt = Long.MIN_VALUE
        lastVoiceAt = Long.MIN_VALUE
        extended = false
    }

    companion object {
        /** Ignore voiced blips shorter than this. */
        const val DEFAULT_MIN_SPEECH_MS = 250L

        /** Hard ceiling on a single command turn. */
        const val DEFAULT_MAX_UTTERANCE_MS = 14_000L

        /** How long a pause may last before the turn is considered over. */
        const val DEFAULT_CONTINUATION_GRACE_MS = 800L

        /** The wider tolerance used once the turn already looks addressed. */
        const val DEFAULT_EXTENDED_GRACE_MS = 1_800L

        /**
         * The shorter tolerance used once the partial transcript already looks like a
         * finished sentence: a clear request is not made to wait the full pause.
         */
        const val DEFAULT_SEMANTIC_GRACE_MS = 450L
    }
}
