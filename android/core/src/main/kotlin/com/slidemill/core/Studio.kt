package com.slidemill.core

import java.io.File
import java.util.concurrent.Executors

open class PipelineError(message: String) : RuntimeException(message)

/**
 * Slideshows Claude writes: build the spec from Claude's plan, find or generate the backgrounds,
 * render the slides, and save them to the user's TikTok Studio drafts through the phone's TikTok tab.
 *
 * A slideshow row keeps the draft attempt's outcome in `draft`: {status: saving | saved | failed,
 * draft_id, error, at}, so a save that outlives Claude's tool call is still reported truthfully.
 * A live post made from the app's POST button is kept the same way in `post`: {status: posting |
 * posted | scheduled | failed, item_id, url, error, at, scheduled_for}. `suggested_time` (unix seconds)
 * is a posting time Claude proposed, waiting for the user's tap.
 */
class Studio(
    private val db: Db,
    private val platform: Platform,
    private val mediaDir: File,
    private val imageGen: ImageGen,
    private val images: Images,
    private val renderer: Renderer,
) {
    val sources = listOf("auto", "stock", "ai")

    companion object {
        const val MAX_SLIDES = 35 // TikTok's photo-post limit
        const val MIN_SCHEDULE_S = 15 * 60L // TikTok Studio's Schedule window
        const val MAX_SCHEDULE_S = 30 * 86_400L // (some accounts only get 10 days)

        const val MIN_GAP_S = 2 * 3600L // autopilot: Slidemill posts at least this far apart
        const val MAX_PER_DAY = 4 // and at most this many within any 24 hours

        /** Why TikTok would refuse a schedule at [at] (unix seconds), or null if it's in the window. */
        fun scheduleProblem(at: Long, nowS: Long = Db.nowMs() / 1000): String? = when {
            at < nowS + MIN_SCHEDULE_S -> "TikTok can only schedule at least 15 minutes ahead."
            at > nowS + MAX_SCHEDULE_S -> "TikTok can only schedule up to 30 days ahead (some accounts: 10)."
            else -> null
        }
    }

    fun specFromPlan(plan: Map<String, Any?>, aspectRatio: String): Obj {
        val slides = plan.objs("slides").map { ps ->
            Models.SLIDE.of(obj(
                "role" to ps.str("role", "body"),
                "text_items" to ps.objs("text_items").map { Models.TEXT_ITEM.of(it) }.toMutableList<Any?>(),
                "image_query" to ps.str("image_query"), "image_prompt" to ps.str("image_prompt"),
            ))
        }
        return Models.SPEC.of(obj(
            "title" to plan.str("title", "Untitled slideshow"),
            "aspect_ratio" to aspectRatio,
            "text_position" to plan.str("text_position", "center"),
            "background_opacity" to 30L,
            "slides" to slides.take(20).toMutableList<Any?>(),
        ))
    }

    /** Store a new slideshow (status "rendering"); [build] fills it in. */
    fun create(plan: Map<String, Any?>, aspectRatio: String): String {
        val spec = specFromPlan(plan, aspectRatio)
        return db.tx {
            insert("slideshows", obj(
                "title" to plan.str("title"), "status" to "rendering", "spec" to spec,
                "caption" to plan.str("caption").take(89), "description" to plan.str("description"),
                "rendered" to arr(), "error" to "", "draft" to null, "prediction" to null,
            ))
        }.str("id")
    }

    /**
     * Store finished slide images Claude made elsewhere, as a ready slideshow (no rendering). Each image is
     * re-encoded as a JPEG of at most 2160x2700, keeping its shape.
     */
    fun importSlides(title: String, caption: String, description: String, images: List<ByteArray>): String {
        if (images.isEmpty()) throw PipelineError("No slides.")
        if (images.size > MAX_SLIDES) throw PipelineError("TikTok takes at most $MAX_SLIDES slides; got ${images.size}.")
        val jpegs = images.mapIndexed { i, data ->
            try {
                platform.imaging.normalize(data, 2160, 2700, 92).jpeg
            } catch (x: Exception) {
                throw PipelineError("Slide ${i + 1} isn't an image Slidemill can read (${x.message ?: "unknown format"}).")
            }
        }
        val id = db.tx {
            insert("slideshows", obj(
                "title" to title.ifEmpty { caption }.ifEmpty { "Imported slideshow" }, "status" to "rendering", "source" to "imported",
                "spec" to Models.SPEC.of(obj("title" to title, "slides" to jpegs.map { obj() }.toMutableList<Any?>())),
                "caption" to caption.take(89), "description" to description,
                "rendered" to arr(), "error" to "", "draft" to null, "prediction" to null,
            ))
        }.str("id")
        val dir = File(mediaDir, "slideshows/$id").also { it.mkdirs() }
        val rendered = jpegs.mapIndexed { i, jpeg ->
            File(dir, "slide_${i + 1}.jpg").writeBytes(jpeg)
            "slideshows/$id/slide_${i + 1}.jpg"
        }
        db.tx { get("slideshows", id)?.let { it["rendered"] = rendered.toMutableList<Any?>(); it["status"] = "completed" } }
        return id
    }

    /** Backgrounds, then the slides. Never throws: a failure is recorded on the row. */
    fun build(id: String, source: String) {
        try {
            val spec = db.tx { get("slideshows", id)?.objAt("spec")?.copyObj() } ?: throw PipelineError("slideshow not found")
            val missing = assignImages(spec, source)
            db.tx {
                get("slideshows", id)?.let {
                    it["spec"] = spec
                    it["warning"] = if (missing == 0) "" else
                        "$missing of ${spec.objs("slides").size} backgrounds couldn't be found or generated, so those slides have a plain background."
                }
            }
            val outDir = File(mediaDir, "slideshows/$id")
            if (outDir.exists()) outDir.deleteRecursively()
            val rendered = renderer.renderSlideshow(Models.SPEC.of(spec), outDir)
            db.tx {
                get("slideshows", id)?.let {
                    it["rendered"] = rendered.toMutableList<Any?>()
                    it["status"] = "completed"
                    it["error"] = ""
                }
            }
        } catch (x: Exception) {
            platform.log("render of $id failed: $x")
            db.tx { get("slideshows", id)?.let { it["status"] = "failed"; it["error"] = x.message ?: x.toString() } }
        }
    }

    /**
     * Fill each slide's background: stock search (Pexels/Unsplash key) or AI generation, AI being the
     * fallback. Returns how many slides got none.
     */
    fun assignImages(spec: Obj, source0: String): Int {
        val source = if (source0 == "auto") (if (images.providers().isNotEmpty()) "stock" else "ai") else source0
        val orientation = images.orientationFor(spec.str("aspect_ratio"))
        val usedUrls = mutableSetOf<String>()
        val needsAi = mutableListOf<Obj>()
        for (slide in spec.objs("slides")) {
            if (slide.strOrNull("image") != null) continue
            val picked = if (source == "stock") images.autoImage(slide.str("image_query").ifEmpty { spec.str("title") }, orientation, usedUrls)?.str("path") else null
            if (picked != null) slide["image"] = picked else needsAi += slide
        }
        if (needsAi.isEmpty()) return 0
        // The free AI source serves one image at a time per address: in parallel they'd all be refused.
        val free = runCatching { imageGen.resolve().provider == "pollinations" }.getOrDefault(true)
        val pool = Executors.newFixedThreadPool(if (free) 1 else 4)
        try {
            val futures = needsAi.map { slide -> pool.submit<String?> { aiBackground(slide, spec) } }
            needsAi.forEachIndexed { i, slide -> slide["image"] = futures[i].get() }
        } finally {
            pool.shutdown()
        }
        return needsAi.count { it["image"] == null }
    }

    private fun aiBackground(slide: Map<String, Any?>, spec: Map<String, Any?>): String? {
        val prompt = slide.str("image_prompt").ifEmpty { slide.str("image_query") }.ifEmpty { spec.str("title") }
        val data = try {
            imageGen.generate(prompt, spec.str("aspect_ratio", "4:5"))
        } catch (e: Exception) {
            platform.log("AI background failed (${e.message}); slide keeps the plain background")
            return null
        }
        val (rel, w, h) = images.storeBytes(data, "library/ai")
        db.tx { insert("images", obj("path" to rel, "source" to "ai", "source_url" to "", "query" to prompt.take(500), "width" to w, "height" to h, "use_count" to 1L)) }
        return rel
    }

    /**
     * Save a rendered slideshow to the user's TikTok Studio drafts, now, through the phone's TikTok tab.
     * Blocks until TikTok answers; returns the draft record. Throws [DraftError] with the reason
     * (also recorded on the slideshow).
     */
    fun saveDraft(id: String, tab: TikTokTab?, again: Boolean = false): Obj {
        val (title, caption, slides) = db.tx {
            val s = get("slideshows", id) ?: throw PipelineError("Slideshow $id not found.")
            when (s.str("status")) {
                "rendering" -> throw DraftError("Still rendering: try again in a minute.")
                "failed" -> throw DraftError("This slideshow failed to render (${s.str("error")}); create it again.")
            }
            val draft = s.objOrNull("draft")
            if (draft?.str("status") == "saving") throw DraftError("A save of this slideshow is already running.")
            if (draft?.str("status") == "saved" && !again) {
                throw DraftError("Already in TikTok drafts (draft id ${draft.str("draft_id")}). Pass again=true to save another copy.")
            }
            s["draft"] = obj("status" to "saving", "draft_id" to "", "error" to "", "at" to Db.iso(Db.nowMs()))
            Triple(
                s.str("caption").ifEmpty { s.str("title") }.take(90), // the line shown on the carousel
                s.str("description"), // the caption with hashtags
                s.strings("rendered").map { File(mediaDir, it) },
            )
        }
        val record = try {
            if (tab == null) throw DraftError("The phone's TikTok tab isn't running: open the Slidemill app on the phone.")
            if (slides.isEmpty() || slides.any { !it.isFile }) throw DraftError("The rendered slides are missing: create the slideshow again.")
            obj("status" to "saved", "draft_id" to tab.saveDraft(title, caption, slides), "error" to "")
        } catch (x: Exception) {
            obj("status" to "failed", "draft_id" to "", "error" to (x.message ?: x.toString()))
        }
        record["at"] = Db.iso(Db.nowMs())
        db.tx { get("slideshows", id)?.set("draft", record) }
        if (record.str("status") != "saved") throw DraftError(record.str("error"))
        return record
    }

    /**
     * Publish a rendered slideshow as a live TikTok photo post through the phone's TikTok tab. Blocks
     * until TikTok answers; returns the post record. Throws [DraftError] with the reason (also recorded).
     */
    fun publish(id: String, tab: TikTokTab?, sound: Boolean = true, at: Long = 0): Obj {
        if (at > 0) scheduleProblem(at)?.let { throw DraftError(it) }
        val (title, caption, slides) = db.tx {
            val s = get("slideshows", id) ?: throw PipelineError("Slideshow $id not found.")
            if (s.str("status") != "completed") throw DraftError("This slideshow isn't rendered (${s.str("status")}).")
            when (s.objOrNull("post")?.str("status")) {
                "posting" -> throw DraftError("It's already being posted.")
                "posted" -> throw DraftError("Already posted: ${s.objAt("post").str("url")}")
                "scheduled" -> throw DraftError("Already scheduled on TikTok for ${s.objAt("post").str("scheduled_for")}.")
            }
            s["post"] = obj("status" to "posting", "item_id" to "", "url" to "", "error" to "", "at" to Db.iso(Db.nowMs()))
            Triple(s.str("caption").ifEmpty { s.str("title") }.take(90), s.str("description"), s.strings("rendered").map { File(mediaDir, it) })
        }
        val record = try {
            if (tab == null) throw DraftError("The phone's TikTok tab isn't running: reopen the Slidemill app.")
            if (slides.isEmpty() || slides.any { !it.isFile }) throw DraftError("The rendered slides are missing on this phone.")
            val user = tab.username()
            val itemId = tab.publish(title, caption, slides, sound, at)
            obj(
                "status" to if (at > 0) "scheduled" else "posted", "item_id" to itemId,
                "url" to "https://www.tiktok.com/@$user/photo/$itemId", "error" to "",
                "scheduled_for" to if (at > 0) Db.iso(at * 1000) else "",
            )
        } catch (x: Exception) {
            obj("status" to "failed", "item_id" to "", "url" to "", "error" to (x.message ?: x.toString()))
        }
        record["at"] = Db.iso(Db.nowMs())
        db.tx { get("slideshows", id)?.set("post", record) }
        if (record.str("status") == "failed") throw DraftError(record.str("error"))
        db.tx { get("slideshows", id)?.set("suggested_time", 0L) }
        return record
    }

    /** Unix seconds of every Slidemill post that's live, scheduled or being posted. */
    fun postTimes(): List<Long> = db.tx {
        all("slideshows").mapNotNull { s ->
            val p = s.objOrNull("post") ?: return@mapNotNull null
            when (p.str("status")) {
                "scheduled" -> runCatching { java.time.Instant.parse(p.str("scheduled_for")).epochSecond }.getOrNull()
                "posted", "posting" -> runCatching { java.time.Instant.parse(p.str("at")).epochSecond }.getOrNull()
                else -> null
            }
        }
    }

    /** Autopilot's spam guard: why posting at [at] would crowd the account, or null if it's fine. */
    fun crowding(at: Long): String? {
        val times = postTimes()
        times.firstOrNull { kotlin.math.abs(it - at) < MIN_GAP_S }?.let {
            return "Another Slidemill post goes out within 2 hours of that time (${Db.iso(it * 1000)} UTC). Keep posts at least 2 hours apart."
        }
        if (times.count { kotlin.math.abs(it - at) < 12 * 3600 } >= MAX_PER_DAY) {
            return "That would be more than $MAX_PER_DAY Slidemill posts within 24 hours. Pick another day."
        }
        return null
    }

    /** Claude's proposed posting time (unix seconds), shown in the app for the user to approve with one tap. */
    fun suggest(id: String, at: Long) {
        scheduleProblem(at)?.let { throw DraftError(it) }
        db.tx {
            val s = get("slideshows", id) ?: throw PipelineError("Slideshow $id not found.")
            if (s.objOrNull("post")?.str("status") in setOf("posted", "scheduled", "posting")) throw DraftError("It's already ${s.objAt("post").str("status")}.")
            s["suggested_time"] = at
        }
    }

    fun delete(id: String): Boolean {
        val s = db.tx { delete("slideshows", id) } ?: return false
        s.strings("rendered").forEach { images.deleteFile(it) }
        return true
    }

    /** Rows killed mid-flight by a closed app. */
    fun recoverInterrupted() = db.tx {
        for (s in where("slideshows") { it.str("status") == "rendering" }) {
            s["status"] = "failed"
            s["error"] = "Interrupted (the app was closed while this was rendering)."
        }
        for (s in where("slideshows") { it.objOrNull("draft")?.str("status") == "saving" }) {
            s["draft"] = obj("status" to "failed", "draft_id" to "", "error" to "Interrupted (the app was closed while saving). Check TikTok Studio drafts before saving again.", "at" to Db.iso(Db.nowMs()))
        }
        for (s in where("slideshows") { it.objOrNull("post")?.str("status") == "posting" }) {
            s["post"] = obj("status" to "failed", "item_id" to "", "url" to "", "error" to "Interrupted (the app was closed while posting). Check your TikTok profile before posting again.", "at" to Db.iso(Db.nowMs()))
        }
    }
}
