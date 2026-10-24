package com.slidemill.core

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ViralityTest {
    private val ai = FakeAi()
    private val e = TestKit.engine(ai)
    private val c = TestKit.Client(e)

    @AfterTest fun tearDown() {
        e.stop()
        ai.stop()
    }

    private fun show(texts: List<String>): String {
        val s = c.post("/api/v1/slideshows/create", obj("slides" to texts.map { obj("text_items" to arr(obj("text" to it))) }.toMutableList<Any?>()))
        return s.str("id")
    }

    @Test fun format_rules() {
        val spec = Models.SPEC.of(obj("slides" to arr(
            obj("role" to "hook", "text_items" to arr(obj("text" to "5 habits that fixed my sleep"))),
            obj("text_items" to arr(obj("text" to "1. one"))), obj("text_items" to arr(obj("text" to "2. two"))),
            obj("text_items" to arr(obj("text" to "3. three"))),
        )))
        val (score, problems) = Virality.formatCheck(spec)
        assertTrue(problems.any { "promises 5 but there are 3" in it }, problems.toString())
        assertTrue(score < 100)
        assertEquals(1.0, Virality.spearman(listOf(1.0, 2.0, 3.0, 4.0), listOf(10.0, 20.0, 30.0, 40.0)))
        assertEquals(-1.0, Virality.spearman(listOf(1.0, 2.0, 3.0), listOf(3.0, 2.0, 1.0)))
        assertNull(Virality.spearman(listOf(1.0, 2.0), listOf(1.0, 2.0)))
    }

    @Test fun predict_endpoint_scores_the_rendered_slides() {
        val id = show(listOf("5 habits that fixed my sleep", "1. dark room", "2. no phone", "3. same wake time", "4. cold room", "5. no caffeine"))
        ai.hookScores += 80
        val p = c.post("/api/v1/slideshows/$id/predict")
        assertEquals(80L, p.long("hook_score"))
        assertEquals(6, p.list("attention").size)
        assertEquals(100L, (p.list("attention")[0] as Long))
        // 5 swipes at 80% each: 0.8^5 = 33%
        assertEquals(33L, p.long("hold_rate"))
        // 0.40*80 + 0.25*33 + 0.20*mean(70,60,50,55) + 0.15*100
        assertEquals((0.40 * 80 + 0.25 * 32.768 + 0.20 * 58.75 + 0.15 * 100).toLong(), p.long("viral_potential"))
        assertEquals(5, p.objAt("dimensions").size)
        assertEquals(6, ai.lastImages) // the model saw the rendered slides
        assertTrue(p.str("basis").startsWith("AI read of the rendered slides"))
        // stored: summary in lists, full on the slideshow
        assertEquals(p.long("viral_potential"), c.get("/api/v1/slideshows").objs("slideshows")[0].objAt("prediction").long("viral_potential"))
        assertEquals("the sleep habit nobody tells you", c.get("/api/v1/slideshows/$id").objAt("prediction").str("better_hook"))
    }

    @Test fun calibration_compares_predictions_with_real_views() {
        repeat(8) { i ->
            val id = show(listOf("hook $i", "a", "b", "c", "d"))
            ai.hookScores += 30 + i * 8
            c.post("/api/v1/slideshows/$id/predict")
            val pid = e.db.tx { e.queue.queuePost(this, get("slideshows", id)!!) }.str("id")
            e.db.tx { get("posts", pid)!!.apply { this["status"] = "published"; this["views"] = 1000L * (i + 1) } }
        }
        val cal = c.get("/api/v1/virality/calibration")
        assertEquals(8L, cal.long("posts"))
        assertEquals(1.0, cal.dbl("spearman"))
        assertTrue("tracks your views well" in cal.str("summary"))
    }

    @Test fun pretest_keeps_the_better_version() {
        val aid = c.post("/api/v1/automations", obj("title" to "sleep", "hooks" to arr("sleep tips"), "min_viral_score" to 60,
            "post_settings" to obj("post_mode" to "BROWSER", "auto_post" to true))).str("id")
        ai.hookScores += listOf(10, 95)
        val kept = e.pipeline.runAutomation(aid)!!
        val shows = e.db.tx { all("slideshows").map { it.copyObj() } }
        assertEquals(1, shows.size) // the weaker draft was deleted
        assertEquals(kept, shows[0].str("id"))
        assertEquals(95L, shows[0].objAt("prediction").long("hook_score"))
        val retryPrompt = ai.requests.map { Json.write(it.second) }.last { "You write viral" in it }
        assertTrue("A first draft scored" in retryPrompt)
        assertEquals(kept, e.db.tx { all("posts").single().str("slideshow_id") })
        assertEquals(60L, c.get("/api/v1/automations/$aid").long("min_viral_score"))
    }

    /** One library account with n slideshows; post i has (i+1)k views and hook "post{i} hook". */
    private fun library(n: Int) {
        java.io.File(e.mediaDir, "library").mkdirs()
        java.io.File(e.mediaDir, "library/bt.jpg").writeBytes(TestKit.jpeg(40, 50))
        e.db.tx {
            deleteWhere("library_posts") { true }
            deleteWhere("library_profiles") { true }
            val prof = insert("library_profiles", obj("username" to "backtester"))
            repeat(n) { i ->
                insert("library_posts", obj(
                    "id" to "bt$i", "profile_id" to prof.str("id"), "is_slideshow" to true, "views" to 1000L * (i + 1),
                    "images" to arr("library/bt.jpg", "library/bt.jpg", "library/bt.jpg", "library/bt.jpg"),
                    "slide_texts" to arr("post$i hook", "b", "c", "d"),
                ))
            }
        }
    }

    private val leaks = java.util.concurrent.CopyOnWriteArrayList<String>()

    private fun scoreBy(order: (Int) -> Int): (String) -> Int = { user ->
        val own = user.substringAfter("1. [hook] ").substringBefore("\n")
        if ("\"$own\" (" in user) leaks += own // a post must not see its own result as a calibration example
        order(own.removePrefix("post").substringBefore(" ").toInt())
    }

    @Test fun backtest_ranks_library_posts_like_their_views() {
        library(10)
        ai.scoreFor = scoreBy { 20 + it * 7 }
        val out = e.virality.backtest(analyze = {})
        assertEquals(10L, out.long("posts"))
        assertEquals(1.0, out.dbl("spearman"))
        assertTrue("real views in your library" in out.str("summary"))
        assertEquals("bt9", out.objs("rows")[0].str("post_id"))
        assertTrue(leaks.isEmpty(), leaks.toString())
        assertEquals(1.0, c.get("/api/v1/virality/calibration").objAt("backtest").dbl("spearman"))
    }

    @Test fun backtest_flags_a_score_that_doesnt_track() {
        library(10)
        ai.scoreFor = scoreBy { listOf(60, 20, 80, 40, 30, 90, 10, 70, 50, 35)[it] }
        val out = e.virality.backtest(analyze = {})
        assertTrue(out.dbl("spearman") < 0.15)
        assertTrue("isn't tracking" in out.str("summary"))
    }

    @Test fun backtest_job_needs_a_big_enough_library() {
        library(4)
        val job = c.post("/api/v1/virality/backtest").str("job_id")
        var j = obj()
        repeat(100) { j = c.get("/api/v1/jobs/$job"); if (j.str("status") in setOf("completed", "failed")) return@repeat; Thread.sleep(50) }
        assertEquals("failed", j.str("status"))
        assertTrue("needs 8" in j.str("message"), j.str("message"))
    }
}
