package com.slidemill.core

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The user's own TikTok numbers, for the app's Analytics tab and Claude's `analytics` tool.
 *
 * TikTok's web pages only show each post's current totals, so every refresh also stores a snapshot
 * (`post_snapshots`, `account_snapshots`). Growth over time (views gained per week, in the last 24
 * hours, followers gained) comes from the difference between snapshots, and is only known from the
 * first refresh on: [report] says so instead of guessing.
 */
class Analytics(private val db: Db, private val platform: Platform) {
    companion object {
        const val MIN_REFRESH_MS = 10 * 60_000L // a refresh lists the whole profile: not more often than this
        const val MAX_POSTS = 100
        const val KEEP_ALL_SNAPSHOTS_DAYS = 14L // older snapshots are thinned to one per post per day
        private const val DAY_S = 86_400L
        private val STATS = listOf("views", "likes", "comments", "shares", "saves")

        fun engagementRate(p: Map<String, Any?>): Double? {
            val views = p.long("views")
            if (views <= 0) return null
            val acts = p.long("likes") + p.long("comments") + p.long("shares") + p.long("saves")
            return Math.round(acts * 1000.0 / views) / 10.0
        }

        fun median(xs: List<Long>): Long = if (xs.isEmpty()) 0 else xs.sorted().let { s ->
            if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
        }
    }

    private val refreshing = AtomicBoolean(false)

    fun lastRefreshMs(): Long = db.tx { get("state", "analytics")?.long("last_refresh") ?: 0L }

    /**
     * List the logged-in account's posts on the phone and record them. Skipped (returns false) when the
     * last refresh is under [MIN_REFRESH_MS] old, unless [force]. Throws [DraftError] when TikTok can't
     * be read (not logged in, page not answering).
     */
    fun refresh(tab: TikTokTab?, force: Boolean = false): Boolean {
        if (!force && Db.nowMs() - lastRefreshMs() < MIN_REFRESH_MS) return false
        if (!refreshing.compareAndSet(false, true)) return false // one at a time; the running one will record
        try {
            val t = tab ?: throw DraftError("The phone's TikTok tab isn't running: open the Slidemill app.")
            val user = t.username().ifEmpty { throw DraftError("Not logged in to TikTok on the phone: open Slidemill → TikTok and log in.") }
            val items = platform.listProfilePosts(user, MAX_POSTS).map { Library.normalizeWebItem(it) }
            if (items.isEmpty()) throw DraftError("TikTok didn't list any posts for @$user (no posts yet, or the page didn't load).")
            record(user, items)
            return true
        } finally {
            refreshing.set(false)
        }
    }

    /** Store one listing (normalized web items): each post's latest totals plus a snapshot. */
    fun record(username: String, items: List<Obj>, atMs: Long = Db.nowMs()) = db.tx {
        for (p in items) {
            val id = p.str("id")
            if (id.isEmpty()) continue
            val row = get("my_posts", id) ?: insert("my_posts", obj("id" to id, "first_seen" to atMs))
            row["username"] = username
            row["description"] = p.str("description")
            row["is_slideshow"] = p.bool("is_slideshow")
            row["posted_at"] = p.long("posted_at")
            row["music"] = p.str("music")
            for (k in STATS) row[k] = p.long(k)
            row["updated_at"] = atMs
            val snap = obj("id" to "$id@$atMs", "post_id" to id, "at" to atMs)
            for (k in STATS) snap[k] = p.long(k)
            insert("post_snapshots", snap)
        }
        items.maxByOrNull { it.long("posted_at") }?.objAt("author")?.takeIf { it["followers"] != null }?.let { a ->
            insert("account_snapshots", obj(
                "id" to "$atMs", "at" to atMs, "username" to username,
                "followers" to a.long("followers"), "total_likes" to a.long("total_likes"), "video_count" to a.long("video_count"),
            ))
        }
        val state = get("state", "analytics") ?: insert("state", obj("id" to "analytics", "since" to atMs))
        state["last_refresh"] = atMs
        state["username"] = username
        // Thin old snapshots to the last one per post per day, so the history stays small.
        val cutoff = atMs - KEEP_ALL_SNAPSHOTS_DAYS * DAY_S * 1000
        val old = where("post_snapshots") { it.long("at") < cutoff }
        val keep = old.groupBy { it.str("post_id") + "/" + it.long("at") / (DAY_S * 1000) }.values.map { day -> day.maxBy { it.long("at") }.str("id") }.toSet()
        old.filter { it.str("id") !in keep }.forEach { delete("post_snapshots", it.str("id")) }
    }

