package com.slidemill.app

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import kotlin.concurrent.thread

/**
 * Keeps Claude's way in open while the phone sleeps. With the screen off, Android stops the CPU, so
 * the relay socket's pings and its reconnect timer stop too, and the socket dies unnoticed. A
 * Doze-proof alarm every [INTERVAL_MS] and the network coming back each wake the phone briefly to
 * reconnect (Relay.ensureConnected) and revive the TikTok tabs.
 */
object Keepalive {
    private const val INTERVAL_MS = 10 * 60_000L // Doze allows an idle alarm about every 9 minutes
    private const val CHECK_MS = 30_000L
    private const val PREFS = "keepalive"

    fun unrestricted(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    /** The system's one-tap "let it run in the background" dialog (falls back to the settings list). */
    @SuppressLint("BatteryLife") // the whole point of the app is to answer Claude with the screen off
    fun askUnrestricted(activity: Activity) {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${activity.packageName}"))
        runCatching { activity.startActivity(direct) }
            .onFailure { runCatching { activity.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } }
    }

    /** Offer the dialog once per install, when it's still needed. */
    fun askOnce(activity: Activity) {
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (unrestricted(activity) || prefs.getBoolean("asked", false)) return
        prefs.edit().putBoolean("asked", true).apply()
        askUnrestricted(activity)
    }

    /** Arm the next wake-up (each one re-arms the next). */
    fun schedule(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(
            context, 0, Intent(context, KeepaliveReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val at = SystemClock.elapsedRealtime() + INTERVAL_MS
        if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
        else am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
    }

    /** Reconnect if needed and wake the TikTok tabs, holding the CPU up until the relay answers (≤30 s). */
    fun check(context: Context) {
        val app = context.applicationContext
        val lock = app.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "slidemill:keepalive").apply { acquire(CHECK_MS + 5_000) }
        thread(name = "keepalive", isDaemon = true) {
            try {
                val relay = Slidemill.engine(app).relay
                relay.ensureConnected()
                TikTokWeb.slots(app).forEach { TikTokWeb.of(it).wake(app) }
                val until = SystemClock.elapsedRealtime() + CHECK_MS
                while (!relay.connected && relay.url.isNotEmpty() && SystemClock.elapsedRealtime() < until) Thread.sleep(500)
            } catch (_: Exception) {
                // the next alarm tries again
            } finally {
                if (lock.isHeld) lock.release()
            }
        }
    }

    /** Reconnect the moment a network comes back (Wi-Fi ↔ mobile, or after Doze). */
    fun watchNetwork(context: Context): ConnectivityManager.NetworkCallback {
        val app = context.applicationContext
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = check(app)
        }
        runCatching { app.getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(cb) }
        return cb
    }

    fun unwatchNetwork(context: Context, cb: ConnectivityManager.NetworkCallback) {
        runCatching { context.applicationContext.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(cb) }
    }
}

/** The alarm's wake-up: check, then arm the next one. */
class KeepaliveReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Keepalive.check(context)
        Keepalive.schedule(context)
    }
}
