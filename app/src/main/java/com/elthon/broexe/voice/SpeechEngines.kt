package com.elthon.broexe.voice

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.GeneratedAudio
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizerResult
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File

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
                modelType = "zipformer2",
            ),
            keywordsFile = keywordsFile.absolutePath,
            keywordsScore = 1.5f,
            keywordsThreshold = 0.25f,
            numTrailingBlanks = 2,
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

/**
 * Open-vocabulary streaming transcriber (one utterance at a time).
 *
 * This is the general ASR the whole engine is built on: it is *not* limited to a
 * phrase list, so any wording the user chooses can be recognized. [confidence]
 * averages the per-frame top-token probabilities sherpa exposes (`ysProbs`), giving
 * the classifier a coarse recognition-quality signal to discount garbled audio.
 *
 * A single recognizer is created up front and reused; streams are per-utterance and
 * must be [OnlineStream.release]d by the caller.
 */
class CommandTranscriber(private val paths: VoicePaths) {

    private val recognizer = OnlineRecognizer(
        assetManager = null,
        config = OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = paths.asrEncoder.absolutePath,
                    decoder = paths.asrDecoder.absolutePath,
                    joiner = paths.asrJoiner.absolutePath,
                ),
                tokens = paths.asrTokens.absolutePath,
                numThreads = 2,
                modelType = "zipformer",
            ),
            enableEndpoint = true,
            decodingMethod = "greedy_search",
        ),
    )

    fun createStream(): OnlineStream = recognizer.createStream()

    fun accept(stream: OnlineStream, samples: FloatArray) {
        stream.acceptWaveform(samples, SAMPLE_RATE)
        while (recognizer.isReady(stream)) recognizer.decode(stream)
    }

    fun isEndpoint(stream: OnlineStream): Boolean = recognizer.isEndpoint(stream)

    fun result(stream: OnlineStream): OnlineRecognizerResult = recognizer.getResult(stream)

    fun text(stream: OnlineStream): String = result(stream).text

    /** Mean per-frame top-token probability in [0, 1]; 0.5 when nothing is decoded. */
    fun confidence(stream: OnlineStream): Float {
        val probs = result(stream).ysProbs
        if (probs.isEmpty()) return 0.5f
        var sum = 0f
        for (p in probs) sum += p
        return (sum / probs.size).coerceIn(0f, 1f)
    }

    fun reset(stream: OnlineStream) = recognizer.reset(stream)

    fun release() = recognizer.release()
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
