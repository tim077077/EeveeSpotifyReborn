package com.slidemill.core

import java.util.Random

/**
 * Automatic replies to comments, on the phone, without anyone tapping anything.
 *
 * The user sets keywords ("PLAN") and what to answer. The background pass reads the comments on the
 * account's own recent posts through the logged-in TikTok tab, and answers every comment whose text
 * matches a keyword. The reply goes out through the same page fetch a human reply uses, so TikTok signs
 * it like any other comment.
 *
 * Guards, all of them configurable: a keyword match is fuzzy (case- and accent-insensitive, one typo),
 * a comment is answered at most once ever, replies rotate so the same sentence isn't repeated, and the
 * whole thing stops at an hourly cap, a daily cap and a random gap between replies.
 */

/** One keyword rule: a comment containing [match] gets one of [replies]. */
data class ReplyRule(
    val match: String = "",
    val replies: List<String> = emptyList(),
    /** Sent instead of [replies] when the commenter's account is private. Blank = treat it like any other. */
    val privateReply: String = "",
    val enabled: Boolean = true,
) {
    val ready: Boolean get() = enabled && match.isNotBlank() && replies.isNotEmpty()

    fun toObj(): Obj = obj(
        "match" to match,
        "replies" to replies.toMutableList<Any?>(),
        "private_reply" to privateReply,
        "enabled" to enabled,
    )

    companion object {
        fun from(row: Map<String, Any?>): ReplyRule = ReplyRule(
            match = row.str("match").trim(),
            replies = row.strings("replies").map { it.trim() }.filter { it.isNotEmpty() },
            privateReply = row.str("private_reply").trim(),
            enabled = row.bool("enabled", true),
        )
    }
}

