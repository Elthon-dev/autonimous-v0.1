package com.elthon.broexe.voice

import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** What the EHS gate concluded about one frame of background audio. */
enum class EhsDecision {
    /** Nothing but the noise floor — the world is quiet. */
    QUIET,

    /** Sound is present but is not usable speech (hiss, clatter, music). */
    NOISE,

    /** Confirmed speech, but not carrying a known core word. */
    SPEECH,

    /** Confirmed speech that carries a core word — worth acting on. */
    TARGET,
}

/**
 * Per-frame acoustic measurements the gate reasons over. Pure numbers, no policy, so
 * they can be unit-tested against synthetic signals.
 */
data class EhsFeatures(
    val rmsDbfs: Float,
    val peakDbfs: Float,
    val zcr: Float,
    val hfRatio: Float,
    val clarity: Int,
)

/**
 * EHS tunables. Baked defaults, tuned later against real recordings — see the plan.
 *
 * The dBFS anchors are grounded, not guessed: normal conversation is 60–65 dB SPL at 1
 * metre and the "conversational intensity level" is ~68 dB; on the phone's own mic
 * scale that lands speech RMS in roughly the −26…−14 dBFS band, with peaks clearing
 * about −12 dBFS.
 */
data class EhsConfig(
    /** RMS band a confirmed human voice sits in. */
    val loDbfs: Float = -26f,
    val hiDbfs: Float = -14f,
    /** Peaks below this are too faint to be usable speech. */
    val peakFloorDbfs: Float = -12f,
    /** Below this the world is simply quiet. */
    val silenceDbfs: Float = -50f,
    /**
     * Above this high-frequency fraction the sound is hiss, not a voice. White noise leaks
     * `2a²/(1+a) ≈ 0.34` of its power through the ~2.5 kHz one-pole high-pass; voiced speech
     * keeps only ~0.05–0.15 there, so 0.25 cleanly separates the two.
     */
    val noiseHfRatio: Float = 0.25f,
    /** Clarity (0–100) below which we call it noise. */
    val minClarity: Int = 35,
    /** Silero probability threshold for the neural confirmor. */
    val sileroThreshold: Float = 0.5f,
    /** Require a KWS core-word before promoting SPEECH to TARGET. */
    val coreWordRequired: Boolean = true,
)

/**
 * Extracts [EhsFeatures] from a single 100 ms frame of 16 kHz mono audio.
 *
 * Three cheap, separable cues:
 *   - RMS + peak in dBFS → loudness vs the conversational band.
 *   - Zero-crossing rate → voiced speech (~0.02–0.08) vs hiss (~0.5).
 *   - High-frequency energy fraction via a one-pole high-pass at ~2.5 kHz → a voice
 *     keeps most energy below the band, white noise does not. No FFT needed.
 *
 * [clarity] fuses them into a 0–100 confidence the gate thresholds on.
 */
class EhsAnalyzer(private val config: EhsConfig = EhsConfig()) {

    fun analyze(frame: FloatArray): EhsFeatures {
        if (frame.isEmpty()) {
            return EhsFeatures(config.silenceDbfs, config.silenceDbfs, 0f, 0f, 0)
        }

        var sumSquares = 0.0
        var peak = 0.0
        var crossings = 0
        var hp = 0.0
        var prev = 0.0
        var hfSquares = 0.0

        for (raw in frame) {
            val x = raw.toDouble()
            sumSquares += x * x
            val a = abs(x)
            if (a > peak) peak = a
            if ((x >= 0.0) != (prev >= 0.0)) crossings++
            // One-pole high-pass: hp = a*(hp + x - x_prev).
            hp = HP_ALPHA * (hp + x - prev)
            hfSquares += hp * hp
            prev = x
        }

        val n = frame.size
        val rms = sqrt(sumSquares / n)
        val rmsDbfs = toDbfs(rms.toFloat())
        val peakDbfs = toDbfs(peak.toFloat())
        val zcr = (crossings.toDouble() / (n - 1).coerceAtLeast(1)).toFloat()
        val hfRatio = if (sumSquares > 0.0) (hfSquares / sumSquares).toFloat().coerceIn(0f, 1f) else 0f
        val clarity = clarity(rmsDbfs, zcr, hfRatio)
        return EhsFeatures(rmsDbfs, peakDbfs, zcr, hfRatio, clarity)
    }

    /** 1.0 inside the conversational band, falling linearly to 0 twelve dB outside it. */
    private fun loudnessScore(rmsDbfs: Float): Float = when {
        rmsDbfs < config.loDbfs || rmsDbfs > config.hiDbfs -> {
            val outside = if (rmsDbfs < config.loDbfs) config.loDbfs - rmsDbfs else rmsDbfs - config.hiDbfs
            (1f - outside / FALLOFF_DB).coerceIn(0f, 1f)
        }
        else -> 1f
    }

    /** 1.0 when little energy sits above ~2.5 kHz (a voice), 0.0 for hiss. */
    private fun hfScore(hfRatio: Float): Float =
        (1f - hfRatio / config.noiseHfRatio).coerceIn(0f, 1f)

    /** 1.0 in the voiced-speech ZCR range, decaying for noisy crossings. */
    private fun zcrScore(zcr: Float): Float = when {
        zcr < ZCR_LO -> (zcr / ZCR_LO).coerceIn(0f, 1f)
        zcr > ZCR_HI -> (1f - (zcr - ZCR_HI) / (1f - ZCR_HI)).coerceIn(0f, 1f)
        else -> 1f
    }

