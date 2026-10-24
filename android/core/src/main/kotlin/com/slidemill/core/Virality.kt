package com.slidemill.core

import java.io.File
import java.util.Base64
import kotlin.math.ln

/**
 * Virality prediction for a slideshow, before it's posted (Slidemill's take on Higgsfield's Virality
 * Predictor, built for photo carousels instead of video).
 *
 * Higgsfield runs a brain-response model over a video and turns predicted cortex activity into a
 * hook score, a hold rate and a viral-potential number. A slideshow has no motion or sound, so here
 * a vision model reads the actual rendered slides like a scrolling viewer (calibrated with real hooks
 * from your library that over- and under-performed), and plain rules check the format. Every
 * prediction is stored, so analytics can later show whether it actually tracks your views.
 */
class Virality(private val db: Db, private val llm: Llm, private val mediaDir: File) {
    companion object {
        const val BACKTEST_MIN = 8
        const val NOISE_POSTS = 2
        const val BACKTEST_VERSION = 2 // results measured before the read and the ranking shared one call don't pick a method
        const val LETTERS = "ABCDEFG"
        const val MAX_IMAGES = 12 // some vision APIs cap images per request (Mistral: reportedly 8-12); past this, rank by text alone

        const val SYSTEM_PROMPT = """You are a TikTok photo-carousel (slideshow) performance analyst. You predict how real viewers on the For You feed react to a slideshow before it is posted.

How viewers behave: they decide in under a second from slide 1 whether to stop scrolling (the hook). Each later slide either earns the next swipe (new, specific, a little surprising, easy to read in 2 seconds) or loses them (repetitive, vague, wall of text, unreadable over the image, the promised payoff never arrives). Saves and shares come from practical value, identity ("this is so me") and curiosity that pays off.

Score honestly against what actually performs on TikTok; most slideshows are average (50-65). Reserve 85+ for hooks that would stop almost anyone in the niche. Penalize: generic hooks, clickbait the slides don't deliver, numbered hooks whose count doesn't match the slides, text hard to read over the photo, more than ~25 words on a slide, slides that repeat each other.

For each slide in order, give swipe_on: the percent of viewers who reach this slide and swipe on to the next one (for the last slide: the percent who finish and engage). Tips must be concrete edits to this slideshow, not general advice."""

        val READ = Schema.obj(
            "ViralityRead",
            "hook_score" to Schema.Integer("0-100: how strongly slide 1 stops the scroll."),
            "curiosity" to Schema.Integer("0-100: how much the slides pull viewers to keep swiping."),
            "relatability" to Schema.Integer("0-100: how much the target viewer sees themselves in it."),
            "payoff" to Schema.Integer("0-100: whether the slides deliver what the hook promised."),
            "shareability" to Schema.Integer("0-100: likelihood of saves, sends and shares."),
            "slides" to Schema.Array(Schema.obj(
                "SlideRead",
                "swipe_on" to Schema.Integer("0-100: percent of viewers who reach this slide and swipe on (last slide: who finish and engage)."),
                "visual_pull" to Schema.Integer("0-100: how arresting and on-theme the image is."),
                "clarity" to Schema.Integer("0-100: how fast the text reads over the image."),
                "note" to Schema.Str("At most 12 words on what helps or hurts this slide."),
            )),
            "tips" to Schema.Array(Schema.Str(), "2-4 concrete edits that would raise the score most."),
            "better_hook" to Schema.Str("A stronger slide-1 hook for this exact slideshow, same language, under 10 words."),
        )

        /** The read schema, plus a blind ranking and/or a transcription when this call carries them. */
        fun readSchema(rank: Boolean, transcribe: Boolean): Schema.Object {
            if (!rank && !transcribe) return READ
            val props = LinkedHashMap(READ.props)
            if (transcribe) props["slide_texts"] = Schema.Array(Schema.Str(), "The overlay text on each slide, in order, exactly as written ('' if none).")
            if (rank) props["rank_order"] = Schema.Array(Schema.Str(), "Every slideshow's letter in the comparison, best first.")
            return Schema.Object("ViralityReadPlus", props)
        }

        val DIMENSIONS = listOf("visual_pull", "curiosity", "clarity", "relatability", "shareability")

        fun verdict(score: Int): Pair<String, String> = when {
            score >= 85 -> "Scroll-stopper." to "Top-decile hook"
            score >= 70 -> "Strong." to "Above average"
            score >= 55 -> "Watchable." to "Average"
            score >= 40 -> "Needs work." to "Below average"
            else -> "Gets scrolled past." to "Weak hook"
        }

        private fun num(v: Any?): Int = when (v) {
            is Number -> v.toInt()
            is String -> Regex("-?\\d+").find(v)?.value?.toIntOrNull() ?: 50
            else -> 50
        }.coerceIn(0, 100)

        private val NUMBER_HOOK = Regex("\\b(\\d{1,2})\\s+(?:[a-z]+\\s+){0,3}?(things|habits|ways|tips|signs|reasons|mistakes|rules|steps|lessons|foods|apps|books|places|ideas|secrets|truths|facts)\\b", RegexOption.IGNORE_CASE)

        /** Rule checks on the slideshow's text and structure: (score 0-100, problems found). */
        fun formatCheck(spec: Map<String, Any?>): Pair<Int, List<String>> {
            val slides = spec.objs("slides")
            val texts = slides.map { s -> s.objs("text_items").joinToString(" ") { it.str("text") }.trim() }
            var score = 100
            val problems = mutableListOf<String>()
            if (slides.size < 4) { score -= 20; problems += "Only ${slides.size} slides: 5-8 usually hold attention better." }
            if (slides.size > 10) { score -= 15; problems += "${slides.size} slides is long: viewers drop off; cut to the strongest 6-8." }
            val hook = texts.firstOrNull().orEmpty()
            val hookWords = hook.split(Regex("\\s+")).count { it.isNotBlank() }
            if (hookWords == 0) { score -= 35; problems += "Slide 1 has no text: the hook has to be readable in a second." }
            else if (hookWords > 14) { score -= 15; problems += "The hook is $hookWords words: keep it under 12 so it reads at a glance." }
            texts.forEachIndexed { i, t ->
                val words = t.split(Regex("\\s+")).count { it.isNotBlank() }
                if (i > 0 && words > 30) { score -= 8; problems += "Slide ${i + 1} has $words words: split it or cut it down." }
            }
            NUMBER_HOOK.find(hook)?.groupValues?.get(1)?.toIntOrNull()?.let { promised ->
                val body = slides.drop(1).count { it.str("role") != "cta" }
                if (promised != body && body > 0) {
                    score -= 20
                    problems += "The hook promises $promised but there are $body list slides."
                }
            }
            if (texts.size > 2 && texts.drop(1).toSet().size < texts.size - 1) { score -= 10; problems += "Two slides say the same thing." }
            return score.coerceIn(0, 100) to problems
        }

        /** Spearman rank correlation. */
        fun spearman(xs: List<Double>, ys: List<Double>): Double? {
            if (xs.size < 3 || xs.size != ys.size) return null
            fun ranks(v: List<Double>): DoubleArray {
                val order = v.indices.sortedBy { v[it] }
                val r = DoubleArray(v.size)
                var i = 0
                while (i < order.size) {
                    var j = i
                    while (j + 1 < order.size && v[order[j + 1]] == v[order[i]]) j++
                    val avg = (i + j) / 2.0 + 1
                    for (k in i..j) r[order[k]] = avg
                    i = j + 1
                }
                return r
            }
            val rx = ranks(xs)
            val ry = ranks(ys)
            val mx = rx.average()
            val my = ry.average()
            var num = 0.0; var dx = 0.0; var dy = 0.0
            for (i in rx.indices) {
                num += (rx[i] - mx) * (ry[i] - my); dx += (rx[i] - mx) * (rx[i] - mx); dy += (ry[i] - my) * (ry[i] - my)
            }
            if (dx == 0.0 || dy == 0.0) return null
            return num / Math.sqrt(dx * dy)
        }
    }