/** The whole configuration: keywords, caps and spacing. Stored as JSON in the app's settings. */
data class AutoReplySettings(
    val enabled: Boolean = false,
    val rules: List<ReplyRule> = emptyList(),
    /** Only comments on the account's own posts published in the last this many days are read. */
    val postsDays: Int = 7,
    /** How many of the newest posts to read. */
    val postsMax: Int = 20,
    /** How many comments to read per post. */
    val commentsPerPost: Int = 60,
    /** Replies sent per pass at most. One, by default: a person answers the comments they open. */
    val repliesPerRun: Int = 1,
    /** Seconds between two replies: a random gap inside [minGapSec]..[maxGapSec]. */
    val minGapSec: Int = 90,
    val maxGapSec: Int = 420,
    val maxPerHour: Int = 8,
    val maxPerDay: Int = 25,
    /** How many typos a keyword match tolerates (0 = exact). */
    val typos: Int = 1,
    /** Local hours it never answers in (from inclusive, to exclusive; equal = no quiet window). */
    val quietFrom: Int = 1,
    val quietTo: Int = 7,
    /** Minutes before the same person may be answered again: nobody is answered twice in a row. */
    val authorCooldownMin: Int = 360,
    /** Percent of matches deliberately left for later, so it doesn't answer every single one. */
    val skipChance: Int = 8,
    /** Minutes between re-reads of the post list. Between them, comments are only read for posts whose
     *  comment count moved, which is what keeps this off TikTok's radar. */
    val postsRefreshMin: Int = 15,
    /** Wait like someone typing before sending a reply. */
    val typingDelay: Boolean = true,
) {
    /** Whether [hour] (the phone's local hour) is inside the quiet window. */
    fun quiet(hour: Int): Boolean = if (quietFrom == quietTo) false
    else if (quietFrom < quietTo) hour in quietFrom until quietTo
    else hour >= quietFrom || hour < quietTo // a window that crosses midnight

    /** The first ready rule whose keyword this comment's text matches; null = leave it alone. */
    fun ruleFor(text: String): ReplyRule? = rules.firstOrNull { it.ready && Fuzzy.matches(text, it.match, typos) }

    fun toObj(): Obj = obj(
        "enabled" to enabled,
        "rules" to rules.map { it.toObj() }.toMutableList<Any?>(),
        "posts_days" to postsDays, "posts_max" to postsMax, "comments_per_post" to commentsPerPost,
        "replies_per_run" to repliesPerRun, "min_gap_sec" to minGapSec, "max_gap_sec" to maxGapSec,
        "max_per_hour" to maxPerHour, "max_per_day" to maxPerDay, "typos" to typos,
        "quiet_from" to quietFrom, "quiet_to" to quietTo, "author_cooldown_min" to authorCooldownMin,
        "skip_chance" to skipChance, "posts_refresh_min" to postsRefreshMin, "typing_delay" to typingDelay,
    )

    fun toJson(): String = Json.write(toObj())

    /** Keywords as the user typed them: "@PLAN", "#plan" and "plan" are the same rule. */
    fun with(match: String, replies: List<String>, privateReply: String): AutoReplySettings {
        val key = clean(match)
        val rule = ReplyRule(key, replies.map { it.trim() }.filter { it.isNotEmpty() }, privateReply.trim())
        return copy(rules = rules.filterNot { clean(it.match).equals(key, ignoreCase = true) } + rule)
    }

    fun without(match: String): AutoReplySettings {
        val key = clean(match)
        return copy(rules = rules.filterNot { clean(it.match).equals(key, ignoreCase = true) })
    }

    fun rule(match: String): ReplyRule? {
        val key = clean(match)
        return rules.firstOrNull { clean(it.match).equals(key, ignoreCase = true) }
    }

    companion object {
        const val KEY = "auto_reply"

        fun clean(keyword: String): String = keyword.trim().trimStart('#', '@').trim()

        fun fromJson(text: String): AutoReplySettings {
            val row = runCatching { Json.parseObj(text) }.getOrNull() ?: return AutoReplySettings()
            return from(row)
        }

        fun from(row: Map<String, Any?>): AutoReplySettings {
            val base = AutoReplySettings()
            return AutoReplySettings(
                enabled = row.bool("enabled", false),
                rules = row.objs("rules").map { ReplyRule.from(it) }.filter { it.match.isNotEmpty() },
                postsDays = row.int("posts_days", base.postsDays).coerceIn(1, 90),
                postsMax = row.int("posts_max", base.postsMax).coerceIn(1, 100),
                commentsPerPost = row.int("comments_per_post", base.commentsPerPost).coerceIn(10, 200),
                repliesPerRun = row.int("replies_per_run", base.repliesPerRun).coerceIn(1, 10),
                minGapSec = row.int("min_gap_sec", base.minGapSec).coerceIn(0, 3600),
                maxGapSec = row.int("max_gap_sec", base.maxGapSec).coerceIn(0, 7200),
                maxPerHour = row.int("max_per_hour", base.maxPerHour).coerceIn(1, 500),
                maxPerDay = row.int("max_per_day", base.maxPerDay).coerceIn(1, 2000),
                typos = row.int("typos", base.typos).coerceIn(0, 2),
                quietFrom = row.int("quiet_from", base.quietFrom).coerceIn(0, 23),
                quietTo = row.int("quiet_to", base.quietTo).coerceIn(0, 24),
                authorCooldownMin = row.int("author_cooldown_min", base.authorCooldownMin).coerceIn(0, 10_080),
                skipChance = row.int("skip_chance", base.skipChance).coerceIn(0, 100),
                postsRefreshMin = row.int("posts_refresh_min", base.postsRefreshMin).coerceIn(1, 240),
                typingDelay = row.bool("typing_delay", base.typingDelay),
            ).let { if (it.maxGapSec < it.minGapSec) it.copy(maxGapSec = it.minGapSec) else it }
        }
    }
}

/**
 * Keyword matching that survives how people actually type: "PLAN", "plan", "pln", "PlAn!", "plan?".
 * Accents and Romanian diacritics are folded, punctuation and emoji are ignored, and a word may be one
 * typo off (never for keywords shorter than four letters, so "ok" doesn't match "op").
 */
