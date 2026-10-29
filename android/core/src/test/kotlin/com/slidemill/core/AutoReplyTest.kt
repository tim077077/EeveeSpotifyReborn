package com.slidemill.core

import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The automatic comment replies: matching, the guards, and a whole pass against a faked TikTok tab.
 */
class AutoReplyTest {
    private val platform = JvmPlatform()
    private val e = TestKit.engine(platform)
    private val tiktok = FakeTikTok("masked4fit").also { e.tiktok = it }
    private var now = 1_793_214_000_000L

    init {
        Db.clock = { Instant.ofEpochMilli(now) }
    }

    @AfterTest fun tearDown() {
        Db.clock = { Instant.now() }
        e.stop()
    }

    // ---- matching -------------------------------------------------------------------------------

    @Test fun a_keyword_matches_the_ways_people_type_it() {
        for (text in listOf("PLAN", "plan", "Plan!", "pls PLAN 🙏", "pln", "PLĂN", "plaan", "plan?", "plan\nplan")) {
            assertTrue(Fuzzy.matches(text, "plan"), "'$text' should match plan")
        }
        for (text in listOf("planning", "explana", "nu ma intereseaza", "pl", "ok")) {
            assertFalse(Fuzzy.matches(text, "plan"), "'$text' should not match plan")
        }
        // Whole words only: a longer word that merely contains the keyword isn't a match.
        assertFalse(Fuzzy.matches("planuri", "plan"))
        // Short keywords are matched exactly: no fuzz, or every two-letter word would hit.
        assertTrue(Fuzzy.matches("da", "da"))
        assertFalse(Fuzzy.matches("du", "da"))
        // A multi-word keyword needs its words in order.
        assertTrue(Fuzzy.matches("vreau plan complet te rog", "plan complet"))
        assertFalse(Fuzzy.matches("complet plan", "plan complet"))
        // An inflected word is a different word: "planul" isn't "plan" (set both keywords if you want both).
        assertFalse(Fuzzy.matches("planul e gata", "plan"))
        // Diacritics and emoji don't get in the way, on either side.
        assertTrue(Fuzzy.matches("vreau ȘI eu", "si"))
        assertTrue(Fuzzy.matches("scrie PLAN!! 🔥🔥", "Plan"))
    }

    @Test fun one_typo_but_not_two() {
        assertTrue(Fuzzy.matches("plna", "plan")) // two letters swapped is one edit
        assertTrue(Fuzzy.matches("pla", "plan")) // one letter dropped is still one edit
        assertFalse(Fuzzy.matches("pannn", "plan"))
        assertFalse(Fuzzy.matches("planar", "plan"))
    }

    // ---- settings -------------------------------------------------------------------------------

    @Test fun keywords_and_replies_survive_a_round_trip() {
        val saved = AutoReplySettings(enabled = true, minGapSec = 60, maxGapSec = 180)
            .with("@PLAN", listOf(" Ți-am trimis planul ", "Verifică mesajele"), "#plan".let { "planx" })
            .with("PLAN", listOf("Ți-am trimis planul", "Verifică mesajele"), "ai cont privat")
        assertEquals(1, saved.rules.size) // "#plan"/"@PLAN"/"PLAN" are the same keyword
        val back = AutoReplySettings.fromJson(saved.toJson())
        assertEquals(true, back.enabled)
        assertEquals(listOf("PLAN"), back.rules.map { it.match }) // stored as the user typed it
        assertEquals(listOf("Ți-am trimis planul", "Verifică mesajele"), back.rules[0].replies)
        assertEquals("ai cont privat", back.rules[0].privateReply)
        assertEquals(60, back.minGapSec)
        assertEquals(180, back.maxGapSec)
        assertEquals(1, back.typos)
        // Junk in, defaults out: never a crash from a half-written setting.
        assertEquals(false, AutoReplySettings.fromJson("").enabled)
        assertEquals(false, AutoReplySettings.fromJson("{not json").enabled)
        assertEquals(emptyList(), AutoReplySettings.fromJson("").rules)
        // A gap range the wrong way round is fixed, not obeyed: the maximum is raised to the minimum.
        assertEquals(300, AutoReplySettings.from(Json.parseObj("""{"min_gap_sec":300,"max_gap_sec":60}""")).maxGapSec)
        val trimmed = saved.without("@plan")
        assertEquals(emptyList(), trimmed.rules.map { it.match })
    }

    // ---- guards ---------------------------------------------------------------------------------