    /**
     * Fuses loudness with voice-vs-noise shape into a 0–100 confidence. Loudness is a
     * gate (silence and off-band sounds score 0); the HF + ZCR terms decide between a
     * voice and hiss. Multiplicative so that loud noise cannot coast on its loudness.
     */
    private fun clarity(rmsDbfs: Float, zcr: Float, hfRatio: Float): Int =
        (loudnessScore(rmsDbfs) * (WEIGHT_HF * hfScore(hfRatio) + WEIGHT_ZCR * zcrScore(zcr)))
            .coerceIn(0f, 1f)
            .times(100f)
            .roundToInt()

    private fun toDbfs(v: Float): Float = if (v <= FLOOR) SILENCE_DBFS else (20.0 * log10(v.toDouble())).toFloat()

    companion object {
        /** First-order high-pass pole for a ~2.5 kHz cutoff at 16 kHz (rc/(rc+dt)). */
        private const val HP_ALPHA = 0.5047f

        private const val FLOOR = 1e-9f
        private const val SILENCE_DBFS = -120f
        private const val FALLOFF_DB = 12f

        private const val ZCR_LO = 0.02f
        private const val ZCR_HI = 0.30f

        private const val WEIGHT_HF = 0.6f
        private const val WEIGHT_ZCR = 0.4f
    }
}

/**
 * A thin, *optional* wrapper over sherpa's native Silero VAD, used as the high-accuracy
 * speech confirmor behind the cheap energy pre-filter.
 *
 * Silero v5 (F1 ~0.99) is far stronger than WebRTC (~0.85), and its JNI already ships
 * inside our prebuilt `libsherpa-onnx-jni.so`, so no native rebuild was needed. The one
 * risk is that the JNI symbols could be absent from that build; if the native `Vad` fails
 * to load ([create] returns null) the gate transparently degrades to pure-Kotlin
 * energy/clarity gating rather than crashing the listener.
 */
class EhsSilero private constructor(private val vad: Vad) {

    /** Feed one frame; the native VAD buffers arbitrary lengths internally. */
    fun feed(frame: FloatArray) {
        runCatching { vad.acceptWaveform(frame) }
    }

    /** True while the model judges the current audio to be speech. */
    fun isSpeech(): Boolean = runCatching { vad.isSpeechDetected() }.getOrDefault(true)

    fun release() {
        runCatching { vad.release() }
    }

    companion object {
        /** Returns a working confirmor, or null to fall back to energy-only gating. */
        fun create(paths: VoicePaths, config: EhsConfig = EhsConfig()): EhsSilero? = try {
            if (!paths.vadModel.isFile) {
                null
            } else {
                val vadConfig = VadModelConfig(
                    sileroVadModelConfig = SileroVadModelConfig(
                        model = paths.vadModel.absolutePath,
                        threshold = config.sileroThreshold,
                        minSilenceDuration = 0.25f,
                        minSpeechDuration = 0.20f,
                        windowSize = 512,
                    ),
                    sampleRate = SAMPLE_RATE,
                    numThreads = 1,
                    provider = "cpu",
                    debug = false,
                )
                EhsSilero(Vad(assetManager = null, config = vadConfig))
            }
        } catch (t: Throwable) {
            // UnsatisfiedLinkError (no Silero JNI) or any load failure → energy-only mode.
            null
        }
    }
}

/**
 * The EHS gate: runs on every background sound frame and decides how "listen-worthy"
 * the moment is.
 *
 * Order is deliberate — cheap checks first, the expensive neural confirmor last:
 *   1. energy pre-filter (silence / below-band / too faint)
 *   2. clarity (high-frequency fraction + ZCR) to reject hiss and clatter
 *   3. Silero neural speech confirm (skipped when unavailable)
 *   4. core-word promotion to TARGET
 *
 * [decide] is a pure function over [EhsFeatures], so the policy is unit-testable without
 * any audio; [onFrame] is the live path that also feeds the optional Silero model.
 */
class EhsGate(
    private val config: EhsConfig = EhsConfig(),
    private val silero: EhsSilero? = null,
) {
    private val analyzer = EhsAnalyzer(config)

    /** True when the native Silero confirmor loaded; false means energy-only mode. */
    val usesSilero: Boolean get() = silero != null

    /** Clarity (0–100) of the most recently analyzed frame. */
    var lastClarity: Int = 0
        private set

    fun onFrame(frame: FloatArray, coreWord: Boolean = false): EhsDecision {
        val features = analyzer.analyze(frame)
        lastClarity = features.clarity
        silero?.feed(frame)
        return decide(features, sileroSpeech = silero?.isSpeech() ?: true, coreWord = coreWord)
    }

    /** Pure policy. Exposed for tests and for callers that already have features. */
    fun decide(features: EhsFeatures, sileroSpeech: Boolean, coreWord: Boolean): EhsDecision {
        if (features.rmsDbfs < config.silenceDbfs) return EhsDecision.QUIET
        if (features.rmsDbfs < config.loDbfs) return EhsDecision.QUIET
        if (features.peakDbfs < config.peakFloorDbfs) return EhsDecision.QUIET
        if (features.hfRatio > config.noiseHfRatio) return EhsDecision.NOISE
        if (features.clarity < config.minClarity) return EhsDecision.NOISE
        if (!sileroSpeech) return EhsDecision.NOISE
        if (config.coreWordRequired && !coreWord) return EhsDecision.SPEECH
        return EhsDecision.TARGET
    }

    fun release() {
        silero?.release()
    }
}

