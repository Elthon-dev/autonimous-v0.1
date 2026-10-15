package com.elthon.broexe

import android.app.Application
import com.elthon.broexe.voice.CrashReporter

/**
 * Bro.exe has no global startup work except crash capture: Voice Mode is started
 * explicitly by the user from the single switch, and its notification channel is
 * created by the foreground service itself.
 *
 * The crash reporter is installed unconditionally and cheaply — if the process
 * dies, we want to know exactly how the next time it starts.
 */
class BroExeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
    }
}