    /**
     * Real hooks from the library that beat or missed their account's usual views, as calibration
     * examples (the closest thing to ground truth the app has).
     */
    fun calibrationExamples(limit: Int = 6, exclude: String = ""): Pair<List<String>, List<String>> = db.tx {
        val byProfile = where("library_posts") { it.bool("is_slideshow") && it.long("views") > 0 }.groupBy { it.str("profile_id") }
        val scored = mutableListOf<Pair<Double, String>>()
        for (posts in byProfile.values) {
            if (posts.size < 3) continue
            val median = Timing.median(posts.map { it.long("views") }).takeIf { it > 0 } ?: continue
            for (p in posts) {
                if (p.str("id") == exclude) continue
                val hook = p.strings("slide_texts").firstOrNull()?.takeIf { it.isNotBlank() }
                    ?: p.str("description").replace(Regex("#\\S+"), "").trim().takeIf { it.isNotBlank() } ?: continue
                scored += ln(p.long("views") / median) to "\"${hook.take(120)}\" (${"%.1f".format(p.long("views") / median)}x the account's usual views)"
            }
        }
        val sorted = scored.sortedByDescending { it.first }
        sorted.take(limit / 2).filter { it.first > 0 }.map { it.second } to
            sorted.takeLast(limit / 2).filter { it.first < 0 }.map { it.second }
    }

