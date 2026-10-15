package com.elthon.broexe.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.Locale

/** One timestamped line in the diagnostics log. */
data class DiagEvent(val at: Long, val tag: String, val message: String)

/**
 * A truthful snapshot of what the phone is actually doing right now.
 *
 * This exists because the old UI could only show a state label, which was set the
 * instant a loop was entered — it could not distinguish "capturing audio" from
 * "the microphone is returning silence". [DiagnosticsSnapshot.micFrames] and
 * [DiagnosticsSnapshot.micPeak] can.
 */
data class DiagnosticsSnapshot(
    val state: VoiceState = VoiceState.OFF,
    val detail: String? = null,
    val micFrames: Long = 0L,
    val micLevel: Float = 0f,
    val micPeak: Float = 0f,
    val wakeHits: Int = 0,
    val wakeCandidates: Int = 0,
    val wakeRejected: Int = 0,
    val lastTrigger: String? = null,
    val lastRejectReason: String? = null,
    val lastAsr: String? = null,
    val lastReply: String? = null,
    val lastError: String? = null,
    // --- open-vocabulary activation engine ---
    val vadStarts: Int = 0,
    val candidateUtterances: Int = 0,
    val rejectedUtterances: Int = 0,
    val activations: Int = 0,
    val clarifications: Int = 0,
    val bargeIns: Int = 0,
    val lastUtteranceId: String? = null,
    val lastConfidence: Float = 0f,
    val lastBand: String? = null,
    val lastActivationReason: String? = null,
    val echoCancellation: Boolean = false,
    val updatedAt: Long = 0L,
) {
    /** True once any frame has carried a meaningful peak. */
    val hasSignal: Boolean get() = micPeak > SILENCE_PEAK

    companion object {
        /** Below this peak the input is treated as silence. */
        const val SILENCE_PEAK = 0.01f
    }
}

private const val MAX_EVENTS = 80

/**
 * Process-wide diagnostics sink. Written from the audio/voice threads, read from
 * the UI. All mutations go through the atomic `StateFlow.update`.
 */
object VoiceDiagnostics {

    private val _snapshot = MutableStateFlow(DiagnosticsSnapshot())
    val snapshot: StateFlow<DiagnosticsSnapshot> = _snapshot.asStateFlow()

    private val _events = MutableStateFlow<List<DiagEvent>>(emptyList())
    val events: StateFlow<List<DiagEvent>> = _events.asStateFlow()

    fun reset() {
        _snapshot.value = DiagnosticsSnapshot()
        _events.value = emptyList()
    }

    /** Clears the capture counters (keeps the event log) so a fresh test reads clean. */
    fun beginCapture() =
        _snapshot.update {
            it.copy(
                micFrames = 0L,
                micLevel = 0f,
                micPeak = 0f,
                wakeCandidates = 0,
                wakeRejected = 0,
                vadStarts = 0,
                candidateUtterances = 0,
                rejectedUtterances = 0,
                activations = 0,
                clarifications = 0,
                bargeIns = 0,
                lastConfidence = 0f,
                lastBand = null,
                lastActivationReason = null,
                lastRejectReason = null,
                updatedAt = System.currentTimeMillis(),
            )
        }

    fun event(tag: String, message: String) {
        val e = DiagEvent(System.currentTimeMillis(), tag, message)
        _events.update { (it + e).takeLast(MAX_EVENTS) }
    }

    fun setState(state: VoiceState) =
        _snapshot.update { it.copy(state = state, updatedAt = System.currentTimeMillis()) }

    fun setDetail(detail: String?) =
        _snapshot.update { it.copy(detail = detail, updatedAt = System.currentTimeMillis()) }

    fun onFrame(level: Float, peak: Float) {
        _snapshot.update {
            it.copy(
                micFrames = it.micFrames + 1,
                micLevel = level,
                micPeak = maxOf(it.micPeak, peak),
                updatedAt = System.currentTimeMillis(),
            )
        }
    }

