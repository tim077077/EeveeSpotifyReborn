package com.slidemill.core

import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Slidemill on the phone, built for Claude: storage, rendering, the research library, Claude-scored
 * predictions, and the relay that carries Claude's connector calls here. Claude decides everything;
 * the phone renders and saves drafts through its logged-in TikTok tab ([tiktok]).
 */
class Engine(val platform: Platform, val dataDir: File) {
    val mediaDir = File(dataDir, "media").also { it.mkdirs() }
    val db = Db(File(dataDir, "db"))
    val store = Store(db, platform)
    val imageGen = ImageGen(store)
    val images = Images(db, store, platform, mediaDir)
    val renderer = Renderer(platform.imaging, mediaDir)
    val studio = Studio(db, platform, mediaDir, imageGen, images, renderer)
    val library = Library(db, images, platform)
    val timing = Timing(db)
    val virality = Virality(db)
    val analytics = Analytics(db, platform)
    val relay = Relay(this)

    /** The phone's TikTok tab; null where there is none (tests set a fake). */
    @Volatile var tiktok: TikTokTab? = null

    /** Background work that outlives a tool call (slow renders, library imports). */
    val work = Executors.newCachedThreadPool { r -> Thread(r, "slidemill").apply { isDaemon = true } }

    /** Snapshots the user's TikTok numbers every few hours, so Analytics can show growth over time. */
    private val ticker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "slidemill-analytics").apply { isDaemon = true } }

    init {
        studio.recoverInterrupted()
    }

    fun start() {
        relay.start()
        ticker.scheduleWithFixedDelay({
            runCatching { platform.awake { analytics.refresh(tiktok) } }.onFailure { platform.log("analytics refresh skipped: ${it.message}") }
        }, 2, 180, TimeUnit.MINUTES)
    }

    /** Slideshows posted from Slidemill, by TikTok post id: their slideshow id and predicted score. */
    fun predictedByPost(): Map<String, Obj> = db.tx {
        all("slideshows").filter { it.objOrNull("post")?.str("item_id").orEmpty().isNotEmpty() }.associate { s ->
            s.objAt("post").str("item_id") to obj("slideshow_id" to s.str("id"), "title" to s.str("title"), "predicted_score" to s.objOrNull("prediction")?.get("viral_potential"))
        }
    }

    fun stop() {
        relay.stop()
        ticker.shutdownNow()
        work.shutdownNow()
    }
}
