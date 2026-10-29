package com.slidemill.core

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import javax.imageio.ImageIO

/** The phone's TikTok tab, faked: records what it was asked to save, answers like tiktok-inject.js. */
class FakeTikTok(var user: String = "tim", override val id: String = TikTokTab.MAIN) : TikTokTab {
    val saved = CopyOnWriteArrayList<Triple<String, String, List<File>>>()
    /** When set, saving fails with this (as TikTok refusing would). */
    @Volatile var refuse: String? = null
    @Volatile var delayMs = 0L

    override fun username() = user

    override fun saveDraft(title: String, caption: String, slides: List<File>): String {
        if (user.isEmpty()) throw DraftError("Not logged in to TikTok in the app.")
        Thread.sleep(delayMs)
        refuse?.let { throw DraftError("TikTok refused the draft: $it") }
        saved += Triple(title, caption, slides)
        return "7" + saved.size.toString().padStart(10, '0')
    }

    val published = CopyOnWriteArrayList<Triple<String, String, List<File>>>()

    val scheduledAt = CopyOnWriteArrayList<Long>()

    override fun publish(title: String, caption: String, slides: List<File>, sound: Boolean, at: Long): String {
        if (user.isEmpty()) throw DraftError("Not logged in to TikTok in the app.")
        Thread.sleep(delayMs)
        refuse?.let { throw DraftError("TikTok refused the post: $it") }
        published += Triple(title, caption, slides)
        scheduledAt += at
        return "75" + published.size.toString().padStart(17, '0')
    }

    /** TikTok Studio's content list; null = this tab can't list (falls back to the profile page). */
    @Volatile var myPosts: List<Obj>? = null

    override fun listMyPosts(max: Int): List<Obj>? = myPosts?.take(max)

    /** Other accounts' posts this tab's page can list directly, by secUid; null = it can't. */
    @Volatile var userPosts: Map<String, List<Obj>>? = null
    /** The known post ids each direct listing was given. */
    val knownGiven = CopyOnWriteArrayList<Set<String>>()

    override fun listUserPosts(secUid: String, max: Int, known: Set<String>): List<Obj>? {
        knownGiven += known
        return userPosts?.get(secUid)?.take(max)
    }

    /** Comments per post id, as the page's comment list returns them (cid, text, author, root_cid, …). */
    val commentsOf = java.util.concurrent.ConcurrentHashMap<String, MutableList<Obj>>()

    override fun comments(itemId: String, max: Int): List<Obj>? =
        commentsOf[itemId]?.take(max)?.map { it.copyObj() }

    /** Every reply the app sent: post id, the comment it answered, and what it said. */
    val replies = CopyOnWriteArrayList<Triple<String, String, String>>()

    /** When set, replying fails with this (as TikTok refusing would). */
    @Volatile var refuseReply: String? = null

    override fun replyToComment(itemId: String, cid: String, rootCid: String, text: String): String? {
        refuseReply?.let { throw DraftError("TikTok refused the reply: $it") }
        replies += Triple(itemId, cid, text)
        return "c9" + replies.size.toString().padStart(6, '0')
    }

    /** Usernames whose accounts are private. */
    @Volatile var privateUsers: Set<String> = emptySet()

    override fun isPrivate(username: String): Boolean? = if (username.isEmpty()) null else username in privateUsers
}

object TestKit {
    fun jpeg(w: Int = 300, h: Int = 400, color: Color = Color(200, 170, 190)): ByteArray {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        img.createGraphics().apply { this.color = color; fillRect(0, 0, w, h); dispose() }
        return ByteArrayOutputStream().also { ImageIO.write(img, "jpg", it) }.toByteArray()
    }

    fun engine(platform: JvmPlatform = JvmPlatform()): Engine {
        val dir = Files.createTempDirectory("slidemill").toFile()
        val e = Engine(platform, dir)
        e.imageGen.override = { _, _ -> jpeg(600, 800) }
        e.library.pause = {}
        return e
    }
}
