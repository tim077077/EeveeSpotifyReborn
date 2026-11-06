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
    private val pinterest: Pinterest = Pinterest(),
) {
    val sources = listOf("auto", "stock", "ai", "pinterest")

    companion object {
        const val MAX_SLIDES = 35 // TikTok's photo-post limit
        const val MIN_SCHEDULE_S = 15 * 60L // TikTok Studio's Schedule window
        const val MAX_SCHEDULE_S = 30 * 86_400L // (some accounts only get 10 days)

        const val MIN_GAP_S = 2 * 3600L // autopilot: Slidemill posts at least this far apart
        const val MAX_PER_DAY = 4 // and at most this many within any 24 hours

        /** TikTok Studio schedules on 5-minute steps: round up, so what's recorded is what TikTok gets. */
        fun roundSchedule(at: Long): Long = if (at <= 0) 0 else (at + 299) / 300 * 300

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

    /** Store a new slideshow (status "rendering") for TikTok [account] ("" = the default one); [build] fills it in. */
    fun create(plan: Map<String, Any?>, aspectRatio: String, account: String = ""): String {
        val spec = specFromPlan(plan, aspectRatio)
        return db.tx {
            insert("slideshows", obj(
                "title" to plan.str("title"), "status" to "rendering", "spec" to spec, "account" to account,
                "caption" to plan.str("caption").take(89), "description" to plan.str("description"),
                "rendered" to arr(), "error" to "", "draft" to null, "prediction" to null,
            ))
        }.str("id")
    }

    /**
     * Claude's fixes to a rendered slideshow, then a fresh render (call [build] after). Each edit names a
     * slide by its number before this call: new texts (styles kept), a new background (new_background, or a new
     * image_query/image_prompt), delete, or insert_after (a new slide after it; slide 0 = first).
     * Only the changed backgrounds are fetched again. Returns the background URLs to avoid.
     */
    fun edit(id: String, caption: String?, description: String?, edits: List<Obj>): Set<String> {
        val replaced = mutableListOf<String>()
        db.tx {
            val row = get("slideshows", id) ?: throw PipelineError("Slideshow $id not found.")
            when {
                row.objOrNull("post") != null -> throw PipelineError("Slideshow $id is already posted or scheduled on TikTok: it can't be edited. Make a new one.")
                row.str("status") == "rendering" -> throw PipelineError("Slideshow $id is still rendering: edit it once it's done.")
                row.str("source") == "imported" && edits.isNotEmpty() -> throw PipelineError("Imported slides are finished images: only caption and description can be edited. Import fixed images instead.")
            }
            val spec = row.objAt("spec").copyObj()
            val originals = spec.objs("slides").toMutableList()
            val deleted = mutableSetOf<Int>()
            val inserts = mutableMapOf<Int, MutableList<Obj>>() // after original slide n (0 = before the first)
            for (ed in edits) {
                val n = ed.int("slide")
                if (ed.bool("insert_after")) {
                    if (n !in 0..originals.size) throw PipelineError("insert_after: slide must be 0-${originals.size}.")
                    // A new slide takes the text style of the slide it follows (or the first one).
                    val like = (originals.getOrNull(n - 1) ?: originals.firstOrNull())?.objs("text_items").orEmpty()
                    inserts.getOrPut(n) { mutableListOf() } += Models.SLIDE.of(obj(
                        "role" to "body",
                        "text_items" to restyle(ed.strings("texts"), like),
                        "image_query" to ed.str("image_query"), "image_prompt" to ed.str("image_prompt"),
                    ))
                    continue
                }
                val slide = originals.getOrNull(n - 1) ?: throw PipelineError("Slide $n doesn't exist: this slideshow has ${originals.size}.")
                if (ed.bool("delete")) { deleted += n; continue }
                if (ed.containsKey("texts")) slide["text_items"] = restyle(ed.strings("texts"), slide.objs("text_items"))
                val newQuery = ed.strOrNull("image_query")?.takeIf { it.isNotBlank() }
                val newPrompt = ed.strOrNull("image_prompt")?.takeIf { it.isNotBlank() }
                newQuery?.let { slide["image_query"] = it }
                newPrompt?.let { slide["image_prompt"] = it }
                if (ed.bool("new_background") || newQuery != null || newPrompt != null) {
                    slide.strOrNull("image")?.let { replaced += it }
                    slide["image"] = null
                }
            }
            val slides = mutableListOf<Any?>()
            inserts[0]?.let { slides.addAll(it) }
            originals.forEachIndexed { i, sl ->
                if (i + 1 !in deleted) slides += sl
                inserts[i + 1]?.let { slides.addAll(it) }
            }
            if (slides.isEmpty()) throw PipelineError("That would delete every slide.")
            if (slides.size > MAX_SLIDES) throw PipelineError("At most $MAX_SLIDES slides.")
            spec["slides"] = slides
            row["spec"] = spec
            caption?.let { row["caption"] = it.take(89) }
            description?.let { row["description"] = it }
            row["status"] = if (row.str("source") == "imported") "completed" else "rendering"
            if (row.str("source") == "imported") row["render_version"] = row.long("render_version") + 1 // the text changed: review again
        }
        return db.tx { replaced.mapNotNull { p -> where("images") { it.str("path") == p }.firstOrNull()?.str("source_url")?.takeIf { it.isNotEmpty() } }.toSet() }
    }

    /** New texts in the styles of [like] (item by item; extra texts take the last style). */
    private fun restyle(texts: List<String>, like: List<Obj>): MutableList<Any?> = texts.mapIndexed { i, t ->
        Models.TEXT_ITEM.of((like.getOrNull(i) ?: like.lastOrNull())?.copyObj()?.also { it["text"] = t } ?: obj("text" to t))
    }.toMutableList()

    /**
     * Record Claude's check of the current render. It passes only when every slide was checked, no
     * problem is left and no claim is false or unsure. Returns the stored review (with the issues and the round).
     */
    fun review(id: String, slidesChecked: Int, problems: List<String>, claims: List<Obj>): Obj = db.tx {
        val row = get("slideshows", id) ?: throw PipelineError("Slideshow $id not found.")
        if (row.str("status") != "completed") throw PipelineError("Slideshow $id isn't rendered (${row.str("status")}): review it once it is.")
        val version = row.long("render_version")
        val issues = arr()
        problems.map { it.trim() }.filter { it.isNotEmpty() }.forEach { issues += it }
        for (c in claims) {
            when (c.str("verdict")) {
                "unsure" -> issues += "claim \"${c.str("claim")}\" is unsure: reword it as a tip or opinion, or remove it"
                "false" -> issues += "claim \"${c.str("claim")}\" is false: fix or remove it"
            }
        }
        val total = row.strings("rendered").size
        if (slidesChecked < total) issues += "only $slidesChecked of $total slides checked: look at every slide"
        val previous = row.objOrNull("review")
        val round = if (previous != null && !previous.bool("passed")) previous.long("round") + 1 else 1L
        val review = obj("version" to version, "passed" to issues.isEmpty(), "issues" to issues, "round" to round, "at" to Db.iso(Db.nowMs()))
        row["review"] = review
        review.copyObj()
    }

    /** Why this slideshow can't go public yet, or null when its current render passed review. */
    fun reviewProblem(id: String): String? {
        val row = db.tx { get("slideshows", id)?.copyObj() } ?: return null
        val r = row.objOrNull("review")
        return when {
            r == null -> "Slideshow $id hasn't been reviewed: look at its slides, fix mistakes with edit_slideshow, then record the check with review_slideshow."
            r.long("version") != row.long("render_version") -> "Slideshow $id changed since its last review: check the new render and call review_slideshow again."
            !r.bool("passed") -> "Slideshow $id failed its last review: fix these with edit_slideshow, then review again: ${r.strings("issues").joinToString("; ")}"
            else -> null
        }
    }

    /**
     * Store finished slide images Claude made elsewhere, as a ready slideshow (no rendering). Each image is
     * re-encoded as a JPEG of at most 2160x2700, keeping its shape.
     */
    fun importSlides(title: String, caption: String, description: String, images: List<ByteArray>, account: String = ""): String {
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
                "title" to title.ifEmpty { caption }.ifEmpty { "Imported slideshow" }, "status" to "rendering", "source" to "imported", "account" to account,
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
        db.tx { get("slideshows", id)?.let { it["rendered"] = rendered.toMutableList<Any?>(); it["status"] = "completed"; it["render_version"] = 1L } }
        return id
    }

    /**
     * Backgrounds, then the slides. Never throws: a failure is recorded on the row. Each render bumps the
     * row's render_version, so a review only covers the slides it saw. [avoid]: background URLs not to
     * pick again (the ones an edit replaced).
     */
    fun build(id: String, source: String, avoid: Set<String> = emptySet()) {
        try {
            val spec = db.tx { get("slideshows", id)?.objAt("spec")?.copyObj() } ?: throw PipelineError("slideshow not found")
            val missing = assignImages(spec, source, avoid)
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
                    it["render_version"] = it.long("render_version") + 1
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
    fun assignImages(spec: Obj, source0: String, avoid: Set<String> = emptySet()): Int {
        val source = if (source0 == "auto") (if (images.providers().isNotEmpty()) "stock" else "ai") else source0
        val orientation = images.orientationFor(spec.str("aspect_ratio"))
        val usedUrls = avoid.toMutableSet()
        val needsAi = mutableListOf<Obj>()
        // The renderer reads the spec's own slide maps, and objs() hands out copies: mutate the originals
        // so the background actually reaches the render.
        val originals = (spec["slides"] as? List<*>)?.mapNotNull { it as? Obj }.orEmpty()
        val slides = if (originals.isNotEmpty()) originals else spec.objs("slides")
        for (slide in slides) {
            if (slide.strOrNull("image") != null) continue
            val picked = when (source) {
                "stock" -> images.autoImage(slide.str("image_query").ifEmpty { spec.str("title") }, orientation, usedUrls)?.str("path")
                "pinterest" -> pinterestBackground(slide.str("image_query").ifEmpty { spec.str("title") }, usedUrls)
                else -> null
            }
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

    /**
     * A portrait Pinterest pin for this slide's query: downloaded once, then reused by URL like any other
     * image. Returns null (and the slide falls back to AI) when Pinterest has nothing usable.
     */
    private fun pinterestBackground(query: String, used: MutableSet<String>): String? {
        val pins = try {
            pinterest.search(query.ifEmpty { "aesthetic background" }, 12)
        } catch (x: Exception) {
            platform.log("pinterest search failed (${x.message})")
            return null
        }
        if (pins.isEmpty()) return null
        val fresh = pins.filter { it.url !in used }
        val pick = (fresh.take(6).ifEmpty { pins }).random()
        used += pick.url
        db.tx { where("images") { it.str("source_url") == pick.url }.firstOrNull() }?.let { return it.str("path") }
        return try {
            images.downloadInto(pick.toStock(), query).str("path")
        } catch (x: Exception) {
            platform.log("pinterest download failed (${x.message})")
            null
        }
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
    fun publish(id: String, tab: TikTokTab?, sound: Boolean = true, at: Long = 0): Obj = publishAt(id, tab, sound, roundSchedule(at))

    private fun publishAt(id: String, tab: TikTokTab?, sound: Boolean, at: Long): Obj {
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
        db.tx {
            val s = get("slideshows", id) ?: return@tx
            s["post"] = record
            if (record.str("status") != "failed") s["account"] = record.str("url").substringAfter("/@").substringBefore("/") // where it really went
        }
        if (record.str("status") == "failed") throw DraftError(record.str("error"))
        db.tx { get("slideshows", id)?.set("suggested_time", 0L) }
        return record
    }

    /** Unix seconds of every Slidemill post that's live, scheduled or being posted. */
    fun postTimes(account: String? = null, unassigned: Boolean = true): List<Long> = db.tx {
        all("slideshows").filter { s ->
            account == null || s.str("account").equals(account, ignoreCase = true) || (unassigned && s.str("account").isEmpty())
        }.mapNotNull { s ->
            val p = s.objOrNull("post") ?: return@mapNotNull null
            when (p.str("status")) {
                "scheduled" -> Db.epochS(p.str("scheduled_for"))
                "posted", "posting" -> Db.epochS(p.str("at"))
                else -> null
            }
        }
    }

    /**
     * Autopilot's spam guard: why posting at [at] would crowd [account] (null = all of them), or null if
     * it's fine. Posts recorded before accounts existed count for the default account ([unassigned]).
     */
    fun crowding(at: Long, account: String? = null, unassigned: Boolean = true): String? {
        val times = postTimes(account, unassigned)
        times.firstOrNull { kotlin.math.abs(it - at) < MIN_GAP_S }?.let {
            return "Another Slidemill post${account?.let { " on @$it" }.orEmpty()} goes out within 2 hours of that time (${Db.iso(it * 1000)} UTC). Keep posts at least 2 hours apart."
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