    // --- the library: real slideshows whose views are known ---------------------------------------

    /** Library slideshows from accounts with 3+ of them, each with its log views vs the account's median. */
    fun libraryRows(): List<Obj> = db.tx {
        val out = mutableListOf<Obj>()
        val byProfile = where("library_posts") { it.bool("is_slideshow") && it.long("views") > 0 }.groupBy { it.str("profile_id") }
        for ((profileId, posts) in byProfile) {
            if (posts.size < 3) continue
            val median = Timing.median(posts.map { it.long("views") }).takeIf { it > 0 } ?: continue
            val username = get("library_profiles", profileId)?.str("username").orEmpty()
            for (p in posts) {
                val texts = p.strings("slide_texts").filter { it.isNotBlank() }
                val hook = texts.firstOrNull() ?: p.str("description").replace(Regex("#\\S+"), "").trim()
                out += obj(
                    "id" to p.str("id"), "read" to texts.isNotEmpty(), "username" to username, "url" to p.str("url"), "hook" to hook,
                    "texts" to (texts.ifEmpty { if (hook.isEmpty()) emptyList() else listOf(hook) }).toMutableList<Any?>(),
                    "images" to p.strings("images").toMutableList<Any?>(), "views" to p.long("views"),
                    "lift" to ln(p.long("views") / median),
                )
            }
        }
        out
    }

    /** Up to 6 read library posts spread from flops to hits to rank a slideshow against (null if under 4). */
    fun references(rows: List<Obj>, exclude: String = ""): List<Obj>? {
        var pool = rows.filter { it.str("id") != exclude && it.bool("read") }
        if (pool.size < 4) pool = rows.filter { it.str("id") != exclude && it.strings("texts").isNotEmpty() }
        return Methods.spread(pool, 6).takeIf { it.size >= 4 }
    }

    /** Where the slideshow (letter `pos`) landed among the references, whose real results are known. */
    private fun rankFromOrder(order: List<String>, refs: List<Obj>, pos: Int): Obj? {
        val letters = order.map { it.trim().uppercase().take(1) }
        val me = LETTERS[pos].toString()
        if (me !in letters) return null
        val above = letters.take(letters.indexOf(me)).toSet()
        val beats = refs.indices.count { i -> LETTERS[if (i < pos) i else i + 1].toString() !in above }
        val lifts = refs.map { it.dbl("lift") }.sorted()
        val at = beats.toDouble() / refs.size * (lifts.size - 1)
        val lo = at.toInt()
        val lift = lifts[lo] + (lifts[minOf(lo + 1, lifts.size - 1)] - lifts[lo]) * (at - lo)
        return obj(
            "score" to Math.round(100.0 * (beats + 0.5) / (refs.size + 1)).toInt(), "lift" to Methods.round2(Math.exp(lift)),
            "beats" to beats, "references" to refs.size,
        )
    }

    fun features(result: Map<String, Any?>, rank: Map<String, Any?>?, similar: Map<String, Any?>?): Obj {
        val d = result.objAt("dimensions")
        return obj(
            "current" to result.int("viral_potential"), "hook" to result.int("hook_score"), "hold" to result.int("hold_rate"),
            "engagement" to Math.round(listOf(d.dbl("curiosity"), d.dbl("relatability"), d.dbl("shareability"), result.dbl("payoff")).average()).toInt(),
            "format" to result.int("format_score"), "rank" to rank?.int("score"), "similar" to similar?.int("score"),
        )
    }

