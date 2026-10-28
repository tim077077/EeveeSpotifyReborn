package com.slidemill.core

import kotlin.math.exp

/**
 * Which carousel formats beat their account's usual views, from the research library (rows from
 * [Virality.libraryRows]: each post's log views against its own account's median, so a big account
 * doesn't win just for being big). Groups posts by structure (slide count, words per slide, hook
 * style, closing call to action, sound) and lists the best posts as examples, so Claude can copy the
 * format, never the words.
 */
object Formats {
    const val MIN_BUCKET = 3 // posts a group needs before it counts
    const val MIN_POSTS = 8

    private val LISTICLE = Regex("^\\s*(\\d+|top \\d+)\\b|\\b\\d+\\s+(ways|things|tips|tricks|mistakes|reasons|signs|steps|rules|hacks|habits|apps|items|brands|ideas)\\b", RegexOption.IGNORE_CASE)
    private val HOW = Regex("\\bhow (to|i|we|you)\\b", RegexOption.IGNORE_CASE)
    private val POV = Regex("^\\s*pov\\b", RegexOption.IGNORE_CASE)
    private val WARNING = Regex("\\b(stop|don'?t|never|avoid|mistakes?|wrong|warning|red flags?)\\b", RegexOption.IGNORE_CASE)
    private val STORY = Regex("\\b(i|my|me)\\b", RegexOption.IGNORE_CASE)
    private val CTA = Regex("\\b(follow|save|comment|share|link in bio|part \\d|dm)\\b", RegexOption.IGNORE_CASE)

    fun hookStyle(hook: String): String = when {
        hook.isBlank() -> "unknown"
        LISTICLE.containsMatchIn(hook) -> "numbered list (\"5 tips…\")"
        POV.containsMatchIn(hook) -> "POV"
        HOW.containsMatchIn(hook) -> "how-to / how I"
        hook.trim().endsWith("?") -> "question"
        WARNING.containsMatchIn(hook) -> "warning (\"stop…\", \"mistakes\")"
        STORY.containsMatchIn(hook) -> "personal story"
        else -> "statement"
    }

    fun slideBucket(n: Int): String = when {
        n <= 4 -> "2-4 slides"
        n <= 6 -> "5-6 slides"
        n <= 9 -> "7-9 slides"
        else -> "10+ slides"
    }

    fun wordsPerSlide(texts: List<String>): Int? =
        texts.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }?.let { t -> t.sumOf { s -> s.trim().split(Regex("\\s+")).size } / t.size }

    private fun wordsBucket(w: Int) = when {
        w <= 8 -> "≤8 words a slide"
        w <= 20 -> "9-20 words a slide"
        else -> "21+ words a slide"
    }

    private fun x(logLift: Double) = Methods.round2(exp(logLift))

    private fun medianLift(rows: List<Obj>): Double {
        val s = rows.map { it.dbl("lift") }.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }

    /**
     * [rows]: library rows plus each post's "music" and "slide_count". Returns groups and the best
     * [examples] posts (with their image paths, for the caller to attach).
     */
    fun analyze(rows: List<Obj>, examples: Int = 4): Obj {
        val accounts = rows.map { it.str("username") }.toSet()
        if (rows.size < MIN_POSTS) return obj(
            "posts_used" to rows.size.toLong(), "accounts" to accounts.size.toLong(),
            "summary" to "Not enough yet: ${rows.size} library slideshows with known lift (needs $MIN_POSTS, from accounts with 3+ each). " +
                "Run discover_slideshows with a few search terms for the niche first.",
            "examples" to arr(),
        )
        val features: Map<String, (Obj) -> String?> = linkedMapOf(
            "slide_count" to { r -> slideBucket(r.long("slide_count").toInt().coerceAtLeast(r.strings("images").size)) },
            "words_per_slide" to { r -> if (r.bool("read")) wordsPerSlide(r.strings("texts"))?.let { wordsBucket(it) } else null },
            "hook_style" to { r -> if (r.bool("read")) hookStyle(r.str("hook")) else null },
            "ends_with_call_to_action" to { r -> if (r.bool("read")) (if (CTA.containsMatchIn(r.strings("texts").lastOrNull().orEmpty())) "yes" else "no") else null },
            "sound" to { r -> r.str("music").let { if (it.isEmpty()) null else if (it.lowercase().startsWith("original sound")) "original sound" else "a named track" } },
        )
        val by = obj()
        val findings = mutableListOf<Pair<Double, String>>()
        for ((name, f) in features) {
            val groups = rows.mapNotNull { r -> f(r)?.let { it to r } }.groupBy({ it.first }, { it.second })
            val out = groups.map { (bucket, rs) ->
                obj(
                    "bucket" to bucket, "posts" to rs.size.toLong(), "accounts" to rs.map { it.str("username") }.toSet().size.toLong(),
                    "median_lift" to x(medianLift(rs)), "enough_data" to (rs.size >= MIN_BUCKET),
                )
            }.sortedByDescending { it.dbl("median_lift") }
            by[name] = out.toMutableList<Any?>()
            val ok = out.filter { it.bool("enough_data") }
            if (ok.size >= 2) {
                val best = ok.first()
                val worst = ok.last()
                if (best.dbl("median_lift") >= 1.2 && best.dbl("median_lift") >= worst.dbl("median_lift") * 1.3) {
                    findings += best.dbl("median_lift") to "$name: ${best.str("bucket")} does ${best["median_lift"]}× its account's usual views " +
                        "(${best.long("posts")} posts, ${best.long("accounts")} accounts); ${worst.str("bucket")} does ${worst["median_lift"]}×."
                }
            }
        }
        val unread = rows.count { !it.bool("read") }
        val best = rows.sortedByDescending { it.dbl("lift") }.take(examples.coerceIn(1, 8)).map { r ->
            obj(
                "post_id" to r.str("id"), "username" to r.str("username"), "url" to r.str("url"), "lift" to x(r.dbl("lift")),
                "views" to r.long("views"), "slides" to r.long("slide_count").coerceAtLeast(r.strings("images").size.toLong()),
                "hook" to r.str("hook").take(200), "hook_style" to if (r.bool("read")) hookStyle(r.str("hook")) else "slide text not read yet",
                "words_per_slide" to if (r.bool("read")) wordsPerSlide(r.strings("texts"))?.toLong() else null,
                "slide_texts" to if (r.bool("read")) r.strings("texts").map { it.take(160) }.toMutableList<Any?>() else arr(),
                "images" to r.strings("images").toMutableList<Any?>(),
            )
        }
        return obj(
            "posts_used" to rows.size.toLong(), "accounts" to accounts.size.toLong(),
            "summary" to (findings.sortedByDescending { it.first }.joinToString(" ") { it.second }.ifEmpty {
                "No structural pattern stands out (nothing does 1.2× and clearly beats the rest with 3+ posts). The topic and hook decide: study the examples."
            } + if (unread > 0) " $unread posts have no slide text yet, so hook and words-per-slide skip them: read_library_slides fixes that." else ""),
            "by" to by,
            "examples" to best.toMutableList<Any?>(),
            "how_to_use" to "Copy the FORMAT of the winners: slide count, words per slide, hook style, text placement and look (see the images), " +
                "ending. Write your own words and your own topic angle: never copy a post's text or images. TikTok holds back " +
                "reposted or near-duplicate content, and lift is relative to each account's usual views.",
        )
    }
}
