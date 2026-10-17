package com.elthon.broexe.voice

import android.content.Context
import android.os.SystemClock
import com.elthon.broexe.ai.ModelManager
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
            VoiceTest.ASR -> asr(context, paths, seconds = 8)
            VoiceTest.AI -> ai(context, paths, CANNED_QUESTION)
            VoiceTest.TTS -> tts(context, paths, CANNED_LINE)
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
        CrashReporter.markNativeInit(context, "kws")
        val detector = WakeWordDetector(paths)
        CrashReporter.clearNativeInit(context)
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

    private fun asr(context: Context, paths: VoicePaths, seconds: Int): String {
        val input = AudioInput()
        CrashReporter.markNativeInit(context, "asr")
        val transcriber = CommandTranscriber(paths)
        CrashReporter.clearNativeInit(context)
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
        CrashReporter.markNativeInit(context, "asr")
        val transcriber = CommandTranscriber(paths)
        CrashReporter.clearNativeInit(context)
        val vad = VoiceActivityDetector()
        val preRoll = AudioRingBuffer(15)
        CrashReporter.markNativeInit(context, "vad")
        val ehs = EhsGate(silero = EhsSilero.create(paths))
        CrashReporter.clearNativeInit(context)
        VoiceDiagnostics.event(
            "ehs",
            if (ehs.usesSilero) "EHS: energy + Silero VAD" else "EHS: energy only",
        )
        input.start()
        val end = SystemClock.elapsedRealtime() + seconds * 1000L
        var capturing = false
        var startedAt = 0L
        var stream: OnlineStream? = null
        var ehsBest: EhsDecision? = null
        var ehsClarity = 0
        try {
            while (SystemClock.elapsedRealtime() < end) {
                val f = input.readFrame()
                if (f.isEmpty()) continue
                val now = SystemClock.elapsedRealtime()
                val level = audioLevel(f)
                VoiceDiagnostics.onFrame(level, audioPeak(f))
                val decision = ehs.onFrame(f)
                if (ehs.lastClarity > ehsClarity) ehsClarity = ehs.lastClarity
                if (ehsBest == null || decision.ordinal > ehsBest!!.ordinal) ehsBest = decision
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
                            ehsDecision = ehsBest,
                            ehsClarity = ehsClarity,
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
            ehs.release()
        }
        VoiceDiagnostics.event("detect", "self-test: no utterance in ${seconds}s")
        return null
    }

    private suspend fun ai(context: Context, paths: VoicePaths, question: String): String {
        val llm = LlmResponder.get(context)
        val active = ModelManager.forContext(context, paths.llm).activeModel()
        if (active == null) {
            VoiceDiagnostics.event("ai", "no chat model available")
            return ""
        }
        VoiceDiagnostics.event("ai", "backends=${llm.backendReport()}")
        val outcome = try {
            llm.ensureLoaded(active.file, LlmResponder.SYSTEM_PROMPT)
        } catch (t: Throwable) {
            VoiceDiagnostics.event("ai", "load FAILED: ${t.javaClass.simpleName}: ${t.message}")
            throw t
        }
        VoiceDiagnostics.event("ai", "model=${active.model.id} $outcome; answering...")
        val reply = try {
            llm.answer(question)
        } finally {
            llm.release()
        }
        VoiceDiagnostics.onReply(reply.ifBlank { "(empty)" })
        return reply
    }

    private fun tts(context: Context, paths: VoicePaths, text: String) {
        CrashReporter.markNativeInit(context, "tts")
        val speaker = Speaker(paths)
        CrashReporter.clearNativeInit(context)
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
        tts(context, paths, reply)
    }

    private const val CANNED_QUESTION = "What is two plus two? Answer in one short sentence."
    private const val CANNED_LINE = "Bro is online and listening."
}
