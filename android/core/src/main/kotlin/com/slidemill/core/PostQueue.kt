package com.slidemill.core

import java.time.Duration
import java.time.Instant

/**
 * The posting queue (port of app/browser_queue.py). On the phone the poster in the app's TikTok
 * tab claims from it in-process; the browser extension uses the same rules over HTTP.
 *
 * Each claim hands out at most one due post, respecting a daily cap and a minimum gap. Claims that
 * never report back are released after CLAIM_TIMEOUT. Posts with `not_before` wait until then
 * (scheduled runs finish a little early and post on time).
 *
 * "draft" posts are never posted publicly: a poster that can save drafts saves them to TikTok Studio's
 * drafts, and they wait in the queue until one can (never handed to the TikTok app).
 */
class PostQueue(private val db: Db, private val store: Store) {
    companion object {
        val CLAIM_TIMEOUT: Duration = Duration.ofMinutes(20)
        const val MAX_ATTEMPTS = 3
        const val DEFAULT_POSTS_PER_DAY = 6
        const val DEFAULT_MIN_GAP_MINUTES = 20
    }

    /** Last check-in from the poster (shown in Settings). */
    val lastSeen = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun intSetting(key: String, default: Int): Int =
        store.get(key).ifEmpty { default.toString() }.toIntOrNull()?.let { maxOf(0, it) } ?: default

    fun limits(): Pair<Int, Int> =
        intSetting("browser_posts_per_day", DEFAULT_POSTS_PER_DAY) to intSetting("browser_min_gap_minutes", DEFAULT_MIN_GAP_MINUTES)

    fun queuePost(tx: Db.Tx, slideshow: Obj, action: String = "publish", sound: Boolean = true, notBefore: Instant? = null): Obj =
        tx.insert("posts", obj(
            "slideshow_id" to slideshow.str("id"),
            "tiktok_account_id" to null,
            "post_mode" to "BROWSER",
            "publish_id" to "",
            "status" to "queued",
            "claimed_at" to null,
            "attempts" to 0L,
            "tiktok_username" to "",
            "options" to obj("action" to action, "sound" to sound),
            "not_before" to notBefore?.toEpochMilli(),
            "post_id" to "",
            "error" to "",
            "views" to 0L, "likes" to 0L, "comments" to 0L, "shares" to 0L,
            "metrics_updated_at" to null,
        ))

    private fun action(post: Map<String, Any?>) = post.objAt("options").str("action", "publish")

    private fun isBrowser(p: Map<String, Any?>) = p.str("post_mode") == "BROWSER"

    private fun releaseStale(tx: Db.Tx, now: Instant) {
        for (post in tx.where("posts") { isBrowser(it) && it.str("status") == "posting" }) {
            val claimed = post["claimed_at"] as? Long ?: continue
            if (Duration.between(Instant.ofEpochMilli(claimed), now) > CLAIM_TIMEOUT) {
                if (post.long("attempts") >= MAX_ATTEMPTS) {
                    post["status"] = "failed"
                    post["error"] = "The poster never reported back (app closed or phone asleep)."
                } else post["status"] = "queued"
                post["claimed_at"] = null
            }
        }
    }

    private fun doneInLast24h(tx: Db.Tx, now: Instant): List<Obj> {
        val since = now.minus(Duration.ofHours(24)).toEpochMilli()
        return tx.where("posts") {
            isBrowser(it) && it.str("status") in setOf("published", "sent_to_drafts", "posting") &&
                ((it["claimed_at"] as? Long) ?: it.long("created_at")) >= since
        }
    }

    private fun due(post: Map<String, Any?>, now: Instant) = (post["not_before"] as? Long)?.let { it <= now.toEpochMilli() } ?: true

    fun status(now: Instant = Db.clock()): Obj {
        val (perDay, gap) = limits()
        return db.tx {
            val recent = doneInLast24h(this, now)
            val queued = where("posts") { isBrowser(it) && it.str("status") in setOf("queued", "posting") }
            val drafts = queued.count { action(it) == "draft" }
            val last = recent.mapNotNull { it["claimed_at"] as? Long }.maxOrNull()
            obj(
                "queued" to queued.size,
                "waiting_for_draft_support" to if (lastSeen["draft"] == "1") 0 else drafts,
                "done_last_24h" to recent.count { it.str("status") != "posting" },
                "posts_per_day" to perDay,
                "min_gap_minutes" to gap,
                "last_posted_at" to Db.iso(last),
                "next_due_at" to Db.iso(queued.filter { action(it) != "draft" }.mapNotNull { it["not_before"] as? Long }.minOrNull()),
                "extension" to obj().also { it.putAll(lastSeen) },
            )
        }
    }