    private fun slideTexts(spec: Map<String, Any?>): List<String> =
        spec.objs("slides").map { s -> s.objs("text_items").joinToString(" ") { it.str("text") }.trim() }.filter { it.isNotEmpty() }

    private fun picked(bt: Obj?): Pair<Obj, String> {
        val b = bt ?: obj()
        return b to if (b.long("version") == BACKTEST_VERSION.toLong()) b.str("method").ifEmpty { "current" } else "current"
    }

    /**
     * One AI call: the viewer read plus a blind ranking among real library posts; the final score uses
     * the method the library test picked. Unchanged slideshows return the stored prediction (no call).
     */
    fun predict(showId: String): Obj {
        val show = db.tx { get("slideshows", showId)?.copyObj() } ?: throw PipelineError("slideshow not found")
        if (show.str("status") != "completed" || show.strings("rendered").isEmpty()) throw PipelineError("Render the slideshow first.")
        val spec = Models.SPEC.of(show.objAt("spec"))
        val rows = libraryRows()
        val (bt, method) = picked(lastBacktest())
        val texts = slideTexts(spec)
        val fingerprint = java.security.MessageDigest.getInstance("SHA-1").digest(Json.write(listOf(
            texts, show.strings("rendered"), show.str("caption"), show.str("description"), bt["ran_at"], method, rows.size,
        )).toByteArray()).joinToString("") { "%02x".format(it) }
        val stored = show.objAt("prediction")
        if (stored.str("fingerprint") == fingerprint) return stored.also { it["cached"] = true }
        val (result, rank, _) = read(spec, show.str("caption"), show.str("description"), show.strings("rendered"), refs = references(rows), seed = showId)
        val similar = Methods.similarEvidence(texts.joinToString(" "), rows)
        if (similar != null) result["similar"] = similar
        if (rank != null) result["rank"] = rank
        val value = if (method != "current") Methods.methodValue(method, features(result, rank, similar), bt) else null
        result["ai_score"] = result.int("viral_potential")
        result["method"] = "current"
        if (value != null) {
            result["viral_potential"] = value
            val (v, tag) = verdict(value)
            result["verdict"] = v
            result["verdict_tag"] = tag
            val rho = bt.objs("methods").firstOrNull { it.str("method") == method }?.get("spearman")
            result["basis"] = result.str("basis") + "; final score from ${Methods.LABELS[method]!!.lowercase()} (the library test's pick, ρ = $rho)"
            result["method"] = method
        }
        result["fingerprint"] = fingerprint
        db.tx { get("slideshows", showId)?.set("prediction", result) }
        return result
    }

    private fun dataUrl(path: String) = "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(File(mediaDir, path).readBytes())

