package com.elthon.broexe.voice

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Wall-clock awareness for a model that has no clock.
 *
 * The local model's training cut-off makes "today", "now" and "this year" unknowable,
 * so the current date and time are injected into the system prompt on every turn. Pure
 * and injectable (instant, zone, locale) so the exact wording is unit-tested rather than
 * assumed — and so the prompt string only changes when the minute does.
 */
object TimeContext {

    fun nowLine(
        nowMillis: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.US,
    ): String {
        val fmt = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy 'at' HH:mm", locale)
        val stamp = Instant.ofEpochMilli(nowMillis).atZone(zone).format(fmt)
        return "The current date and time is $stamp."
    }

    /** The base system prompt with the current time appended. */
    fun systemPrompt(base: String, nowMillis: Long = System.currentTimeMillis()): String =
        base.trimEnd() + " " + nowLine(nowMillis)
}
