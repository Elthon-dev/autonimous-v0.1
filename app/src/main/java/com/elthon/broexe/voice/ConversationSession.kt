package com.elthon.broexe.voice

/** Coarse phase of an open conversation. */
enum class SessionPhase { IDLE, LISTENING, THINKING, SPEAKING }

/**
 * Tracks whether a conversation is open so follow-up turns work without repeating a
 * wake phrase, and closes it after a quiet period.
 *
 * Pure and clock-driven (the caller passes `atMs`), so the multi-turn and timeout
 * behaviour is testable without threads or a device.
 *
 * Lifecycle: [activate] opens a session → [userSpoke] / [assistantThinking] /
 * [assistantSpeaking] / [assistantDone] move it through the turn → [tick] closes it
 * back to IDLE once nothing has happened for [idleTimeoutMs].
 */
class ConversationSession(
    private val followUpWindowMs: Long = DEFAULT_FOLLOW_UP_MS,
    private val idleTimeoutMs: Long = DEFAULT_IDLE_TIMEOUT_MS,
) {
    private var phase: SessionPhase = SessionPhase.IDLE
    private var lastInteractionAt = Long.MIN_VALUE
    private var lastAssistantSpokeAt = Long.MIN_VALUE
    private var lastUserSpokeAt = Long.MIN_VALUE
    private var turnCount = 0

    fun currentPhase(): SessionPhase = phase
    fun isActive(): Boolean = phase != SessionPhase.IDLE
    fun turns(): Int = turnCount

    /** Open (or refresh) a conversation after a successful activation. */
    fun activate(atMs: Long) {
        phase = SessionPhase.LISTENING
        lastInteractionAt = atMs
        if (turnCount == 0) turnCount = 1
    }

    fun userSpoke(atMs: Long) {
        lastUserSpokeAt = atMs
        lastInteractionAt = atMs
        phase = SessionPhase.LISTENING
    }

    fun assistantThinking(atMs: Long) {
        phase = SessionPhase.THINKING
        lastInteractionAt = atMs
    }

    fun assistantSpeaking(atMs: Long) {
        phase = SessionPhase.SPEAKING
        lastAssistantSpokeAt = atMs
        lastInteractionAt = atMs
    }

    /** Assistant finished its reply; go back to listening and count the turn. */
    fun assistantDone(atMs: Long) {
        phase = SessionPhase.LISTENING
        lastInteractionAt = atMs
        turnCount += 1
    }

    fun toIdle() {
        phase = SessionPhase.IDLE
        turnCount = 0
        lastInteractionAt = Long.MIN_VALUE
    }

    /**
     * Advance the clock. Returns true when the session closed because it went idle.
     */
    fun tick(atMs: Long): Boolean {
        if (phase == SessionPhase.IDLE) return false
        if (lastInteractionAt == Long.MIN_VALUE) return false
        if (phase == SessionPhase.SPEAKING) return false
        if (atMs - lastInteractionAt >= idleTimeoutMs) {
            toIdle()
            return true
        }
        return false
    }

    /** True while a user utterance may ride on the previous turn without a wake phrase. */
    fun inFollowUpWindow(atMs: Long): Boolean {
        if (phase == SessionPhase.IDLE) return false
        if (lastAssistantSpokeAt == Long.MIN_VALUE) return false
        return atMs - lastAssistantSpokeAt <= followUpWindowMs
    }

    fun msSinceAssistantSpoke(atMs: Long): Long =
        if (lastAssistantSpokeAt == Long.MIN_VALUE) Long.MAX_VALUE
        else (atMs - lastAssistantSpokeAt).coerceAtLeast(0L)

    fun msSinceInteraction(atMs: Long): Long =
        if (lastInteractionAt == Long.MIN_VALUE) Long.MAX_VALUE
        else (atMs - lastInteractionAt).coerceAtLeast(0L)

    companion object {
        const val DEFAULT_FOLLOW_UP_MS = 8000L
        const val DEFAULT_IDLE_TIMEOUT_MS = 30000L
    }
}