    /**
     * One AI call: read the slideshow like a viewer and score it, rank it blind among `refs` (real library
     * posts) when given, and transcribe its slide text when `transcribe`. Returns (result, rank, slide texts).
     */
    private fun read(
        spec0: Obj, caption: String, description: String, images: List<String>, exclude: String = "",
        refs: List<Obj>? = null, seed: String = "", transcribe: Boolean = false,
    ): Triple<Obj, Obj?, List<String>> {
        var spec = spec0
        val slides = spec.objs("slides")
        val cfg = llm.resolve() ?: throw LlmError("Add an AI key in Settings first.")
        if (transcribe && !cfg.vision) throw LlmError("Reading slide text needs a vision-capable AI key.")
        val (good, bad) = calibrationExamples(exclude = exclude)
        val dataUrls = if (cfg.vision) images.take(10).map { dataUrl(it) }.toMutableList() else null
        // Where this slideshow sits among the references is fixed per slideshow, so position bias can't favour it.
        val pos = if (refs == null) 0 else (java.util.zip.CRC32().apply { update(seed.toByteArray()) }.value % (refs.size + 1)).toInt()
        val user = buildString {
            append("Caption: $caption\nDescription: $description\n\nSlides (text as written on each image):\n")
            slides.forEachIndexed { i, s ->
                val text = if (transcribe) "(unknown: read it off the attached slide)" else s.objs("text_items").joinToString(" / ") { it.str("text") }
                append("${i + 1}. [${s.str("role")}] $text\n")
            }
            if (good.isNotEmpty() || bad.isNotEmpty()) {
                append("\nCalibration from real slideshows in this creator's research library:\n")
                good.forEach { append("- over-performed: $it\n") }
                bad.forEach { append("- under-performed: $it\n") }
            }
            append("\nThere are ${slides.size} slides; return exactly ${slides.size} entries in `slides`.")
            if (transcribe) append(" Also return `slide_texts`: the overlay text of each slide, in order.")
            if (dataUrls != null) append(" The rendered slides are attached in order: judge the actual images and how readable the text is on them.")
            if (refs != null) {
                val names = LETTERS.take(refs.size + 1).toList()
                val own = if (transcribe) "its slides are attached" else slideTexts(spec).take(10).joinToString(" / ") { it.take(160) }
                append("\n\nComparison: rank this slideshow (Slideshow ${LETTERS[pos]}) together with ${refs.size} real slideshows from the " +
                    "research library, best to worst, by how far each would beat the views its account usually gets. " +
                    "You are not told how any of them did. Return `rank_order`: all ${names.size} letters (${names.joinToString(", ")}), best first.")
                var k = 0
                for (letter in names) {
                    if (letter == LETTERS[pos]) append("\nSlideshow $letter (this one): $own")
                    else append("\nSlideshow $letter: " + refs[k++].strings("texts").take(10).joinToString(" / ") { it.take(160) })
                }
                val covers = refs.map { it.strings("images").firstOrNull() }
                if (dataUrls != null && covers.all { it != null } && dataUrls.size + covers.size <= MAX_IMAGES) {
                    covers.forEach { dataUrls += dataUrl(it!!) }
                    append("\nAfter this slideshow's slides, the cover slide of each other slideshow is attached in letter order.")
                }
            }
        }
        val read = llm.completeJson(SYSTEM_PROMPT, user, readSchema(refs != null, transcribe), images = dataUrls, cfg = cfg)
        var texts = slideTexts(spec)
        if (transcribe) {
            val got = read.strings("slide_texts").map { it.trim() }.take(slides.size)
            spec = Models.SPEC.of(spec.copyObj().also { sp ->
                sp["slides"] = sp.objs("slides").mapIndexed { i, sl ->
                    sl.also { it["text_items"] = got.getOrNull(i)?.takeIf { t -> t.isNotEmpty() }?.let { t -> arr(obj("text" to t)) } ?: arr() }
                }.toMutableList<Any?>()
            })
            texts = got.filter { it.isNotEmpty() }
        }
        val rank = refs?.let { rankFromOrder(read.strings("rank_order"), it, pos) }
        return Triple(score(read, spec, visionUsed = cfg.vision, calibrated = good.size + bad.size), rank, texts)
    }

    /** Turn the model's read plus the rule checks into the numbers shown in the app. */
    fun score(read: Map<String, Any?>, spec: Map<String, Any?>, visionUsed: Boolean, calibrated: Int): Obj {
        val n = spec.objs("slides").size
        val per = read.objs("slides").take(n).toMutableList()
        while (per.size < n) per += obj("swipe_on" to "60", "visual_pull" to "50", "clarity" to "50", "note" to "")
        val hook = num(read["hook_score"])
        // Attention curve: the share of viewers who stopped on slide 1 that are still there at each slide.
        val curve = mutableListOf(1.0)
        for (i in 0 until n - 1) curve += curve.last() * (num(per[i]["swipe_on"]) / 100.0).coerceIn(0.3, 0.99)
        val hold = curve.last()
        val (format, problems) = formatCheck(spec)
        val dims = linkedMapOf<String, Any?>(
            "visual_pull" to per.map { num(it["visual_pull"]) }.average().toInt(),
            "curiosity" to num(read["curiosity"]),
            "clarity" to ((per.map { num(it["clarity"]) }.average() * 0.7) + format * 0.3).toInt(),
            "relatability" to num(read["relatability"]),
            "shareability" to num(read["shareability"]),
        )
        val payoff = num(read["payoff"])
        val engagement = listOf(num(read["curiosity"]), num(read["relatability"]), num(read["shareability"]), payoff).average()
        val viral = (0.40 * hook + 0.25 * hold * 100 + 0.20 * engagement + 0.15 * format).toInt().coerceIn(0, 100)
        val (verdict, tag) = verdict(viral)
        // Where attention drops most: the slide to fix first.
        // Only when one slide clearly loses more viewers than the rest.
        val swipes = (0 until n - 1).map { num(per[it]["swipe_on"]) }
        val drop = swipes.indices.minByOrNull { swipes[it] }?.takeIf { swipes[it] <= swipes.average() - 8 }
        val tips = (problems + read.strings("tips")).distinct().take(6)
        return obj(
            "viral_potential" to viral,
            "hook_score" to hook,
            "hold_rate" to Math.round(hold * 100).toInt(),
            "payoff" to payoff,
            "format_score" to format,
            "verdict" to verdict,
            "verdict_tag" to tag,
            "attention" to curve.map { Math.round(it * 100).toInt() }.toMutableList<Any?>(),
            "slides" to per.mapIndexed { i, s ->
                obj("swipe_on" to num(s["swipe_on"]), "visual_pull" to num(s["visual_pull"]), "clarity" to num(s["clarity"]), "note" to s.str("note"), "attention" to Math.round(curve[i] * 100).toInt())
            }.toMutableList<Any?>(),
            "biggest_drop" to drop?.let { it + 1 },
            "dimensions" to dims,
            "tips" to tips.toMutableList<Any?>(),
            "better_hook" to read.str("better_hook"),
            "basis" to buildString {
                append(if (visionUsed) "AI read of the rendered slides" else "AI read of the slide text (this AI key can't see images)")
                append(" + format rules")
                if (calibrated > 0) append(", calibrated with $calibrated real library hooks")
            },
            "predicted_at" to Db.iso(Db.nowMs()),
        )
    }

