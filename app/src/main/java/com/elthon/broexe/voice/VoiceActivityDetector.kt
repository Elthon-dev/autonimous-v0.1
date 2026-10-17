package com.elthon.broexe.voice

/** What a VAD frame decided. */
enum class VadEvent { NONE, SPEECH_START, SPEECH_END }

/**
 * Lightweight, model-free voice activity detector.
 *
 * It tracks the RMS energy of incoming ~100 ms frames against an adaptive noise
 * floor, so it works in a quiet room and a noisy one without a neural VAD model
 * (which would cost memory the 3 GB target cannot spare next to ASR + the LLM).
 *
 * Two thresholds give hysteresis: [startRms] to enter speech, the lower [endRms] to
 * leave it after [minSilenceMs] of quiet. The noise floor is an exponential moving
 * average of non-speech energy, so [startThreshold] floats up in a noisy room.
 *
 * Pure and deterministic — the frame clock is supplied by the caller, so every
 * transition is unit-testable without a device.
 */
class VoiceActivityDetector(
    private val startRms: Float = DEFAULT_START_RMS,
    private val endRms: Float = DEFAULT_END_RMS,
    private val minSpeechMs: Long = DEFAULT_MIN_SPEECH_MS,
    private val minSilenceMs: Long = DEFAULT_MIN_SILENCE_MS,
    private val frameMs: Long = DEFAULT_FRAME_MS,
    private val adaptNoiseFloor: Boolean = true,
    private val noiseMultiplier: Float = DEFAULT_NOISE_MULTIPLIER,
) {
    private var speaking = false
    private var speechStartedAt = 0L
    private var lastVoiceAt = 0L
    private var noiseFloor = INITIAL_NOISE_FLOOR

    val startThreshold: Float get() = maxOf(startRms, noiseFloor * noiseMultiplier)
    val floor: Float get() = noiseFloor

    fun reset() {
        speaking = false
        speechStartedAt = 0L
        lastVoiceAt = 0L
        noiseFloor = INITIAL_NOISE_FLOOR
    }

    fun isSpeaking(): Boolean = speaking

    /** Milliseconds of voiced audio since speech started (0 when idle). */
    fun speechDurationMs(atMs: Long): Long =
        if (speaking) (lastVoiceAt - speechStartedAt).coerceAtLeast(0L) else 0L

    /** Timestamp of the most recent voiced frame since speech started. */
    fun lastVoiceAt(): Long = lastVoiceAt

    /**
     * Feed one frame's RMS energy (in [0, 1]) and its timestamp. Returns the
     * transition this frame caused, if any.
     */
    fun process(rms: Float, atMs: Long): VadEvent {
        if (!speaking) {
            if (rms >= startThreshold) {
                speaking = true
                speechStartedAt = atMs
                lastVoiceAt = atMs
                return VadEvent.SPEECH_START
            }
            if (adaptNoiseFloor) {
                val r = rms.coerceAtLeast(0f)
                noiseFloor = when {
                    // First non-speech frame seeds the floor at the ambient level.
                    noiseFloor == INITIAL_NOISE_FLOOR -> r
                    // Room got quieter: fall quickly so the detector re-sensitises.
                    r < noiseFloor -> noiseFloor * (1f - FALL_RATE) + r * FALL_RATE
                    // Room got noisier: creep up slowly so one bang does not deafen it.
                    else -> noiseFloor * (1f - RISE_RATE) + r * RISE_RATE
                }
            }
            return VadEvent.NONE
        }

        if (rms >= endThreshold) lastVoiceAt = atMs

        if (atMs - lastVoiceAt >= minSilenceMs) {
            speaking = false
            return VadEvent.SPEECH_END
        }
        return VadEvent.NONE
    }

    private val endThreshold: Float
        get() = maxOf(endRms, noiseFloor * noiseMultiplier * 0.6f)

    companion object {
        /** Enter speech above this RMS in a quiet room. */
        const val DEFAULT_START_RMS = 0.02f

        /** Leave speech once RMS drops below this (hysteresis). */
        const val DEFAULT_END_RMS = 0.012f

        /** Ignore blips shorter than this (coughs, clicks). */
        const val DEFAULT_MIN_SPEECH_MS = 250L

        /** How long the room must stay quiet before an utterance is considered over. */
        const val DEFAULT_MIN_SILENCE_MS = 600L

        const val DEFAULT_FRAME_MS = 100L

        /** The speech threshold floats at least this many times above the noise floor. */
        const val DEFAULT_NOISE_MULTIPLIER = 3.0f

        /** Blend used when ambient noise rises: slow, so a transient bang cannot raise it. */
        private const val RISE_RATE = 0.03f

        /** Blend used when ambient noise falls: faster, so the mic re-sensitises promptly. */
        private const val FALL_RATE = 0.20f

        private const val INITIAL_NOISE_FLOOR = 0.005f
    }
}
