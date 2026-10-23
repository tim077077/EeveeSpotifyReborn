package com.slidemill.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.slidemill.core.Engine
import com.slidemill.core.Imaging
import com.slidemill.core.Notice
import com.slidemill.core.Obj
import com.slidemill.core.Platform
import java.io.File
import java.security.SecureRandom
import java.time.ZoneId

/** The engine's platform on the phone. */
class AndroidPlatform(private val context: Context) : Platform {
    override val name = "android"
    override val imaging: Imaging = AndroidImaging(context.assets)

    override fun encryptSecret(plain: String) = Secrets.encrypt(plain)
    override fun decryptSecret(stored: String) = Secrets.decrypt(stored)

    override fun exportVideo(frames: List<File>, secondsPerSlide: Double, target: File) = VideoEncoder.encode(frames, secondsPerSlide, target)

    override fun asset(path: String): ByteArray? = runCatching { context.assets.open(path).use { it.readBytes() } }.getOrNull()

    override fun notify(notice: Notice) = Notifications.show(context, notice)

    override fun listProfilePosts(username: String, max: Int): List<Obj> = LibraryScraper.profilePosts(context, username, max)

    override fun defaultTimezone(): String = ZoneId.systemDefault().id

    override fun log(message: String) {
        Log.i("Slidemill", message)
    }
}

/** The one engine for the app process, its local server, and the token the dashboard uses. */
object Slidemill {
    private const val PREFERRED_PORT = 47_821
    @Volatile private var engine: Engine? = null

    fun engine(context: Context): Engine = engine ?: synchronized(this) {
        engine ?: run {
            Prefs.init(context)
            val app = context.applicationContext
            val e = Engine(AndroidPlatform(app), File(app.filesDir, "slidemill"), token())
            try {
                e.startServer(PREFERRED_PORT)
            } catch (x: Exception) {
                e.startServer(0) // the preferred port is taken: any free one
            }
            e.poster = AppHooks(app)
            engine = e
            e
        }
    }

    /** Random per-install password for the local API (other apps on the phone can't use it). */
    private fun token(): String = Prefs.token.ifEmpty {
        val bytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
        bytes.joinToString("") { "%02x".format(it) }.also { Prefs.token = it }
    }

    fun dashboardUrl(context: Context): String {
        val e = engine(context)
        return "http://127.0.0.1:${e.port}/?t=${e.token}"
    }

    val TIKTOK_PACKAGES = listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill", "com.ss.android.ugc.aweme")

    /** Open TikTok's own editor with the slides; the caption goes to the clipboard to paste. */
    fun shareToTikTok(context: Context, postId: String, slides: List<File>, caption: String) {
        val uris = ArrayList<Uri>(slides.map { SlideProvider.uriFor(context, it) })
        if (caption.isNotEmpty()) {
            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("TikTok caption", caption))
        }
        val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "image/jpeg"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            putExtra(Intent.EXTRA_TEXT, caption)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            clipData = ClipData.newRawUri("slides", uris[0]).also { c -> uris.drop(1).forEach { c.addItem(ClipData.Item(it)) } }
        }
        val pm = context.packageManager
        val pkg = TIKTOK_PACKAGES.firstOrNull { p -> runCatching { pm.getPackageInfo(p, 0) }.isSuccess }
        if (pkg != null) {
            context.startActivity(Intent(send).setPackage(pkg))
        } else {
            context.startActivity(Intent.createChooser(send, "Post with…").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        Notifications.cancel(context, postId)
    }
}

object Notifications {
    const val POSTS = "posts"
    const val SERVICE = "poster"

    fun channels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(SERVICE, "Slidemill running", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(POSTS, "Posts ready", NotificationManager.IMPORTANCE_HIGH))
    }

    private fun idFor(postId: String?) = 1000 + ((postId ?: "").hashCode() and 0xFFFF)

    fun show(context: Context, notice: Notice) {
        channels(context)
        val intent = if (notice.kind == "share" && notice.postId != null) {
            Intent(context, ShareActivity::class.java).putExtra("post_id", notice.postId)
        } else Intent(context, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            context, idFor(notice.postId), intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(context, POSTS)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentTitle(notice.title)
            .setContentText(notice.text)
            .setStyle(Notification.BigTextStyle().bigText(notice.text))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(idFor(notice.postId), n) }
    }

    fun cancel(context: Context, postId: String) =
        context.getSystemService(NotificationManager::class.java).cancel(idFor(postId))
}