    private fun summary(rho: Double?, what: String): String = when {
        rho == null -> "Not enough variation in views yet."
        rho >= 0.4 -> "The score tracks $what well (ρ = ${"%.2f".format(rho)}): trust it to pick between drafts."
        rho >= 0.15 -> "The score weakly tracks $what (ρ = ${"%.2f".format(rho)}): use it as a tiebreaker."
        else -> "The score isn't tracking $what (ρ = ${"%.2f".format(rho)}): don't rely on it yet."
    }

    /** Does the prediction track reality? Compare predicted scores with views of posted slideshows. */
    fun calibration(): Obj {
        val pairs = db.tx {
            val out = mutableListOf<Pair<Double, Double>>()
            for (s in where("slideshows") { it["prediction"] is Map<*, *> }) {
                val views = where("posts") { it.str("slideshow_id") == s.str("id") && it.str("status") == "published" }.maxOfOrNull { it.long("views") } ?: continue
                if (views <= 0) continue
                out += s.objAt("prediction").dbl("viral_potential") to ln(views.toDouble())
            }
            out
        }
        val rho = spearman(pairs.map { it.first }, pairs.map { it.second })
        return obj(
            "posts" to pairs.size,
            "spearman" to rho?.let { Math.round(it * 100) / 100.0 },
            "summary" to if (pairs.size < 8) {
                "Post at least 8 predicted slideshows to see whether the score tracks your views (${pairs.size} so far)."
            } else summary(rho, "your views"),
            "backtest" to lastBacktest(),
        )
    }

    // --- backtest: race the methods on real library posts whose views are already known -------------

    private fun librarySpec(post: Map<String, Any?>): Obj {
        val texts = post.strings("slide_texts")
        val slides = post.strings("images").take(10).mapIndexed { i, path ->
            val t = texts.getOrNull(i).orEmpty()
            obj("role" to if (i == 0) "hook" else "body", "image" to path,
                "text_items" to if (t.isBlank()) mutableListOf<Any?>() else mutableListOf<Any?>(obj("text" to t)))
        }
        return Models.SPEC.of(obj("title" to post.str("description").take(80).ifEmpty { "Library post" }, "slides" to slides.toMutableList<Any?>()))
    }

