package com.slidemill.core

import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Reading other accounts costs TikTok as few requests as it can: direct lists, no re-reads, one explore at a time. */
class LibraryReadsTest {
    private val platform = JvmPlatform()
    private val e = TestKit.engine(platform)
    private val tab = FakeTikTok("tim").also { e.tiktok = it }
    private var profileFetches = 0
    private var now = 1_793_000_000L

    init {
        Db.clock = { Instant.ofEpochSecond(now) }
        e.library.fetchHtml = { url ->
            profileFetches++
            val user = url.substringAfter("@")
            val scope = obj("webapp.user-detail" to obj("userInfo" to obj("user" to obj("uniqueId" to user, "secUid" to "sec-$user"), "stats" to obj("followerCount" to 1000L))))
            "<script id=\"__UNIVERSAL_DATA_FOR_REHYDRATION__\">" + Json.write(obj("__DEFAULT_SCOPE__" to scope)) + "</script>"
        }
        e.library.downloadImage = { TestKit.jpeg(60, 75) }
    }

    @AfterTest fun tearDown() {
        Db.clock = { Instant.now() }
        e.stop()
    }

    private fun post(user: String, id: String) = CoreTest.WEB_ITEM.copyObj().also {
        it["id"] = id
        it["author"] = obj("uniqueId" to user)
        it["imagePost"] = obj("images" to arr(obj("imageURL" to obj("urlList" to arr("https://cdn.example/$id.jpeg")))))
    }

    private fun postIds() = e.db.tx { all("library_posts").map { it.str("id") }.sorted() }

    @Test fun the_research_page_lists_posts_directly_instead_of_scrolling() {
        tab.userPosts = mapOf("sec-glow" to listOf(post("glow", "1"), post("glow", "2")))
        platform.profilePosts["glow"] = listOf(post("glow", "9"))
        e.library.importProfile("glow", 30)
        assertEquals(listOf("1", "2"), postIds())
        assertTrue(platform.via.isEmpty(), "no profile scroll when the page can list") // the scroll fallback wasn't used
        assertEquals("direct", e.db.tx { all("library_profiles").single().str("read_via") })
    }

    @Test fun it_scrolls_the_profile_when_the_page_cant_list() {
        tab.userPosts = null
        platform.profilePosts["glow"] = listOf(post("glow", "9"))
        e.library.importProfile("glow", 30)
        assertEquals(listOf("9"), postIds())
        assertEquals(listOf(""), platform.via.toList())
        assertEquals("scroll", e.db.tx { all("library_profiles").single().str("read_via") })
    }

    @Test fun a_fresh_profile_isnt_read_again_and_a_stale_one_stops_at_known_posts() {
        tab.userPosts = mapOf("sec-glow" to listOf(post("glow", "1"), post("glow", "2")))
        e.library.importProfile("glow", 30)
        assertEquals(1, profileFetches)
        now += 3_600 // an hour later: nothing is asked
        e.library.importProfile("glow", 30)
        assertEquals(1, profileFetches)
        assertEquals(1, tab.knownGiven.size)
        now += 21 * 3_600 // a day later: read again, told which posts are already in the library
        tab.userPosts = mapOf("sec-glow" to listOf(post("glow", "3"), post("glow", "1")))
        e.library.importProfile("glow", 30)
        assertEquals(2, profileFetches)
        assertEquals(setOf("1", "2"), tab.knownGiven.last())
        assertEquals(listOf("1", "2", "3"), postIds())
        e.library.importProfile("glow", 30, force = true) // forced reads ignore freshness
        assertEquals(3, profileFetches)
    }

    @Test fun a_second_explore_while_one_runs_reports_progress() {
        e.relay.mcp.waitMs = 200
        platform.searchDelayMs = 1_500
        platform.searchResults["vinted tips"] = emptyList()
        fun call(args: Obj) = Json.parseObj(e.relay.mcp.handle("POST", Json.write(obj("jsonrpc" to "2.0", "id" to 1, "method" to "tools/call",
            "params" to obj("name" to "discover_slideshows", "arguments" to args)))).body).objAt("result").objs("content")[0].str("text")
        assertTrue("Still exploring" in call(obj("queries" to arr("vinted tips"))))
        val again = call(obj("queries" to arr("vinted tips")))
        assertTrue("Already exploring on the phone: searching \"vinted tips\" (1 of 1)" in again, again)
        Thread.sleep(2_000)
        assertEquals(1, platform.via.size) // TikTok was searched once
        assertTrue("Already exploring" !in call(obj("queries" to arr("vinted tips")))) // done: a new run may start
    }
}