object Fuzzy {
    private val FOLD = mapOf(
        'ă' to 'a', 'â' to 'a', 'á' to 'a', 'à' to 'a', 'ä' to 'a', 'ã' to 'a', 'å' to 'a',
        'î' to 'i', 'í' to 'i', 'ì' to 'i', 'ï' to 'i', 'ı' to 'i',
        'ș' to 's', 'ş' to 's', 'ś' to 's', 'š' to 's',
        'ț' to 't', 'ţ' to 't', 'ť' to 't',
        'é' to 'e', 'è' to 'e', 'ê' to 'e', 'ë' to 'e',
        'ó' to 'o', 'ò' to 'o', 'ô' to 'o', 'ö' to 'o', 'õ' to 'o',
        'ú' to 'u', 'ù' to 'u', 'û' to 'u', 'ü' to 'u',
        'ç' to 'c', 'ñ' to 'n', 'ý' to 'y', 'ÿ' to 'y', 'ß' to 's',
    )

    /** "PlAn!! 🔥" -> "plan"; "PLĂN" -> "plan"; everything that isn't a letter or digit becomes a space. */
    fun normalize(text: String): String {
        val out = StringBuilder(text.length)
        var gap = false
        for (ch in text.lowercase()) {
            val c = FOLD[ch] ?: ch
            if (c in 'a'..'z' || c in '0'..'9') {
                out.append(c)
                gap = false
            } else if (c.isLetterOrDigit()) {
                out.append(c) // non-Latin scripts (CJK, Cyrillic) stay as their own words
                gap = false
            } else if (!gap) {
                out.append(' ')
                gap = true
            }
        }
        return out.toString().trim()
    }

    fun words(text: String): List<String> = normalize(text).split(' ').filter { it.isNotEmpty() }

    /** Whether [text] contains [keyword]: every word of the keyword, in order, one typo allowed. */
    fun matches(text: String, keyword: String, typos: Int = 1): Boolean {
        val key = words(keyword)
        if (key.isEmpty()) return false
        val have = words(text)
        if (have.size < key.size) return false
        for (start in 0..have.size - key.size) {
            if (key.indices.all { i -> same(have[start + i], key[i], typos) }) return true
        }
        return false
    }

    /** One word against another: equal, or close enough when the keyword is long enough to mean it. */
    fun same(word: String, keyword: String, typos: Int = 1): Boolean {
        if (word == keyword) return true
        if (typos <= 0 || keyword.length < 4) return false
        if (kotlin.math.abs(word.length - keyword.length) > typos) return false
        return distance(word, keyword) <= typos
    }

    /**
     * Edit distance, with a swap of two neighbouring letters counting as one edit ("plna" for "plan"):
     * the typos this is used on are short words, so the extra row costs nothing.
     */
    fun distance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var prev2 = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                var best = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) best = minOf(best, prev2[j - 2] + 1)
                cur[j] = best
            }
            val old = prev2
            prev2 = prev
            prev = cur
            cur = old
        }
        return prev[b.length]
    }
}

/**
 * The reply log and the guards: every reply the phone sent, so a comment is never answered twice, the
 * caps are counted from real history, and the rotating replies cycle instead of repeating.
 */
class ReplyBook(private val db: Db) {
    companion object {
        const val TABLE = "auto_reply"
        const val NEXT_AT = "auto_reply_next_at"
        const val LAST_RUN = "auto_reply_last_run"
        /** The account's own posts with their comment counts, and when each was last read. */
        const val POSTS = "auto_reply_posts"
    }

    /** Overridable for tests. */
    var random: Random = Random()

    fun already(cid: String): Boolean = db.tx { get(TABLE, cid) } != null

    fun record(
        cid: String, itemId: String, keyword: String, text: String,
        author: String, comment: String, replyCid: String, at: Long = Db.nowMs(),
    ): Obj = db.tx {
        insert(TABLE, obj(
            "id" to cid, "item_id" to itemId, "keyword" to keyword, "text" to text,
            "author" to author, "comment" to comment, "reply_cid" to replyCid, "at" to at,
        ))
    }

    /** What went out, newest first. */
    fun recent(limit: Int = 20): List<Obj> =
        db.tx { all(TABLE).sortedByDescending { it.long("at") }.take(limit).map { it.copyObj() } }