    private class History(val postedAt: Long, val snaps: List<Pair<Long, Long>>) { // (at seconds, views)
        /**
         * Views at time t (seconds): 0 before it was posted; otherwise the last snapshot at or before t, if it's
         * at most a day older than t (with a longer gap, growth can't be placed in time: null).
         */
        fun viewsAt(t: Long): Long? {
            if (postedAt > 0 && t <= postedAt) return 0
            return snaps.lastOrNull { it.first <= t }?.takeIf { t - it.first <= DAY_S }?.second
        }

        fun latest(): Long? = snaps.lastOrNull()?.second
    }

    /**
     * Everything at once: account, totals, the last 7 days against the 7 before, views per week (by
     * posting week, and gained in each week once tracked), and per-post numbers newest first.
     * [predicted] maps a TikTok post id to the Slidemill slideshow and score it came from.
     */
    fun report(weeks: Int = 8, postsLimit: Int = 30, zone: ZoneId = ZoneId.of(platform.defaultTimezone()), predicted: Map<String, Obj> = emptyMap()): Obj {
        val posts = db.tx { all("my_posts").map { it.copyObj() } }
        val snaps = db.tx { all("post_snapshots").map { it.copyObj() } }
        val accounts = db.tx { all("account_snapshots").map { it.copyObj() } }
        val state = db.tx { get("state", "analytics")?.copyObj() }
        if (state == null || posts.isEmpty()) {
            return obj("status" to "No analytics yet: refresh once (the app's Analytics tab, or analytics with refresh=true). The phone must be logged in to TikTok.")
        }
        val nowS = Db.nowMs() / 1000
        val sinceS = state.long("since") / 1000
        val history = snaps.groupBy { it.str("post_id") }.mapValues { (_, s) -> s.map { it.long("at") / 1000 to it.long("views") }.sortedBy { it.first } }
        val hist = posts.associate { p -> p.str("id") to History(p.long("posted_at"), history[p.str("id")].orEmpty()) }

        /** Views gained between two times across all posts; null when tracking hadn't started by [from]. */
        fun gained(from: Long, to: Long): Long? {
            if (from < sinceS) return null
            var sum = 0L
            for (h in hist.values) {
                val a = h.viewsAt(from) ?: return null
                val b = (if (to >= nowS) h.latest() else h.viewsAt(to)) ?: return null
                sum += maxOf(0, b - a)
            }
            return sum
        }

        val views = posts.map { it.long("views") }
        val totals = obj("posts" to posts.size.toLong())
        for (k in STATS) totals[k] = posts.sumOf { it.long(k) }
        totals["avg_views"] = if (posts.isEmpty()) 0L else totals.long("views") / posts.size
        totals["median_views"] = median(views)
        totals["engagement_rate_pct"] = engagementRate(totals)

        fun window(from: Long, to: Long) = obj(
            "views_gained" to gained(from, to),
            "posts_published" to posts.count { it.long("posted_at") in from until to }.toLong(),
        )
        val last7 = window(nowS - 7 * DAY_S, nowS)
        val prev7 = window(nowS - 14 * DAY_S, nowS - 7 * DAY_S)

        val thisMonday = Instant.ofEpochSecond(nowS).atZone(zone).toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val weekRows = (weeks.coerceIn(1, 26) - 1 downTo 0).map { back ->
            val day = thisMonday.minusWeeks(back.toLong())
            val from = day.atStartOfDay(zone).toEpochSecond()
            val to = day.plusWeeks(1).atStartOfDay(zone).toEpochSecond()
            val published = posts.filter { it.long("posted_at") in from until to }
            obj(
                "week_of" to day.toString(),
                "posts_published" to published.size.toLong(),
                "views_on_posts_published" to published.sumOf { it.long("views") },
                "avg_views_per_post" to if (published.isEmpty()) null else published.sumOf { it.long("views") } / published.size,
                "views_gained" to gained(from, to),
            )
        }

        val account = obj("username" to state.str("username"), "last_refresh" to Db.iso(state.long("last_refresh")), "tracking_since" to Db.iso(state.long("since")))
        accounts.maxByOrNull { it.long("at") }?.let { a ->
            account["followers"] = a.long("followers")
            account["total_likes"] = a.long("total_likes")
            val weekAgo = accounts.filter { it.long("at") / 1000 <= nowS - 7 * DAY_S }.maxByOrNull { it.long("at") }
            account["followers_gained_7d"] = weekAgo?.let { a.long("followers") - it.long("followers") }
        }

        fun postRow(p: Obj): Obj {
            val h = hist.getValue(p.str("id"))
            val age = if (p.long("posted_at") > 0) maxOf(1L, nowS - p.long("posted_at")) else null
            val user = state.str("username")
            val out = obj(
                "url" to "https://www.tiktok.com/@$user/${if (p.bool("is_slideshow")) "photo" else "video"}/${p.str("id")}",
                "description" to p.str("description").take(150),
                "type" to if (p.bool("is_slideshow")) "slideshow" else "video",
                "posted_at" to if (p.long("posted_at") > 0) Db.iso(p.long("posted_at") * 1000) else null,
                "age_days" to age?.let { Math.round(it * 10.0 / DAY_S) / 10.0 },
            )
            for (k in STATS) out[k] = p.long(k)
            out["engagement_rate_pct"] = engagementRate(p)
            out["views_per_day"] = age?.let { Math.round(p.long("views") * DAY_S.toDouble() / it) }
            out["views_last_24h"] = h.viewsAt(nowS - DAY_S)?.let { maxOf(0, p.long("views") - it) }
            predicted[p.str("id")]?.let { out["slidemill"] = it }
            return out
        }
        val newest = posts.sortedByDescending { it.long("posted_at") }
        val byType = posts.groupBy { if (it.bool("is_slideshow")) "slideshow" else "video" }.mapValues { (_, ps) ->
            obj("posts" to ps.size.toLong(), "avg_views" to ps.sumOf { it.long("views") } / ps.size, "median_views" to median(ps.map { it.long("views") }), "engagement_rate_pct" to engagementRate(obj(*STATS.map { k -> k to ps.sumOf { it.long(k) } }.toTypedArray())))
        }

        return obj(
            "account" to account,
            "totals" to totals,
            "since_tracking" to obj("from" to Db.iso(state.long("since")), "views_gained" to gained(sinceS, nowS)),
            "last_7_days" to last7,
            "previous_7_days" to prev7,
            "weeks" to weekRows.toMutableList<Any?>(),
            "by_type" to byType.toMutableMap<String, Any?>(),
            "top_by_views" to posts.sortedByDescending { it.long("views") }.take(3).map { postRow(it) }.toMutableList<Any?>(),
            "top_by_engagement" to posts.filter { it.long("views") >= 100 }.sortedByDescending { engagementRate(it) ?: 0.0 }.take(3).map { postRow(it) }.toMutableList<Any?>(),
            "posts" to newest.take(postsLimit.coerceIn(1, MAX_POSTS)).map { postRow(it) }.toMutableList<Any?>(),
            "prediction_check" to Virality.predictionCheck(posts.mapNotNull { p ->
                val score = (predicted[p.str("id")]?.get("predicted_score") as? Number)?.toDouble()
                if (score == null || p.long("posted_at") <= 0 || nowS - p.long("posted_at") < Virality.CHECK_AGE_S) null else score to p.long("views").toDouble()
            }),
            "notes" to ("Totals cover the posts TikTok listed (up to $MAX_POSTS newest). Views gained per week / in 24h come from " +
                "snapshots and are null before tracking started (${Db.iso(state.long("since"))})."),
        )
    }
}
