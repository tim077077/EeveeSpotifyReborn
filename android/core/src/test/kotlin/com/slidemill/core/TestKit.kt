package com.slidemill.core

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import javax.imageio.ImageIO

/** The phone's TikTok tab, faked: records what it was asked to save, answers like tiktok-inject.js. */
class FakeTikTok(var user: String = "tim") : TikTokTab {
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

    override fun publish(title: String, caption: String, slides: List<File>, sound: Boolean): String {
        if (user.isEmpty()) throw DraftError("Not logged in to TikTok in the app.")
        Thread.sleep(delayMs)
        refuse?.let { throw DraftError("TikTok refused the post: $it") }
        published += Triple(title, caption, slides)
        return "75" + published.size.toString().padStart(17, '0')
    }
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
