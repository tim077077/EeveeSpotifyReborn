package com.slidemill.core

import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnalyticsTest {
    private val platform = JvmPlatform()
    private val e = TestKit.engine(platform)
    private val tiktok = FakeTikTok("tim")
    private val day = 86_400L
    private val t0 = 1_793_000_000L // a Monday-ish epoch second; the exact weekday doesn't matter here

    @AfterTest fun reset() {
        Db.clock = { Instant.now() }
    }

    private fun at(s: Long) {
        Db.clock = { Instant.ofEpochSecond(s) }
    }

    private fun item(id: String, postedAt: Long, views: Long, likes: Long = views / 10, slideshow: Boolean = true, followers: Long = 1000, slides: Int = 3) =
        CoreTest.WEB_ITEM.copyObj().also {
            it["id"] = id
            it["createTime"] = postedAt
            it["author"] = obj("uniqueId" to "tim")
            it["authorStats"] = obj("followerCount" to followers, "heartCount" to 5000L, "videoCount" to 3L)
            it["stats"] = obj("playCount" to views, "diggCount" to likes, "commentCount" to 5L, "shareCount" to 2L, "collectCount" to 3L)
            it["imagePost"] = obj("images" to (0 until slides).map { i -> obj("imageURL" to obj("urlList" to arr("https://cdn.example/$id-$i.jpeg"))) }.toMutableList<Any?>())
            if (!slideshow) it.remove("imagePost")
        }

    @Test fun refresh_records_snapshots_and_the_report_shows_growth() {
        // Nothing yet: the report says how to start.
        assertTrue("No analytics yet" in e.analytics.report().str("status"))

        at(t0)
        platform.profilePosts["tim"] = listOf(item("1", t0 - 3 * day, 1000), item("2", t0 - day, 200, slideshow = false))
        assertTrue(e.analytics.refresh(tiktok))
        assertEquals(false, e.analytics.refresh(tiktok)) // throttled
        val first = e.analytics.report()
        assertEquals(1200L, first.objAt("totals").long("views"))
        assertNull(first.objAt("last_7_days")["views_gained"]) // tracking just started: unknown, not zero

        // Eight days later: views grew, a new post appeared, followers went up.
        at(t0 + 8 * day)
        platform.profilePosts["tim"] = listOf(
            item("1", t0 - 3 * day, 1500), item("2", t0 - day, 900, slideshow = false), item("3", t0 + 7 * day, 300, followers = 1100),
        )
        assertTrue(e.analytics.refresh(tiktok))
        val r = e.analytics.report(postsLimit = 10, predicted = mapOf("3" to obj("slideshow_id" to "s3", "predicted_score" to 70L)))
        val totals = r.objAt("totals")
        assertEquals(2700L, totals.long("views"))
        assertEquals(3L, totals.long("posts"))
        assertEquals(900L, totals.long("avg_views"))
        assertEquals(900L, totals.long("median_views"))
        assertEquals(1500L, r.objAt("since_tracking").long("views_gained")) // 500 + 700 + 300
        assertEquals(1100L, r.objAt("account").long("followers"))
        assertEquals(100L, r.objAt("account").long("followers_gained_7d"))
        assertEquals(1L, r.objAt("last_7_days").long("posts_published"))

        val posts = r.objs("posts")
        assertEquals(listOf("3", "2", "1"), posts.map { it.str("url").substringAfterLast("/") }) // newest first
        assertEquals("https://www.tiktok.com/@tim/video/2", posts[1].str("url"))
        assertEquals("s3", posts[0].objAt("slidemill").str("slideshow_id"))
        assertEquals(300L, posts[0].long("views_last_24h")) // posted within the last day
        assertNull(posts[2]["views_last_24h"]) // no snapshot from a day ago
        assertEquals(1500L, r.objs("top_by_views")[0].long("views"))
        assertEquals(2L, r.objAt("by_type").objAt("slideshow").long("posts"))
        assertEquals(8, r.objs("weeks").size)
        assertEquals(2700L, r.objs("weeks").sumOf { it.long("views_on_posts_published") })
        assertTrue(r.objAt("prediction_check").str("verdict").startsWith("Not enough"))
    }

    @Test fun what_works_finds_what_beats_the_accounts_median() {
        at(t0)
        // 4 long slideshows do far better than 4 short ones; videos sit in between. All settled (3+ days old).
        val items = (1..4).map { item("long$it", t0 - (3 + it) * day, 5000L + it, slides = 8) } +
            (1..4).map { item("short$it", t0 - (3 + it) * day, 500L + it, slides = 2) } +
            (1..3).map { item("vid$it", t0 - (3 + it) * day, 1000L + it, slideshow = false) } +
            listOf(item("fresh", t0 - 3600, 10)) // too new to count
        platform.profilePosts["tim"] = items
        assertTrue(e.analytics.refresh(tiktok))
        val ww = e.analytics.report(predicted = mapOf("long4" to obj("hook" to "5 habits that fixed my sleep", "title" to "sleep"))).objAt("what_works")
        assertEquals(11L, ww.long("posts_used"))
        assertTrue("7-9 slides" in ww.str("summary"), ww.str("summary"))
        val slides = ww.objAt("by").objs("slide_count")
        assertEquals("7-9 slides", slides[0].str("bucket"))
        assertTrue((slides[0]["vs_your_median"] as Double) > 1.2)
        assertEquals("1-3 slides", slides.last().str("bucket"))
        assertEquals("5 habits that fixed my sleep", ww.objs("best_posts")[0].str("hook"))
        assertEquals(500L + 1, ww.objs("worst_posts")[0].long("views"))

        // Too few settled posts: no patterns claimed.
        val few = e.analytics.whatWorks(e.db.tx { all("my_posts").take(3).map { it.copyObj() } }, java.time.ZoneId.of("UTC"), emptyMap())
        assertTrue("Not enough yet" in few.str("summary"))
    }

    @Test fun old_snapshots_are_thinned_to_one_per_day() {
        for (h in 0 until 48 step 6) {
            at(t0 + h * 3600)
            e.analytics.record("tim", listOf(Library.normalizeWebItem(item("1", t0 - day, 100L + h))))
        }
        assertEquals(8, e.db.tx { all("post_snapshots").size })
        at(t0 + 30 * day)
        e.analytics.record("tim", listOf(Library.normalizeWebItem(item("1", t0 - day, 500))))
        assertEquals(4, e.db.tx { all("post_snapshots").size }) // the 42 hours touch 3 UTC days: one each, + today's
    }

    @Test fun refresh_says_why_it_cant_read_tiktok() {
        assertTrue("TikTok tab" in assertFailsWith<DraftError> { e.analytics.refresh(null, force = true) }.message!!)
        tiktok.user = ""
        assertTrue("Not logged in" in assertFailsWith<DraftError> { e.analytics.refresh(tiktok, force = true) }.message!!)
        tiktok.user = "tim"
        assertTrue("didn't list any posts" in assertFailsWith<DraftError> { e.analytics.refresh(tiktok, force = true) }.message!!)
    }
}
