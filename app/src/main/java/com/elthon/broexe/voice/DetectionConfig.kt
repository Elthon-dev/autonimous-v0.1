package com.elthon.broexe.voice

import android.content.Context

/**
 * User-tunable knobs for the open-vocabulary activation engine.
 *
 * The defaults are the shipped policy: standalone "bro" works, high confidence
 * activates promptly, the middle band waits for more context rather than barging in,
 * and a conversation stays open for follow-ups. Every field is persisted so the
 * tuning trade-offs are real, not decorative.
 */
data class DetectionConfig(
    /** Allow a bare "bro"/"bruh" (no other words) to activate. */
    val standaloneBro: Boolean = true,
    /** Score at/above which we activate immediately, in [0, 1]. */
    val highThreshold: Float = 0.50f,
    /** Score at/above which the utterance is "ambiguous", in [0, 1]. */
    val ambiguousThreshold: Float = 0.35f,
    /** After Bro speaks, follow-ups this soon are treated as addressed. */
    val followUpMs: Long = 8_000L,
    /** A conversation with no activity this long closes back to idle. */
    val idleTimeoutMs: Long = 30_000L,
    /** Allow the user to interrupt Bro while it is speaking. */
    val bargeIn: Boolean = true,
    /** In the ambiguous band, ask a short clarification instead of waiting. */
    val ambiguousClarify: Boolean = false,
    /** Use a keyword-spotter hit as extra (non-decisive) evidence. */
    val keywordHint: Boolean = false,
) {
    /** Clamp out-of-range values that could come from corrupt prefs. */
    fun sanitized(): DetectionConfig = copy(
        highThreshold = highThreshold.coerceIn(0.05f, 0.99f),
        ambiguousThreshold = ambiguousThreshold.coerceIn(0.05f, 0.99f)
            .coerceAtMost(highThreshold.coerceIn(0.05f, 0.99f)),
        followUpMs = followUpMs.coerceIn(0L, 120_000L),
        idleTimeoutMs = idleTimeoutMs.coerceIn(2_000L, 600_000L),
    )

    companion object {
        val DEFAULT = DetectionConfig()
    }
}

/** Persists [DetectionConfig] in SharedPreferences. */
object DetectionConfigStore {

    private const val PREFS = "broexe_detection"
    private const val KEY_STANDALONE = "standalone_bro"
    private const val KEY_HIGH = "high_threshold"
    private const val KEY_AMBIGUOUS = "ambiguous_threshold"
    private const val KEY_FOLLOW_UP = "follow_up_ms"
    private const val KEY_IDLE = "idle_timeout_ms"
    private const val KEY_BARGE_IN = "barge_in"
    private const val KEY_CLARIFY = "ambiguous_clarify"
    private const val KEY_KEYWORD_HINT = "keyword_hint"

    fun load(context: Context): DetectionConfig {
        val p = prefs(context)
        val d = DetectionConfig.DEFAULT
        return DetectionConfig(
            standaloneBro = p.getBoolean(KEY_STANDALONE, d.standaloneBro),
            highThreshold = p.getFloat(KEY_HIGH, d.highThreshold),
            ambiguousThreshold = p.getFloat(KEY_AMBIGUOUS, d.ambiguousThreshold),
            followUpMs = p.getLong(KEY_FOLLOW_UP, d.followUpMs),
            idleTimeoutMs = p.getLong(KEY_IDLE, d.idleTimeoutMs),
            bargeIn = p.getBoolean(KEY_BARGE_IN, d.bargeIn),
            ambiguousClarify = p.getBoolean(KEY_CLARIFY, d.ambiguousClarify),
            keywordHint = p.getBoolean(KEY_KEYWORD_HINT, d.keywordHint),
        ).sanitized()
    }

    fun save(context: Context, config: DetectionConfig) {
        val c = config.sanitized()
        prefs(context).edit()
            .putBoolean(KEY_STANDALONE, c.standaloneBro)
            .putFloat(KEY_HIGH, c.highThreshold)
            .putFloat(KEY_AMBIGUOUS, c.ambiguousThreshold)
            .putLong(KEY_FOLLOW_UP, c.followUpMs)
            .putLong(KEY_IDLE, c.idleTimeoutMs)
            .putBoolean(KEY_BARGE_IN, c.bargeIn)
            .putBoolean(KEY_CLARIFY, c.ambiguousClarify)
            .putBoolean(KEY_KEYWORD_HINT, c.keywordHint)
            .apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
