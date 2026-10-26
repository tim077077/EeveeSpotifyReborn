package com.slidemill.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.PowerManager
import android.util.Log
import com.slidemill.core.Engine
import com.slidemill.core.Imaging
import com.slidemill.core.Obj
import com.slidemill.core.Platform
import java.io.File
import java.time.ZoneId

/** The engine's platform on the phone. */
class AndroidPlatform(private val context: Context) : Platform {
    override val name = "android"
    override val imaging: Imaging = AndroidImaging(context.assets)

    override fun encryptSecret(plain: String) = Secrets.encrypt(plain)
    override fun decryptSecret(stored: String) = Secrets.decrypt(stored)

    override fun listProfilePosts(username: String, max: Int): List<Obj> = LibraryScraper.profilePosts(context, username, max)

    override fun <T> awake(block: () -> T): T {
        val lock = context.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "slidemill:claude").apply { acquire(10 * 60_000L) }
        try {
            return block()
        } finally {
            if (lock.isHeld) lock.release()
        }
    }

    override fun defaultTimezone(): String = ZoneId.systemDefault().id

    override fun log(message: String) {
        Log.i("Slidemill", message)
    }
}

/** The one engine for the app process, connected to the relay and the TikTok tab. */
object Slidemill {
    const val CHANNEL = "running"
    @Volatile private var engine: Engine? = null

    fun engine(context: Context): Engine = engine ?: synchronized(this) {
        engine ?: run {
            val app = context.applicationContext
            val e = Engine(AndroidPlatform(app), File(app.filesDir, "slidemill"))
            e.tiktok = AndroidTikTok(app)
            e.start() // Claude's way in
            engine = e
            e
        }
    }

    fun channel(context: Context) {
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "Slidemill running", NotificationManager.IMPORTANCE_MIN))
    }
}
