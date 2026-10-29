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
    val pinterest = Pinterest()
    val renderer = Renderer(platform.imaging, mediaDir)
    val studio = Studio(db, platform, mediaDir, imageGen, images, renderer, pinterest)
    val library = Library(db, images, platform).also { l ->
        l.via = { accounts.researchSlot() }
        l.researchTab = { accounts.researchSlot().let { s -> if (s == Accounts.NO_LOGIN) null else tabs[s.ifEmpty { TikTokTab.MAIN }] } }
    }
    val timing = Timing(db)
    val virality = Virality(db)
    val analytics = Analytics(db, platform).also { a -> a.profile = { user -> library.fetchProfile(user) } }
    val relay = Relay(this)

    /** The automatic comment replies: the reply log, and the pass that answers matching comments. */
    val replies = ReplyBook(db)
    val autoReplier = AutoReplier(this)

    /** The user's auto-reply keywords and caps, as the app's Auto-reply card saves them. */
    fun autoReplySettings(): AutoReplySettings = AutoReplySettings.fromJson(store.get(AutoReplySettings.KEY))

    fun saveAutoReply(settings: AutoReplySettings) = store.setMany(mapOf(AutoReplySettings.KEY to settings.toJson()))

    /** The phone's logged-in TikTok sessions, one per account, by [TikTokTab.id]. */
    val tabs = java.util.concurrent.ConcurrentHashMap<String, TikTokTab>()

    /** The main TikTok tab (the first account); null where there is none (tests set a fake). */
    var tiktok: TikTokTab?
        get() = tabs[TikTokTab.MAIN] ?: tabs.values.firstOrNull()
        set(v) {
            if (v == null) tabs.remove(TikTokTab.MAIN) else tabs[v.id] = v
        }

    val accounts = Accounts(this)

    /** Background work that outlives a tool call (slow renders, library imports). */
    val work = Executors.newCachedThreadPool { r -> Thread(r, "slidemill").apply { isDaemon = true } }

    /** Snapshots the user's TikTok numbers every few hours, so Analytics can show growth over time. */
    private val ticker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "slidemill-analytics").apply { isDaemon = true } }

    /** Answers matching comments on its own, on this phone, with nobody watching. */
    private val replier = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "slidemill-replies").apply { isDaemon = true } }

    init {
        studio.recoverInterrupted()
    }

    fun start() {
        relay.start()
        // Analytics: a wide, uneven interval (2.5 to 4 hours) instead of a metronome. A request that lands
        // every exactly-180-minutes is one of the easiest patterns to spot in a server's logs.
        ticker.scheduleWithFixedDelay({
            for (tab in tabs.values.toList()) {
                runCatching { platform.awake { analytics.refresh(tab) } }.onFailure { platform.log("analytics refresh of ${tab.id} skipped: ${it.message}") }
            }
        }, 2, 150 + (0..90L).random(), TimeUnit.MINUTES)
        // Every five minutes (plus a minute of jitter): one pass that reads what changed and answers at
        // most one comment. It does nothing while auto-reply is off, and the caps, the gap, the quiet
        // hours and the per-person cooldown inside AutoReplier stop it looking like a machine.
        val jitter = (0..60_000L).random()
        replier.scheduleWithFixedDelay({
            Thread.sleep(jitter)
            runCatching { platform.awake { autoReplier.runOnce() } }.onFailure { platform.log("auto-reply pass skipped: ${it.message}") }
        }, 45, 300, TimeUnit.SECONDS)
    }

    /** Slideshows posted from Slidemill, by TikTok post id: their slideshow id and predicted score. */
    fun predictedByPost(): Map<String, Obj> = db.tx {
        all("slideshows").filter { it.objOrNull("post")?.str("item_id").orEmpty().isNotEmpty() }.associate { s ->
            val hook = s.objOrNull("spec")?.objs("slides")?.firstOrNull()?.objs("text_items")?.joinToString(" / ") { it.str("text") }.orEmpty()
            s.objAt("post").str("item_id") to obj(
                "slideshow_id" to s.str("id"), "title" to s.str("title"), "hook" to hook,
                "predicted_score" to s.objOrNull("prediction")?.get("viral_potential"),
            )
        }
    }

    fun stop() {
        relay.stop()
        ticker.shutdownNow()
        replier.shutdownNow()
        work.shutdownNow()
    }
}
