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
    fun listProfilePosts(username: String, max: Int, via: String = "", known: Set<String> = emptySet()): List<Obj> = emptyList()

    /**
     * Raw TikTok web `itemStruct`s from TikTok's search for [query], read the same way. Empty when it can't.
     * [via] is the account slot ([TikTokTab.id]) whose logged-in session reads TikTok ("" = the main one).
     */
    fun searchPosts(query: String, max: Int, via: String = ""): List<Obj> = emptyList()

    /** Keep the device awake while `block` runs (Claude asked for something through the relay). */
    fun <T> awake(block: () -> T): T = block()

    /** Whether Android lets the app run freely with the screen off (null = not a phone / unknown). */
    fun batteryUnrestricted(): Boolean? = null

    fun defaultTimezone(): String = ZoneId.systemDefault().id

    fun log(message: String) = println("slidemill: $message")
}

/**
 * The phone's logged-in tiktok.com tab. Both calls block until TikTok answers (or a timeout) and
 * throw [DraftError] with a reason the user can act on.
 */
interface TikTokTab {
    /** Which logged-in session this is: "main", or another account's slot. */
    val id: String get() = MAIN

    companion object {
        const val MAIN = "main"
    }

    /** The logged-in TikTok username; "" when nobody is logged in. */
    fun username(): String

    /** Upload the slides and save them to the user's TikTok Studio drafts. Returns TikTok's draft id. */
    fun saveDraft(title: String, caption: String, slides: List<File>): String

    /**
     * Upload the slides and publish them as a live photo post, or with [at] (unix seconds, 0 = now) hand
     * them to TikTok Studio's Schedule, so TikTok publishes them then. With [sound], TikTok's top suggested
     * web-library sound is attached (silent if it has none). Returns the post's item id.
     */
    fun publish(title: String, caption: String, slides: List<File>, sound: Boolean, at: Long = 0): String

    /**
     * The logged-in account's own published posts with their stats, newest first, as TikTok Studio's
     * Content page lists them: maps with id, desc, posted_at (unix s), views, likes, comments, shares,
     * saves, is_slideshow, slides (count). null when this tab can't list them.
     */
    fun listMyPosts(max: Int): List<Obj>? = null

    /**
     * Another account's newest public posts (raw itemStructs), asked by this tab's page with its own
     * fetch, stopping after the page that reaches a post in [known]. null when the page can't, so the
     * caller falls back to scrolling the profile.
     */
    fun listUserPosts(secUid: String, max: Int, known: Set<String>): List<Obj>? = null

    /**
     * The comments on one of this account's own posts, newest first, read by the logged-in page: maps
     * with cid, text, author, nickname, at (unix s), likes, creator (written by the account itself),
     * root_cid (set when the comment is a reply to another comment) and replies (how many answers it
     * has). null when the page can't read them.
     */
    fun comments(itemId: String, max: Int): List<Obj>? = null

    /**
     * Answer a comment publicly, as the logged-in account. [cid] is the comment being answered; pass
     * [rootCid] when that comment is itself a reply, so TikTok threads the answer under the right one.
     * Returns the new comment's id, "" when TikTok didn't say, null when the page couldn't ask.
     */
    fun replyToComment(itemId: String, cid: String, rootCid: String, text: String): String? = null

    /** Whether that username's account is private. null when it can't be told. */
    fun isPrivate(username: String): Boolean? = null
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