    @Test fun replies_rotate_instead_of_repeating() {
        val rule = ReplyRule("plan", listOf("A", "B", "C"))
        assertEquals("A", e.replies.pick(rule)) // never used: the first one
        e.replies.record("c1", "p1", "plan", "A", "gigel", "PLAN", "r1")
        assertEquals("B", e.replies.pick(rule))
        e.replies.record("c2", "p1", "plan", "B", "ana", "plan", "r2")
        assertEquals("C", e.replies.pick(rule))
        e.replies.record("c3", "p1", "plan", "C", "ioana", "pln", "r3")
        assertEquals("A", e.replies.pick(rule)) // back to the start
        // Another keyword's replies don't count against this one.
        assertEquals("A", e.replies.pick(ReplyRule("altceva", listOf("A", "B"))))
    }

    @Test fun the_caps_and_the_gap_stop_it() {
        val tight = AutoReplySettings(enabled = true, rules = listOf(ReplyRule("plan", listOf("x"))), maxPerHour = 1, maxPerDay = 2, minGapSec = 60, maxGapSec = 60)
        assertEquals("", e.replies.blocked(tight, now))
        e.replies.record("c1", "p1", "plan", "x", "gigel", "PLAN", "r1", now)
        e.replies.arm(tight, now)
        assertEquals(now + 60_000, e.replies.nextAllowed())
        assertEquals("waiting 60s", e.replies.blocked(tight, now))
        assertEquals("hourly cap (1)", e.replies.blocked(tight, now + 61_000)) // the gap is gone, the hour isn't
        assertEquals("daily cap (1)", e.replies.blocked(AutoReplySettings(enabled = true, maxPerHour = 99, maxPerDay = 1), now + 61_000))
        assertEquals("", e.replies.blocked(AutoReplySettings(enabled = true, maxPerHour = 99, maxPerDay = 99), now + 61_000))
        assertEquals("off", e.replies.blocked(tight.copy(enabled = false), now + 61_000))
        // The gap is random inside the range the user set.
        val ranged = AutoReplySettings(minGapSec = 60, maxGapSec = 180)
        val gaps = (1..40).map { e.replies.arm(ranged, 0); e.replies.nextAllowed() }
        assertTrue(gaps.all { it in 60_000..180_000 }, "gap out of range: ${gaps.filter { it !in 60_000..180_000 }}")
        assertTrue(gaps.distinct().size > 1, "the gap is always the same number: $gaps")
    }

    // ---- a whole pass ---------------------------------------------------------------------------

    private fun save(
        vararg rules: ReplyRule,
        enabled: Boolean = true,
        gapSec: Int = 0,
        perRun: Int = 5,
        perHour: Int = 20,
        perDay: Int = 50,
        postsDays: Int = 7,
        quietFrom: Int = 0,
        quietTo: Int = 0,
        cooldownMin: Int = 0,
        skipChance: Int = 0,
        typing: Boolean = false,
        refreshMin: Int = 1,
    ) = e.saveAutoReply(
        AutoReplySettings(
            enabled = enabled, rules = rules.toList(), repliesPerRun = perRun,
            minGapSec = gapSec, maxGapSec = gapSec, maxPerHour = perHour, maxPerDay = perDay, postsDays = postsDays,
            quietFrom = quietFrom, quietTo = quietTo, authorCooldownMin = cooldownMin,
            skipChance = skipChance, typingDelay = typing, postsRefreshMin = refreshMin,
        ),
    )

    /** One of the account's posts as listMyPosts returns it. [comments] is the count the change detector
     *  compares against, so a test that adds a comment has to move it, exactly like TikTok does. */
    private fun post(id: String, ageDays: Double = 1.0, private: Boolean = false, comments: Long = 3) = obj(
        "id" to id, "desc" to "5 money tips", "posted_at" to (now / 1000 - (ageDays * 86_400).toLong()),
        "views" to 1200L, "comments" to comments, "private" to private, "is_slideshow" to true,
    )

    private fun comment(cid: String, text: String, author: String = "gigel", root: String = "") =
        obj("cid" to cid, "text" to text, "author" to author, "root_cid" to root, "at" to now / 1000)

