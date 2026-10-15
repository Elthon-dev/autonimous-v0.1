package com.elthon.broexe.voice

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Foreground microphone service that owns the voice pipeline for the whole time
 * Voice Mode is ON. The persistent notification is both the honest "we are
 * listening" indicator and the only reliable stop control on ColorOS.
 *
 * Also hosts the standalone stage tests (`ACTION_TEST`) so they can borrow the
 * real engines while Voice Mode is OFF.
 *
 * Started only from the visible activity after RECORD_AUDIO is granted. Returns
 * START_NOT_STICKY: the microphone must never be resurrected behind the user's back.
 */
class VoiceService : Service() {

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, throwable ->
            // A coroutine blowing up must not take the process with it: record it and
            // let the service keep running so the user can read the log and retry.
            VoiceDiagnostics.onError(throwable.message ?: throwable.javaClass.simpleName)
        },
    )
    private var session: VoiceSession? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TEST -> {
                startTest(intent)
                return START_NOT_STICKY
            }
            else -> startVoice()
        }
        return START_NOT_STICKY
    }

    private fun startVoice() {
        if (session != null) return
        if (!hasMicPermission()) {
            _detail.value = "Microphone permission is needed."
            _state.value = VoiceState.ERROR
            stopSelf()
            return
        }

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(VoiceState.LOADING_AI.label()),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        )

        _state.value = VoiceState.LOADING_AI
        scope.launch {
            val paths = try {
                VoicePaths.ensureExtracted(this@VoiceService)
            } catch (e: InsufficientStorageException) {
                _detail.value = "Not enough storage: ${e.message}"
                VoiceDiagnostics.onError("Not enough storage: ${e.message}")
                _state.value = VoiceState.ERROR
                stopSelf()
                return@launch
            }

            VoiceDiagnostics.reset()
            VoiceDiagnostics.event("service", "voice mode on")

            val newSession = VoiceSession(
                context = this@VoiceService,
                paths = paths,
                onState = { state ->
                    _state.value = state
                    VoiceDiagnostics.setState(state)
                    updateNotification(state.label())
                },
                onDetail = { detail ->
                    _detail.value = detail
                    VoiceDiagnostics.setDetail(detail)
                },
            )
            session = newSession
            try {
                newSession.run()
            } catch (t: Throwable) {
                VoiceDiagnostics.onError(t.message ?: t.javaClass.simpleName)
            } finally {
                session = null
                stopSelf()
            }
        }
    }

    /**
     * Runs one standalone stage test. Voice Mode must be OFF (the UI disables the
     * buttons while it is on), so this never contends for the microphone.
     */
    private fun startTest(intent: Intent) {
        val name = intent.getStringExtra(EXTRA_TEST) ?: return
        val test = runCatching { VoiceTest.valueOf(name) }.getOrNull() ?: return

        if (!hasMicPermission()) {
            VoiceDiagnostics.onError("Microphone permission is needed for tests.")
            stopSelf()
            return
        }

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification("Test: ${test.name.lowercase()}"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        )

        // A live voice session or another test already owns the microphone.
        if (session != null || _testRunning) return

        _state.value = VoiceState.LOADING_AI
        _testRunning = true
        scope.launch {
            try {
                val paths = VoicePaths.ensureExtracted(this@VoiceService)
                VoiceSelfTest.run(test, this@VoiceService, paths)
            } catch (e: InsufficientStorageException) {
                VoiceDiagnostics.onError("Not enough storage: ${e.message}")
            } catch (e: Throwable) {
                VoiceDiagnostics.onError(e.message ?: "Test failed")
            } finally {
                _testRunning = false
                _state.value = VoiceState.OFF
                stopSelf()
            }
        }
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    override fun onDestroy() {
        session?.stop()
        session = null
        scope.cancel()
        _state.value = VoiceState.OFF
        super.onDestroy()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, VoiceService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Bro.exe")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "Stop", stopIntent)
            .build()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Bro.exe voice mode",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Keeps the voice listener running"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_START = "com.elthon.broexe.voice.START"
        const val ACTION_STOP = "com.elthon.broexe.voice.STOP"
        const val ACTION_TEST = "com.elthon.broexe.voice.TEST"
        private const val EXTRA_TEST = "com.elthon.broexe.voice.EXTRA_TEST"
        private const val CHANNEL_ID = "broexe_voice"
        private const val NOTIFICATION_ID = 1001

        @Volatile
        private var _testRunning = false

        private val _state = MutableStateFlow(VoiceState.OFF)
        val state: StateFlow<VoiceState> = _state.asStateFlow()

        private val _detail = MutableStateFlow<String?>(null)
        val detail: StateFlow<String?> = _detail.asStateFlow()

        fun start(context: Context) {
            val intent = Intent(context, VoiceService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, VoiceService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }

        fun test(context: Context, test: VoiceTest) {
            val intent = Intent(context, VoiceService::class.java)
                .setAction(ACTION_TEST)
                .putExtra(EXTRA_TEST, test.name)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
