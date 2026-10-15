package com.elthon.broexe.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlin.math.max
import kotlin.math.min

/** 16 kHz mono PCM16 — what the KWS and ASR models expect. */
const val SAMPLE_RATE = 16000

/** ~100 ms frames keep activation latency low without spinning the CPU. */
private const val FRAME_SAMPLES = SAMPLE_RATE / 10

/** Raised when the microphone cannot be opened or read — surfaced, never swallowed. */
class AudioCaptureException(message: String) : IllegalStateException(message)

/**
 * Microphone capture. Frames are handed to callers as normalized floats in [-1, 1],
 * which is exactly what sherpa-onnx `acceptWaveform` wants.
 *
 * Uses [MediaRecorder.AudioSource.MIC] on purpose: it is the one capture path every
 * device (MediaTek/ColorOS included) is guaranteed to honour. `VOICE_RECOGNITION`
 * silently starves the buffer on some HALs, which looks exactly like "the wake word
 * never fires". Failures are thrown, not hidden, so a dead mic shows up as an error
 * instead of a forever-spinning "Listening".
 *
 * Best-effort [AcousticEchoCanceler]/[NoiseSuppressor] are attached so that barge-in
 * (speaking over Bro's own TTS) does not immediately re-activate on Bro's voice.
 * Effect availability is reported via [echoCancellationActive] and never assumed.
 */
class AudioInput {
    private val minBuffer = AudioRecord.getMinBufferSize(
        SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
    )

    private val bufferBytes = max(minBuffer, FRAME_SAMPLES * 2 * 4)

    @SuppressLint("MissingPermission") // guarded by the caller after RECORD_AUDIO is granted
    private val record = AudioRecord(
        MediaRecorder.AudioSource.MIC,
        SAMPLE_RATE,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
        bufferBytes,
    )

    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null

    /** True only if an AEC effect was created and actually enabled. */
    var echoCancellationActive: Boolean = false
        private set

    init {
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord not initialized (state=${record.state}, minBuffer=$minBuffer)")
            throw AudioCaptureException("Microphone unavailable (state=${record.state})")
        }
        enableAudioEffects()
        Log.i(
            TAG,
            "AudioRecord ready: source=MIC minBuffer=$minBuffer buffer=$bufferBytes " +
                "aec=$echoCancellationActive",
        )
    }

    private fun enableAudioEffects() {
        val session = record.audioSessionId
        runCatching {
            if (session != 0 && AcousticEchoCanceler.isAvailable()) {
                echoCanceler = AcousticEchoCanceler.create(session).also { it.enabled = true }
                echoCancellationActive = echoCanceler?.enabled == true
            }
        }.onFailure { Log.w(TAG, "AEC unavailable: ${it.message}") }
        runCatching {
            if (session != 0 && NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(session).also { it.enabled = true }
            }
        }.onFailure { Log.w(TAG, "NoiseSuppressor unavailable: ${it.message}") }
    }

    fun start() {
        if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) return
        record.startRecording()
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            Log.e(TAG, "startRecording did not enter RECORDING (state=${record.recordingState})")
            throw AudioCaptureException("Microphone did not start (state=${record.recordingState})")
        }
    }

    /**
     * Blocking read of up to one frame; returns fewer samples only at shutdown.
     *
     * A negative return is a hard capture error and is thrown so the pipeline can
     * surface it. A zero-length read is treated as a transient empty frame.
     */
    fun readFrame(): FloatArray {
        val buf = ShortArray(FRAME_SAMPLES)
        val n = record.read(buf, 0, buf.size)
        if (n < 0) {
            Log.e(TAG, "AudioRecord.read failed (code=$n)")
            throw AudioCaptureException("Microphone read failed (code=$n)")
        }
        if (n == 0) return FloatArray(0)
        return pcm16ToFloat(buf, n)
    }

    fun stop() {
        runCatching { record.stop() }
    }

    fun release() {
        runCatching { echoCanceler?.release() }
        runCatching { noiseSuppressor?.release() }
        echoCanceler = null
        noiseSuppressor = null
        echoCancellationActive = false
        runCatching { record.release() }
    }

    /** Continuous stream of frames for coroutine consumers. */
    fun frames(): Flow<FloatArray> = flow {
        start()
        try {
            while (true) {
                val frame = readFrame()
                if (frame.isEmpty()) break
                emit(frame)
            }
        } finally {
            stop()
        }
    }.flowOn(Dispatchers.IO)

    companion object {
        private const val TAG = "BroExeAudio"

        /**
         * Whether the current output is routed to a wired/Bluetooth headset — where
         * Bro's own voice is far less likely to leak into the mic during barge-in.
         */
        fun outputIsExternal(context: Context): Boolean {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
            val external = setOf(
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_BLE_HEADSET,
            )
            return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in external }
        }
    }
}

/**
 * Plays float PCM (sherpa-onnx TTS output) through [AudioTrack].
 *
 * Playback is chunked and checks a volatile stop flag between chunks, so [stop] can
 * cut Bro off mid-sentence for barge-in within a few tens of milliseconds instead of
 * waiting for the whole utterance to drain.
 */
class AudioOutput {
    @Volatile
    private var stopRequested = false

    @Volatile
    private var playing = false

    fun isPlaying(): Boolean = playing

    /** Ask any in-flight [play] call to stop at the next chunk boundary. */
    fun stop() {
        stopRequested = true
    }

    fun play(samples: FloatArray, sampleRate: Int, chunkSamples: Int = DEFAULT_CHUNK) {
        if (samples.isEmpty()) return
        val bufferBytes = max(
            AudioTrack.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT
            ),
            chunkSamples * 4,
        )
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        stopRequested = false
        playing = true
        try {
            track.play()
            var offset = 0
            while (offset < samples.size && !stopRequested) {
                val n = min(chunkSamples, samples.size - offset)
                val written = track.write(samples, offset, n, AudioTrack.WRITE_BLOCKING)
                if (written <= 0) break
                offset += written
            }
        } finally {
            playing = false
            runCatching { track.stop() }
            track.release()
        }
    }

    companion object {
        private const val DEFAULT_CHUNK = 4096
    }
}
