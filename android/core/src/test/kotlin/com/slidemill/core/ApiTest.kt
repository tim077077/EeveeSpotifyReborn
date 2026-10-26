package com.slidemill.core

import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The local API end to end, as the dashboard uses it (ports of tests/test_flow, test_features, test_browser). */
class ApiTest {
    private val ai = FakeAi()
    private val platform = JvmPlatform()
    private val e = TestKit.engine(ai, platform)
    private val c = TestKit.Client(e)
    private val T0: Instant = Instant.parse("2026-10-24T12:00:00Z")

    @AfterTest fun tearDown() {
        e.stop()
        ai.stop()
        Db.clock = { Instant.now() }
    }

    private fun makeShow(): String {
        val s = c.post("/api/v1/slideshows/create", obj("slides" to arr(obj("text_items" to arr(obj("text" to "a"))), obj("text_items" to arr(obj("text" to "b"))))))
        val id = s.str("id")
        c.put("/api/v1/slideshows/$id", obj("caption" to "5 habits that fixed my sleep", "description" to "#sleep #habits"))
        return id
    }

    private fun queue(action: String = "publish"): String {
        val (code, body) = c.call("POST", "/api/v1/slideshows/${makeShow()}/publish", obj("post_mode" to "BROWSER", "browser_action" to action, "sound" to true))
        assertEquals(202, code, body.toString())
        body as Obj
        assertEquals("queued", body.str("status"))
        return body.str("id")
    }

    @Test fun auth_errors_and_dashboard() {
        val (code, body) = c.call("GET", "/api/v1/status", auth = false)
        assertEquals(401, code)
        assertEquals("UNAUTHORIZED", (body as Obj).objAt("detail").str("code"))
        val missing = c.call("GET", "/api/v1/slideshows/nope")
        assertEquals(404, missing.first)
        assertEquals("slideshow not found", (missing.second as Obj).objAt("detail").str("error"))
        val st = c.get("/api/v1/status")
        assertEquals("android", st.str("platform"))
        assertEquals(true, st.bool("ai"))
        assertTrue(st.str("image_generation").startsWith("pollinations"))
        val page = c.raw("/?t=test-token").body().toString(Charsets.UTF_8)
        assertTrue("localStorage.setItem('slidemill_key',\"test-token\")" in page)
        assertTrue("slidemill_key" !in c.raw("/?t=wrong").body().toString(Charsets.UTF_8))
        assertEquals(200, c.raw("/static/app.js").statusCode())
    }

    @Test fun studio_makes_complete_slideshows_and_an_automation() {
        val job = c.post("/api/v1/studio", obj("brief" to "sleep tips for students", "count" to 3, "image_source" to "ai",
            "save_as_automation" to true, "target_country" to "RO", "posts_per_day" to 2))
        val done = c.waitJob(job.str("job_id"))
        assertEquals("completed", done.str("status"), done.toString())
        val shows = done.objs("slideshows")
        assertEquals(3, shows.size)
        for (s in shows) {
            assertEquals("completed", s.str("status"), s.toString())
            assertEquals(3, s.strings("slides").size)
            assertTrue(TestKit.mediaFile(e, s.strings("slides")[0]).length() > 1000)
        }
        val full = c.get("/api/v1/slideshows/${shows[0].str("id")}")
        assertTrue(full.objAt("spec").objs("slides").all { it.strOrNull("image")?.startsWith("library/ai/") == true })
        val autoId = done.objAt("result").str("automation_id")
        val auto = c.get("/api/v1/automations/$autoId")
        assertEquals(13, auto.strings("hooks").size)
        assertEquals("Europe/Bucharest", auto.str("timezone"))
        assertEquals(listOf("0 17 * * *", "0 20 * * *"), auto.objs("schedule").map { it.str("cron") })
        assertEquals("BROWSER", auto.objAt("post_settings").str("post_mode"))
        assertEquals(5, auto.list("next_runs").size)
    }

