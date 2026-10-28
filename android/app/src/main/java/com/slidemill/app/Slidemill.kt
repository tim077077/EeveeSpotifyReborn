package com.slidemill.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
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

    override fun listProfilePosts(username: String, max: Int, via: String, known: Set<String>): List<Obj> = LibraryScraper.profilePosts(context, username, max, via, known)
    override fun searchPosts(query: String, max: Int, via: String): List<Obj> = LibraryScraper.searchPosts(context, query, max, via)

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
            TikTokWeb.slots(app).forEach { e.tabs[it] = AndroidTikTok(app, it) }
            e.start() // Claude's way in
            engine = e
            e
        }
    }

    fun channel(context: Context) {
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "Slidemill running", NotificationManager.IMPORTANCE_MIN))
    }

    private val TIKTOK_PACKAGES = listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill")

    /**
     * Open the TikTok app's photo editor with these slides (where the user picks a sound and posts),
     * with the caption on the clipboard to paste. Falls back to the share sheet if TikTok isn't installed.
     */
    fun shareToTikTok(context: Context, slides: List<File>, caption: String) {
        require(slides.isNotEmpty() && slides.all { it.isFile }) { "The slides are missing on this phone." }
        val uris = ArrayList<Uri>(slides.map { SlideProvider.uriFor(context, it) })
        if (caption.isNotEmpty()) {
            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("TikTok caption", caption))
        }
        val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "image/jpeg"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            clipData = ClipData.newRawUri("slides", uris[0]).also { c -> uris.drop(1).forEach { c.addItem(ClipData.Item(it)) } }
        }
        val pm = context.packageManager
        val pkg = TIKTOK_PACKAGES.firstOrNull { p -> runCatching { pm.getPackageInfo(p, 0) }.isSuccess }
        context.startActivity(if (pkg != null) Intent(send).setPackage(pkg) else Intent.createChooser(send, "Post with…").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
