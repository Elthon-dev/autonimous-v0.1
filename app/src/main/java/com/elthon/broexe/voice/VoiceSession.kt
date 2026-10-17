package com.elthon.broexe.voice

import android.content.Context
import android.os.SystemClock
import com.elthon.broexe.ai.ActiveModel
import com.elthon.broexe.ai.ModelManager
import com.elthon.broexe.memory.FileMemoryStore
import com.elthon.broexe.memory.Memory
import com.elthon.broexe.memory.MemoryDirective
import com.elthon.broexe.memory.MemoryFlow
import com.elthon.broexe.memory.MemoryStore
import com.k2fsa.sherpa.onnx.OnlineStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** One captured, transcribed utterance ready to be judged. */
private data class Utterance(
    val id: String,
    val text: String,
    val confidence: Float,
    val startedAt: Long,
    val endedAt: Long,
    val speechMs: Long,
    val keywordHit: String? = null,
    val ehsDecision: EhsDecision? = null,
    val ehsClarity: Int = 0,
)

/**
 * The open-vocabulary voice loop.
 *
 * There is no wake-word gate any more. The pipeline is layered:
 *
 *  - **Stage A** — the mic is captured continuously; an energy [VoiceActivityDetector]
 *    and a bounded [AudioRingBuffer] (pre-roll) track speech without a model in the hot
 *    path.
 *  - **Stage B** — when speech starts, a long-lived streaming ASR stream is primed with
 *    the pre-roll and fed the utterance, producing partial logs and a final transcript
 *    plus confidence.
 *  - **Stage C** — [AddressingDetector] scores how likely the utterance is addressed to
 *    Bro from its *meaning*, not from a fixed phrase.
 *  - **Stage D** — [ActivationDecider] applies confidence thresholds, hysteresis and
 *    dedup and returns activate / wait / clarify / ignore.
 *  - **Stage E** — [ConversationSession] keeps the conversation open for follow-ups and
 *    closes it on inactivity; barge-in lets the user interrupt Bro mid-reply.
 *
 * Engines are leased conservatively: the ASR model stays resident during a voice
 * session, the chat model is loaded once and reused for every turn (not reloaded per
 * utterance) and only released when the session ends, and the TTS model only while
 * synthesizing.
 */
