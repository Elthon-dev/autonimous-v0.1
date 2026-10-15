package com.elthon.broexe.voice

/**
 * Stage-1 wake candidate: the keyword spotter fired on the audio stream.
 *
 * @param phrase  sherpa's `@phrase` for the matched keyword (e.g. `HEYBRO`)
 * @param atMs    monotonic time of the detection
 * @param peak    peak amplitude observed across the trigger window, in [0, 1]
 */
data class WakeCandidate(
    val phrase: String,
    val atMs: Long,
    val peak: Float,
)

/**
 * Stage-2 verdict. [accepted] means the candidate survives the energy gate, the
 * refractory window and (optionally) a phrase confirmation from the transcript.
 */
data class WakeDecision(
    val accepted: Boolean,
    val reason: String,
    val gate: String,
)

/**
 * Second stage of the wake engine.
 *
 * Stage 1 is the acoustic keyword spotter. Stage 2 is this verifier: it rejects a
 * candidate whose trigger window carried no real energy (a phantom detection from
 * silence), and enforces a refractory window so one utterance cannot fire the
 * whole pipeline twice back to back.
 *
 * Pure and deterministic — the clock is injected, so every branch is unit-testable.
 */
class WakeVerifier(
    private val minPeak: Float = DEFAULT_MIN_PEAK,
    private val refractoryMs: Long = DEFAULT_REFRACTORY_MS,
) {
    private var lastAcceptedAt = Long.MIN_VALUE / 4

    fun reset() {
        lastAcceptedAt = Long.MIN_VALUE / 4
    }

    fun evaluate(candidate: WakeCandidate): WakeDecision {
        val since = candidate.atMs - lastAcceptedAt
        if (candidate.peak < minPeak) {
            return WakeDecision(false, "rejected", "low-energy peak=${"%.4f".format(candidate.peak)}")
        }
        if (since >= 0 && since < refractoryMs) {
            return WakeDecision(false, "rejected", "refractory ${since}ms<${refractoryMs}ms")
        }
        lastAcceptedAt = candidate.atMs
        return WakeDecision(true, "confirmed", "peak=${"%.4f".format(candidate.peak)}")
    }

    companion object {
        /** Below this peak a "detection" is not backed by real sound. */
        const val DEFAULT_MIN_PEAK = 0.02f

        /** One utterance must not wake the pipeline twice inside this window. */
        const val DEFAULT_REFRACTORY_MS = 1500L
    }
}
