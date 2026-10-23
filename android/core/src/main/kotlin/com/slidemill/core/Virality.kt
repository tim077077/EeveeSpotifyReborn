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
    fun calibrationExamples(limit: Int = 6): Pair<List<String>, List<String>> = db.tx {
        val byProfile = where("library_posts") { it.bool("is_slideshow") && it.long("views") > 0 }.groupBy { it.str("profile_id") }
        val scored = mutableListOf<Pair<Double, String>>()
        for (posts in byProfile.values) {
            if (posts.size < 3) continue
            val median = Timing.median(posts.map { it.long("views") }).takeIf { it > 0 } ?: continue
            for (p in posts) {
                val hook = p.strings("slide_texts").firstOrNull()?.takeIf { it.isNotBlank() }
                    ?: p.str("description").replace(Regex("#\\S+"), "").trim().takeIf { it.isNotBlank() } ?: continue
                scored += ln(p.long("views") / median) to "\"${hook.take(120)}\" (${"%.1f".format(p.long("views") / median)}x the account's usual views)"
            }
        }
        val sorted = scored.sortedByDescending { it.first }
        sorted.take(limit / 2).filter { it.first > 0 }.map { it.second } to
            sorted.takeLast(limit / 2).filter { it.first < 0 }.map { it.second }
    }

    /** Predict and store the result on the slideshow. Throws LlmError / PipelineError. */
    fun predict(showId: String): Obj {
        val show = db.tx { get("slideshows", showId)?.copyObj() } ?: throw PipelineError("slideshow not found")
        if (show.str("status") != "completed" || show.strings("rendered").isEmpty()) throw PipelineError("Render the slideshow first.")
        val spec = Models.SPEC.of(show.objAt("spec"))
        val slides = spec.objs("slides")
        val cfg = llm.resolve() ?: throw LlmError("Add an AI key in Settings first.")

        val (good, bad) = calibrationExamples()
        val user = buildString {
            append("Caption: ${show.str("caption")}\nDescription: ${show.str("description")}\n\nSlides (text as written on each image):\n")
            slides.forEachIndexed { i, s ->
                append("${i + 1}. [${s.str("role")}] ${s.objs("text_items").joinToString(" / ") { it.str("text") }}\n")
            }
            if (good.isNotEmpty() || bad.isNotEmpty()) {
                append("\nCalibration from real slideshows in this creator's research library:\n")
                good.forEach { append("- over-performed: $it\n") }
                bad.forEach { append("- under-performed: $it\n") }
            }
            append("\nThere are ${slides.size} slides; return exactly ${slides.size} entries in `slides`.")
            if (cfg.vision) append(" The rendered slides are attached in order: judge the actual images and how readable the text is on them.")
        }
        val images = if (cfg.vision) show.strings("rendered").take(10).map {
            "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(File(mediaDir, it).readBytes())
        } else null
        val read = llm.completeJson(SYSTEM_PROMPT, user, READ, images = images, cfg = cfg)
        val result = score(read, spec, visionUsed = cfg.vision, calibrated = good.size + bad.size)
        db.tx { get("slideshows", showId)?.set("prediction", result) }
        return result
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

    /** Does the prediction track reality? Compare predicted scores with views of posted slideshows. */
    fun calibration(): Obj = db.tx {
        val pairs = mutableListOf<Pair<Double, Double>>()
        for (s in where("slideshows") { it["prediction"] is Map<*, *> }) {
            val views = where("posts") { it.str("slideshow_id") == s.str("id") && it.str("status") == "published" }.maxOfOrNull { it.long("views") } ?: continue
            if (views <= 0) continue
            pairs += s.objAt("prediction").dbl("viral_potential") to ln(views.toDouble())
        }
        val rho = spearman(pairs.map { it.first }, pairs.map { it.second })
        obj(
            "posts" to pairs.size,
            "spearman" to rho?.let { Math.round(it * 100) / 100.0 },
            "summary" to when {
                pairs.size < 8 -> "Post at least 8 predicted slideshows to see whether the score tracks your views (${pairs.size} so far)."
                rho == null -> "Not enough variation in views yet."
                rho >= 0.4 -> "The score tracks your views well (ρ = ${"%.2f".format(rho)}): trust it to pick between drafts."
                rho >= 0.15 -> "The score weakly tracks your views (ρ = ${"%.2f".format(rho)}): use it as a tiebreaker."
                else -> "The score isn't tracking your views (ρ = ${"%.2f".format(rho)}): don't rely on it for this account yet."
            },
        )
    }
}