    @Test fun a_pass_answers_the_matching_comments_and_skips_the_rest() {
        save(ReplyRule("plan", listOf("Ți-am trimis planul", "Vezi mesajele")), ReplyRule("ghid", listOf("Îți trimit ghidul")))
        tiktok.myPosts = listOf(post("p1"), post("p2", ageDays = 30.0), post("p3", private = true))
        tiktok.commentsOf["p1"] = mutableListOf(
            comment("c1", "PLAN te rog 🔥"), // matches
            comment("c2", "ce frumos"), // no keyword
            comment("c3", "pln"), // matches with a typo
            comment("c4", "plan", author = "masked4fit"), // the account answering itself
            comment("c5", "PLAN", author = "ana", root = "c1"), // a reply under someone else's comment
        )
        tiktok.commentsOf["p2"] = mutableListOf(comment("c9", "PLAN")) // too old to look at
        tiktok.commentsOf["p3"] = mutableListOf(comment("c10", "PLAN")) // private post

        val first = e.autoReplier.runOnce()
        assertEquals(1, first.int("posts")) // the 30-day-old and the private post are left alone
        assertEquals(5, first.int("comments"))
        assertEquals(3, first.int("matched")) // c1, c3, c5 — never c4, which the account wrote itself
        assertEquals(3, first.int("replied"))
        assertEquals(
            listOf("c1" to "Ți-am trimis planul", "c3" to "Vezi mesajele", "c5" to "Ți-am trimis planul"),
            tiktok.replies.map { it.second to it.third },
        )
        assertEquals(listOf("p1", "p1", "p1"), tiktok.replies.map { it.first })
        // Every one is on the record, with the comment it answered.
        assertEquals(3, e.replies.sentToday())
        assertEquals("Ți-am trimis planul", e.replies.recent(1)[0].str("text"))
        assertEquals("PLAN te rog 🔥", e.replies.recent(3)[0].str("comment"))
        assertEquals("gigel", e.replies.recent(3)[0].str("author"))

        // A second pass a moment later: the same comments are never answered twice.
        assertEquals(0, e.autoReplier.runOnce().int("replied"))
        assertEquals(3, tiktok.replies.size)
        // A new comment on the same post does get an answer, and the replies keep rotating.
        now += 60_000
        tiktok.myPosts = listOf(post("p1", comments = 4)) // the comment count moved, so the post is read again
        tiktok.commentsOf["p1"]!!.add(comment("c6", "Plan!"))
        val later = e.autoReplier.runOnce()
        assertEquals(1, later.int("replied"))
        assertEquals("c6", e.replies.recent(1)[0].str("id"))
        assertTrue(tiktok.replies.last().third in listOf("Ți-am trimis planul", "Vezi mesajele"))
    }

    @Test fun the_pass_respects_the_caps_and_the_gap() {
        save(ReplyRule("plan", listOf("x")), perRun = 1, gapSec = 120)
        tiktok.myPosts = listOf(post("p1", comments = 2))
        tiktok.commentsOf["p1"] = mutableListOf(comment("c1", "PLAN"), comment("c2", "plan"))
        val first = e.autoReplier.runOnce()
        assertEquals(1, first.int("replied")) // repliesPerRun
        assertEquals(1, tiktok.replies.size)
        tiktok.myPosts = listOf(post("p1", comments = 3))
        now += 61_000 // a minute on: the post list is re-read, and the gap still has 59s to run
        val second = e.autoReplier.runOnce()
        assertEquals(0, second.int("replied"))
        assertTrue(second.str("waiting").startsWith("waiting"), second.str("waiting")) // 59s of the gap left
        assertEquals(1, second.int("blocked"))
        // After the gap, the next one goes out; the daily cap still stops the third.
        now += 61_000
        tiktok.myPosts = listOf(post("p1", comments = 4))
        assertEquals(1, e.autoReplier.runOnce().int("replied"))
        tiktok.commentsOf["p1"]!!.add(comment("c3", "PLAN"))
        tiktok.myPosts = listOf(post("p1", comments = 5))
        now += 121_000 // past the gap: now only the daily cap can stop it
        e.saveAutoReply(AutoReplySettings(enabled = true, rules = listOf(ReplyRule("plan", listOf("x"))), maxPerHour = 5, maxPerDay = 2, minGapSec = 0, maxGapSec = 0, repliesPerRun = 5, authorCooldownMin = 0, skipChance = 0, quietFrom = 0, quietTo = 0, typingDelay = false, postsRefreshMin = 1))
        val capped = e.autoReplier.runOnce()
        assertEquals(0, capped.int("replied"))
        assertEquals("daily cap (2)", capped.str("waiting"))
    }

