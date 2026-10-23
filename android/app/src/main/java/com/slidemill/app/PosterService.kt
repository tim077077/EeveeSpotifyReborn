package com.slidemill.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * Foreground service that does what the extension's background worker does: once a minute, ask
 * Slidemill for the next due post, download its slides, have the TikTok WebView post them (or
 * save a draft), and report back. Slidemill enforces the daily cap and the gap between posts.
 */
class PosterService : Service() {

    companion object {
        private const val CHANNEL = "poster"
        private const val NOTIFICATION_ID = 1
        private const val TICK_MS = 60_000L
        private const val JOB_TIMEOUT_MS = 12 * 60_000L

        @Volatile var status: String = "Not running"
            private set
        @Volatile var running = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, PosterService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }

        fun stop(context: Context) = context.stopService(Intent(context, PosterService::class.java))

        fun checkNow(context: Context) = context.startService(Intent(context, PosterService::class.java).setAction("check"))
    }

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    @Volatile private var busy = false
    private var wakeLock: PowerManager.WakeLock? = null

    private val tick = object : Runnable {
        override fun run() {
            poll()
            main.postDelayed(this, TICK_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        createChannel()
        val notification = buildNotification("Starting…")
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        running = true
        TikTokWeb.get(this) // load tiktok.com in the background
        main.postDelayed(tick, 5_000)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "check") main.post { poll() }
        return START_STICKY
    }

    override fun onDestroy() {
        main.removeCallbacks(tick)
        running = false
        setStatus("Stopped")
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    // ---- the loop ---------------------------------------------------------------------------

    private fun poll() {
        if (busy) return
        if (!Prefs.configured) return setStatus("Set the Slidemill address and password in the Poster tab")
        if (!Prefs.autoPost) return setStatus("Auto-posting is off")
        busy = true
        io.execute { flushSavedResults() }
        if (!TikTokWeb.pageReady) {
            busy = false
            return setStatus("Loading tiktok.com… (log in on the TikTok tab if you haven't)")
        }
        TikTokWeb.ask("hello", JSONObject(), 8_000) { hello ->
            if (hello == null || !hello.optBoolean("ready")) {
                busy = false
                TikTokWeb.reload()
                return@ask setStatus("TikTok tab didn't answer; reloading it")
            }
            io.execute { claimAndPost(hello) }
        }
    }

    private fun claimAndPost(hello: JSONObject) {
        try {
            val username = hello.optString("username")
            if (username.isEmpty()) return finish("Not logged in to TikTok: open the TikTok tab and log in")
            val q = "version=android-1.0&username=${Uri.encode(username)}&draft=${if (hello.optBoolean("draft")) 1 else 0}"
            val res = Http.api("GET", "/api/v1/extension/next?$q")
            val job = res.optJSONObject("job") ?: return finish("Connected as @$username · ${res.optString("reason")}")

            val postId = job.getString("id")
            val title = job.optString("title")
            setStatus((if (job.optString("action") == "draft") "Saving draft: " else "Posting: ") + title)
            acquireWakeLock()

            val dir = File(cacheDir, "slides/$postId").apply { mkdirs() }
            val urls = job.getJSONArray("images")
            val files = (0 until urls.length()).map { i ->
                File(dir, "${i + 1}.jpg").also { Http.download(urls.getString(i), it) }
            }
            job.put("images", JSONArray(TikTokWeb.serve(postId, files)))

            TikTokWeb.ask("job", JSONObject().put("job", job), JOB_TIMEOUT_MS) { result ->
                io.execute {
                    try {
                        if (result == null) {
                            // No answer: don't report a failure (that could re-queue a post that still
                            // succeeds and post it twice). Slidemill releases the claim after 20 min.
                            finish("Still waiting for TikTok to finish: $title")
                            return@execute
                        }
                        Prefs.saveResult(postId, result.toString())
                        report(postId, result)
                        Prefs.removeResult(postId)
                        finish(
                            if (result.optBoolean("ok")) (if (job.optString("action") == "draft") "✓ Saved draft: " else "✓ Posted: ") + title
                            else "✗ " + result.optString("error", "failed"),
                        )
                    } catch (e: Exception) {
                        finish("✗ Couldn't report to Slidemill: ${e.message} (will retry)")
                    } finally {
                        TikTokWeb.unserve(postId)
                        dir.deleteRecursively()
                        releaseWakeLock()
                    }
                }
            }
        } catch (e: Exception) {
            releaseWakeLock()
            finish("✗ ${e.message}")
        }
    }

    private fun report(postId: String, r: JSONObject) {
        try {
            Http.api(
                "POST", "/api/v1/extension/jobs/$postId/result",
                JSONObject()
                    .put("ok", r.optBoolean("ok"))
                    .put("item_id", r.optString("itemId"))
                    .put("username", r.optString("username"))
                    .put("error", r.optString("error"))
                    .put("unsupported", r.optBoolean("unsupported")),
            )
        } catch (e: Http.ApiError) {
            if (e.message?.contains("404") != true) throw e // 404: already recorded
        }
    }

    private fun flushSavedResults() {
        for ((postId, json) in Prefs.savedResults()) {
            runCatching { report(postId, JSONObject(json)) }.onSuccess { Prefs.removeResult(postId) }
        }
    }

    private fun finish(text: String) {
        busy = false
        setStatus(text)
    }

    // ---- status + notification ----------------------------------------------------------------

    private fun setStatus(text: String) {
        status = text
        main.post {
            if (running) getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL, "Slidemill poster", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        return builder
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .setContentTitle("Slidemill poster")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "slidemill:posting").apply { acquire(JOB_TIMEOUT_MS) }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }
}