    fun countSince(sinceMs: Long): Int = db.tx { all(TABLE).count { it.long("at") >= sinceMs } }

    /** When this person last got an answer (0 = never), so nobody is answered twice in a row. */
    fun lastTo(author: String): Long = db.tx {
        all(TABLE).filter { it.str("author").equals(author, ignoreCase = true) }.maxOfOrNull { it.long("at") } ?: 0L
    }

    /** Remember that a post's comments were read, with the comment count seen at the time. */
    fun markRead(marks: List<Pair<String, Long>>, now: Long = Db.nowMs()) {
        if (marks.isEmpty()) return
        val row = state(POSTS) ?: return
        val posts = row.objs("posts")
        for ((id, count) in marks) posts.firstOrNull { it.str("id") == id }?.let {
            it["read"] = count
            it["read_at"] = now
        }
        setState(POSTS, obj("at" to row.long("at"), "posts" to posts.toMutableList<Any?>()))
    }

    fun sentToday(now: Long = Db.nowMs()): Int = countSince(now - 86_400_000L)

    fun sentLastHour(now: Long = Db.nowMs()): Int = countSince(now - 3_600_000L)

    fun lastAt(): Long = db.tx { all(TABLE).maxOfOrNull { it.long("at") } ?: 0L }

    /** The earliest the next reply may go out (the gap is picked when the last one was sent). */
    fun nextAllowed(): Long = state(NEXT_AT)?.let { (it["at"] as? Number)?.toLong() } ?: 0L

    /** Start the gap after a reply: a random number of seconds inside the configured range. */
    fun arm(settings: AutoReplySettings, now: Long = Db.nowMs()) {
        val low = settings.minGapSec
        val high = kotlin.math.max(settings.maxGapSec, low)
        val gap = if (high <= low) low else low + random.nextInt(high - low + 1)
        setState(NEXT_AT, obj("at" to now + gap * 1000L))
    }

    /** Why the next reply can't go out yet, or "" when it can. */
    fun blocked(settings: AutoReplySettings, now: Long = Db.nowMs()): String = when {
        !settings.enabled -> "off"
        now < nextAllowed() -> "waiting ${(nextAllowed() - now) / 1000}s"
        sentLastHour(now) >= settings.maxPerHour -> "hourly cap (${settings.maxPerHour})"
        sentToday(now) >= settings.maxPerDay -> "daily cap (${settings.maxPerDay})"
        else -> ""
    }

    /**
     * The reply to use next: the one used longest ago (never used first), so a keyword's replies rotate
     * in order instead of repeating the same sentence at everyone.
     */
    fun pick(rule: ReplyRule): String {
        val used = db.tx { all(TABLE).filter { it.str("keyword").equals(AutoReplySettings.clean(rule.match), ignoreCase = true) } }
        return rule.replies.minByOrNull { text -> used.filter { it.str("text") == text }.maxOfOrNull { it.long("at") } ?: -1L }
            ?: rule.replies.first()
    }

    fun state(key: String): Obj? = db.tx { get("state", key) }

    fun setState(key: String, value: Obj) = db.tx { insert("state", value.toMutableMap().also { it["id"] = key }) }

    fun lastRun(): Obj? = state(LAST_RUN)
}

/**
 * One pass over the account's recent posts: read the comments, match the keywords, answer the ones that
 * match, and record every reply. Nothing here blocks anything else for long: a pass is a handful of
 * requests, and the caps and the gap stop it early when it has done enough.
 */
class AutoReplier(private val e: Engine) {
    val book get() = e.replies

    fun settings(): AutoReplySettings = AutoReplySettings.fromJson(e.store.get(AutoReplySettings.KEY))

    /** Answer the matching comments on the account's recent posts. Returns a summary of the pass. */
    fun runOnce(now: Long = Db.nowMs()): Obj {
        val out = pass(now, post = true)
        book.setState(ReplyBook.LAST_RUN, out.toMutableMap().also { it["id"] = ReplyBook.LAST_RUN })
        return out
    }