    /**
     * Score real library slideshows blind with every method (each post is left out of its own calibration
     * examples, references and look-alikes), measure how well each ranks them like their real views did,
     * and pick the method the app should use. One AI call per post: the read, the ranking and, for posts
     * not read yet, the slide text all come back together. Throws PipelineError when the library is too small.
     */
    fun backtest(limit: Int = 20, progress: (Int, Int) -> Unit = { _, _ -> }): Obj {
        val all = libraryRows().filter { it.strings("images").isNotEmpty() }
        val alreadyRead = all.filter { it.bool("read") }
        val sample = Methods.spread(if (alreadyRead.size >= limit) alreadyRead else all, limit) // read posts need no transcription
        if (sample.size < BACKTEST_MIN) throw PipelineError(
            "The library has ${sample.size} usable slideshows; it needs $BACKTEST_MIN. " +
                "Add accounts in your niche (3+ slideshow posts each) to the Slideshow library first.",
        )
        val cfg = llm.resolve() ?: throw LlmError("Add an AI key in Settings first.")
        val rows = mutableListOf<Obj>()
        sample.forEachIndexed { n, row ->
            val postId = row.str("id")
            progress(n, sample.size)
            if (!row.bool("read") && !cfg.vision) return@forEachIndexed // neither the slide text nor a way to read it
            val post = db.tx { get("library_posts", postId)?.copyObj() } ?: return@forEachIndexed
            val others = libraryRows().filter { it.str("id") != postId }
            val images = post.strings("images")
            val (result, rank, texts) = try {
                read(librarySpec(post), post.str("description"), "", images, exclude = postId,
                    refs = references(others), seed = postId, transcribe = !row.bool("read"))
            } catch (e: RuntimeException) { return@forEachIndexed }
            if (!row.bool("read") && texts.isNotEmpty()) {
                db.tx { get("library_posts", postId)?.set("slide_texts", texts.toMutableList<Any?>()) } // the library gets read for free
            }
            val similar = Methods.similarEvidence(texts.joinToString(" "), others)
            rows += obj(
                "post_id" to postId, "url" to row.str("url"), "username" to row.str("username"),
                "hook" to (texts.firstOrNull() ?: post.str("description").take(80)), "views" to row.long("views"),
                "lift" to Methods.round2(Math.exp(row.dbl("lift"))), "lift_log" to row.dbl("lift"),
                "score" to result.int("viral_potential"), "f" to features(result, rank, similar),
            )
        }
        // Noise: the same slideshow read twice should get about the same score.
        val drifts = rows.take(NOISE_POSTS).mapNotNull { r ->
            val id = r.str("post_id")
            val post = db.tx { get("library_posts", id)?.copyObj() } ?: return@mapNotNull null
            val others = libraryRows().filter { it.str("id") != id }
            try {
                val again = read(librarySpec(post), post.str("description"), "", post.strings("images"), exclude = id, refs = references(others), seed = id).first
                Math.abs(again.int("viral_potential") - r.int("score")).toDouble()
            } catch (e: RuntimeException) { null }
        }
        progress(sample.size, sample.size)
        val cmp = if (rows.size >= BACKTEST_MIN) Methods.compare(rows) else null
        val rho = cmp?.methods?.first { it.str("method") == "current" }?.get("spearman") as? Double
        var summary = if (cmp == null) "Only ${rows.size} library slideshows could be scored; the test needs $BACKTEST_MIN."
        else summary(rho, "real views in your library")
        if (cmp != null) {
            if (cmp.winner != "current") {
                val won = cmp.methods.first { it.str("method") == cmp.winner }
                summary += " ${won.str("label")} did better (ρ = ${won["spearman"]}), so the app now scores with it."
            } else if ((rho == null || rho < Methods.USEFUL) && cmp.methods.all { ((it["spearman"] as? Double) ?: 0.0) < Methods.USEFUL }) {
                summary += " No other method tracked views either, so treat every score as a guess for now."
            }
        }
        val out = obj(
            "version" to BACKTEST_VERSION, "posts" to rows.size, "spearman" to rho, "summary" to summary,
            "method" to (cmp?.winner ?: "current"), "methods" to (cmp?.methods ?: emptyList()).toMutableList<Any?>(),
            "model" to cmp?.model, "populations" to (cmp?.populations ?: obj()),
            "noise" to if (drifts.isEmpty()) null else Math.round(drifts.average() * 10) / 10.0,
            "rows" to rows.sortedByDescending { it.int("score") }.map { r ->
                val f = r.objAt("f")
                (r.filterKeys { it != "f" && it != "lift_log" } + mapOf("rank" to f["rank"], "similar" to f["similar"])).toMutableMap()
            }.toMutableList<Any?>(),
            "ran_at" to Db.iso(java.time.Instant.now()),
        )
        db.tx { (get("state", "virality_backtest") ?: insert("state", obj("id" to "virality_backtest")))["result"] = out }
        return out
    }

    fun lastBacktest(): Obj? = db.tx { get("state", "virality_backtest")?.get("result") as? Map<String, Any?> }?.toMutableMap()
}
