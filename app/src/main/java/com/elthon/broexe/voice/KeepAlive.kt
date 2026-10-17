package com.elthon.broexe.voice

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * Small, honest helpers for staying alive on aggressive OEM builds (ColorOS and
 * friends) that quietly freeze idle apps.
 *
 * Nothing here is sneaky: the wake lock only exists while Voice Mode is genuinely on,
 * and the battery-optimization exemption is requested through the system dialog — we
 * never grant it ourselves.
 */
object KeepAlive {

    private const val PREFS = "broexe_keep_alive"
    private const val KEY_AUTO_START = "auto_start"

    /** Whether to re-arm the listener (via a tap-to-resume notification) after reboot. */
    fun autoStart(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_START, true)

    fun setAutoStart(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_START, value).apply()
    }

    /** False means the OS is allowed to doze us; true means we are on its exempt list. */
    fun isExemptFromBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(PowerManager::class.java) ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** Opens the system "ignore battery optimizations" dialog for this app. */
    fun requestBatteryExemption(context: Context) {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
