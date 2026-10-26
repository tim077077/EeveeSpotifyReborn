package com.slidemill.core

import java.io.File
import java.time.Instant
import java.time.ZoneId

/**
 * What the engine needs from the device it runs on. Android implements it with Canvas and the
 * Keystore; tests implement it with Java2D and plain files.
 */
interface Platform {
    /** "android" on the phone. */
    val name: String

    fun now(): Instant = Instant.now()

    /** Environment fallback for settings (desktop/test only; the phone has none). */
    fun env(name: String): String? = null

    fun encryptSecret(plain: String): String
    fun decryptSecret(stored: String): String

    val imaging: Imaging

    /**
     * Raw TikTok web `itemStruct`s for a public profile's latest posts, read by a logged-in TikTok page
     * on the device. Empty when the platform can't (the HTTP profile fetch still fills the stats).
     */
    fun listProfilePosts(username: String, max: Int): List<Obj> = emptyList()

    /** Keep the device awake while `block` runs (Claude asked for something through the relay). */
    fun <T> awake(block: () -> T): T = block()

    fun defaultTimezone(): String = ZoneId.systemDefault().id

    fun log(message: String) = println("slidemill: $message")
}

/**
 * The phone's logged-in tiktok.com tab. Both calls block until TikTok answers (or a timeout) and
 * throw [DraftError] with a reason the user can act on.
 */
interface TikTokTab {
    /** The logged-in TikTok username; "" when nobody is logged in. */
    fun username(): String

    /** Upload the slides and save them to the user's TikTok Studio drafts. Returns TikTok's draft id. */
    fun saveDraft(title: String, caption: String, slides: List<File>): String

    /**
     * Upload the slides and publish them as a live photo post. With [sound], TikTok's top suggested
     * web-library sound is attached (silent if it has none). Returns the post's item id.
     */
    fun publish(title: String, caption: String, slides: List<File>, sound: Boolean): String
}

class DraftError(message: String) : RuntimeException(message)

/** Image decoding and drawing primitives; the slide layout itself lives in [Renderer]. */
interface Imaging {
    /** Decode any image, apply EXIF rotation, shrink to fit maxW x maxH, re-encode as JPEG. */
    fun normalize(data: ByteArray, maxW: Int, maxH: Int, quality: Int): NormalizedImage

    fun load(data: ByteArray): Picture

    /** `file` is a font file name under fonts/; `weight` sets a variable font's wght axis. */
    fun font(file: String, weight: Int?, sizePx: Int, cjk: Boolean): FontRef

    fun canvas(width: Int, height: Int): Canvas2D
}

class NormalizedImage(val jpeg: ByteArray, val width: Int, val height: Int)

interface Picture {
    val width: Int
    val height: Int
}

interface FontRef {
    val ascent: Float
    val descent: Float
    fun measure(text: String): Float
}

interface Canvas2D {
    /** Scale-to-fill a box (crop the overflow), vertical crop centered at `centerY`. */
    fun drawCover(picture: Picture, x: Int, y: Int, w: Int, h: Int, centerY: Double = 0.45)

    /** Heavily blurred cover of the whole canvas (background behind a letterboxed photo). */
    fun drawBlurredCover(picture: Picture)

    /** Scale-to-fit inside the canvas, centered. */
    fun drawContain(picture: Picture)

    fun verticalGradient(topRgb: Int, bottomRgb: Int)

    /** Fill the whole canvas with a (translucent) ARGB color. */
    fun fill(argb: Int)

    fun roundRect(left: Float, top: Float, right: Float, bottom: Float, radius: Float, argb: Int)

    /** Draw one line with its top-left at (x, top). `stroke` is the outline width outside the glyphs. */
    fun text(text: String, x: Float, top: Float, font: FontRef, fillRgb: Int, stroke: Float, strokeRgb: Int)

    fun jpeg(quality: Int): ByteArray
}