    @Test fun generate_edit_rerender_video_delete() {
        val g = c.call("POST", "/api/v1/slideshows/generate", obj("prompt" to "morning routine"))
        assertEquals(201, g.first)
        val id = (g.second as Obj).str("slideshow_id")
        val s = c.waitShow(id)
        assertEquals("completed", s.str("status"), s.toString())
        assertEquals("Plan: morning routine", s.str("title"))
        val spec = s.objAt("spec")
        spec.objs("slides")[0].objs("text_items")[0]["text"] = "edited hook"
        val slides = spec.objs("slides")
        slides[0]["text_items"] = arr(obj("text" to "edited hook", "font_size" to "48px", "text_align" to "left"))
        spec["slides"] = slides.toMutableList<Any?>()
        spec["aspect_ratio"] = "9:16"
        val updated = c.put("/api/v1/slideshows/$id", obj("spec" to spec))
        assertEquals("completed", updated.str("status"), updated.toString())
        val img = javax.imageio.ImageIO.read(TestKit.mediaFile(e, updated.strings("slides")[0]))
        assertEquals(1080 to 1920, img.width to img.height)
        val video = c.post("/api/v1/slideshows/$id/video")
        assertTrue(video.str("video_url").endsWith("slideshow.mp4"))
        assertEquals(200, c.raw(video.str("video_url")).statusCode())
        assertEquals(true, c.call("DELETE", "/api/v1/slideshows/$id").let { (it.second as Obj).bool("deleted") })
        assertEquals(404, c.call("GET", "/api/v1/slideshows/$id").first)
    }

    @Test fun collections_upload_and_pick_least_used() {
        val col = c.post("/api/v1/collections", obj("name" to "pink"))
        val up = c.upload("/api/v1/collections/${col.str("id")}/upload", "files", listOf("a.jpg" to TestKit.jpeg(), "b.jpg" to TestKit.jpeg(500, 300)))
        assertEquals(201, up.first, up.second.toString())
        assertEquals(2, (up.second as Obj).objs("images").size)
        assertEquals(2L, c.get("/api/v1/collections").objs("collections")[0].long("image_count"))
        val picks = (1..4).map { e.images.pickFromCollection(col.str("id"), emptySet())!!.str("id") }
        assertEquals(2, picks.take(2).toSet().size) // both used once before any repeats
        val bad = c.upload("/api/v1/collections/${col.str("id")}/upload", "files", listOf("x.jpg" to "nope".toByteArray()))
        assertEquals(422, bad.first)
    }

    @Test fun queue_one_at_a_time_and_report() {
        val pid = queue()
        val r = e.queue.claimNext(client = mapOf("version" to "app", "username" to "tim"))
        val job = r.objAt("job")
        assertEquals(pid, job.str("id"))
        assertEquals("5 habits that fixed my sleep", job.str("title"))
        assertEquals("#sleep #habits", job.str("caption"))
        assertEquals(2, job.strings("images").size)
        assertTrue(job.strings("images")[0].startsWith("/media/slideshows/"))
        assertEquals("a post is already in progress", e.queue.claimNext().str("reason"))
        assertEquals("published", e.queue.recordResult(pid, true, "7400000000000000009", "tim")!!.str("status"))
        assertNull(e.queue.recordResult(pid, true))
        val st = c.get("/api/v1/extension/status")
        assertEquals(1L, st.long("done_last_24h"))
        assertEquals("tim", st.objAt("extension").str("username"))
    }

    @Test fun gap_and_daily_cap() {
        e.store.setMany(mapOf("browser_posts_per_day" to "2", "browser_min_gap_minutes" to "30"))
        val ids = (1..3).map { queue() }
        val j1 = e.queue.claimNext(T0).objAt("job")
        e.queue.recordResult(j1.str("id"), true, "1")
        assertTrue("waiting" in e.queue.claimNext(T0.plus(Duration.ofMinutes(10))).str("reason"))
        val j2 = e.queue.claimNext(T0.plus(Duration.ofMinutes(31))).objAt("job")
        e.queue.recordResult(j2.str("id"), true, "2")
        assertTrue("daily limit" in e.queue.claimNext(T0.plus(Duration.ofHours(2))).str("reason"))
        val j3 = e.queue.claimNext(T0.plus(Duration.ofHours(25))).objAt("job")
        assertEquals(ids, listOf(j1.str("id"), j2.str("id"), j3.str("id")))
    }

    @Test fun drafts_go_to_the_tiktok_app_never_public() {
        val pid = queue(action = "draft")
        val r = e.queue.claimNext(T0, skipDrafts = true)
        assertNull(r["job"])
        assertEquals("queue empty", r.str("reason"))
        e.tick(T0)
        val notice = platform.notices.single()
        assertEquals("share", notice.kind)
        assertEquals(pid, notice.postId)
        assertEquals("ready_to_share", e.db.tx { get("posts", pid)!!.str("status") })
        assertTrue(e.queue.claimNext(T0, skipDrafts = true)["job"] == null)
        e.queue.markShared(pid)
        assertEquals("sent_to_drafts", e.db.tx { get("posts", pid)!!.str("status") })
    }

