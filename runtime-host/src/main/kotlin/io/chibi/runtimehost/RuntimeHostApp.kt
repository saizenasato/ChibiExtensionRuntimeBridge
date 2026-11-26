package io.chibi.runtimehost

import android.app.Application

/**
 * Headless host entry point. The runtime is initialised lazily when a client
 * binds [ChibiRuntimeService]; the Application only provides an early hook so
 * crashes are attributed to the host process.
 */
class RuntimeHostApp : Application() {
    override fun onCreate() {
        super.onCreate()
        HostBridge.initialize(this)
    }
}
