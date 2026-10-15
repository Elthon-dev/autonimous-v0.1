package com.elthon.broexe.voice

/** One captured audio frame plus the monotonic time (ms) it was captured. */
data class AudioFrame(val atMs: Long, val samples: FloatArray)

/**
 * Bounded circular buffer of the most recent audio frames.
 *
 * The detector runs on the microphone stream, but the recognizer stream only starts
 * when voice activity is confirmed. Without this buffer the first syllable(s) of the
 * utterance ("…what's the weather" while "bro" is still settling) are already gone.
 * We keep the last N frames — a strict, fixed memory ceiling — so the recognizer can
 * be primed with the speech that immediately preceded the trigger. Audio older than
 * the window is evicted; nothing is retained indefinitely.
 */
class AudioRingBuffer(private val capacityFrames: Int) {

    init {
        require(capacityFrames > 0) { "capacityFrames must be positive" }
    }

    private val frames = ArrayDeque<AudioFrame>(capacityFrames)

    /** Append a frame, evicting the oldest once at capacity. Empty frames are ignored. */
    @Synchronized
    fun add(frame: AudioFrame) {
        if (frame.samples.isEmpty()) return
        if (frames.size == capacityFrames) frames.removeFirst()
        frames.addLast(AudioFrame(frame.atMs, frame.samples.copyOf()))
    }

    /** Convenience overload for callers that do not track time. */
    @Synchronized
    fun add(samples: FloatArray, atMs: Long = 0L) = add(AudioFrame(atMs, samples))

    /** Remove and return the buffered frames oldest-first, as raw samples. */
    @Synchronized
    fun drain(): List<FloatArray> = drainFrames().map { it.samples }

    /** Remove and return the buffered frames oldest-first, with timestamps. */
    @Synchronized
    fun drainFrames(): List<AudioFrame> {
        val out = frames.toList()
        frames.clear()
        return out
    }

    @Synchronized
    fun clear() {
        frames.clear()
    }

    @Synchronized
    fun size(): Int = frames.size

    /** Peak amplitude across the buffered frames, in [0, 1]. */
    @Synchronized
    fun peak(): Float {
        var peak = 0f
        for (frame in frames) {
            val p = audioPeak(frame.samples)
            if (p > peak) peak = p
        }
        return peak
    }

    /** Wall-clock span covered by the buffered frames, in ms (0 for < 2 frames). */
    @Synchronized
    fun spanMs(): Long {
        val first = frames.firstOrNull() ?: return 0L
        val last = frames.lastOrNull() ?: return 0L
        return (last.atMs - first.atMs).coerceAtLeast(0L)
    }
}
