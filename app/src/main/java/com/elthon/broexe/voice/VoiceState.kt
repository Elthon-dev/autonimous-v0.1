package com.elthon.broexe.voice

/**
 * User-visible voice pipeline states. Each maps to one honest status line — no
 * spinner that lies about what the phone is doing.
 *
 * The conversation states mirror the layered engine:
 *
 *     IDLE → SPEECH_CANDIDATE → ACTIVATION_DECISION → LISTENING →
 *     THINKING/SEARCHING → SPEAKING → LISTENING → … → IDLE (on inactivity)
 *
 * There is no wake-word state any more: activation is decided from the audio and
 * the transcript, not from matching a fixed phrase.
 */
enum class VoiceState {
    OFF,
    REQUESTING_PERMISSION,
    STARTING,
    LOADING_AI,

    /** Nothing happening; waiting for speech that looks addressed to Bro. */
    IDLE,

    /** Voice activity detected; the candidate utterance is being recorded. */
    SPEECH_CANDIDATE,

    /** Utterance ended; deciding whether the user is addressing Bro. */
    ACTIVATION_DECISION,

    /** Activated (or in an active session); listening to the user's words. */
    LISTENING,

    THINKING,
    SEARCHING,
    SPEAKING,
    ERROR,
    STOPPED_BY_SYSTEM;

    /** Short, truthful label for the single status line in the UI. */
    fun label(): String = when (this) {
        OFF -> "Voice Mode off"
        REQUESTING_PERMISSION -> "Requesting microphone permission"
        STARTING -> "Starting voice engine"
        LOADING_AI -> "Loading local AI"
        IDLE -> "Idle \u2014 listening for you"
        SPEECH_CANDIDATE -> "Hearing speech\u2026"
        ACTIVATION_DECISION -> "Deciding if you're talking to me"
        LISTENING -> "Listening to you"
        THINKING -> "Thinking"
        SEARCHING -> "Searching the web"
        SPEAKING -> "Speaking"
        ERROR -> "Recovering from an error"
        STOPPED_BY_SYSTEM -> "Voice Mode stopped by the system"
    }
}
