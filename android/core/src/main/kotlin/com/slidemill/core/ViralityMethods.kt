package com.slidemill.core

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * The pure maths behind scoring against the library: look-alike posts by TF-IDF, percentiles and
 * spreading references from flops to hits. Library rows are Objs with id, username, url, hook, texts, images, views and lift
 * (log views vs the account's median).
 */
object Methods {
    fun pct(value: Double, population: List<Double>): Double {
        if (population.isEmpty()) return 50.0
        return 100.0 * (population.count { it < value } + 0.5 * population.count { it == value }) / population.size
    }

    /** k rows evenly spaced from the lowest lift to the highest. */
    fun spread(rows: List<Obj>, k: Int): List<Obj> {
        val sorted = rows.sortedBy { it.dbl("lift") }
        if (sorted.size <= k) return sorted
        return (0 until k).map { sorted[Math.round(it * (sorted.size - 1).toDouble() / (k - 1)).toInt()] }
    }

    fun round2(v: Double): Double = Math.round(v * 100) / 100.0

    // --- posts like this ---

    private val STOP = ("the a an and or of to in on for is are was it its this that my your you i me we with at be by as from " +
        "how what why when not no do so if just all").split(" ").toSet()

    fun tokens(text: String): List<String> =
        Regex("\\p{L}{2,}|\\d+").findAll(text.lowercase()).map { it.value }.filter { it !in STOP }.toList()

    /** The k library rows whose slide text is closest to `text` (TF-IDF cosine), best first. */
    fun similarPosts(text: String, rows: List<Obj>, k: Int = 5): List<Pair<Double, Obj>> {
        val query = tokens(text)
        val docs = rows.map { tokens(it.strings("texts").joinToString(" ")) }
        if (query.isEmpty() || rows.isEmpty()) return emptyList()
        val df = HashMap<String, Int>()
        docs.forEach { d -> d.toSet().forEach { df[it] = (df[it] ?: 0) + 1 } }
        fun vec(toks: List<String>): Map<String, Double> {
            val v = HashMap<String, Double>()
            toks.forEach { w -> v[w] = (v[w] ?: 0.0) + ln((docs.size + 1.0) / ((df[w] ?: 0) + 1.0)) + 1 }
            val norm = sqrt(v.values.sumOf { it * it }).takeIf { it > 0 } ?: 1.0
            return v.mapValues { it.value / norm }
        }
        val q = vec(query)
        return rows.zip(docs).filter { it.second.isNotEmpty() }
            .map { (r, d) -> val dv = vec(d); q.entries.sumOf { (w, x) -> x * (dv[w] ?: 0.0) } to r }
            .filter { it.first > 0.05 }.sortedByDescending { it.first }.take(k)
    }

    /** What library posts like this one actually got (needs 3 matches). */
    fun similarEvidence(text: String, rows: List<Obj>): Obj? {
        val top = similarPosts(text, rows)
        if (top.size < 3) return null
        val lift = top.sumOf { it.first * it.second.dbl("lift") } / top.sumOf { it.first }
        return obj(
            "score" to Math.round(pct(lift, rows.map { it.dbl("lift") })).toInt(),
            "lift" to round2(exp(lift)),
            "posts" to top.map { (sim, r) ->
                obj("hook" to r.str("hook").take(120), "url" to r.str("url"), "username" to r.str("username"),
                    "lift" to round2(exp(r.dbl("lift"))), "similarity" to round2(sim))
            }.toMutableList<Any?>(),
        )
    }
}
