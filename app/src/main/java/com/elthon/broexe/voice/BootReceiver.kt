package com.elthon.broexe.voice

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Re-arms the listener after a reboot.
 *
 * Android 14 will not let us restart a microphone foreground service straight from
 * `BOOT_COMPLETED`, so we do the only honest thing: post a notification the user can
 * tap to resume. A notification tap counts as user interaction, which grants the
 * temporary background-start allowance the mic service needs.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON",
            -> Unit

            else -> return
        }
        if (!KeepAlive.autoStart(context)) return
        runCatching { postResumeNotification(context) }
    }

    private fun postResumeNotification(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Bro.exe startup",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Asks whether to resume listening after a restart"
                setShowBadge(true)
            },
        )

        val resume = PendingIntent.getForegroundService(
            context,
            0,
            Intent(context, VoiceService::class.java).setAction(VoiceService.ACTION_START),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Bro.exe is ready")
            .setContentText("Tap to resume listening.")
            .setContentIntent(resume)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
        // POST_NOTIFICATIONS may not be granted yet; a no-op is fine, never a crash.
        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification) }
    }

    private companion object {
        private const val CHANNEL_ID = "broexe_startup"
        private const val NOTIFICATION_ID = 1002
    }
}
