package com.slidemill.core

import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Two TikTok accounts on one phone: each slideshow posts from its own account, with its own limits and numbers. */
class AccountsTest {
    private val platform = JvmPlatform()
    private val e = TestKit.engine(platform)
    private val main = FakeTikTok("vintedbot.org").also { e.tiktok = it }
    private val second = FakeTikTok("thriftfinds", id = "acct2").also { e.tabs[it.id] = it }

    init {
        e.analytics.profile = { null }
    }

    @AfterTest fun tearDown() {
        Db.clock = { Instant.now() }
        e.stop()
    }

    private fun call(tool: String, args: Obj = obj()): Obj = Json.parseObj(e.relay.mcp.handle("POST",
        Json.write(obj("jsonrpc" to "2.0", "id" to 1, "method" to "tools/call", "params" to obj("name" to tool, "arguments" to args)))).body).objAt("result")

    private fun text(out: Obj, i: Int = 0) = out.objs("content")[i].str("text")

    private val plan = obj(
        "title" to "t", "caption" to "c", "description" to "d #x",
        "slides" to (1..3).map { obj("image_query" to "q", "image_prompt" to "p", "text_items" to arr(obj("text" to "slide $it"))) }.toMutableList<Any?>(),
    )

    private fun create(account: String = ""): String =
        Json.parseObj(text(call("create_slideshow", obj("plan" to plan, "image_source" to "ai", "account" to account)))).str("id")

    @Test fun each_slideshow_posts_from_its_own_account_with_its_own_limits() {
        val a = create()
        val b = create("@ThriftFinds")
        assertEquals("vintedbot.org", e.db.tx { get("slideshows", a)!!.str("account") })
        assertEquals("thriftfinds", e.db.tx { get("slideshows", b)!!.str("account") })

        val at = Instant.ofEpochSecond(Db.nowMs() / 1000 + 6 * 3600).toString()
        assertTrue("Scheduled on @vintedbot.org" in text(call("schedule_slideshow", obj("slideshow_id" to a, "at" to at))))
        // The same time on the other account is fine: the 2-hour guard is per account.
        assertTrue("Scheduled on @thriftfinds" in text(call("schedule_slideshow", obj("slideshow_id" to b, "at" to at))))
        assertEquals(1, main.published.size)
        assertEquals(1, second.published.size)
        val c = create("thriftfinds")
        assertTrue("2 hours" in text(call("schedule_slideshow", obj("slideshow_id" to c, "at" to at))))
        // ...and it can move to the other account before it's posted.
        val later = Instant.ofEpochSecond(Db.nowMs() / 1000 + 9 * 3600).toString()
        assertTrue("Scheduled on @vintedbot.org" in text(call("schedule_slideshow", obj("slideshow_id" to c, "at" to later, "account" to "vintedbot.org"))))

        val listed = Json.parse(text(call("list_slideshows", obj("account" to "thriftfinds")))) as List<*>
        assertEquals(listOf(b), listed.map { (it as Map<*, *>)["id"] }) // c moved away
        val unknown = call("create_slideshow", obj("plan" to plan, "image_source" to "ai", "account" to "nobody"))
        assertEquals(true, unknown["isError"])
        assertTrue("@vintedbot.org, @thriftfinds" in text(unknown), text(unknown))
        val status = Json.parseObj(text(call("phone_status")))
        assertEquals(listOf("@vintedbot.org (default, research)", "@thriftfinds"), status.strings("tiktok_accounts"))
    }

    @Test fun analytics_are_kept_per_account() {
        Db.clock = { Instant.ofEpochSecond(1_793_000_000L) }
        fun posts(prefix: String, views: Long) = (1..3).map { i ->
            obj("id" to "$prefix$i", "desc" to "p", "posted_at" to 1_792_000_000L, "views" to views, "is_slideshow" to true, "slides" to 5L)
        }
        main.myPosts = posts("m", 1000)
        second.myPosts = posts("s", 50)
        // A pre-accounts analytics row belongs to the main account and moves to it.
        e.db.tx { insert("state", obj("id" to "analytics", "since" to 1L, "username" to "vintedbot.org", "last_refresh" to 0L)) }
        e.analytics.refresh(main)
        e.analytics.refresh(second)
        val m = Json.parseObj(text(call("analytics", obj("refresh" to false))))
        val s = Json.parseObj(text(call("analytics", obj("refresh" to false, "account" to "thriftfinds"))))
        assertEquals(3000L, m.objAt("totals").long("views"))
        assertEquals(150L, s.objAt("totals").long("views"))
        assertEquals("thriftfinds", s.objAt("account").str("username"))
        assertEquals(1L, e.db.tx { get("state", "analytics@vintedbot.org")!!.long("since") }) // tracking kept its start
    }

    @Test fun research_runs_on_the_research_account() {
        e.store.setMany(mapOf("research_account" to "thriftfinds"))
        platform.searchResults["q"] = emptyList()
        call("discover_slideshows", obj("queries" to arr("q")))
        assertEquals(listOf("acct2"), platform.via.toList())
        assertTrue("@thriftfinds (research)" in Json.parseObj(text(call("phone_status"))).strings("tiktok_accounts"))
    }

    @Test fun research_can_run_with_no_login() {
        e.store.setMany(mapOf("research_account" to Accounts.NO_LOGIN))
        main.user = "" // works even with every account logged out
        platform.searchResults["q"] = emptyList()
        val out = text(call("discover_slideshows", obj("queries" to arr("q"))))
        assertEquals(listOf(Accounts.NO_LOGIN), platform.via.toList())
        assertTrue("Nothing found with no login" in out, out)
        assertTrue("research: no login (only public pages)" in Json.parseObj(text(call("phone_status"))).strings("tiktok_accounts"))
    }
}