    @Test fun a_private_commenter_gets_the_private_reply() {
        save(ReplyRule("plan", listOf("Ți-am trimis planul"), privateReply = "ai cont privat, scrie-mi din nou"))
        tiktok.myPosts = listOf(post("p1"))
        tiktok.commentsOf["p1"] = mutableListOf(comment("c1", "PLAN", author = "gigel"), comment("c2", "plan", author = "ana"))
        tiktok.privateUsers = setOf("gigel")
        assertEquals(2, e.autoReplier.runOnce().int("replied"))
        assertEquals(listOf("ai cont privat, scrie-mi din nou", "Ți-am trimis planul"), tiktok.replies.map { it.third })
        // With no private reply set, a private commenter gets the normal one.
        e.saveAutoReply(AutoReplySettings(enabled = true, rules = listOf(ReplyRule("plan", listOf("normal"))), minGapSec = 0, maxGapSec = 0, authorCooldownMin = 0, skipChance = 0, quietFrom = 0, quietTo = 0, typingDelay = false, postsRefreshMin = 1))
        now += 61_000
        tiktok.myPosts = listOf(post("p1", comments = 4))
        tiktok.commentsOf["p1"] = mutableListOf(comment("c7", "PLAN", author = "gigel"))
        assertEquals(1, e.autoReplier.runOnce().int("replied"))
        assertEquals("normal", tiktok.replies.last().third)
    }

    @Test fun a_refused_reply_is_reported_and_can_be_retried() {
        save(ReplyRule("plan", listOf("x")))
        tiktok.myPosts = listOf(post("p1"))
        tiktok.commentsOf["p1"] = mutableListOf(comment("c1", "PLAN"))
        tiktok.refuseReply = "rate limit"
        val failed = e.autoReplier.runOnce()
        assertEquals(0, failed.int("replied"))
        assertTrue(failed.str("error").contains("rate limit"), failed.str("error"))
        assertFalse(e.replies.already("c1")) // nothing recorded, so a later pass tries again
        tiktok.refuseReply = null
        assertEquals(1, e.autoReplier.runOnce().int("replied"))
        assertTrue(e.replies.already("c1"))
    }

    @Test fun nothing_happens_while_it_is_off_or_unset() {
        tiktok.myPosts = listOf(post("p1"))
        tiktok.commentsOf["p1"] = mutableListOf(comment("c1", "PLAN"))
        assertEquals("auto-reply is off", e.autoReplier.runOnce().str("error"))
        save(ReplyRule("plan", listOf("x")), enabled = false)
        assertEquals("auto-reply is off", e.autoReplier.runOnce().str("error"))
        // On, but with no replies to send: still nothing.
        save(ReplyRule("plan", emptyList()))
        assertEquals("auto-reply is off", e.autoReplier.runOnce().str("error"))
        assertEquals(0, tiktok.replies.size)
        // Not logged in: said plainly, not a crash.
        tiktok.user = ""
        save(ReplyRule("plan", listOf("x")))
        assertEquals("not logged in to TikTok in the app", e.autoReplier.runOnce().str("error"))
    }

    @Test fun a_preview_says_what_it_would_send_without_sending_it() {
        save(ReplyRule("plan", listOf("A", "B")))
        tiktok.myPosts = listOf(post("p1"))
        tiktok.commentsOf["p1"] = mutableListOf(comment("c1", "PLAN"), comment("c2", "pln", author = "ana"))
        val preview = e.autoReplier.preview()
        assertEquals(0, preview.int("replied"))
        assertEquals(0, tiktok.replies.size)
        val would = preview.objs("would_reply")
        assertEquals(listOf("gigel", "ana"), would.map { it.str("author") })
        assertEquals(listOf("A", "A"), would.map { it.str("reply") }) // nothing sent yet, so both would use the first reply
        assertEquals(listOf("c1", "c2"), would.map { it.str("cid") })
    }

    @Test fun it_stays_quiet_at_night_and_answers_a_person_only_once() {
        val hour = java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.of(platform.defaultTimezone())).hour
        save(ReplyRule("plan", listOf("x")), quietFrom = hour, quietTo = (hour + 1) % 24)
        tiktok.myPosts = listOf(post("p1", comments = 1))
        tiktok.commentsOf["p1"] = mutableListOf(comment("c1", "PLAN"))
        val night = e.autoReplier.runOnce()
        assertEquals(0, night.int("replied"))
        assertTrue(night.str("waiting").startsWith("quiet hours"), night.str("waiting"))
        assertEquals(0, tiktok.replies.size) // commented at 3am, answered in the morning
        assertEquals(0, night.int("comments")) // and nothing was even read