    /**
     * The next post to make, or why there's nothing to do yet. `client` is what the poster reports:
     * version, the logged-in username, and whether it can save drafts ("draft": "1"). `mediaBase`
     * prefixes slide links ("" gives /media/... paths for in-app use). `draftsOnly`: hand out drafts
     * but nothing public (the phone with auto-posting off).
     */
    fun claimNext(
        now: Instant = Db.clock(),
        client: Map<String, String> = emptyMap(),
        mediaBase: String = "",
        skipDrafts: Boolean = false,
        draftsOnly: Boolean = false,
    ): Obj {
        val c = client.filterValues { it.isNotEmpty() }.mapValues { it.value.take(128) }
        lastSeen.clear()
        lastSeen["at"] = Db.iso(now)
        lastSeen.putAll(c)
        val canDraft = c["draft"] == "1"
        val (perDay, gap) = limits()
        val base = mediaBase.trimEnd('/')

        return db.tx {
            releaseStale(this, now)
            val recent = doneInLast24h(this, now)
            if (recent.any { it.str("status") == "posting" }) return@tx obj("job" to null, "reason" to "a post is already in progress")
            if (perDay > 0 && recent.count { it.str("status") != "posting" } >= perDay) {
                return@tx obj("job" to null, "reason" to "daily limit reached ($perDay in 24h)")
            }
            val last = recent.mapNotNull { it["claimed_at"] as? Long }.maxOrNull()
            if (last != null && gap > 0 && now.toEpochMilli() - last < gap * 60_000L) {
                val waitMs = gap * 60_000L - (now.toEpochMilli() - last)
                return@tx obj("job" to null, "reason" to "waiting ${waitMs / 60_000 + 1} min between posts")
            }

            val candidates = where("posts") {
                isBrowser(it) && it.str("status") == "queued" && !(skipDrafts && action(it) == "draft") && !(draftsOnly && action(it) != "draft")
            }
                .sortedBy { it.long("created_at") }
            val dueOnes = candidates.filter { due(it, now) }
            val post = dueOnes.firstOrNull { canDraft || action(it) != "draft" }
            if (post == null) {
                if (dueOnes.isNotEmpty()) return@tx obj("job" to null, "reason" to "${dueOnes.size} draft(s) waiting: this poster can't save drafts yet")
                if (candidates.isNotEmpty()) return@tx obj("job" to null, "reason" to "${candidates.size} scheduled for later")
                return@tx obj("job" to null, "reason" to "queue empty")
            }
            val show = get("slideshows", post.str("slideshow_id"))
            val rendered = show?.strings("rendered").orEmpty()
            if (show == null || rendered.isEmpty()) {
                post["status"] = "failed"
                post["error"] = "slideshow has no rendered slides"
                return@tx obj("job" to null, "reason" to "skipped a post without slides")
            }
            post["status"] = "posting"
            post["claimed_at"] = now.toEpochMilli()
            post["attempts"] = post.long("attempts") + 1
            val options = post.objAt("options")
            obj(
                "job" to obj(
                    "id" to post.str("id"),
                    "slideshow_id" to show.str("id"),
                    "action" to options.str("action", "publish"),
                    // TikTok's photo-post "title" is the line shown on the carousel; the caption carries hashtags.
                    "title" to show.str("caption").ifEmpty { show.str("title") }.take(90),
                    "caption" to show.str("description"),
                    "images" to rendered.map { "$base/media/$it" }.toMutableList(),
                    "rendered" to rendered.toMutableList<Any?>(),
                    "sound" to options.bool("sound", true),
                ),
                "reason" to "",
            )
        }
    }

    fun recordResult(postId: String, ok: Boolean, itemId: String = "", username: String = "", error: String = "", unsupported: Boolean = false): Obj? {
        val browserUser = store.get("browser_username")
        return db.tx {
            val post = get("posts", postId) ?: return@tx null
            if (!isBrowser(post) || post.str("status") != "posting") return@tx null
            when {
                ok -> {
                    post["status"] = if (action(post) == "draft") "sent_to_drafts" else "published"
                    post["post_id"] = itemId
                    post["error"] = ""
                    post["tiktok_username"] = username.ifEmpty { browserUser }
                }
                unsupported -> {
                    // Not a failure of this post: hand the attempt back and wait for a capable poster.
                    post["status"] = "queued"
                    post["attempts"] = maxOf(0L, post.long("attempts") - 1)
                    post["claimed_at"] = null
                    post["error"] = error.take(2000)
                }
                else -> {
                    post["error"] = error.take(2000)
                    post["status"] = if (post.long("attempts") >= MAX_ATTEMPTS) "failed" else "queued"
                }
            }
            if (username.isNotEmpty()) lastSeen["username"] = username
            post.copyObj()
        }
    }

    /** The user opened the share sheet for a ready post. */
    fun markShared(postId: String): Obj? = db.tx {
        val post = get("posts", postId) ?: return@tx null
        if (post.str("status") in setOf("ready_to_share", "queued")) post["status"] = "sent_to_drafts"
        post.copyObj()
    }

    /** Published posts that have a public page to read metrics from: (post id, item id, username). */
    fun metricTargets(): List<Triple<String, String, String>> {
        val fallback = store.get("browser_username")
        return db.tx {
            where("posts") { isBrowser(it) && it.str("status") == "published" && it.str("post_id").isNotEmpty() }
                .map { Triple(it.str("id"), it.str("post_id"), it.str("tiktok_username").ifEmpty { fallback }) }
        }
    }
}