class VoiceSession(
    private val context: Context,
    private val paths: VoicePaths,
    private val onState: (VoiceState) -> Unit,
    private val onDetail: (String?) -> Unit,
) {

    private val audioIn = AudioInput()
    private val audioOut = AudioOutput()
    private val webSearch = WebSearch()

    private val modelManager by lazy { ModelManager.forContext(context, paths.llm) }

    /**
     * Persistent, retrievable user facts. Text-only and written solely on an
     * explicit "remember ..." command — never microphone audio or ambient speech.
     */
    private val memoryStore: MemoryStore by lazy { FileMemoryStore(File(context.filesDir, "memory.txt")) }

    /** The model chosen for this session, resolved once in [run]. */
    private var activeModel: ActiveModel? = null

    /** One responder reused for the whole session so the GGUF is never re-read per turn. */
    private var llm: LlmResponder? = null

    @Volatile
    private var running = true

    private var utteranceSeq = 0L

    fun stop() {
        running = false
        audioOut.stop()
        audioIn.stop()
    }

    suspend fun run() {
        var asr: CommandTranscriber? = null
        var hint: KeywordHint? = null
        var ehs: EhsGate? = null
        try {
            CrashReporter.markNativeInit(context, "asr")
            val transcriber = CommandTranscriber(paths)
            CrashReporter.clearNativeInit(context)
            asr = transcriber
            val config = DetectionConfigStore.load(context)
            activeModel = modelManager.activeModel()
            VoiceDiagnostics.event(
                "ai",
                activeModel?.let {
                    "active model=${it.model.id} file=${it.file.name}"
                } ?: "no chat model available",
            )
            val decider = ActivationDecider(
                highThreshold = config.highThreshold,
                ambiguousThreshold = config.ambiguousThreshold,
                ambiguousClarify = config.ambiguousClarify,
            )
            val session = ConversationSession(config.followUpMs, config.idleTimeoutMs)
            if (config.keywordHint) {
                hint = runCatching { KeywordHint(context, paths) }
                    .onFailure { VoiceDiagnostics.event("detect", "keyword hint unavailable: ${it.message}") }
                    .getOrNull()
            }

            if (config.ehsEnabled) {
                CrashReporter.markNativeInit(context, "vad")
                val gate = EhsGate(silero = EhsSilero.create(paths))
                CrashReporter.clearNativeInit(context)
                ehs = gate
                VoiceDiagnostics.event(
                    "ehs",
                    if (gate.usesSilero) "EHS on (energy + Silero VAD)" else "EHS on (energy only)",
                )
            } else {
                VoiceDiagnostics.event("ehs", "EHS off")
            }

            audioIn.start()
            VoiceDiagnostics.setEchoCancellation(audioIn.echoCancellationActive)
            VoiceDiagnostics.event(
                "stage",
                "detection ready (high=%.2f ambiguous=%.2f standalone=%s bargeIn=%s hint=%s)"
                    .format(
                        config.highThreshold, config.ambiguousThreshold,
                        config.standaloneBro, config.bargeIn, config.keywordHint,
                    ),
            )

            while (running) {
                try {
                    onDetail(null)
                    onState(if (session.isActive()) VoiceState.LISTENING else VoiceState.IDLE)
                    val utterance = awaitSpeech(transcriber, session, config, hint, ehs) ?: break
                    handleUtterance(utterance, transcriber, config, decider, session)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: InsufficientStorageException) {
                    throw e
                } catch (e: Throwable) {
                    if (!running) break
                    // A transient failure in one turn must not tear down voice mode.
                    onDetail(e.message ?: "Voice error")
                    VoiceDiagnostics.onError(e.message ?: "Voice error")
                    onState(VoiceState.ERROR)
                    delay(800)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: InsufficientStorageException) {
            onDetail("Not enough storage: ${e.message}")
            VoiceDiagnostics.onError("Not enough storage: ${e.message}")
            onState(VoiceState.ERROR)
        } catch (e: Throwable) {
            if (running) {
                onDetail(e.message ?: "Voice error")
                VoiceDiagnostics.onError(e.message ?: "Voice error")
                onState(VoiceState.ERROR)
            }
        } finally {
            runCatching { hint?.release() }
            runCatching { ehs?.release() }
            runCatching { audioIn.stop() }
            runCatching { audioIn.release() }
            runCatching { asr?.release() }
            // Session over: drop the chat model so its hundreds of MB are not pinned
            // while voice mode is off. Turns within a session reuse it.
            runCatching { llm?.release() }
            llm = null
            VoiceDiagnostics.event("ai", "chat model released (voice session ended)")
            onState(VoiceState.OFF)
        }
    }

    /**
     * Stage A+B: block until a complete utterance is captured and transcribed.
     *
     * The turn is held open across short mid-sentence pauses (see [UtteranceAssembler])
     * so one sentence is never split into fragments that are each judged — and mostly
     * dropped — on their own. Once a partial transcript already looks addressed to Bro,
     * the pause tolerance widens so the rest of the sentence is recovered. Returns null
     * only when [stop] was requested.
     */
    private fun awaitSpeech(
        asr: CommandTranscriber,
        session: ConversationSession,
        config: DetectionConfig,
        hint: KeywordHint?,
        ehs: EhsGate?,
    ): Utterance? {
        val vad = VoiceActivityDetector()
        val preRoll = AudioRingBuffer(PRE_ROLL_FRAMES)
        val assembler = UtteranceAssembler(
            minSpeechMs = MIN_SPEECH_MS,
            maxUtteranceMs = MAX_UTTERANCE_MS,
            continuationGraceMs = config.continuationGraceMs,
        )
        var stream: OnlineStream? = null
        var startedAt = 0L
        var lastPartialAt = 0L
        var lastPartial = ""
        var ehsBest: EhsDecision? = null
        var ehsClarity = 0

        try {
            while (running) {
                val frame = audioIn.readFrame()
                if (frame.isEmpty()) continue
                val now = SystemClock.elapsedRealtime()
                val level = audioLevel(frame)
                VoiceDiagnostics.onFrame(level, audioPeak(frame))
                hint?.feed(frame)

                if (ehs != null) {
                    val decision = ehs.onFrame(frame, coreWord = hint?.peek() != null)
                    if (ehs.lastClarity > ehsClarity) ehsClarity = ehs.lastClarity
                    if (ehsBest == null || decision.ordinal > ehsBest!!.ordinal) ehsBest = decision
                }

                if (!assembler.isCapturing) {
                    if (session.tick(now)) onState(VoiceState.IDLE)
                    preRoll.add(frame, now)
                    if (vad.process(level, now) == VadEvent.SPEECH_START) {
                        startedAt = now
                        assembler.begin(now)
                        lastPartial = ""
                        lastPartialAt = now
                        onState(VoiceState.SPEECH_CANDIDATE)
                        val s = asr.createStream()
                        stream = s
                        // Prime with the audio captured just before speech was confirmed.
                        for (f in preRoll.drain()) asr.accept(s, f)
                        VoiceDiagnostics.onSpeechCandidate(nextId(), 0)
                    }
                    continue
                }

                val active = stream ?: continue
                asr.accept(active, frame)
                if (vad.process(level, now) == VadEvent.SPEECH_START || vad.isSpeaking()) {
                    assembler.onVoice(now)
                }

                // Partial-driven early address: if the in-flight transcript already looks
                // addressed, give the user more time to finish the sentence.
                if (now - lastPartialAt >= PARTIAL_LOG_MS) {
                    lastPartialAt = now
                    val partial = asr.text(active)
                    if (partial.isNotBlank() && partial != lastPartial) {
                        lastPartial = partial
                        VoiceDiagnostics.event("asr~", partial)
                        val signal = AddressingDetector.evaluate(
                            partial,
                            provisionalContext(session, config, assembler, now, ehsBest, ehsClarity),
                        )
                        if (signal.score >= config.highThreshold) {
                            if (!assembler.graceExtended) {
                                VoiceDiagnostics.event(
                                    "detect",
                                    "addressed mid-utterance; waiting for the rest of the sentence",
                                )
                            }
                            assembler.extendGrace()
                        }
                    }
                }

                if (assembler.exceededMax(now)) {
                    return finishUtterance(asr, active, startedAt, now, assembler.speechMs, hint)
                        .copy(ehsDecision = ehsBest, ehsClarity = ehsClarity)
                }

                if (!vad.isSpeaking()) {
                    val complete = lastPartial.isNotBlank() && SemanticTurnEnd.looksComplete(lastPartial)
                    when (assembler.onPause(now, complete)) {
                        HoldDecision.FINALIZE ->
                            return finishUtterance(asr, active, startedAt, now, assembler.speechMs, hint)
                                .copy(ehsDecision = ehsBest, ehsClarity = ehsClarity)

                        HoldDecision.DISCARD -> {
                            runCatching { active.release() }
                            stream = null
                            assembler.reset()
                            vad.reset()
                            preRoll.clear()
                        }

                        HoldDecision.HOLD -> Unit
                    }
                }
            }
            return null
        } finally {
            runCatching { stream?.release() }
        }
    }

    /** Best-effort context for classifying an in-flight partial transcript. */
    private fun provisionalContext(
        session: ConversationSession,
        config: DetectionConfig,
        assembler: UtteranceAssembler,
        now: Long,
        ehsDecision: EhsDecision?,
        ehsClarity: Int,
    ): DetectionContext = DetectionContext(
        inSession = session.isActive(),
        msSinceAssistantSpoke = session.msSinceAssistantSpoke(now),
        followUpWindowMs = config.followUpMs,
        speechMs = assembler.speechMs,
        standaloneBro = config.standaloneBro,
        keywordHit = null,
        ehsDecision = ehsDecision,
        ehsClarity = ehsClarity,
    )

    private fun finishUtterance(
        asr: CommandTranscriber,
        stream: OnlineStream,
        startedAt: Long,
        endedAt: Long,
        speechMs: Long,
        hint: KeywordHint?,
    ): Utterance {
        val text = asr.text(stream).trim()
        val confidence = asr.confidence(stream)
        runCatching { stream.release() }
        val hintHit = hint?.poll() // consume any hint so it is not reused
        return Utterance(nextId(), text, confidence, startedAt, endedAt, speechMs, hintHit)
    }

    /** Stage C+D+E: classify, then act on the decision. */
    private suspend fun handleUtterance(
        utterance: Utterance,
        asr: CommandTranscriber,
        config: DetectionConfig,
        decider: ActivationDecider,
        session: ConversationSession,
    ) {
        onState(VoiceState.ACTIVATION_DECISION)
        val now = SystemClock.elapsedRealtime()
        VoiceDiagnostics.onAsr(utterance.text.ifBlank { "(nothing)" })
        utterance.ehsDecision?.let {
            VoiceDiagnostics.event("ehs", "${it.name.lowercase()} clarity=${utterance.ehsClarity}")
        }

        if (utterance.text.isBlank()) {
            VoiceDiagnostics.onUtteranceRejected(
                utterance.id, 0f, ConfidenceBand.LOW.name, "empty transcript",
            )
            return
        }

        val ctx = DetectionContext(
            inSession = session.isActive(),
            msSinceAssistantSpoke = session.msSinceAssistantSpoke(now),
            followUpWindowMs = config.followUpMs,
            speechMs = utterance.speechMs,
            asrConfidence = utterance.confidence,
            standaloneBro = config.standaloneBro,
            keywordHit = utterance.keywordHit,
            ehsDecision = utterance.ehsDecision,
            ehsClarity = utterance.ehsClarity,
        )
        val signal = AddressingDetector.evaluate(utterance.text, ctx)
        val result = decider.decide(signal, ctx, utterance.id, now)
        VoiceDiagnostics.event(
            "detect",
            "score %.2f (%s) -> %s: %s"
                .format(signal.score, result.band.name, result.decision.name, signal.reasonString()),
        )

        when (result.decision) {
            ActivationDecision.ACTIVATE -> {
                VoiceDiagnostics.onActivation(
                    utterance.id, signal.score, result.band.name, result.reason, utterance.text,
                )
                session.activate(now)
                handleCommand(utterance.text, asr, config, session)
            }
            ActivationDecision.CLARIFY -> {
                VoiceDiagnostics.onClarify(utterance.id, signal.score)
                session.activate(now)
                speakReply(CLARIFY_LINE, asr, session, config)
            }
            ActivationDecision.WAIT -> {
                VoiceDiagnostics.event("detect", "waiting: ${signal.reasonString()}")
            }
            ActivationDecision.IGNORE -> {
                VoiceDiagnostics.onUtteranceRejected(
                    utterance.id, signal.score, result.band.name, result.reason,
                )
            }
        }
    }

    private suspend fun handleCommand(
        command: String,
        asr: CommandTranscriber,
        config: DetectionConfig,
        session: ConversationSession,
    ) {
        val now = SystemClock.elapsedRealtime()
        session.userSpoke(now)
        val request = RequestExtractor.extract(command)
        if (request.isBlank()) {
            onDetail("Didn't catch that.")
            return
        }
        VoiceDiagnostics.onAsr(request)
        if (RequestExtractor.containsTrigger(command)) {
            VoiceDiagnostics.event("detect", "address phrase present in transcript")
        }

        // Memory commands are answered without the LLM; other turns get any
        // relevant remembered facts injected into the prompt below.
        val facts = when (val directive = MemoryFlow.handle(memoryStore, request, now)) {
            is MemoryDirective.Reply -> {
                VoiceDiagnostics.event("memory", "direct reply (${directive.text})")
                session.assistantThinking(SystemClock.elapsedRealtime())
                onState(VoiceState.THINKING)
                if (!running) return
                speakReply(directive.text, asr, session, config)
                return
            }

            is MemoryDirective.Ask -> directive.facts
        }
        if (facts.isNotEmpty()) {
            VoiceDiagnostics.event("memory", "recalled ${facts.size} fact(s) for this turn")
        }

        val searchQuery = SearchTrigger.detect(request)
        val results = if (searchQuery != null) {
            onState(VoiceState.SEARCHING)
            val found = runCatching { webSearch.search(searchQuery) }.getOrDefault(emptyList())
            if (found.isEmpty()) onDetail("No web results; answering locally.")
            found
        } else {
            null
        }

        onState(VoiceState.THINKING)
        session.assistantThinking(SystemClock.elapsedRealtime())
        val reply = withContext(Dispatchers.Default) { think(request, results, facts) }
        if (!running) return
        if (reply.isBlank()) {
            onDetail("The local AI produced no answer.")
            return
        }
        speakReply(reply, asr, session, config)
    }

    /**
     * Stage E: speak the reply, and while speaking watch the mic for barge-in.
     *
     * Playback runs on an IO thread; this loop keeps reading frames. If the user talks
     * over Bro, playback is stopped and the interrupting utterance is captured and
     * handled as a fresh turn — staying in the same session.
     */
    private suspend fun speakReply(
        text: String,
        asr: CommandTranscriber,
        session: ConversationSession,
        config: DetectionConfig,
    ) {
        CrashReporter.markNativeInit(context, "tts")
        val speaker = Speaker(paths)
        CrashReporter.clearNativeInit(context)
        val audio = try {
            speaker.synthesize(text)
        } finally {
            speaker.release()
        }
        if (audio.samples.isEmpty()) {
            VoiceDiagnostics.event("tts", "no audio produced")
            session.assistantDone(SystemClock.elapsedRealtime())
            return
        }

        VoiceDiagnostics.onReply(text)
        onState(VoiceState.SPEAKING)
        session.assistantSpeaking(SystemClock.elapsedRealtime())

        val captured = if (config.bargeIn) {
            playWithBargeIn(audio.samples, audio.sampleRate, asr, config)
        } else {
            audioOut.play(audio.samples, audio.sampleRate)
            null
        }

        session.assistantDone(SystemClock.elapsedRealtime())
        if (captured != null) {
            VoiceDiagnostics.onBargeIn()
            val decider = ActivationDecider(
                highThreshold = config.highThreshold,
                ambiguousThreshold = config.ambiguousThreshold,
                ambiguousClarify = config.ambiguousClarify,
            )
            handleUtterance(captured, asr, config, decider, session)
        }
    }

    private suspend fun playWithBargeIn(
        samples: FloatArray,
        sampleRate: Int,
        asr: CommandTranscriber,
        config: DetectionConfig,
    ): Utterance? = coroutineScope {
        val vad = VoiceActivityDetector(
            startRms = BARGE_START_RMS,
            endRms = BARGE_END_RMS,
            minSpeechMs = BARGE_MIN_SPEECH_MS,
            minSilenceMs = BARGE_MIN_SILENCE_MS,
        )
        val preRoll = AudioRingBuffer(PRE_ROLL_FRAMES)
        val assembler = UtteranceAssembler(
            minSpeechMs = BARGE_MIN_SPEECH_MS,
            maxUtteranceMs = MAX_UTTERANCE_MS,
            continuationGraceMs = config.continuationGraceMs,
        )
        var stream: OnlineStream? = null
        var startedAt = 0L

        val playJob = launch(Dispatchers.IO) { audioOut.play(samples, sampleRate) }
        try {
            // Keep reading after playback ends if a barge-in turn is still being assembled.
            while (running && (playJob.isActive || assembler.isCapturing)) {
                val frame = audioIn.readFrame()
                if (frame.isEmpty()) continue
                val now = SystemClock.elapsedRealtime()
                val level = audioLevel(frame)
                VoiceDiagnostics.onFrame(level, audioPeak(frame))

                if (!assembler.isCapturing) {
                    preRoll.add(frame, now)
                    if (vad.process(level, now) == VadEvent.SPEECH_START) {
                        startedAt = now
                        assembler.begin(now)
                        audioOut.stop()
                        onState(VoiceState.SPEECH_CANDIDATE)
                        val s = asr.createStream()
                        stream = s
                        for (f in preRoll.drain()) asr.accept(s, f)
                        VoiceDiagnostics.onSpeechCandidate(nextId(), 0)
                    }
                    continue
                }

                val active = stream ?: continue
                asr.accept(active, frame)
                if (vad.process(level, now) == VadEvent.SPEECH_START || vad.isSpeaking()) {
                    assembler.onVoice(now)
                }

                if (assembler.exceededMax(now)) {
                    return@coroutineScope finishUtterance(asr, active, startedAt, now, assembler.speechMs, null)
                }
                if (!vad.isSpeaking()) {
                    when (assembler.onPause(now)) {
                        HoldDecision.FINALIZE ->
                            return@coroutineScope finishUtterance(asr, active, startedAt, now, assembler.speechMs, null)

                        HoldDecision.DISCARD -> {
                            runCatching { active.release() }
                            stream = null
                            assembler.reset()
                            vad.reset()
                            preRoll.clear()
                        }

                        HoldDecision.HOLD -> Unit
                    }
                }
            }
        } finally {
            playJob.cancel()
            runCatching { stream?.release() }
        }
        null
    }

    private suspend fun think(
        question: String,
        results: List<SearchResult>?,
        facts: List<Memory> = emptyList(),
    ): String {
        val responder = llm ?: LlmResponder.get(context).also { llm = it }
        val active = activeModel
        if (active == null) {
            VoiceDiagnostics.event("ai", "no chat model available; cannot answer")
            return ""
        }
        val basePrompt = if (results != null) {
            LlmResponder.SEARCH_SYSTEM_PROMPT
        } else {
            LlmResponder.SYSTEM_PROMPT
        }
        val systemPrompt = TimeContext.systemPrompt(basePrompt)
        val outcome = try {
            responder.ensureLoaded(active.file, systemPrompt)
        } catch (t: Throwable) {
            VoiceDiagnostics.event("ai", "load FAILED: ${t.javaClass.simpleName}: ${t.message}")
            throw t
        }
        VoiceDiagnostics.event(
            "ai",
            "model=${active.model.id} file=${active.file.name} bytes=${active.file.length()} $outcome; answering...",
        )
        if (outcome == LoadOutcome.LOADED) {
            runCatching { responder.backendReport() }
                .onSuccess { VoiceDiagnostics.event("ai", "backends=$it") }
        }
        // Not unloaded here: the model stays resident for the next turn. It is freed
        // once, in run()'s finally, when the voice session ends.
        val prompt = if (results == null && facts.isEmpty()) {
            question
        } else {
            buildString {
                if (results != null) {
                    append("Web notes:\n")
                    append(LlmResponder.renderResults(results))
                    append("\n\n")
                }
                if (facts.isNotEmpty()) {
                    append("Facts you remember about the user:\n")
                    facts.forEach { append("- ").append(it.text).append('\n') }
                    append('\n')
                }
                append("Question: ")
                append(question)
            }
        }
        val draft = responder.answer(prompt, thinking = active.model.thinking)
        if (draft.isBlank() ||
            !ReflectionPolicy.shouldReflect(question, results != null, facts.isNotEmpty())
        ) {
            return draft
        }
        // Bounded self-review: exactly one extra decode, only for grounded/complex turns.
        VoiceDiagnostics.event("ai", "self-review pass")
        val improved = responder.answer(
            ReflectionPolicy.reviewPrompt(question, draft),
            thinking = active.model.thinking,
        )
        return improved.ifBlank { draft }
    }

    private fun nextId(): String = "u" + (++utteranceSeq)

    companion object {
        /** Hard cap so a stuck utterance can never pin the microphone forever. */
        const val MAX_UTTERANCE_MS = 12_000L

        /** Shortest voiced span that counts as a real utterance. */
        const val MIN_SPEECH_MS = 300L

        /** ~1.5 s of pre-trigger audio at 100 ms frames. */
        const val PRE_ROLL_FRAMES = 15

        private const val PARTIAL_LOG_MS = 500L

        private const val BARGE_START_RMS = 0.05f
        private const val BARGE_END_RMS = 0.03f
        private const val BARGE_MIN_SPEECH_MS = 350L
        private const val BARGE_MIN_SILENCE_MS = 500L

        private const val CLARIFY_LINE = "Did you mean to ask me something? Just say it and I'll help."
    }
}

/**
 * Optional keyword-spotter booster. When `DetectionConfig.keywordHint` is on, the KWS
 * model runs alongside the general pipeline purely to add a small confidence nudge when
 * a known phrase fires. It is never required, and voice mode works identically with it
 * off (the default).
 */
private class KeywordHint(context: Context, paths: VoicePaths) {
    private val detector: WakeWordDetector
    private val stream: OnlineStream
    private var lastHit: String? = null

    init {
        val triggers = WakeConfig.active(context, paths.keywords)
        WakeConfig.writeActive(paths.activeKeywords, triggers)
        CrashReporter.markNativeInit(context, "kws")
        detector = WakeWordDetector(paths)
        CrashReporter.clearNativeInit(context)
        stream = detector.createStream()
    }

    fun feed(frame: FloatArray) {
        detector.accept(stream, frame)
        val hit = detector.poll(stream)
        if (hit != null) lastHit = hit
    }

    fun poll(): String? {
        val hit = lastHit
        lastHit = null
        return hit
    }

    /** Non-consuming read of the most recent hit, for the EHS core-word signal. */
    fun peek(): String? = lastHit

    fun release() {
        runCatching { stream.release() }
        runCatching { detector.release() }
    }
}