    fun onWake(keyword: String) {
        _snapshot.update {
            it.copy(
                wakeHits = it.wakeHits + 1,
                lastTrigger = WakeKeywords.friendly(keyword),
                updatedAt = System.currentTimeMillis(),
            )
        }
        event("wake", "confirmed \u201c${WakeKeywords.friendly(keyword)}\u201d")
    }

    /** A stage-1 detection that has not yet cleared the verifier. */
    fun onCandidate(candidate: WakeCandidate) {
        _snapshot.update {
            it.copy(wakeCandidates = it.wakeCandidates + 1, updatedAt = System.currentTimeMillis())
        }
        event("wake", "candidate \u201c${WakeKeywords.friendly(candidate.phrase)}\u201d")
    }

    /** A stage-1 detection the verifier threw away (phantom or too soon). */
    fun onReject(candidate: WakeCandidate, reason: String) {
        _snapshot.update {
            it.copy(
                wakeRejected = it.wakeRejected + 1,
                lastRejectReason = reason,
                updatedAt = System.currentTimeMillis(),
            )
        }
        event("wake", "rejected \u201c${WakeKeywords.friendly(candidate.phrase)}\u201d ($reason)")
    }

    fun onAsr(text: String) {
        _snapshot.update { it.copy(lastAsr = text, updatedAt = System.currentTimeMillis()) }
        event("asr", text)
    }

    fun setEchoCancellation(active: Boolean) =
        _snapshot.update { it.copy(echoCancellation = active, updatedAt = System.currentTimeMillis()) }

    /** Voice activity began; an utterance is being captured. */
    fun onSpeechCandidate(utteranceId: String, speechMs: Long) {
        _snapshot.update {
            it.copy(
                vadStarts = it.vadStarts + 1,
                candidateUtterances = it.candidateUtterances + 1,
                lastUtteranceId = utteranceId,
                updatedAt = System.currentTimeMillis(),
            )
        }
        event("detect", "candidate $utteranceId (${speechMs}ms speech)")
    }

    /** The classifier accepted this utterance as addressed to Bro. */
    fun onActivation(utteranceId: String, score: Float, band: String, reason: String, transcript: String) {
        _snapshot.update {
            it.copy(
                activations = it.activations + 1,
                lastUtteranceId = utteranceId,
                lastConfidence = score,
                lastBand = band,
                lastActivationReason = reason,
                updatedAt = System.currentTimeMillis(),
            )
        }
        event(
            "detect",
            "ACTIVATE $utteranceId [%s %.2f] %s \u201c%s\u201d".format(band, score, reason, transcript),
        )
    }

    /** The classifier rejected this utterance (not addressed to Bro). */
    fun onUtteranceRejected(utteranceId: String, score: Float, band: String, reason: String) {
        _snapshot.update {
            it.copy(
                rejectedUtterances = it.rejectedUtterances + 1,
                lastUtteranceId = utteranceId,
                lastConfidence = score,
                lastBand = band,
                lastRejectReason = reason,
                updatedAt = System.currentTimeMillis(),
            )
        }
        event("detect", "ignore $utteranceId [%s %.2f] %s".format(band, score, reason))
    }

    /** The classifier was unsure and asked a short clarification. */
    fun onClarify(utteranceId: String, score: Float) {
        _snapshot.update {
            it.copy(
                clarifications = it.clarifications + 1,
                lastUtteranceId = utteranceId,
                lastConfidence = score,
                updatedAt = System.currentTimeMillis(),
            )
        }
        event("detect", "clarify $utteranceId [%.2f]".format(score))
    }

    fun onBargeIn() {
        _snapshot.update { it.copy(bargeIns = it.bargeIns + 1, updatedAt = System.currentTimeMillis()) }
        event("detect", "barge-in: user spoke over Bro")
    }

    fun onReply(text: String) {
        _snapshot.update { it.copy(lastReply = text, updatedAt = System.currentTimeMillis()) }
        event("ai", text)
    }

    fun onError(message: String) {
        _snapshot.update { it.copy(lastError = message, updatedAt = System.currentTimeMillis()) }
        event("error", message)
    }

