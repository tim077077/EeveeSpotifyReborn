package com.slidemill.core

import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.LocalTime

/** What the app's poster provides to the engine (the TikTok tab, the share sheet). */
interface PosterHooks {
    /** Shown in /status: whether auto-posting is on, the logged-in user, the last result. */
    fun status(): Obj

    /** Something was queued: try posting soon instead of waiting for the next tick. */
    fun kick() {}

    /** Open the TikTok app with these slides (the caption goes to the clipboard). */
    fun share(postId: String, slides: List<File>, caption: String)
}

/**
 * Slidemill's whole engine in one object: storage, AI, rendering, scheduling, the posting queue,
 * the library and the local API server. The Android service owns one; tests create their own.
 */
class Engine(val platform: Platform, val dataDir: File, val token: String) {
    val mediaDir = File(dataDir, "media").also { it.mkdirs() }
    val db = Db(File(dataDir, "db"))
    val store = Store(db, platform)
    val llm = Llm(store)
    val generator = Generator(llm)
    val imageGen = ImageGen(store, llm)
    val images = Images(db, store, platform, mediaDir)
    val renderer = Renderer(platform.imaging, mediaDir)
    val queue = PostQueue(db, store)
    val timing = Timing(db)
    val pipeline = Pipeline(db, platform, mediaDir, generator, imageGen, images, renderer, queue, timing)
    val library = Library(db, store, llm, images, platform, mediaDir, pipeline)
    val scheduler = Scheduler(db, platform, pipeline) { periodic(it) }

    @Volatile var poster: PosterHooks? = null
    private var api: Api? = null
    @Volatile private var lastMetrics: Instant? = null
    @Volatile private var lastLibraryDay: String? = null

    init {
        recoverInterrupted()
    }

    /** Rows left mid-flight by a killed process: fail generations, re-queue claimed posts. */
    private fun recoverInterrupted() = db.tx {
        for (s in where("slideshows") { it.str("status") in setOf("generating", "rendering", "queued") }) {
            s["status"] = "failed"
            s["error"] = "Interrupted (the app was closed while this was being made)."
        }
        for (j in where("jobs") { it.str("status") in setOf("queued", "running") }) {
            j["status"] = "failed"
            j["message"] = "Interrupted (the app was closed)."
        }
    }

    /** Start the local API server; returns the port it listens on. */
    fun startServer(port: Int = 0): Int {
        api?.let { return it.listeningPort }
        val server = Api(this, token, port)
        server.start(30_000, false)
        api = server
        return server.listeningPort
    }

    val port: Int get() = api?.listeningPort ?: -1

    fun stop() {
        api?.stop()
        api = null
        pipeline.shutdown()
    }

    /** Called every ~30 s by the host: scheduled runs, posts due for sharing, periodic refreshes. */
    fun tick(now: Instant = Db.clock()) {
        scheduler.tick(now)
    }

    private fun periodic(now: Instant) {
        for (post in queue.dueShares(now)) {
            val title = db.tx { get("slideshows", post.str("slideshow_id"))?.str("title") } ?: "Slideshow"
            platform.notify(Notice("share", "Ready to post: $title", "Tap to open it in TikTok, add a sound and post.", post.str("id")))
        }
        if (lastMetrics == null || Duration.between(lastMetrics, now) >= Duration.ofMinutes(10)) {
            lastMetrics = now
            pipeline.executor.submit { refreshMetrics() }
        }
        // Library profiles refresh once a day, after 04:17 local time.
        val local = now.atZone(Timing.zone(platform.defaultTimezone()))
        val day = local.toLocalDate().toString()
        if (local.toLocalTime() >= LocalTime.of(4, 17) && lastLibraryDay != day) {
            val stored = db.tx { get("state", "library")?.str("day") }
            lastLibraryDay = day
            if (stored != day) {
                db.tx { (get("state", "library") ?: insert("state", obj("id" to "library")))["day"] = day }
                pipeline.jobExecutor.submit { library.refreshAll() }
            }
        }
    }

    fun refreshMetrics() = pipeline.refreshPosts { library.fetchPost(it) }

    fun onQueued() {
        poster?.kick()
    }

    fun posterStatus(): Obj = poster?.status() ?: obj("available" to false)

    /** Open a post's slides in the TikTok app and mark it handed over. */
    fun sharePost(postId: String) {
        val (slides, caption) = db.tx {
            val post = get("posts", postId) ?: throw ApiError(404, "post not found", "NOT_FOUND")
            val show = get("slideshows", post.str("slideshow_id")) ?: throw ApiError(404, "slideshow not found", "NOT_FOUND")
            show.strings("rendered").map { File(mediaDir, it) } to listOf(show.str("caption"), show.str("description")).filter { it.isNotEmpty() }.joinToString("\n\n")
        }
        val hooks = poster ?: throw ApiError(422, "Sharing to the TikTok app works in the phone app only.", "VALIDATION_ERROR")
        hooks.share(postId, slides, caption)
        queue.markShared(postId)
    }

    /** Everything as a zip (database and media, without caches). */
    fun backup(): ByteArray = synchronized(db) {
        Api.zipDir(dataDir) { f -> f.path.contains("${File.separator}cache${File.separator}") || f.name.endsWith(".tmp") }
    }

    fun restore(zip: ByteArray) {
        val staging = File(dataDir.parentFile, "restore-${System.currentTimeMillis()}")
        try {
            Api.unzipInto(zip, staging)
            if (!File(staging, "db").isDirectory) throw ValidationError("That zip isn't a Slidemill backup.")
            synchronized(db) {
                File(dataDir, "db").listFiles()?.forEach { it.delete() }
                File(staging, "db").copyRecursively(File(dataDir, "db"), overwrite = true)
                File(staging, "media").takeIf { it.isDirectory }?.copyRecursively(mediaDir, overwrite = true)
                db.reload()
            }
        } finally {
            staging.deleteRecursively()
        }
    }
}