        // Daytime, with a six-hour per-person cooldown: the same person twice is still one answer.
        save(ReplyRule("plan", listOf("x")), cooldownMin = 360)
        tiktok.commentsOf["p1"] = mutableListOf(comment("c1", "PLAN"), comment("c2", "plan"), comment("c3", "pln", author = "ana"))
        val day = e.autoReplier.runOnce()
        assertEquals(2, day.int("replied")) // gigel once, ana once
        assertEquals(1, day.int("skipped_person"))
        assertEquals(listOf("c1", "c3"), tiktok.replies.map { it.second })
    }

    @Test fun it_misses_one_on_purpose_and_leaves_an_unchanged_post_alone() {
        save(ReplyRule("plan", listOf("x")), skipChance = 100)
        tiktok.myPosts = listOf(post("p1", comments = 1))
        tiktok.commentsOf["p1"] = mutableListOf(comment("c1", "PLAN"))
        val skipped = e.autoReplier.runOnce()
        assertEquals(0, skipped.int("replied"))
        assertEquals(1, skipped.int("skipped_random"))
        assertEquals(1, skipped.int("comments")) // it looked, then chose not to
        // A deliberate miss stays unread, so the next pass still finds it.
        e.replies.random = java.util.Random(7)
        assertEquals(1, e.autoReplier.runOnce().int("skipped_random"))

        // Answered comments are remembered: an unchanged post is not read again, so no request goes out.
        save(ReplyRule("plan", listOf("x")))
        e.replies.random = java.util.Random(7)
        assertEquals(1, e.autoReplier.runOnce().int("replied"))
        val quiet = e.autoReplier.runOnce()
        assertEquals(0, quiet.int("comments"))
        assertEquals(1, quiet.int("skipped_unchanged"))
    }

    // ---- Claude's tool --------------------------------------------------------------------------

    private fun rpc(method: String, params: Obj = obj()): Obj =
        Json.parseObj(e.relay.mcp.handle("POST", Json.write(obj("jsonrpc" to "2.0", "id" to 1, "method" to method, "params" to params))).body).objAt("result")

    private fun call(tool: String, vararg args: Pair<String, Any?>): Obj {
        val result = rpc("tools/call", obj("name" to tool, "arguments" to obj(*args)))
        val text = result.objs("content").firstOrNull()?.str("text").orEmpty()
        assertFalse(result.bool("isError"), "the tool failed: $text")
        return Json.parseObj(text)
    }

    @Test fun claude_can_set_and_read_the_keywords() {
        val names = rpc("tools/list").objs("tools").map { it.str("name") }
        assertTrue("auto_reply" in names, "auto_reply is missing from the tool list: $names")

        val before = call("auto_reply")
        assertEquals(false, before.bool("enabled"))
        assertEquals(0, before.int("sent_today"))

        val set = call("auto_reply", "action" to "set", "keyword" to "PLAN", "replies" to "Ți-am trimis planul\nVezi mesajele", "private_reply" to "ai cont privat")
        assertEquals(true, set.objs("rules").isNotEmpty())
        assertEquals("PLAN", e.autoReplySettings().rules[0].match)
        assertEquals(listOf("Ți-am trimis planul", "Vezi mesajele"), e.autoReplySettings().rules[0].replies)
        assertEquals("ai cont privat", e.autoReplySettings().rules[0].privateReply)

        // Turning it on and off, and what the answer says about the caps.
        assertEquals(true, call("auto_reply", "action" to "on").bool("enabled"))
        val state = call("auto_reply")
        assertEquals(true, state.bool("enabled"))
        assertEquals("PLAN", state.objs("rules")[0].str("keyword"))
        assertEquals(8, state.objAt("caps").int("per_hour"))
        assertEquals("90-420", state.objAt("caps").str("gap_sec"))
        assertEquals(false, call("auto_reply", "action" to "off").bool("enabled"))

        // Removing, and the errors a wrong call gets.
        assertTrue(call("auto_reply", "action" to "remove", "keyword" to "#plan").objs("rules").isEmpty())
        val missing = rpc("tools/call", obj("name" to "auto_reply", "arguments" to obj("action" to "remove", "keyword" to "nope")))
        assertTrue(missing.bool("isError"))
        assertTrue(missing.objs("content")[0].str("text").contains("No keyword"))
        val empty = rpc("tools/call", obj("name" to "auto_reply", "arguments" to obj("action" to "set", "keyword" to "plan")))
        assertTrue(empty.bool("isError"))
        val nonsense = rpc("tools/call", obj("name" to "auto_reply", "arguments" to obj("action" to "dance")))
        assertTrue(nonsense.bool("isError"))
        assertNull(e.replies.state("nothing"))
    }
}