    /** What a pass would do right now, without posting anything. */
    fun preview(limit: Int = 5, now: Long = Db.nowMs()): Obj = pass(now, post = false, dryLimit = limit)

    /**
     * The pass itself. Reading happens even while a cap blocks replying, so the app can show what it
     * found; the moment a reply is due, it goes out and the gap starts again.
     */
    private fun pass(now: Long, post: Boolean, dryLimit: Int = 0): Obj {
        val settings = settings()
        val summary = obj("at" to now, "enabled" to settings.enabled, "posts" to 0, "comments" to 0, "matched" to 0, "replied" to 0, "blocked" to 0, "dry" to !post)
        if (!settings.enabled || settings.rules.none { it.ready }) {
            summary["error"] = "auto-reply is off"
            return summary
        }
        // Nobody answers comments at four in the morning; that gap alone is a tell. The hour comes from the
        // pass's own clock, not the wall clock, so this is testable.
        val hour = runCatching { java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.of(e.platform.defaultTimezone())).hour }.getOrDefault(12)
        if (post && settings.quiet(hour)) {
            summary["blocked"] = 1
            summary["waiting"] = "quiet hours (${settings.quietFrom}:00 to ${settings.quietTo}:00)"
            return summary
        }
        val tab = e.tiktok ?: run { summary["error"] = "no TikTok account"; return summary }
        val me = runCatching { tab.username() }.getOrElse { summary["error"] = "tiktok.com isn't answering: ${it.message}"; return summary }
        if (me.isEmpty()) {
            summary["error"] = "not logged in to TikTok in the app"
            return summary
        }
        val mine = posts(settings, now)
        if (mine.isEmpty()) {
            summary["error"] = "TikTok didn't list the account's posts"
            return summary
        }
        summary["posts"] = mine.size

