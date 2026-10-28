package com.slidemill.core

import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Tool answers shrunk for Claude's context, where every character is paid for: no nulls or empty
 * values, lists of similar records as one table ({"cols": [...], "rows": [[...]]}) instead of repeating
 * every key, TikTok post links as bare post ids, and timestamps as short phone-local times.
 */
object Lean {
    private val ISO = Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(:\\d{2}(\\.\\d+)?)?(Z|[+-]\\d{2}:\\d{2})$")
    private val POST_URL = Regex("^https://www\\.tiktok\\.com/@[^/]+/(photo|video)/(\\d+)$")
    private val SHORT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    const val MIN_TABLE = 3

    fun write(v: Any?, zone: ZoneId): String = Json.write(lean(v, zone))

    fun lean(v: Any?, zone: ZoneId): Any? = when (v) {
        is Map<*, *> -> {
            val out = obj()
            for ((k, x) in v) {
                val key = k.toString()
                val y = lean(x, zone)
                if (empty(y)) continue
                val post = if (key == "url" && y is String) POST_URL.find(y) else null
                if (post != null) out["post_id"] = post.groupValues[2] else out[key] = y
            }
            out
        }
        is List<*> -> {
            val items = v.map { lean(it, zone) }.filterNot { empty(it) }
            table(items) ?: items.toMutableList()
        }
        is String -> if (ISO.matches(v)) runCatching { OffsetDateTime.parse(v).atZoneSameInstant(zone).format(SHORT) }.getOrDefault(v) else v
        is Double -> if (v == Math.rint(v) && Math.abs(v) < 1e15) v.toLong() else Math.round(v * 100) / 100.0
        else -> v
    }

    private fun empty(v: Any?) = v == null || (v is String && v.isEmpty()) || (v is Collection<*> && v.isEmpty()) || (v is Map<*, *> && v.isEmpty())

    /** Three or more flat records become one table; columns are every key seen, in first-seen order. */
    private fun table(items: List<Any?>): Obj? {
        if (items.size < MIN_TABLE || items.any { it !is Map<*, *> }) return null
        // One level of nesting ({"slidemill": {"score": 60}}) flattens to "slidemill.score".
        val maps = items.map { m ->
            val flat = LinkedHashMap<String, Any?>()
            for ((k, x) in m as Map<*, *>) {
                if (x is Map<*, *>) x.forEach { (k2, y) -> flat["$k.$k2"] = y } else flat[k.toString()] = x
            }
            flat
        }
        if (maps.any { m -> m.values.any { it is Map<*, *> || (it is List<*> && it.any { x -> x is Map<*, *> || x is List<*> }) } }) return null
        val cols = LinkedHashSet<String>().also { s -> maps.forEach { m -> m.keys.forEach { s += it.toString() } } }.toList()
        return obj("cols" to cols.toMutableList<Any?>(), "rows" to maps.map { m -> cols.map { m[it] }.toMutableList<Any?>() }.toMutableList<Any?>())
    }
}
