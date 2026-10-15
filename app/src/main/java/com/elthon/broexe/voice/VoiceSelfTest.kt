package com.elthon.broexe.voice

import android.content.Context
import android.os.SystemClock
import com.k2fsa.sherpa.onnx.OnlineStream
import java.util.Locale

/** The stages the diagnostics screen can exercise one at a time. */
enum class VoiceTest { MIC, WAKE, DETECT, ASR, AI, TTS, FULL }

/**
 * Standalone, honest stage tests. Each one drives the *real* engine — no canned
 * values, no shortcuts — and records its outcome in [VoiceDiagnostics]. If a stage
 * fails you can see exactly which one and why.
 *
 * These run on the service when Voice Mode is OFF, so they never fight the live loop.
 */
object VoiceSelfTest {

    suspend fun run(test: VoiceTest, context: Context, paths: VoicePaths) {
        VoiceDiagnostics.beginCapture()
        VoiceDiagnostics.event("test", "start ${test.name}")
        when (test) {
            VoiceTest.MIC -> mic(paths, seconds = 4)
            VoiceTest.WAKE -> wake(context, paths, seconds = 20)
            VoiceTest.DETECT -> detect(context, paths, seconds = 20)
            VoiceTest.ASR -> asr(paths, seconds = 8)
            VoiceTest.AI -> ai(context, paths, CANNED_QUESTION)
            VoiceTest.TTS -> tts(paths, CANNED_LINE)
            VoiceTest.FULL -> full(context, paths)
        }
        VoiceDiagnostics.event("test", "done ${test.name}")
    }

    // --- individual stages ----------------------------------------------------

    private fun mic(paths: VoicePaths, seconds: Int) {
        val input = AudioInput()
        input.start()
        val end = SystemClock.elapsedRealtime() + seconds * 1000L
        var frames = 0
        try {
            while (SystemClock.elapsedRealtime() < end) {
                val f = input.readFrame()
                if (f.isEmpty()) continue
                frames++
                VoiceDiagnostics.onFrame(audioLevel(f), audioPeak(f))
            }
        } finally {
            input.stop()
            input.release()
        }
        val peak = VoiceDiagnostics.snapshot.value.micPeak
        VoiceDiagnostics.event(
            "mic",
            String.format(
                Locale.US,
                "frames=%d peak=%.4f signal=%s",
                frames, peak, peak > DiagnosticsSnapshot.SILENCE_PEAK,
            ),
        )
    }

    private fun wake(context: Context, paths: VoicePaths, seconds: Int): Boolean {
        val input = AudioInput()
        val triggers = WakeConfig.active(context, paths.keywords)
        WakeConfig.writeActive(paths.activeKeywords, triggers)
        val detector = WakeWordDetector(paths)
        val stream = detector.createStream()
        val verifier = WakeVerifier()
        input.start()
        val end = SystemClock.elapsedRealtime() + seconds * 1000L
        var frames = 0
        try {
            while (SystemClock.elapsedRealtime() < end) {
                val f = input.readFrame()
                if (f.isEmpty()) continue
                frames++
                VoiceDiagnostics.onFrame(audioLevel(f), audioPeak(f))
                detector.accept(stream, f)
                val hit = detector.poll(stream) ?: continue
                val candidate = WakeCandidate(hit, SystemClock.elapsedRealtime(), audioPeak(f))
                VoiceDiagnostics.onCandidate(candidate)
                if (verifier.evaluate(candidate).accepted) {
                    VoiceDiagnostics.onWake(candidate.phrase)
                    return true
                }
            }
        } finally {
            runCatching { stream.release() }
            input.stop()
            input.release()
            detector.release()
        }
        VoiceDiagnostics.event("wake", "no hit in ${seconds}s (frames=$frames)")
        return false
    }

    private fun asr(paths: VoicePaths, seconds: Int): String {
        val input = AudioInput()
        val transcriber = CommandTranscriber(paths)
        val stream = transcriber.createStream()
        input.start()
        val end = SystemClock.elapsedRealtime() + seconds * 1000L
        var text = ""
        try {
            while (SystemClock.elapsedRealtime() < end) {
                val f = input.readFrame()
                if (f.isEmpty()) continue
                VoiceDiagnostics.onFrame(audioLevel(f), audioPeak(f))
                transcriber.accept(stream, f)
                val t = transcriber.text(stream)
                if (t.isNotBlank()) text = t
                if (transcriber.isEndpoint(stream) && text.isNotBlank()) break
            }
        } finally {
            input.stop()
            input.release()
            transcriber.release()
        }
        VoiceDiagnostics.onAsr(text.ifBlank { "(nothing)" })
        return text
    }

