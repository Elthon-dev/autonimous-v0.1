package com.elthon.broexe.voice

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.GeneratedAudio
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File

/**
 * The model family our ASR checkpoint belongs to.
 *
 * `sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8` is an NVIDIA NeMo **TDT transducer**
 * export; sherpa's offline recognizer only builds it for the exact string
 * `"nemo_transducer"`. When `model_type` is non-empty sherpa skips its automatic
 * detection and constructs the class for that exact string; a wrong value reads
 * missing ONNX metadata and the native loader calls `SHERPA_ONNX_EXIT(-1)` =
 * `_Exit(-1)`, an uncatchable process death that leaves no NativeCrash tombstone.
 * That was the Detect/Speech self-test crash: do not "simplify" this string.
 * `StreamingModelTypeTest` fails loudly if it changes.
 */
const val ASR_MODEL_TYPE = "nemo_transducer"

/** The keyword-spotter checkpoints are zipformer2 exports too; same `_Exit(-1)` trap. */
const val KWS_MODEL_TYPE = "zipformer2"

/**
 * Wake-word detector backed by the sherpa-onnx keyword spotter.
 *
 * The keyword set is read from a file the app owns ([VoicePaths.activeKeywords]),
 * which [WakeConfig] rewrites whenever triggers are toggled. If that file is
 * missing we fall back to the bundled `keywords.txt`.
 *
 * A silent stream is fed continuously; when a keyword fires the stream is reset so
 * the next utterance can be detected without reallocating anything.
 */
class WakeWordDetector(private val paths: VoicePaths) {

    private val keywordsFile: File =
        if (paths.activeKeywords.isFile && paths.activeKeywords.length() > 0L) {
            paths.activeKeywords
        } else {
            paths.keywords
        }

    // Official zipformer-KWS scoring: 1.0 boost / 0.25 threshold and a single trailing
    // blank before re-arming, so consecutive "bro"s are not swallowed. The per-keyword
    // boosts in keywords.txt still win where present.
    private val spotter = KeywordSpotter(
        assetManager = null,
        config = KeywordSpotterConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = paths.kwsEncoder.absolutePath,
                    decoder = paths.kwsDecoder.absolutePath,
                    joiner = paths.kwsJoiner.absolutePath,
                ),
                tokens = paths.kwsTokens.absolutePath,
                numThreads = 1,
                modelType = KWS_MODEL_TYPE,
            ),
            keywordsFile = keywordsFile.absolutePath,
            keywordsScore = 1.0f,
            keywordsThreshold = 0.25f,
            numTrailingBlanks = 1,
        ),
    )

    fun createStream(): OnlineStream = spotter.createStream()

    fun accept(stream: OnlineStream, samples: FloatArray) {
        stream.acceptWaveform(samples, SAMPLE_RATE)
        while (spotter.isReady(stream)) spotter.decode(stream)
    }

    /** Returns the keyword if it fired on this stream, then re-arms the stream. */
    fun poll(stream: OnlineStream): String? {
        val result = spotter.getResult(stream)
        return if (result.keyword.isNotBlank()) {
            spotter.reset(stream)
            result.keyword
        } else {
            null
        }
    }

    fun release() = spotter.release()
}

/** One offline transcription plus the coarse confidence the classifier consumes. */
data class Transcription(val text: String, val confidence: Float)

/**
 * Open-vocabulary offline transcriber (one utterance at a time).
 *
 * The ASR is NVIDIA Parakeet TDT 0.6B v2 (int8), an **offline** (non-streaming) NeMo
 * transducer. It is markedly clearer than the streaming zipformer it replaced: it
 * sees the whole utterance at once with a full bidirectional context instead of
 * emitting tokens chunk-by-chunk from a causally-truncated window. The caller
 * buffers a turn and hands the samples over once, at the end.
 *
 * The offline API exposes no per-token probabilities, so [Transcription.confidence]
 * is a neutral constant; the classifier leans on the transcript and the acoustics.
 * [normalize] peak-scales a quiet microphone up first — an un-gained phone mic is the
 * single biggest cause of garbled transcripts.
 */
class CommandTranscriber(paths: VoicePaths) {

    private val recognizer = OfflineRecognizer(
        assetManager = null,
        config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = paths.asrEncoder.absolutePath,
                    decoder = paths.asrDecoder.absolutePath,
                    joiner = paths.asrJoiner.absolutePath,
                ),
                tokens = paths.asrTokens.absolutePath,
                numThreads = 2,
                modelType = ASR_MODEL_TYPE,
            ),
            decodingMethod = "greedy_search",
        ),
    )

    fun transcribe(samples: FloatArray): Transcription {
        if (samples.isEmpty()) return Transcription("", 0f)
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(normalize(samples), SAMPLE_RATE)
            recognizer.decode(stream)
            return Transcription(recognizer.getResult(stream).text.trim(), CONFIDENCE)
        } finally {
            stream.release()
        }
    }

    fun release() = recognizer.release()

    private fun normalize(samples: FloatArray): FloatArray {
        var peak = 0f
        for (s in samples) {
            val a = if (s < 0f) -s else s
            if (a > peak) peak = a
        }
        // Near-silence is left untouched (nothing to gain); already-hot audio is left
        // alone so we never push an already-clipped signal further into the rails.
        if (peak < MIN_PEAK || peak >= TARGET_PEAK) return samples
        val gain = (TARGET_PEAK / peak).coerceAtMost(MAX_GAIN)
        val out = FloatArray(samples.size)
        for (i in samples.indices) out[i] = (samples[i] * gain).coerceIn(-1f, 1f)
        return out
    }

    companion object {
        // No probabilistic output from the offline API; report a neutral mid-band value
        // so the addressing classifier is driven by text + acoustics, not a fake score.
        private const val CONFIDENCE = 0.5f

        private const val TARGET_PEAK = 0.95f
        private const val MIN_PEAK = 1e-4f
        private const val MAX_GAIN = 12f
    }
}

/** Concatenate the captured frames of one turn into a single utterance buffer. */
internal fun flattenAudio(frames: List<FloatArray>): FloatArray {
    var total = 0
    for (f in frames) total += f.size
    val out = FloatArray(total)
    var off = 0
    for (f in frames) {
        System.arraycopy(f, 0, out, off, f.size)
        off += f.size
    }
    return out
}

/** Offline speech synthesis (Piper VITS). */
class Speaker(private val paths: VoicePaths) {

    private val tts = OfflineTts(
        assetManager = null,
        config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = paths.ttsModel.absolutePath,
                    tokens = paths.ttsTokens.absolutePath,
                    dataDir = paths.ttsDataDir.absolutePath,
                ),
                numThreads = 2,
            ),
        ),
    )

    val sampleRate: Int get() = tts.sampleRate()

    fun synthesize(text: String, speed: Float = 1.0f): GeneratedAudio =
        tts.generate(text, sid = 0, speed = speed)

    fun release() = tts.release()
}
