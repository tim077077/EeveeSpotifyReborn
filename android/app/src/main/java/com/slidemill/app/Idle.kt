package com.slidemill.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicInteger

/**
 * Closes what runs in the background once Claude is done: CLOSE_MS after the last call (and with the
 * Slidemill screen not showing), every tiktok.com page is closed. With wake-up push set up, the
 * foreground service and its 15-minute wake-ups stop too: the relay's push starts them again when
 * Claude calls. Without push they stay, since they're then the only way Claude can reach the phone.
 */
object Idle {
    private const val CLOSE_MS = 2 * 60_000L
    private val main = Handler(Looper.getMainLooper())
    private val calls = AtomicInteger()
    private lateinit var app: Context
    private val closeCheck = Runnable { closeIfIdle() }

    /**
     * A Claude call (or background job) starts: cancel any pending close. It doesn't restart the service:
     * the push does that, and a background job restarting it every few minutes would undo the close.
     */
    fun begin(context: Context) {
        app = context.applicationContext
        calls.incrementAndGet()
        main.removeCallbacks(closeCheck)
    }

    fun end(context: Context) {
        app = context.applicationContext
        if (calls.decrementAndGet() <= 0) { calls.set(0); later() }
    }

    /** Check again in CLOSE_MS (the screen was left, or a call ended). */
    fun later(context: Context? = null) {
        context?.let { app = it.applicationContext }
        main.removeCallbacks(closeCheck)
        main.postDelayed(closeCheck, CLOSE_MS)
    }

    fun cancel() = main.removeCallbacks(closeCheck)

    private fun closeIfIdle() {
        if (!::app.isInitialized || calls.get() > 0 || TikTokWeb.foreground) return
        if (TikTokWeb.inUse()) return later()
        TikTokWeb.closeAll()
        if (Push.ready(app)) {
            Keepalive.cancel(app)
            app.stopService(android.content.Intent(app, EngineService::class.java))
        }
    }
}
