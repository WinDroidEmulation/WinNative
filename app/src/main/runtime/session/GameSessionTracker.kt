package com.winlator.cmod.runtime.session

import java.util.concurrent.atomic.AtomicInteger

object GameSessionTracker {
    private val activeSessions = AtomicInteger(0)

    @JvmStatic
    fun onSessionStarted() {
        activeSessions.incrementAndGet()
    }

    @JvmStatic
    fun onSessionEnded() {
        activeSessions.updateAndGet { count -> if (count > 0) count - 1 else 0 }
    }

    @JvmStatic
    fun isSessionActive(): Boolean = activeSessions.get() > 0
}
