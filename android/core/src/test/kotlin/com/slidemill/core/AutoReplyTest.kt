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
    ) = e.saveAutoReply(
        AutoReplySettings(
            enabled = enabled, rules = rules.toList(), repliesPerRun = perRun,
            minGapSec = gapSec, maxGapSec = gapSec, maxPerHour = perHour, maxPerDay = perDay, postsDays = postsDays,
        ),
    )

    private fun post(id: String, ageDays: Double = 1.0, private: Boolean = false) = obj(
        "id" to id, "desc" to "5 money tips", "posted_at" to (now / 1000 - (ageDays * 86_400).toLong()),
        "views" to 1200L, "comments" to 3L, "private" to private, "is_slideshow" to true,
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
        tiktok.commentsOf["p1"]!!.add(comment("c6", "Plan!"))
        val later = e.autoReplier.runOnce()
        assertEquals(1, later.int("replied"))
        assertEquals("c6", e.replies.recent(1)[0].str("id"))
        assertTrue(tiktok.replies.last().third in listOf("Ți-am trimis planul", "Vezi mesajele"))
    }

    @Test fun the_pass_respects_the_caps_and_the_gap() {
        save(ReplyRule("plan", listOf("x")), perRun = 1, gapSec = 120)
        tiktok.myPosts = listOf(post("p1"))
        tiktok.commentsOf["p1"] = mutableListOf(comment("c1", "PLAN"), comment("c2", "plan"))
        val first = e.autoReplier.runOnce()
        assertEquals(1, first.int("replied")) // repliesPerRun
        assertEquals(1, tiktok.replies.size)
        val second = e.autoReplier.runOnce()
        assertEquals(0, second.int("replied"))
        assertEquals("waiting 120s", second.str("waiting"))
        assertEquals(1, second.int("blocked"))
        // After the gap, the next one goes out; the daily cap still stops the third.
        now += 121_000
        assertEquals(1, e.autoReplier.runOnce().int("replied"))
        tiktok.commentsOf["p1"]!!.add(comment("c3", "PLAN"))
        e.saveAutoReply(AutoReplySettings(enabled = true, rules = listOf(ReplyRule("plan", listOf("x"))), maxPerHour = 5, maxPerDay = 2, minGapSec = 0, maxGapSec = 0, repliesPerRun = 5))
        // The gap armed by the last reply still stands, whatever the caps are now.
        assertEquals("waiting 120s", e.autoReplier.runOnce().str("waiting"))
        now += 121_000
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
        e.saveAutoReply(AutoReplySettings(enabled = true, rules = listOf(ReplyRule("plan", listOf("normal"))), minGapSec = 0, maxGapSec = 0))
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
        assertEquals(20, state.objAt("caps").int("per_hour"))
        assertEquals("60-180", state.objAt("caps").str("gap_sec"))
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