        var replied = 0
        val preview = arr()
        val readMarks = ArrayList<Pair<String, Long>>()
        // A post is only remembered as read when its comments were actually dealt with: anything deferred
        // or failed stays unread, so the next pass looks at it again.
        fun defer(id: String) {
            readMarks.removeAll { it.first == id }
        }
        for (each in mine) {
            val itemId = each.str("id")
            if (itemId.isEmpty()) continue
            // The comment count recorded the last time this post was read. Unchanged = nothing new to
            // answer, so no comment request leaves the phone at all.
            val count = each.long("comments")
            val readBefore = each.long("read")
            if (readBefore > 0 && readBefore == count && now - each.long("read_at") < 3_600_000L) {
                summary["skipped_unchanged"] = summary.int("skipped_unchanged") + 1
                continue
            }
            val comments = try {
                tab.comments(itemId, settings.commentsPerPost)
            } catch (x: Exception) {
                summary["error"] = "TikTok didn't list the comments: ${x.message}"
                null
            }
            if (comments == null) continue
            readMarks += itemId to count
            summary["comments"] = summary.int("comments") + comments.size
            for (c in comments) {
                val cid = c.str("cid")
                val text = c.str("text")
                if (cid.isEmpty() || text.isEmpty()) continue
                if (c.str("author").equals(me, ignoreCase = true)) continue
                if (book.already(cid)) continue
                val rule = settings.ruleFor(text) ?: continue
                summary["matched"] = summary.int("matched") + 1
                if (!post) {
                    if (preview.size < dryLimit) preview.add(obj("post" to itemId, "cid" to cid, "author" to c.str("author"), "comment" to text, "keyword" to rule.match, "reply" to book.pick(rule)))
                    continue
                }
                val who = c.str("author")
                // Never twice to the same person inside the cooldown, and let one go by on purpose now and
                // then: answering every single keyword immediately is the easiest pattern to spot.
                val lastTo = book.lastTo(who)
                if (settings.authorCooldownMin > 0 && lastTo > 0 && now - lastTo < settings.authorCooldownMin * 60_000L) {
                    summary["skipped_person"] = summary.int("skipped_person") + 1
                    defer(itemId)
                    continue
                }
                if (settings.skipChance > 0 && book.random.nextInt(100) < settings.skipChance) {
                    summary["skipped_random"] = summary.int("skipped_random") + 1
                    defer(itemId)
                    continue
                }
                val blocked = book.blocked(settings, now)
                if (blocked.isNotEmpty()) {
                    summary["blocked"] = summary.int("blocked") + 1
                    summary["waiting"] = blocked
                    defer(itemId)
                    book.markRead(readMarks, now)
                    return done(summary, preview, post)
                }
                val usePrivate = rule.privateReply.isNotEmpty() && runCatching { tab.isPrivate(who) }.getOrNull() == true
                val out = if (usePrivate) rule.privateReply else book.pick(rule)
                // Read the comment, then type the answer: no reply lands in the same second as the read.
                if (settings.typingDelay) Thread.sleep(typingMs(out))
                val replyCid = try {
                    tab.replyToComment(itemId, cid, c.str("root_cid"), out)
                } catch (x: Exception) {
                    summary["error"] = "TikTok refused a reply: ${x.message}"
                    defer(itemId)
                    null
                }
                if (replyCid.isNullOrEmpty()) {
                    if (replyCid != null) summary["error"] = "TikTok answered without a comment id"
                    defer(itemId)
                    continue
                }
                book.record(cid, itemId, rule.match, out, who, text, replyCid, now)
                book.arm(settings, now)
                replied++
                summary["replied"] = replied
                if (replied >= settings.repliesPerRun) {
                    book.markRead(readMarks, now)
                    return done(summary, preview, post)
                }
            }
        }
        book.markRead(readMarks, now)
        return done(summary, preview, post)
    }

    /**
     * The account's own recent posts, re-read from TikTok at most once every `postsRefreshMin` minutes and
     * remembered with their comment counts. Between re-reads a pass costs no post-list request at all, and
     * the remembered counts tell it which posts can even have something new.
     */
    private fun posts(settings: AutoReplySettings, now: Long): List<Obj> {
        val cached = book.state(ReplyBook.POSTS)
        if (cached != null && now - cached.long("at") < settings.postsRefreshMin * 60_000L) return cached.objs("posts")
        val tab = e.tiktok ?: return emptyList()
        val listed = runCatching { tab.listMyPosts(settings.postsMax) }.getOrNull().orEmpty()
        val since = now / 1000 - settings.postsDays * 86_400L
        val before = cached?.objs("posts").orEmpty()
        val mine = listed.filter { it.long("posted_at") >= since && !it.bool("private") }.map { p ->
            val was = before.firstOrNull { it.str("id") == p.str("id") }
            obj(
                "id" to p.str("id"), "comments" to p.long("comments"), "posted_at" to p.long("posted_at"),
                "read" to was?.long("read"), "read_at" to was?.long("read_at"),
            )
        }
        book.setState(ReplyBook.POSTS, obj("at" to now, "posts" to mine.toMutableList<Any?>()))
        return mine
    }

    /** How long someone would take to type [text]: enough that the read and the write aren't one event. */
    private fun typingMs(text: String): Long = ((900L + text.length * 55L).coerceAtMost(6000L)) + book.random.nextInt(700)

    /** Attach the dry-run list when nothing was posted. */
    private fun done(summary: Obj, preview: Arr, post: Boolean): Obj {
        if (!post && preview.isNotEmpty()) summary["would_reply"] = preview
        return summary
    }

    /** The account's own recent posts' comments, for the app's Auto-reply card. */
    fun recentComments(perPost: Int = 10): List<Obj> {
        val tab = e.tiktok ?: return emptyList()
        val settings = settings()
        val mine = posts(settings, Db.nowMs()) // the same cache the passes use: reading a card costs no request
        return mine.flatMap { each ->
            runCatching { tab.comments(each.str("id"), perPost) }.getOrNull().orEmpty().map { c ->
                obj(
                    "cid" to c.str("cid"), "item_id" to each.str("id"), "author" to c.str("author"),
                    "comment" to c.str("text"), "at" to c.long("at"), "keyword" to (settings.ruleFor(c.str("text"))?.match ?: ""),
                    "answered" to book.already(c.str("cid")),
                )
            }
        }.sortedByDescending { it.long("at") }.take(30)
    }
}