    /** Human/robot-readable report for the "Copy report" button. */
    fun report(crash: String? = null): String {
        val s = _snapshot.value
        val sb = StringBuilder()
        sb.append("Bro.exe diagnostics\n")
        sb.append("state=").append(s.state.name).append('\n')
        sb.append("detail=").append(s.detail ?: "-").append('\n')
        sb.append("micFrames=").append(s.micFrames).append('\n')
        sb.append(String.format(Locale.US, "micLevel=%.4f%n", s.micLevel))
        sb.append(String.format(Locale.US, "micPeak=%.4f%n", s.micPeak))
        sb.append("hasSignal=").append(s.hasSignal).append('\n')
        sb.append("wakeCandidates=").append(s.wakeCandidates).append('\n')
        sb.append("wakeRejected=").append(s.wakeRejected).append('\n')
        sb.append("wakeHits=").append(s.wakeHits).append('\n')
        sb.append("lastTrigger=").append(s.lastTrigger ?: "-").append('\n')
        sb.append("lastRejectReason=").append(s.lastRejectReason ?: "-").append('\n')
        sb.append("vadStarts=").append(s.vadStarts).append('\n')
        sb.append("candidateUtterances=").append(s.candidateUtterances).append('\n')
        sb.append("rejectedUtterances=").append(s.rejectedUtterances).append('\n')
        sb.append("activations=").append(s.activations).append('\n')
        sb.append("clarifications=").append(s.clarifications).append('\n')
        sb.append("bargeIns=").append(s.bargeIns).append('\n')
        sb.append("lastUtteranceId=").append(s.lastUtteranceId ?: "-").append('\n')
        sb.append(String.format(Locale.US, "lastConfidence=%.3f%n", s.lastConfidence))
        sb.append("lastBand=").append(s.lastBand ?: "-").append('\n')
        sb.append("lastActivationReason=").append(s.lastActivationReason ?: "-").append('\n')
        sb.append("echoCancellation=").append(s.echoCancellation).append('\n')
        sb.append("lastAsr=").append(s.lastAsr ?: "-").append('\n')
        sb.append("lastReply=").append(s.lastReply ?: "-").append('\n')
        sb.append("lastError=").append(s.lastError ?: "-").append('\n')
        sb.append(memoryLine()).append('\n')
        if (!crash.isNullOrBlank()) {
            sb.append("-- last crash --\n")
            sb.append(crash.trimEnd()).append('\n')
        }
        sb.append("-- events --\n")
        for (e in _events.value) {
            sb.append(fmtClock(e.at)).append(' ').append(e.tag).append(": ").append(e.message).append('\n')
        }
        return sb.toString()
    }

    /** Java-heap headroom, cheap enough to compute on every report. */
    fun memoryLine(): String {
        val rt = Runtime.getRuntime()
        val usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
        val maxMb = rt.maxMemory() / (1024 * 1024)
        return "javaHeapMb=$usedMb/$maxMb"
    }

    private fun fmtClock(at: Long): String {
        val sec = at / 1000
        return String.format(
            Locale.US, "%02d:%02d:%02d",
            (sec / 3600) % 24, (sec / 60) % 60, sec % 60,
        )
    }
}

/** Root-mean-square of a normalized float frame, in [0, 1]. */
fun audioLevel(frame: FloatArray): Float {
    if (frame.isEmpty()) return 0f
    var sum = 0.0
    for (x in frame) sum += (x * x).toDouble()
    return kotlin.math.sqrt(sum / frame.size).toFloat()
}

/** Peak absolute sample of a normalized float frame, in [0, 1]. */
fun audioPeak(frame: FloatArray): Float {
    var peak = 0f
    for (x in frame) {
        val a = kotlin.math.abs(x)
        if (a > peak) peak = a
    }
    return peak
}

/**
 * PCM16 (Android native little-endian short) -> normalized float in [-1, 1].
 *
 * Pure and android-free so it can be unit-tested without a device.
 */
internal fun pcm16ToFloat(src: ShortArray, count: Int): FloatArray =
    FloatArray(count) { src[it] / 32768.0f }
