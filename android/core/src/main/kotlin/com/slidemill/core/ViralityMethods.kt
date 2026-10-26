package com.slidemill.core

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * The pure maths behind the virality methods (same as app/virality.py): look-alike posts by TF-IDF,
 * percentiles, ridge regression for learned weights, and the race that picks which method the app
 * scores with. Library rows are Objs with id, username, url, hook, texts, images, views and lift
 * (log views vs the account's median).
 */
object Methods {
    val LABELS = linkedMapOf(
        "current" to "AI score", "rank" to "Rank vs library", "similar" to "Posts like this",
        "blend" to "Blend of all three", "learned" to "Learned from your library",
    )
    val FEATURES = listOf("hook", "hold", "engagement", "format", "rank", "similar")
    const val SWITCH_MARGIN = 0.05 // a method has to beat the AI score by this much ρ before the app switches to it
    const val USEFUL = 0.15 // ...and track views at least weakly itself; beating a useless score by luck doesn't count
    const val LEARN_MIN = 30
    const val MIN_POSTS = 8

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

    // --- learned weights ---

    fun vector(f: Map<String, Any?>): List<Double> = FEATURES.map { (f[it] as? Number)?.toDouble() ?: 50.0 }

    private fun solve(a: List<DoubleArray>, b: DoubleArray): DoubleArray {
        val n = b.size
        val m = Array(n) { i -> a[i].copyOf(n + 1).also { it[n] = b[i] } }
        for (c in 0 until n) {
            val piv = (c until n).maxBy { Math.abs(m[it][c]) }
            val t = m[c]; m[c] = m[piv]; m[piv] = t
            if (Math.abs(m[c][c]) < 1e-12) continue
            for (r in 0 until n) if (r != c) {
                val f = m[r][c] / m[c][c]
                for (j in 0..n) m[r][j] -= f * m[c][j]
            }
        }
        return DoubleArray(n) { if (Math.abs(m[it][it]) > 1e-12) m[it][n] / m[it][it] else 0.0 }
    }

    /** Ridge regression of log lift on standardized features. */
    fun fit(xs: List<List<Double>>, ys: List<Double>, lam: Double = 1.0): Obj {
        val n = xs.size
        val k = xs[0].size
        val mu = (0 until k).map { j -> xs.sumOf { it[j] } / n }
        val sd = (0 until k).map { j -> sqrt(xs.sumOf { (it[j] - mu[j]) * (it[j] - mu[j]) } / n).takeIf { it > 0 } ?: 1.0 }
        val z = xs.map { r -> (0 until k).map { j -> (r[j] - mu[j]) / sd[j] } }
        val ym = ys.average()
        val a = (0 until k).map { i -> DoubleArray(k) { j -> z.sumOf { it[i] * it[j] } + if (i == j) lam else 0.0 } }
        val b = DoubleArray(k) { i -> z.indices.sumOf { r -> z[r][i] * (ys[r] - ym) } }
        return obj("mu" to mu.toMutableList<Any?>(), "sd" to sd.toMutableList<Any?>(), "w" to solve(a, b).map { it as Any? }.toMutableList(), "b" to ym)
    }

    fun applyFit(model: Map<String, Any?>, x: List<Double>): Double {
        val mu = model.list("mu").map { (it as Number).toDouble() }
        val sd = model.list("sd").map { (it as Number).toDouble() }
        val w = model.list("w").map { (it as Number).toDouble() }
        return model.dbl("b") + x.indices.sumOf { w[it] * (x[it] - mu[it]) / sd[it] }
    }

    private fun pop(bt: Map<String, Any?>, m: String): List<Double> = bt.objAt("populations").list(m).map { (it as Number).toDouble() }

    /** A 0-100 score for one slideshow's features under `method`, using the library test's populations. */
    fun methodValue(method: String, f: Map<String, Any?>, bt: Map<String, Any?>): Int? {
        fun v(k: String) = (f[k] as? Number)?.toDouble()
        return when (method) {
            "current" -> v("current")?.toInt()
            "rank", "similar" -> v(method)?.toInt()
            "blend" -> listOf("current", "rank", "similar").mapNotNull { m -> v(m)?.let { x -> pop(bt, m).takeIf { it.isNotEmpty() }?.let { pct(x, it) } } }
                .takeIf { it.isNotEmpty() }?.let { Math.round(it.average()).toInt() }
            "learned" -> (bt["model"] as? Map<String, Any?>)?.let { Math.round(pct(applyFit(it, vector(f)), pop(bt, "learned"))).toInt() }
            else -> null
        }
    }

    class Comparison(val methods: List<Obj>, val winner: String, val model: Obj?, val populations: Obj)

    /** ρ of every method on rows ({f, lift_log}), the winner, the learned model (if it won) and populations. */
    fun compare(rows: List<Obj>): Comparison {
        val ys = rows.map { it.dbl("lift_log") }
        val pops = obj()
        for (m in listOf("current", "rank", "similar")) pops[m] = rows.mapNotNull { (it.objAt("f")[m] as? Number)?.toDouble() }.toMutableList<Any?>()
        val bt = obj("populations" to pops)
        val results = mutableListOf<Obj>()
        fun add(method: String, scores: List<Double?>) {
            val pairs = scores.zip(ys).filter { it.first != null }
            val rho = if (pairs.size >= MIN_POSTS) Virality.spearman(pairs.map { it.first!! }, pairs.map { it.second }) else null
            results += obj("method" to method, "label" to LABELS[method], "posts" to pairs.size, "spearman" to rho?.let { round2(it) })
        }
        for (m in listOf("current", "rank", "similar", "blend")) add(m, rows.map { methodValue(m, it.objAt("f"), bt)?.toDouble() })
        var model: Obj? = null
        if (rows.size >= LEARN_MIN) {
            val xs = rows.map { vector(it.objAt("f")) }
            // Leave-one-out: each post is predicted by weights fitted without it.
            add("learned", xs.indices.map { i -> applyFit(fit(xs.filterIndexed { j, _ -> j != i }, ys.filterIndexed { j, _ -> j != i }), xs[i]) })
            model = fit(xs, ys)
            pops["learned"] = xs.map { applyFit(model, it) }.toMutableList<Any?>()
        }
        val rho = results.associate { it.str("method") to (it["spearman"] as? Double) }
        val singles = listOf("current", "rank", "similar", "blend").filter { rho[it] != null }
        var best = singles.maxByOrNull { rho[it]!! } ?: "current"
        if (rho["learned"] != null && rho["learned"]!! >= (rho[best] ?: -1.0) + SWITCH_MARGIN) best = "learned"
        val base = rho["current"]
        val good = rho[best] != null && rho[best]!! >= USEFUL
        val winner = if (good && (base == null || rho[best]!! >= base + SWITCH_MARGIN)) best else "current"
        if (winner != "learned") {
            model = null
            pops.remove("learned")
        }
        return Comparison(results, winner, model, pops)
    }
}
