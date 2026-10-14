package com.elthon.broexe.voice

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.ApplicationExitInfo
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Writes the crash, then brings the app back instead of letting the OS kill it. */
private const val RESTART_MIN_INTERVAL_MS = 10_000L

/**
 * Records why the app died, so a crash on a device we cannot attach a debugger to
 * still leaves evidence behind.
 *
 * Two sources are captured:
 *   1. Java/Kotlin crashes — via a chained default uncaught-exception handler.
 *   2. Native crashes, ANRs and low-memory kills — via [ApplicationExitInfo]
 *      (API 30+), read on the next launch.
 *
 * The result is plain text in `filesDir/crashes/last_crash.txt`, shown in the
 * diagnostics panel and included in the "Copy report" output.
 */
object CrashReporter {

    private const val DIR = "crashes"
    private const val FILE_NAME = "last_crash.txt"
    private const val TRACE_NAME = "last_crash_trace.bin"
    private const val PREFS = "broexe_crash"
    private const val KEY_LAST_LAUNCH = "last_launch_ms"
    private const val KEY_LAST_RESTART = "last_restart_ms"

    /**
     * Installs the handler and folds in any exit recorded since the last launch.
     *
     * On a Java uncaught exception we persist the report, then relaunch the app on a
     * short alarm instead of handing the process to the OS crash dialog. The alarm is
     * rate-limited so a deterministic crash cannot spin the app in a restart loop — if
     * it fails twice within [RESTART_MIN_INTERVAL_MS] we fall back to the default
     * handler and let the system show the dialog.
     */
    fun install(context: Context) {
        val app = context.applicationContext
        runCatching { capturePreviousExit(app) }
        runCatching { markLaunch(app) }

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { write(app, formatUncaught(thread, throwable)) }
            val scheduled = runCatching { scheduleRestart(app) }.getOrDefault(false)
            if (scheduled) {
                runCatching {
                    android.os.Process.killProcess(android.os.Process.myPid())
                    kotlin.system.exitProcess(10)
                }
            } else {
                previous?.uncaughtException(thread, throwable)
            }
        }
    }

    /** Schedules one relaunch of the app; false when rate-limited. */
    private fun scheduleRestart(context: Context): Boolean {
        val now = System.currentTimeMillis()
        val last = prefs(context).getLong(KEY_LAST_RESTART, 0L)
        if (now - last < RESTART_MIN_INTERVAL_MS) return false
        prefs(context).edit().putLong(KEY_LAST_RESTART, now).apply()

        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: Intent().setClassName(context.packageName, "${context.packageName}.MainActivity")
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val pending = PendingIntent.getActivity(
            context,
            0,
            launch,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE,
        )
        val am = context.getSystemService(AlarmManager::class.java) ?: return false
        am.set(AlarmManager.RTC, System.currentTimeMillis() + 700, pending)
        return true
    }

    /** The most recent crash text, or null if nothing was recorded. */
    fun read(context: Context): String? = file(context).takeIf { it.isFile }?.readText()

    /** Absolute path to the preserved raw trace from the last native crash, if any. */
    fun rawTracePath(context: Context): String? =
        traceFile(context).takeIf { it.isFile }?.absolutePath

    fun clear(context: Context) {
        runCatching { file(context).delete() }
        runCatching { traceFile(context).delete() }
    }

    private fun file(context: Context) = File(File(context.filesDir, DIR), FILE_NAME)

    private fun traceFile(context: Context) = File(File(context.filesDir, DIR), TRACE_NAME)

    private fun write(context: Context, text: String) {
        val f = file(context)
        f.parentFile?.mkdirs()
        f.writeText(text)
    }

    private fun formatUncaught(thread: Thread, throwable: Throwable): String = buildString {
        append("Bro.exe crash report\n")
        append("type=uncaught-exception\n")
        append("time=").append(stamp(System.currentTimeMillis())).append('\n')
        append("thread=").append(thread.name).append('\n')
        append("exception=").append(throwable.javaClass.name).append('\n')
        append("message=").append(throwable.message ?: "-").append('\n')
        append("-- stack --\n")
        append(throwable.stackTraceToString())
    }

    private fun capturePreviousExit(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val am = context.getSystemService(ActivityManager::class.java) ?: return
        val since = prefs(context).getLong(KEY_LAST_LAUNCH, 0L)
        val infos = runCatching { am.getHistoricalProcessExitReasons(null, 0, 8) }.getOrNull() ?: return

        val interesting = infos.filter { it.timestamp > since && isFailure(it.reason) }
            .maxByOrNull { it.timestamp } ?: return
        val raw = runCatching {
            interesting.traceInputStream?.use { it.readBytes() }
        }.getOrNull()

        val text = buildString {
            append("Bro.exe crash report\n")
            append("type=process-exit\n")
            append("reason=").append(reasonName(interesting.reason)).append('\n')
            append("time=").append(stamp(interesting.timestamp)).append('\n')
            append("timestamp_ms=").append(interesting.timestamp).append('\n')
            append("pid=").append(interesting.pid).append('\n')
            append("process=").append(interesting.processName ?: "-").append('\n')
            append("description=").append(interesting.description ?: "-").append('\n')
            append("status=").append(interesting.status).append('\n')
            append("trace_bytes=").append(raw?.size ?: 0).append('\n')
            if (raw != null && raw.isNotEmpty()) {
                append("trace_sha256=").append(sha256(raw)).append('\n')
            }
            append("-- recent exits --\n")
            infos.forEach {
                append("  ").append(stamp(it.timestamp))
                    .append(" reason=").append(reasonName(it.reason))
                    .append(" status=").append(it.status)
                    .append('\n')
            }

            val isNative = interesting.reason == ApplicationExitInfo.REASON_CRASH_NATIVE
            val analysis = if (isNative) TombstoneDecoder.analyze(raw) else null
            val tombstone = analysis?.tombstone

            if (tombstone != null) {
                append('\n')
                append(TombstoneDecoder.format(tombstone))
                val partial = analysis?.takeIf { it.isPartial() }
                if (partial != null) {
                    append("\nnote=PARTIAL tombstone decode: ").append(partial.error).append('\n')
                }
            } else {
                append("note=")
                append(
                    if (isNative)
                        "native crash trace could not be decoded as a Tombstone proto"
                    else
                        "exit reason is not a native crash; trace is a printable-strings scan"
                )
                append('\n')
                if (analysis != null) {
                    append("-- tombstone decode diagnostics --\n")
                    append("head=").append(analysis.headHex).append('\n')
                    analysis.error?.let {
                        append("error=").append(it)
                        if (analysis.errorOffset >= 0) append(" (at offset ").append(analysis.errorOffset).append(')')
                        append('\n')
                    }
                    append("truncated=").append(analysis.truncated).append('\n')
                    if (analysis.fields.isNotEmpty()) {
                        append("-- top-level proto fields --\n")
                        append(analysis.fields.joinToString("\n")).append('\n')
                    }
                }
                val strings = TombstoneDecoder.printableStrings(raw, max = 4000)
                if (strings.isEmpty()) {
                    append("(trace empty or not text)\n")
                } else {
                    append("-- printable strings (").append(strings.size).append(") --\n")
                    append(strings.joinToString("\n"))
                    append('\n')
                }
            }

            if (raw != null && raw.isNotEmpty()) {
                val saved = runCatching {
                    val f = traceFile(context)
                    f.parentFile?.mkdirs()
                    f.writeBytes(raw)
                    f.absolutePath
                }.getOrNull()
                if (saved != null) {
                    append("\nraw_trace=").append(saved).append(" (").append(raw.size).append(" bytes)\n")
                }
            }
        }

        // A Java uncaught-exception report carries a real stack trace, while a
        // REASON_CRASH exit record does not. When we auto-review after restarting from
        // such a crash, keep the richer report and only annotate it.
        val existing = runCatching { read(context) }.getOrNull()
        if (interesting.reason == ApplicationExitInfo.REASON_CRASH &&
            existing != null && existing.contains("type=uncaught-exception")
        ) {
            val merged = existing.trimEnd() + "\n\n-- process exit observed --\n" +
                "reason=" + reasonName(interesting.reason) + "\n" +
                "time=" + stamp(interesting.timestamp) + "\n" +
                "status=" + interesting.status + "\n"
            write(context, merged)
        } else {
            write(context, text)
        }
    }

    private fun markLaunch(context: Context) {
        prefs(context).edit().putLong(KEY_LAST_LAUNCH, System.currentTimeMillis()).apply()
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun isFailure(reason: Int): Boolean = when (reason) {
        ApplicationExitInfo.REASON_CRASH,
        ApplicationExitInfo.REASON_CRASH_NATIVE,
        ApplicationExitInfo.REASON_ANR,
        ApplicationExitInfo.REASON_LOW_MEMORY,
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
        -> true
        else -> false
    }

    private fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_EXIT_SELF -> "exit-self"
        ApplicationExitInfo.REASON_SIGNALED -> "signaled"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "low-memory"
        ApplicationExitInfo.REASON_CRASH -> "crash"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "crash-native"
        ApplicationExitInfo.REASON_ANR -> "anr"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "init-failure"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "permission-change"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "excessive-resource-usage"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "user-requested"
        ApplicationExitInfo.REASON_USER_STOPPED -> "user-stopped"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "dependency-died"
        ApplicationExitInfo.REASON_OTHER -> "other"
        else -> "reason-$reason"
    }

    private fun stamp(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(ms))
}