    @Test fun a_tiktok_tab_that_saves_drafts_takes_them_instead_of_the_tiktok_app() {
        val canDraft = mapOf("username" to "tim", "draft" to "1")
        val pid = queue(action = "draft")
        val job = e.queue.claimNext(T0, client = canDraft).objAt("job")
        assertEquals(pid, job.str("id"))
        assertEquals("draft", job.str("action")) // saved as a draft, never published
        e.tick(T0)
        assertTrue(platform.notices.none { it.kind == "share" })
        assertEquals("sent_to_drafts", e.queue.recordResult(pid, true, username = "tim")!!.str("status"))

        // The tab stops checking in (logged out, phone asleep): drafts go back to the TikTok app.
        val later = queue(action = "draft")
        e.tick(T0.plus(Duration.ofMinutes(5)))
        assertTrue(platform.notices.none { it.kind == "share" })
        e.tick(T0.plus(Duration.ofMinutes(11)))
        assertEquals(later, platform.notices.single { it.kind == "share" }.postId)
    }

    @Test fun failures_retry_then_give_up_and_stale_claims_release() {
        e.store.setMany(mapOf("browser_min_gap_minutes" to "0"))
        val pid = queue()
        var post: Obj? = null
        for (attempt in 1..PostQueue.MAX_ATTEMPTS) {
            assertEquals(pid, e.queue.claimNext(T0.plus(Duration.ofHours(attempt.toLong()))).objAt("job").str("id"))
            post = e.queue.recordResult(pid, false, error = "captcha")
        }
        assertEquals("failed", post!!.str("status"))
        assertEquals("captcha", post.str("error"))

        val p2 = queue()
        assertEquals(p2, e.queue.claimNext(T0).objAt("job").str("id"))
        assertEquals("a post is already in progress", e.queue.claimNext(T0.plus(Duration.ofMinutes(5))).str("reason"))
        assertEquals(p2, e.queue.claimNext(T0.plus(Duration.ofMinutes(25))).objAt("job").str("id"))
    }

    @Test fun scheduled_run_generates_early_and_posts_on_time() {
        val auto = c.post("/api/v1/automations", obj(
            "title" to "sleep", "hooks" to arr("sleep tips"), "timezone" to "Europe/Bucharest", "schedule" to arr("0 19 * * *"),
            "post_settings" to obj("post_mode" to "BROWSER", "browser_action" to "publish", "auto_post" to true),
        ))
        val zone = ZoneId.of("Europe/Bucharest")
        val start = ZonedDateTime.of(2026, 10, 24, 18, 40, 0, 0, zone).toInstant()
        e.tick(start)
        e.tick(start.plus(Duration.ofMinutes(4))) // 18:44: nothing yet
        assertEquals(0, e.db.tx { all("slideshows").size })
        e.tick(start.plus(Duration.ofMinutes(5))) // 18:45: generation starts 15 min early
        val deadline = System.currentTimeMillis() + 20_000
        while (e.db.tx { all("posts").size } == 0 && System.currentTimeMillis() < deadline) Thread.sleep(50)
        val post = e.db.tx { all("posts").single().copyObj() }
        val notBefore = Instant.ofEpochMilli(post.long("not_before"))
        val slot = ZonedDateTime.of(2026, 10, 24, 19, 0, 0, 0, zone).toInstant()
        assertTrue(!notBefore.isBefore(slot) && notBefore.isBefore(slot.plusSeconds(241)), "post at $notBefore")
        assertTrue("later" in e.queue.claimNext(start.plus(Duration.ofMinutes(6))).str("reason"))
        assertEquals(post.str("id"), e.queue.claimNext(slot.plusSeconds(300)).objAt("job").str("id"))
        val show = e.db.tx { all("slideshows").single() }
        assertEquals(auto.objs("schedule")[0].str("job_id"), show.str("created_by_cron_id"))
        // paused automations skip their schedule
        c.patch("/api/v1/automations/${auto.str("id")}", obj("paused" to true))
        e.tick(start.plus(Duration.ofDays(1)).plus(Duration.ofMinutes(5)))
        Thread.sleep(200)
        assertEquals(1, e.db.tx { all("slideshows").size })
    }

    @Test fun apply_timing_and_validation() {
        val aid = c.post("/api/v1/automations", obj("title" to "x", "hooks" to arr("h"))).str("id")
        val r = c.post("/api/v1/automations/$aid/apply-timing", obj("country" to "RO", "posts_per_day" to 2, "browser_action" to "draft"))
        val ps = r.objAt("automation").objAt("post_settings")
        assertEquals("BROWSER", ps.str("post_mode"))
        assertEquals("draft", ps.str("browser_action"))
        assertTrue(r.strings("warnings").any { "TikTok app" in it })
        assertEquals(404, c.call("POST", "/api/v1/automations/$aid/apply-timing", obj("country" to "XX")).first)
        assertEquals(422, c.call("POST", "/api/v1/automations", obj("schedule" to arr("99 9 * * *"))).first)
        assertEquals(422, c.call("POST", "/api/v1/automations", obj("timezone" to "Mars/Base")).first)
        assertEquals(52, c.get("/api/v1/timing/countries").list("countries").size)
        assertEquals("Europe/Berlin", c.get("/api/v1/timing/best?country=de&posts_per_day=2").str("timezone"))
    }

