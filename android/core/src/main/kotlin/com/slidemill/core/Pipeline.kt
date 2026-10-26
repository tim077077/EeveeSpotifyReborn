package com.slidemill.core

import java.io.File
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.Semaphore
import kotlin.random.Random

open class PipelineError(message: String) : RuntimeException(message)

/** generate -> source images -> render -> (video) -> queue -> track (port of app/pipeline.py). */
class Pipeline(
    private val db: Db,
    private val platform: Platform,
    private val mediaDir: File,
    private val generator: Generator,
    private val imageGen: ImageGen,
    private val images: Images,
    private val renderer: Renderer,
    private val queue: PostQueue,
    private val timing: Timing,
) {
    companion object {
        const val MAX_CONCURRENT_GENERATIONS = 3
    }

    /** Set by the engine; scores slideshows for pre-testing. */
    @Volatile var virality: Virality? = null

    val executor = Executors.newFixedThreadPool(8)
    // Long-running orchestration (studio batches, library scrapes) waits on `executor` work, so it
    // runs on its own pool to avoid starving it.
    val jobExecutor = Executors.newFixedThreadPool(4)
    private val generationSlots = Semaphore(MAX_CONCURRENT_GENERATIONS)

    /** True on the phone: there is no TikTok API account, every post goes through the app. */
    private val phone get() = platform.name == "android"

    fun log(msg: String) = platform.log(msg)

    // --- building the spec -------------------------------------------------------------

    fun specFromPlan(plan: Map<String, Any?>, imageSettings: Map<String, Any?>, aspectRatio: String? = null): Obj {
        val slides = plan.objs("slides").mapIndexed { i, ps ->
            var items: List<Any?> = ps.objs("text_items").map { Models.TEXT_ITEM.of(it) }
            if (imageSettings.bool("text_on_first_slide_only") && i > 0) items = emptyList()
            Models.SLIDE.of(obj(
                "role" to ps.str("role", "body"), "text_items" to items.toMutableList(),
                "image_query" to ps.str("image_query"), "image_prompt" to ps.str("image_prompt"),
            ))
        }
        return Models.SPEC.of(obj(
            "title" to plan.str("title", "Untitled slideshow"),
            "aspect_ratio" to (aspectRatio ?: imageSettings.str("aspect_ratio", "4:5")),
            "text_position" to plan.str("text_position", "center"),
            "overlay_body" to imageSettings.bool("overlay_body"),
            "overlay_hook" to imageSettings.bool("overlay_hook"),
            "overlay_cta" to imageSettings.bool("overlay_cta"),
            "background_opacity" to imageSettings.long("background_opacity", 30),
            "keep_original_aspect_ratio" to imageSettings.bool("keep_original_aspect_ratio"),
            "slides" to slides.take(20).toMutableList<Any?>(),
        ))
    }

    private fun sourceFor(s: Map<String, Any?>): String {
        val src = s.str("source", "auto")
        return if (src == "auto") (if (images.providers().isNotEmpty()) "stock" else "ai") else src
    }

    /** Fill each slide's background from explicit refs, collections, stock search or AI generation. */
    fun assignImages(spec: Obj, settings: Map<String, Any?>, explicit: List<String> = emptyList()) {
        val usedIds = mutableSetOf<String>()
        val usedUrls = mutableSetOf<String>()
        val orientation = images.orientationFor(spec.str("aspect_ratio"))
        val source = sourceFor(settings)
        @Suppress("UNCHECKED_CAST")
        val slides = spec["slides"] as MutableList<Any?>

        val hasCta = slides.any { (it as Obj).str("role") == "cta" }
        if (settings.bool("cta_slide") && settings.strOrNull("cta_collection_id") != null && !hasCta && slides.size < 20) {
            slides += Models.SLIDE.of(obj("role" to "cta"))
        }

        val needsAi = mutableListOf<Obj>()
        slides.forEachIndexed { i, s ->
            val slide = s as Obj
            if (i < explicit.size && explicit[i].isNotEmpty()) { slide["image"] = explicit[i]; return@forEachIndexed }
            if (slide.strOrNull("image") != null || slide.strings("images").isNotEmpty()) return@forEachIndexed
            val collectionId = when (slide.str("role")) {
                "hook" -> settings.strOrNull("hook_collection_id")
                "cta" -> settings.strOrNull("cta_collection_id")
                else -> null
            } ?: if (source == "collection") settings.strOrNull("collection_id") else null
            var picked: String? = null
            if (collectionId != null) {
                images.pickFromCollection(collectionId, usedIds)?.let { usedIds += it.str("id"); picked = it.str("path") }
            }
            if (picked == null && source == "stock") {
                picked = images.autoImage(slide.str("image_query").ifEmpty { spec.str("title") }, orientation, usedUrls)?.str("path")
            }
            if (picked == null && source in setOf("ai", "stock")) {
                // AI is also the fallback when stock search finds nothing.
                needsAi += slide
                return@forEachIndexed
            }
            slide["image"] = picked
        }
        if (needsAi.isNotEmpty()) {
            val pool = Executors.newFixedThreadPool(4)
            try {
                val futures = needsAi.map { slide -> pool.submit<String?> { aiBackground(slide, spec, settings.str("visual_style")) } }
                needsAi.forEachIndexed { i, slide -> slide["image"] = futures[i].get() }
            } finally {
                pool.shutdown()
            }
        }
    }

    private fun aiBackground(slide: Map<String, Any?>, spec: Map<String, Any?>, visualStyle: String): String? {
        val prompt = slide.str("image_prompt").ifEmpty { slide.str("image_query") }.ifEmpty { spec.str("title") }
        val data = try {
            imageGen.generate(prompt, spec.str("aspect_ratio", "4:5"), visualStyle)
        } catch (e: Exception) {
            log("AI background failed (${e.message}); slide keeps the plain background")
            return null
        }
        val (rel, w, h) = images.storeBytes(data, "library/ai")
        db.tx { insert("images", obj("collection_id" to null, "path" to rel, "source" to "ai", "source_url" to "", "query" to prompt.take(500), "width" to w, "height" to h, "use_count" to 1L)) }
        return rel
    }

    /** Render a slideshow row's spec (call inside no transaction; updates the row). */
    fun render(showId: String) {
        val spec = db.tx { get("slideshows", showId)?.objAt("spec")?.copyObj() } ?: throw PipelineError("slideshow not found")
        val outDir = File(mediaDir, "slideshows/$showId")
        if (outDir.exists()) outDir.deleteRecursively()
        val rendered = renderer.renderSlideshow(Models.SPEC.of(spec), outDir)
        db.tx {
            get("slideshows", showId)?.let {
                it["rendered"] = rendered.toMutableList<Any?>()
                it["video_path"] = ""
            }
        }
    }

    /** Stitch rendered slides into an MP4 (for Reels/Shorts). */
    fun exportVideo(showId: String, secondsPerSlide: Double = 3.0): String {
        val rendered = db.tx { get("slideshows", showId)?.strings("rendered") } ?: throw PipelineError("slideshow not found")
        if (rendered.isEmpty()) throw PipelineError("slideshow has not been rendered")
        val target = File(mediaDir, "slideshows/$showId/slideshow.mp4")
        try {
            platform.exportVideo(rendered.map { File(mediaDir, it) }, secondsPerSlide, target)
        } catch (e: Exception) {
            throw PipelineError("video export failed: ${e.message}")
        }
        val rel = "slideshows/$showId/slideshow.mp4"
        db.tx { get("slideshows", showId)?.set("video_path", rel) }
        return rel
    }

    // --- generation ---------------------------------------------------------------------

    fun recentTitles(tx: Db.Tx, automationId: String?, limit: Int = 30): List<String> =
        tx.where("slideshows") { it.str("status") == "completed" && (automationId == null || it.str("automation_id") == automationId) }
            .sortedByDescending { it.long("created_at") }.map { it.str("title") }.filter { it.isNotEmpty() }.take(limit)

    /** Insert a slideshow row to be filled by generateInto. Single requests are refused when slots are full. */
    fun createPending(tx: Db.Tx, prompt: String, automationId: String? = null, cronId: String? = null, queued: Boolean = false): Obj {
        if (!queued) {
            val active = tx.where("slideshows") { it.str("status") in setOf("generating", "rendering") }.size
            if (active >= MAX_CONCURRENT_GENERATIONS) throw ConcurrentLimit("$active slideshows already generating (limit $MAX_CONCURRENT_GENERATIONS).")
        }
        return tx.insert("slideshows", obj(
            "automation_id" to automationId, "created_by_cron_id" to cronId, "title" to "Untitled slideshow", "prompt" to prompt,
            "status" to if (queued) "queued" else "generating", "spec" to obj(), "caption" to "", "description" to "",
            "rendered" to arr(), "video_path" to "", "fingerprint" to "", "error" to "",
        ))
    }

    class ConcurrentLimit(message: String) : PipelineError(message)
    class PublishLimit(message: String) : PipelineError(message)

    private fun setStatus(id: String, status: String, error: String = "") = db.tx {
        get("slideshows", id)?.let {
            it["status"] = status
            if (error.isNotEmpty()) it["error"] = error.take(2000)
        }
    }

    /** Fill a pending slideshow: write text, source images, render. Runs in a worker thread. */
    fun generateInto(
        id: String,
        style: String = "",
        language: String = "English",
        imageSettings: Map<String, Any?>? = null,
        product: Map<String, Any?>? = null,
        explicitImages: List<String> = emptyList(),
        aspectRatio: String? = null,
        reference: String = "",
    ) {
        val settings = imageSettings ?: Models.IMAGE_SETTINGS.of(null)
        generationSlots.acquire()
        try {
            setStatus(id, "generating")
            val (topic, titles) = db.tx {
                val show = get("slideshows", id) ?: throw PipelineError("slideshow vanished")
                show.str("prompt") to recentTitles(this, show.strOrNull("automation_id"))
            }
            val plan = generator.generatePlan(topic, style, language, product, titles, visualStyle = settings.str("visual_style"), reference = reference)
            val spec = specFromPlan(plan, settings, aspectRatio)
            assignImages(spec, settings, explicitImages)
            db.tx {
                val show = get("slideshows", id) ?: return@tx
                show["title"] = plan.str("title")
                show["caption"] = plan.str("caption")
                show["description"] = plan.str("description")
                show["fingerprint"] = Generator.fingerprint(spec.objs("slides").firstOrNull()?.objs("text_items")?.joinToString(" ") { it.str("text") } ?: "")
                show["spec"] = spec
                show["status"] = "rendering"
            }
            render(id)
            setStatus(id, "completed")
        } catch (e: Exception) {
            log("generation failed for $id: $e")
            setStatus(id, "failed", e.message ?: e.toString())
        } finally {
            generationSlots.release()
        }
    }

    // --- studio: brief -> format -> finished slideshows -----------------------------------

    fun job(jobId: String, status: String? = null, message: String? = null, result: Map<String, Any?>? = null) = db.tx {
        val job = get("jobs", jobId) ?: return@tx
        status?.let { job["status"] = it }
        message?.let { job["message"] = it }
        result?.let { r -> job["result"] = job.objAt("result").also { it.putAll(r) } }
    }

    fun newJob(kind: String): String = db.tx { insert("jobs", obj("kind" to kind, "status" to "queued", "message" to "", "result" to obj())) }.str("id")

    /** Plan a content format from a brief, then generate `count` complete slideshows from it. */
    fun runStudio(jobId: String, req: Map<String, Any?>, schedule0: List<String>) {
        job(jobId, status = "running", message = "Planning the format…")
        try {
            val count = req.int("count", 3)
            val save = req.bool("save_as_automation")
            val language = req.strOrNull("language")
            val accountId = req.strOrNull("tiktok_account_id")
            val targetCountry = req.strOrNull("target_country")
            val browserAction = if (req.bool("via_browser") || phone) req.str("browser_action", "publish") else null
            // Extra hooks give a saved automation material for future runs.
            val campaign = generator.planCampaign(req.str("brief"), count + if (save) 10 else 0)
            val imageSettings = Models.IMAGE_SETTINGS.of(obj(
                "source" to req.str("image_source", "auto"), "visual_style" to campaign.str("visual_style"),
                "aspect_ratio" to (req.strOrNull("aspect_ratio") ?: campaign.str("aspect_ratio", "4:5")),
            ))
            var schedule = schedule0
            var product: Obj? = null
            var automationId: String? = null
            val ids = db.tx {
                req.strOrNull("product_id")?.let { pid -> get("products", pid)?.let { product = obj("name" to it.str("name"), "description" to it.str("description")) } }
                if (save) {
                    val a = obj(
                        "title" to campaign.str("name"), "tiktok_account_id" to accountId, "product_id" to req.strOrNull("product_id"),
                        "hooks" to campaign.strings("hooks").toMutableList<Any?>(), "style" to campaign.str("style"),
                        "language" to (language ?: campaign.str("language", "English")),
                        "post_settings" to Models.POST_SETTINGS.of(obj("auto_post" to (accountId != null))),
                        "image_settings" to imageSettings, "timezone" to "", "target_country" to "", "paused" to false, "favorite" to false,
                    )
                    if (targetCountry != null) {
                        val plan = timing.bestTimes(targetCountry, req.int("posts_per_day", 2), accountId)
                        a["timezone"] = plan.str("timezone")
                        a["target_country"] = plan.str("country")
                        schedule = plan.strings("crons")
                        val auto = req.bool("auto_publish")
                        a["post_settings"] = Models.POST_SETTINGS.of(obj(
                            "auto_post" to (accountId != null), "post_mode" to if (auto) "DIRECT_POST" else "MEDIA_UPLOAD",
                            "visibility" to (req.strOrNull("privacy_level") ?: "SELF_ONLY"), "consented" to auto, "allow_comments" to false,
                        ))
                    }
                    if (browserAction != null) {
                        a["post_settings"] = Models.POST_SETTINGS.of(obj("auto_post" to true, "post_mode" to "BROWSER", "browser_action" to browserAction))
                    }
                    a["schedules"] = schedule.map { obj("id" to Db.newId(), "cron" to it) }.toMutableList<Any?>()
                    automationId = insert("automations", a).str("id")
                }
                campaign.strings("hooks").take(count).map { createPending(this, it, automationId, queued = true).str("id") }
            }
            job(jobId, message = "Writing ${ids.size} slideshows…", result = obj(
                "campaign" to campaign, "slideshow_ids" to ids.toMutableList<Any?>(), "automation_id" to automationId,
            ))
            val lang = language ?: campaign.str("language", "English")
            val futures: List<Future<*>> = ids.map { sid ->
                executor.submit {
                    generateInto(sid, campaign.str("style"), lang, imageSettings, product, aspectRatio = imageSettings.str("aspect_ratio"))
                    // Score every Studio slideshow so you can pick the best one to post first.
                    virality?.let { v -> runCatching { v.predict(sid) } }
                }
            }
            futures.forEach { it.get() }
            job(jobId, status = "completed", message = "Done")
        } catch (e: Exception) {
            log("studio job $jobId failed: $e")
            job(jobId, status = "failed", message = (e.message ?: e.toString()).take(2000))
        }
    }

    // --- automations --------------------------------------------------------------------

    fun pickHook(tx: Db.Tx, automation: Map<String, Any?>): String {
        val hooks = automation.strings("hooks").filter { it.isNotBlank() }
        if (hooks.isEmpty()) return automation.str("title")
        val used = tx.where("slideshows") { it.str("automation_id") == automation.str("id") }.groupingBy { it.str("prompt") }.eachCount()
        val low = hooks.minOf { used[it] ?: 0 }
        return hooks.filter { (used[it] ?: 0) == low }.random()
    }

    /**
     * Generate one slideshow for an automation and, if configured, queue it. Blocking.
     * With `publishAt` (scheduled runs start a little early) the post waits in the queue until
     * then, plus a few random minutes so posts don't land on the same second every day.
     */
    fun runAutomation(automationId: String, hook: String? = null, mode: String = "export", cronId: String? = null, publishAt: Instant? = null): String? {
        class Run(val showId: String, val kwargs: Obj, val post: Obj, val account: String?, val minScore: Int)
        val run = db.tx {
            val a = get("automations", automationId) ?: return@tx null
            if (cronId != null && a.bool("paused")) {
                log("automation $automationId paused; skipping scheduled run")
                return@tx null
            }
            val show = createPending(this, hook ?: pickHook(this, a), automationId, cronId)
            val product = a.strOrNull("product_id")?.let { get("products", it) }?.let {
                obj("name" to it.str("name"), "description" to it.str("description"), "url" to it.str("url"))
            }
            Run(
                show.str("id"),
                obj("style" to a.str("style"), "language" to a.str("language", "English"), "image_settings" to Models.IMAGE_SETTINGS.of(a.objAt("image_settings")), "product" to product),
                Models.POST_SETTINGS.of(a.objAt("post_settings")),
                a.strOrNull("tiktok_account_id"),
                a.int("min_viral_score"),
            )
        } ?: return null

        generateInto(run.showId, run.kwargs.str("style"), run.kwargs.str("language"), run.kwargs.objAt("image_settings"), run.kwargs.objOrNull("product"))
        val showId = pretest(run.showId, run.minScore, run.kwargs)

        val ps = run.post
        if (mode == "export" && ps.bool("auto_post", true) && (run.account != null || ps.str("post_mode") == "BROWSER" || phone)) {
            val notBefore = publishAt?.plusSeconds(Random.nextLong(0, 241))
            try {
                publish(showId, ps, notBefore = notBefore)
            } catch (e: PipelineError) {
                log("auto-publish failed for $showId: ${e.message}")
            }
        }
        return showId
    }

    /**
     * Pre-test: predict the new slideshow's virality; below `minScore`, write one more version using
     * the predictor's tips and keep whichever scores higher (the other is deleted). 0 = off.
     */
    fun pretest(showId: String, minScore: Int, kwargs: Map<String, Any?>): String {
        val v = virality ?: return showId
        if (minScore <= 0) return showId
        val first = runCatching { v.predict(showId) }.getOrElse { log("pre-test skipped: ${it.message}"); return showId }
        if (first.int("viral_potential") >= minScore) return showId
        val (prompt, automationId, cronId) = db.tx {
            val s = get("slideshows", showId)!!
            Triple(s.str("prompt"), s.strOrNull("automation_id"), s.strOrNull("created_by_cron_id"))
        }
        val retryId = db.tx { createPending(this, prompt, automationId, cronId, queued = true) }.str("id")
        val advice = (listOf("A first draft scored ${first.int("viral_potential")}/100. Fix this:") + first.strings("tips") +
            listOfNotNull(first.strOrNull("better_hook")?.let { "Consider a hook like: $it" })).joinToString("\n- ")
        generateInto(retryId, kwargs.str("style") + "\n\n" + advice, kwargs.str("language", "English"), kwargs.objAt("image_settings"), kwargs.objOrNull("product"))
        val second = runCatching { v.predict(retryId) }.getOrNull()
        val keep = if (second != null && second.int("viral_potential") > first.int("viral_potential")) retryId else showId
        val drop = if (keep == showId) retryId else showId
        deleteSlideshow(drop)
        log("pre-test kept $keep (${first.int("viral_potential")} -> ${second?.int("viral_potential")})")
        return keep
    }

    fun deleteSlideshow(id: String) {
        val s = db.tx { delete("slideshows", id) } ?: return
        (s.strings("rendered") + listOfNotNull(s.strOrNull("video_path"))).forEach { images.deleteFile(it) }
        db.tx { deleteWhere("posts") { it.str("slideshow_id") == id } }
    }

    // --- publishing -----------------------------------------------------------------------

    /**
     * Queue a rendered slideshow. Every post goes through the queue: the phone's TikTok tab (or the
     * browser extension) publishes it, or saves it to TikTok Studio's drafts for "draft" posts.
     * The official TikTok API modes map onto those: DIRECT_POST -> publish, MEDIA_UPLOAD -> draft.
     */
    fun publish(
        showId: String,
        postSettings: Map<String, Any?>? = null,
        postMode: String? = null,
        caption: String? = null,
        description: String? = null,
        browserAction: String? = null,
        sound: Boolean? = null,
        notBefore: Instant? = null,
    ): String {
        val ps = postSettings ?: Models.POST_SETTINGS.of(null)
        return db.tx {
            val show = get("slideshows", showId)
            if (show == null || show.str("status") != "completed" || show.strings("rendered").isEmpty()) throw PipelineError("slideshow is not rendered yet")
            val mode = postMode ?: ps.str("post_mode", "MEDIA_UPLOAD")
            val action = when (mode) {
                "BROWSER" -> browserAction ?: ps.str("browser_action", "publish")
                "DIRECT_POST" -> "publish"
                else -> "draft"
            }
            caption?.let { show["caption"] = it.take(89) }
            description?.let { show["description"] = it.take(4000) }
            if (ps.str("caption_mode") == "static" && caption == null && ps.str("caption_static").isNotEmpty()) show["caption"] = ps.str("caption_static").take(89)
            if (ps.str("description_mode") == "static" && description == null && ps.str("description_static").isNotEmpty()) show["description"] = ps.str("description_static").take(4000)
            queue.queuePost(this, show, action, sound ?: ps.bool("auto_music", true), notBefore).str("id")
        }
    }

    /** Views and likes for published posts, read from each post's public page. */
    fun refreshPosts(fetchPost: (String) -> Map<String, Any?>) {
        for ((pid, itemId, username) in queue.metricTargets()) {
            if (username.isEmpty()) continue
            val data = try {
                fetchPost("https://www.tiktok.com/@$username/photo/$itemId")
            } catch (e: Exception) {
                log("metrics fetch failed for $itemId: ${e.message}")
                continue
            }
            db.tx {
                get("posts", pid)?.let {
                    for (k in listOf("views", "likes", "comments", "shares")) it[k] = data.long(k)
                    it["metrics_updated_at"] = Db.nowMs()
                }
            }
        }
    }

    fun mediaUrl(rel: String): String = "/media/$rel"

    fun shutdown() {
        executor.shutdownNow()
        jobExecutor.shutdownNow()
    }
}
