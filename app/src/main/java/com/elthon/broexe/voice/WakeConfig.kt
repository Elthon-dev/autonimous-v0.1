package com.elthon.broexe.voice

import android.content.Context
import java.io.File

/**
 * Which wake triggers are live, and how they map onto the bundled `keywords.txt`.
 *
 * Triggers and their per-keyword boost/threshold are baked into `keywords.txt` by
 * `scripts/fetch_voice_assets.sh` (the BPE tokenizer only exists at build time).
 * At runtime we only toggle which of those entries are handed to the spotter, so
 * "Hey Bro" can be kept while "Bro" is muted, without regenerating any tokens.
 */
object WakeConfig {

    private const val PREFS = "broexe_wake"
    private const val KEY_DISABLED = "disabled_triggers"

    /** Every trigger the app knows about, read from the bundled keywords file. */
    fun all(keywordsFile: File): List<WakeKeyword> {
        val parsed = runCatching { WakeKeywords.parse(keywordsFile.readText()) }
            .getOrDefault(emptyList())
        return parsed.ifEmpty { WakeKeywords.fallback() }
    }

    /** The triggers actually handed to the spotter, given the user's toggles. */
    fun active(context: Context, keywordsFile: File): List<WakeKeyword> {
        val off = disabled(context)
        val enabled = all(keywordsFile).filter { it.phrase !in off }
        // Never leave the engine with nothing to listen for.
        return enabled.ifEmpty { WakeKeywords.fallback() }
    }

    fun disabled(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_DISABLED, emptySet())?.toSet() ?: emptySet()

    fun setEnabled(context: Context, phrase: String, enabled: Boolean) {
        val current = disabled(context).toMutableSet()
        if (enabled) current.remove(phrase) else current.add(phrase)
        prefs(context).edit().putStringSet(KEY_DISABLED, current).apply()
    }

    /**
     * Writes the enabled triggers to [target] in sherpa keyword-file syntax so the
     * spotter loads exactly those. Returns false if nothing could be written.
     */
    fun writeActive(target: File, keywords: List<WakeKeyword>): Boolean {
        if (keywords.isEmpty()) return false
        val body = WakeKeywords.render(keywords).replace("/", "\n")
        return runCatching {
            target.writeText(body + "\n")
            true
        }.getOrDefault(false)
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
