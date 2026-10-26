package com.slidemill.app

import android.app.AlarmManager
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import com.slidemill.core.Engine
import com.slidemill.core.Obj
import com.slidemill.core.PosterHooks
import com.slidemill.core.bool
import com.slidemill.core.obj
import com.slidemill.core.str
import com.slidemill.core.strings
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * Keeps Slidemill working while the app is closed: runs the engine's clock (scheduled slideshows,
 * "ready to post" notifications, analytics) and works the queue from the TikTok tab's logged-in
 * session, one post at a time within the daily cap and gap: drafts always, public posts only with
 * auto-posting on.
 */
class EngineService : Service() {
    companion object {
        private const val NOTIFICATION_ID = 1
        private const val TICK_MS = 30_000L
        private const val JOB_TIMEOUT_MS = 12 * 60_000L

        @Volatile var status: String = "Not running"
            private set
        @Volatile var running = false
            private set
        @Volatile var username: String = ""
            private set

        fun start(context: Context) {
            context.startForegroundService(Intent(context, EngineService::class.java))
        }

        fun checkNow(context: Context) {
            context.startForegroundService(Intent(context, EngineService::class.java).setAction("check"))
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var engine: Engine
    @Volatile private var busy = false
    private var lastPoll = 0L
    private var wakeLock: PowerManager.WakeLock? = null

    private val loop = object : Runnable {
        override fun run() {
            tick()
            main.postDelayed(this, TICK_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        Notifications.channels(this)
        val n = notification("Starting…")
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTIFICATION_ID, n)
        running = true
        engine = Slidemill.engine(this)
        TikTokWeb.get(this) // load tiktok.com in the background
        main.postDelayed(loop, 3_000)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "check" -> main.post { poll(force = true) }
            "tick" -> main.post { tick() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        main.removeCallbacks(loop)
        running = false
        status = "Stopped"
        releaseWakeLock()
        super.onDestroy()
    }

    // ---- the clock ----------------------------------------------------------------------------

    private fun tick() {
        io.execute {
            runCatching { engine.tick() }.onFailure { engine.platform.log("tick failed: $it") }
            scheduleAlarm()
        }
        if (System.currentTimeMillis() - lastPoll >= 55_000) poll()
    }

    /**
     * Wake the phone for the next thing that must happen on time (Doze would otherwise stretch the
     * 30-second loop to many minutes): the next schedule's generation start or a post coming due.
     */
    private fun scheduleAlarm() {
        val next = runCatching { nextWakeup() }.getOrNull() ?: return
        val am = getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getForegroundService(
            this, 7, Intent(this, EngineService::class.java).setAction("tick"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
        }
    }

    private fun nextWakeup(): Long {
        val now = java.time.Instant.now()
        var best = now.plusSeconds(15 * 60) // heartbeat
        val autos = engine.db.tx { where("automations") { !it.bool("paused") }.map { it.toMutableMap() } }
        for (a in autos) {
            val zone = engine.scheduler.zoneOf(a)
            for (s in (a["schedules"] as? List<*>).orEmpty()) {
                val cron = (s as? Map<*, *>)?.get("cron")?.toString() ?: continue
                val early = com.slidemill.core.Cron.shiftEarlier(cron, com.slidemill.core.Cron.LEAD_MINUTES) ?: cron
                runCatching { com.slidemill.core.Cron.parse(early).next(now, zone) }.getOrNull()?.let { if (it < best) best = it }
            }
        }
        engine.db.tx {
            where("posts") { it.str("status") == "queued" && it["not_before"] is Long }.forEach {
                val t = java.time.Instant.ofEpochMilli(it["not_before"] as Long)
                if (t.isAfter(now) && t < best) best = t
            }
        }
        return best.toEpochMilli()
    }

    // ---- posting ------------------------------------------------------------------------------

    private fun poll(force: Boolean = false) {
        lastPoll = System.currentTimeMillis()
        if (busy) return
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
        if (force) setStatus("Checking…")
    }

    private fun claimAndPost(hello: JSONObject) {
        try {
            val user = hello.optString("username")
            username = user
            if (user.isEmpty()) return finish("Not logged in to TikTok: open the TikTok tab and log in")
            val res = engine.queue.claimNext(
                // The tab saves drafts to TikTok Studio itself.
                client = mapOf("version" to "android-3", "username" to user, "draft" to "1"),
                draftsOnly = !Prefs.autoPost, // drafts aren't public, so they don't wait for auto-posting
            )
            @Suppress("UNCHECKED_CAST")
            val job = res["job"] as? Obj
                ?: return finish("@$user · ${res.str("reason")}${if (Prefs.autoPost) "" else " · auto-posting off (drafts only)"}")
            val postId = job.str("id")
            val title = job.str("title")
            setStatus("Posting: $title")
            acquireWakeLock()

            val files = job.strings("rendered").map { File(engine.mediaDir, it) }
            val payload = JSONObject()
                .put("id", postId).put("slideshow_id", job.str("slideshow_id")).put("action", job.str("action", "publish"))
                .put("title", title).put("caption", job.str("caption")).put("sound", job.bool("sound", true))
                .put("images", JSONArray(TikTokWeb.serve(postId, files)))

            TikTokWeb.ask("job", JSONObject().put("job", payload), JOB_TIMEOUT_MS) { result ->
                io.execute {
                    try {
                        if (result == null) {
                            // No answer: don't record a failure (that could re-queue a post that still
                            // succeeds and post it twice). The claim is released after 20 minutes.
                            finish("Still waiting for TikTok to finish: $title")
                            return@execute
                        }
                        Prefs.saveResult(postId, result.toString())
                        report(postId, result)
                        Prefs.removeResult(postId)
                        finish(if (result.optBoolean("ok")) "✓ ${if (job.str("action") == "draft") "Saved to drafts" else "Posted"}: $title" else "✗ " + result.optString("error", "failed"))
                    } finally {
                        TikTokWeb.unserve(postId)
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
        engine.queue.recordResult(
            postId, r.optBoolean("ok"), r.optString("itemId"), r.optString("username"), r.optString("error"), r.optBoolean("unsupported"),
        )
    }

    private fun flushSavedResults() {
        for ((postId, json) in Prefs.savedResults()) {
            runCatching { report(postId, JSONObject(json)) }
            Prefs.removeResult(postId)
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
            if (running) getSystemService(android.app.NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
        }
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, Notifications.SERVICE)
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .setContentTitle("Slidemill")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun acquireWakeLock() {
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "slidemill:posting").apply { acquire(JOB_TIMEOUT_MS) }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }
}

/** What the engine asks of the app (status for the dashboard, waking the poster, sharing to TikTok). */
class AppHooks(private val context: Context) : PosterHooks {
    override fun status(): Obj = obj(
        "available" to true,
        "running" to EngineService.running,
        "auto_post" to Prefs.autoPost,
        "logged_in" to EngineService.username.isNotEmpty(),
        "username" to EngineService.username,
        "status" to EngineService.status,
    )

    override fun kick() {
        runCatching { EngineService.checkNow(context) } // drafts go out even with auto-posting off
    }

    override fun share(postId: String, slides: List<File>, caption: String) = Slidemill.shareToTikTok(context, postId, slides, caption)
}