    @Test fun library_import_search_analyze_remix_and_metrics() {
        val html = "<script id=\"__UNIVERSAL_DATA_FOR_REHYDRATION__\" type=\"application/json\">" +
            Json.write(obj("__DEFAULT_SCOPE__" to obj("webapp.video-detail" to obj("itemInfo" to obj("itemStruct" to CoreTest.WEB_ITEM))))) + "</script>"
        val fetched = mutableListOf<String>()
        e.library.fetchHtml = { fetched += it; html }
        e.library.downloadImage = { TestKit.jpeg() }
        val job = c.post("/api/v1/library/import", obj("items" to arr("https://www.tiktok.com/@glowdaily/photo/7400000000000000001")))
        val done = c.waitJob(job.str("job_id"))
        assertEquals("completed", done.str("status"), done.toString())
        assertEquals<Any?>(listOf(obj("niche" to "skincare", "profile_count" to 1L)), c.get("/api/v1/library/niches").list("niches"))
        val found = c.get("/api/v1/library?q=changed%20my%20skin")
        assertEquals(1L, found.long("total"))
        val prof = found.objs("profiles")[0]
        assertEquals("glowdaily", prof.str("username"))
        assertEquals(120000L, prof.long("followers"))
        assertEquals(1L, prof.long("slideshow_count"))
        val postId = "7400000000000000001"
        assertEquals(3, c.get("/api/v1/library/profiles/${prof.str("id")}").objs("slideshows")[0].strings("images").size)
        val read = c.post("/api/v1/library/posts/$postId/analyze")
        assertEquals("1. double cleanse", read.strings("slide_texts")[1])
        val remix = c.call("POST", "/api/v1/library/posts/$postId/remix", obj("count" to 2, "image_source" to "ai"))
        assertEquals(202, remix.first)
        val ids = (remix.second as Obj).strings("slideshow_ids")
        assertEquals(2, ids.size)
        ids.forEach { assertEquals("completed", c.waitShow(it).str("status")) }
        val writerCall = ai.requests.last { "You write viral" in it.second.objs("messages")[0].str("content") }
        assertTrue("reference_slideshow" in Json.write(writerCall.second) && "double cleanse" in Json.write(writerCall.second))

        // metrics for a published phone post come from its public page
        val pid = queue()
        e.queue.claimNext()
        e.queue.recordResult(pid, true, "7400000000000000009", "tim")
        e.refreshMetrics()
        assertTrue(fetched.last().endsWith("/@tim/photo/7400000000000000009"))
        assertEquals(850000L, e.db.tx { get("posts", pid)!!.long("views") })
        val stats = c.get("/api/v1/posts?days=30").objAt("statistics")
        assertEquals(850000L, stats.long("views"))
    }

    @Test fun settings_round_trip_and_test() {
        val s = c.put("/api/v1/settings", obj("values" to obj("pexels_api_key" to "pexels-key-1234567890")))
        assertEquals(true, s.objAt("values").objAt("pexels_api_key").bool("set"))
        assertTrue("anthropic" in s.objAt("llm_providers"))
        val t = c.post("/api/v1/settings/test")
        assertEquals(true, t.objAt("ai").bool("ok"), t.toString())
        assertEquals(422, c.call("PUT", "/api/v1/settings", obj("values" to obj("bogus" to "1"))).first)
        val detected = c.put("/api/v1/settings", obj("values" to obj("llm_api_key" to "gsk_abcdef", "llm_provider" to "")))
        assertEquals("groq", detected.objAt("values").objAt("llm_provider").str("value"))
    }

    @Test fun backup_and_restore() {
        val id = makeShow()
        val zip = c.raw("/api/v1/backup").body()
        c.call("DELETE", "/api/v1/slideshows/$id")
        assertEquals(404, c.call("GET", "/api/v1/slideshows/$id").first)
        e.restore(zip)
        val back = c.get("/api/v1/slideshows/$id")
        assertEquals("5 habits that fixed my sleep", back.str("caption"))
        assertTrue(File(e.mediaDir, back.strings("slides")[0].removePrefix("/media/")).isFile)
        assertNotNull(back["spec"])
    }
}
