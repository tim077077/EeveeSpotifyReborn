package com.slidemill.core

import java.io.File
import java.time.Instant
import java.time.ZoneId

/**
 * What the engine needs from the device it runs on. Android implements it with Canvas, MediaCodec,
 * the Keystore and notifications; tests implement it with Java2D and plain files.
 */
interface Platform {
    /** "android" on the phone; anything else elsewhere (the dashboard adapts to it). */
    val name: String

    fun now(): Instant = Instant.now()

    /** Environment fallback for settings (desktop/test only; the phone has none). */
    fun env(name: String): String? = null

    fun encryptSecret(plain: String): String
    fun decryptSecret(stored: String): String

    val imaging: Imaging

    /** Stitch JPEG frames into an MP4 at `target`. Throws with a readable message on failure. */
    fun exportVideo(frames: List<File>, secondsPerSlide: Double, target: File)

    /** Bundled files: the dashboard ("static/index.html") and fonts ("fonts/Anton-Regular.ttf"). */
    fun asset(path: String): ByteArray?

    /** Something the user should see outside the app (a post is ready to share, a post failed). */
    fun notify(notice: Notice) {}

    /**
     * Raw TikTok web `itemStruct`s for a public profile's latest posts, read by a logged-in TikTok page
     * on the device. Empty when the platform can't (the HTTP profile fetch still fills the stats).
     */
    fun listProfilePosts(username: String, max: Int): List<Obj> = emptyList()

    fun defaultTimezone(): String = ZoneId.systemDefault().id

    fun log(message: String) = println("slidemill: $message")
}

data class Notice(val kind: String, val title: String, val text: String, val postId: String? = null)

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