    /**
     * Captures one utterance with the VAD, transcribes it and runs the real
     * addressing classifier — the whole open-vocabulary front end, without the LLM.
     * Reports the transcript, score and decision honestly.
     */
    private fun detect(context: Context, paths: VoicePaths, seconds: Int): Pair<String, AddressingSignal>? {
        val input = AudioInput()
        val transcriber = CommandTranscriber(paths)
        val vad = VoiceActivityDetector()
        val preRoll = AudioRingBuffer(15)
        input.start()
        val end = SystemClock.elapsedRealtime() + seconds * 1000L
        var capturing = false
        var startedAt = 0L
        var stream: OnlineStream? = null
        try {
            while (SystemClock.elapsedRealtime() < end) {
                val f = input.readFrame()
                if (f.isEmpty()) continue
                val now = SystemClock.elapsedRealtime()
                val level = audioLevel(f)
                VoiceDiagnostics.onFrame(level, audioPeak(f))
                if (!capturing) {
                    preRoll.add(f, now)
                    if (vad.process(level, now) == VadEvent.SPEECH_START) {
                        capturing = true
                        startedAt = now
                        val s = transcriber.createStream()
                        stream = s
                        for (p in preRoll.drain()) transcriber.accept(s, p)
                        VoiceDiagnostics.onSpeechCandidate("self-test", 0)
                    }
                } else {
                    val s = stream!!
                    transcriber.accept(s, f)
                    val dur = vad.speechDurationMs(now)
                    if (vad.process(level, now) == VadEvent.SPEECH_END || now - startedAt > VoiceSession.MAX_UTTERANCE_MS) {
                        val text = transcriber.text(s).trim()
                        val conf = transcriber.confidence(s)
                        s.release()
                        stream = null
                        val ctx = DetectionContext(
                            speechMs = dur,
                            asrConfidence = conf,
                            standaloneBro = true,
                        )
                        val signal = AddressingDetector.evaluate(text, ctx)
                        val result = ActivationDecider().decide(signal, ctx, "self-test", now)
                        VoiceDiagnostics.onAsr(text.ifBlank { "(nothing)" })
                        VoiceDiagnostics.event(
                            "detect",
                            String.format(
                                Locale.US,
                                "self-test: \u201c%s\u201d score=%.2f (%s) -> %s [%s]",
                                text, signal.score, result.band.name, result.decision.name,
                                signal.reasonString(),
                            ),
                        )
                        return text to signal
                    }
                }
            }
        } finally {
            runCatching { stream?.release() }
            input.stop()
            input.release()
            transcriber.release()
        }
        VoiceDiagnostics.event("detect", "self-test: no utterance in ${seconds}s")
        return null
    }

    private suspend fun ai(context: Context, paths: VoicePaths, question: String): String {
        val llm = LlmResponder(context)
        VoiceDiagnostics.event("ai", "model=${paths.llm.name} bytes=${paths.llm.length()}")
        VoiceDiagnostics.event("ai", "backends=${llm.backendReport()}")
        VoiceDiagnostics.event("ai", "loading model...")
        try {
            llm.load(paths.llm, LlmResponder.SYSTEM_PROMPT)
        } catch (t: Throwable) {
            VoiceDiagnostics.event("ai", "load FAILED: ${t.javaClass.simpleName}: ${t.message}")
            throw t
        }
        VoiceDiagnostics.event("ai", "model ready; answering...")
        val reply = try {
            llm.answer(question)
        } finally {
            llm.unload()
        }
        VoiceDiagnostics.onReply(reply.ifBlank { "(empty)" })
        return reply
    }

    private fun tts(paths: VoicePaths, text: String) {
        val speaker = Speaker(paths)
        try {
            val audio = speaker.synthesize(text)
            if (audio.samples.isNotEmpty()) {
                AudioOutput().play(audio.samples, audio.sampleRate)
            }
            VoiceDiagnostics.event(
                "tts",
                "played ${audio.samples.size} samples @ ${audio.sampleRate}Hz",
            )
        } finally {
            speaker.release()
        }
    }

    // --- full loop ------------------------------------------------------------

    private suspend fun full(context: Context, paths: VoicePaths) {
        VoiceDiagnostics.event("full", "stage 1/4: detect (open-vocabulary, 20s)")
        val detected = detect(context, paths, seconds = 20)
        if (detected == null) {
            VoiceDiagnostics.event("full", "aborted: no utterance detected")
            return
        }
        val command = detected.first
        if (command.isBlank()) {
            VoiceDiagnostics.event("full", "aborted: nothing recognized")
            return
        }
        val result = ActivationDecider().decide(detected.second, DetectionContext(), "full-test", SystemClock.elapsedRealtime())
        VoiceDiagnostics.event("full", "stage 2/4: decision = ${result.decision.name}")
        if (result.decision == ActivationDecision.IGNORE || result.decision == ActivationDecision.WAIT) {
            VoiceDiagnostics.event("full", "aborted: not addressed to Bro (${result.reason})")
            return
        }
        VoiceDiagnostics.event("full", "stage 3/4: answer")
        val reply = ai(context, paths, command)
        if (reply.isBlank()) {
            VoiceDiagnostics.event("full", "aborted: empty answer")
            return
        }
        VoiceDiagnostics.event("full", "stage 4/4: speak")
        tts(paths, reply)
    }

    private const val CANNED_QUESTION = "What is two plus two? Answer in one short sentence."
    private const val CANNED_LINE = "Bro is online and listening."
}
