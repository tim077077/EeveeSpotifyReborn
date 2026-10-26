package com.slidemill.core

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The phone as Claude sees it: Claude writes and decides, the phone renders and saves TikTok drafts in the same call. */
class McpTest {
    private val platform = JvmPlatform()
    private val e = TestKit.engine(platform)
    private val tiktok = FakeTikTok().also { e.tiktok = it }

    @AfterTest fun tearDown() = e.stop()

    private val plan = obj(
        "title" to "Sleep fixes nobody tells you", "caption" to "3 sleep fixes that actually work",
        "description" to "Small changes, deep sleep. #sleep #sleeptips", "text_position" to "center",
        "slides" to listOf("3 sleep fixes nobody tells you", "1. make the room pitch dark", "2. phone out of reach", "3. same wake time").mapIndexed { i, t ->
            obj(
                "role" to if (i == 0) "hook" else "body", "image_query" to "dark bedroom", "image_prompt" to "a dark bedroom",
                "text_items" to arr(obj("text" to t, "font" to "tiktok", "font_size" to "large", "text_style" to "outline", "text_color" to "white")),
            )
        }.toMutableList<Any?>(),
    )

    private fun rpc(method: String, params: Obj = obj(), id: Any? = 1): Mcp.Reply =
        e.relay.mcp.handle("POST", Json.write(obj("jsonrpc" to "2.0", "id" to id, "method" to method, "params" to params)))

    private fun result(method: String, params: Obj = obj()): Obj {
        val r = rpc(method, params)
        assertEquals(200, r.status, r.body)
        return Json.parseObj(r.body).objAt("result")
    }

    private fun call(tool: String, args: Obj = obj()): Obj = result("tools/call", obj("name" to tool, "arguments" to args))
    private fun text(out: Obj, i: Int = 0) = out.objs("content")[i].str("text")
    private fun row(id: String) = e.db.tx { get("slideshows", id)!!.copyObj() }
    private fun create(vararg extra: Pair<String, Any?>): Obj = call("create_slideshow", obj("plan" to plan, "image_source" to "ai", *extra))
    private fun idOf(out: Obj) = Json.parseObj(text(out)).str("id")

    @Test fun handshake_and_tool_list() {
        val init = result("initialize", obj("protocolVersion" to "2025-06-18", "capabilities" to obj(), "clientInfo" to obj("name" to "t", "version" to "1")))
        assertEquals("2025-06-18", init.str("protocolVersion"))
        assertEquals("Slidemill", init.objAt("serverInfo").str("name"))
        assertTrue("TikTok Studio drafts" in init.str("instructions"))
        assertEquals("2025-11-25", result("initialize", obj("protocolVersion" to "1999-01-01")).str("protocolVersion"))
        val names = result("tools/list").objs("tools").map { it.str("name") }
        assertEquals(listOf(
            "writing_guide", "research_library", "add_to_library", "read_library_slides", "save_slide_texts", "create_slideshow",
            "import_slides", "save_draft", "view_slideshow", "list_slideshows", "delete_slideshow", "predict_virality", "my_posts", "best_times", "phone_status",
        ), names)
        // Notifications get no body; modern-era discovery falls back to the handshake; GET isn't served.
        assertEquals(202, e.relay.mcp.handle("POST", """{"jsonrpc":"2.0","method":"notifications/initialized"}""").status)
        assertEquals(-32601L, Json.parseObj(rpc("server/discover").body).objAt("error").long("code"))
        assertEquals(405, e.relay.mcp.handle("GET", "").status)
        assertEquals(400, e.relay.mcp.handle("POST", "{not json").status)
    }

    @Test fun create_and_save_draft_answers_with_tiktoks_draft_id() {
        val out = create("save_draft" to true)
        assertEquals(false, out["isError"], out.toString())
        assertEquals("Saved to TikTok Studio drafts (draft id 70000000001).", text(out, 1))
        assertEquals(4, out.objs("content").count { it.str("type") == "image" }) // Claude sees what it made
        val id = idOf(out)

        val (title, caption, slides) = tiktok.saved.single()
        assertEquals(plan.str("caption"), title) // the line on the carousel
        assertEquals(plan.str("description"), caption) // the caption with hashtags
        assertEquals(4, slides.size)
        assertTrue(slides.all { it.isFile })
        val draft = row(id).objAt("draft")
        assertEquals("saved", draft.str("status"))
        assertEquals("70000000001", draft.str("draft_id"))
        assertTrue("saved (70000000001)" in text(call("list_slideshows")))

        // Saving the same slideshow twice needs an explicit "again" (it would be a duplicate draft).
        val twice = call("save_draft", obj("slideshow_id" to id))
        assertEquals(true, twice["isError"])
        assertTrue("Already in TikTok drafts (draft id 70000000001)" in text(twice), text(twice))
        assertTrue("draft id 70000000002" in text(call("save_draft", obj("slideshow_id" to id, "again" to true))))
        assertEquals(2, tiktok.saved.size)
    }

    @Test fun slides_claude_made_are_imported_and_saved_as_a_draft() {
        // One slide by link (served locally), one inline: the phone stores them as they are and saves the draft.
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        val cover = TestKit.jpeg(1080, 1350, java.awt.Color(10, 120, 200))
        server.createContext("/s1.jpg") { ex -> ex.sendResponseHeaders(200, cover.size.toLong()); ex.responseBody.use { it.write(cover) } }
        server.start()
        try {
            val inline = "data:image/jpeg;base64," + java.util.Base64.getEncoder().encodeToString(TestKit.jpeg(1080, 1350))
            val out = call("import_slides", obj(
                "slides" to arr("http://127.0.0.1:${server.address.port}/s1.jpg", inline),
                "caption" to "my own slides", "description" to "made by Claude #test", "save_draft" to true,
            ))
            assertEquals(false, out["isError"], out.toString())
            assertEquals("Saved to TikTok Studio drafts (draft id 70000000001).", text(out, 1))
            assertEquals(2, out.objs("content").count { it.str("type") == "image" })
            val (title, caption, slides) = tiktok.saved.single()
            assertEquals("my own slides", title)
            assertEquals("made by Claude #test", caption)
            assertEquals(2, slides.size)
            val first = javax.imageio.ImageIO.read(slides[0])
            assertEquals(1080 to 1350, first.width to first.height) // kept as made, no text drawn on it
            assertTrue(java.awt.Color(first.getRGB(500, 600)).blue > 150)
        } finally {
            server.stop(0)
        }

        val bad = call("import_slides", obj("slides" to arr("data:image/png;base64,bm90IGFuIGltYWdl"), "caption" to "x"))
        assertEquals(true, bad["isError"])
        assertTrue("Slide 1 isn't an image" in text(bad), text(bad))
        assertTrue("expected an https link" in text(call("import_slides", obj("slides" to arr("file:///etc/passwd"), "caption" to "x"))))
        assertTrue("TikTok allows 89" in text(call("import_slides", obj("slides" to arr("https://x"), "caption" to "x".repeat(90)))))
        assertEquals(1, tiktok.saved.size)
    }

    @Test fun a_failed_save_says_why_and_is_recorded() {
        // TikTok refuses: the error comes back in the same call, and the slideshow remembers it.
        tiktok.refuse = "status_code 8 (login expired)"
        val out = create("save_draft" to true)
        assertEquals(false, out["isError"]) // the slideshow itself rendered
        assertEquals("Rendered, but NOT saved to TikTok drafts: TikTok refused the draft: status_code 8 (login expired)", text(out, 1))
        val id = idOf(out)
        assertEquals("failed", row(id).objAt("draft").str("status"))

        tiktok.refuse = null
        tiktok.user = ""
        val loggedOut = call("save_draft", obj("slideshow_id" to id))
        assertEquals(true, loggedOut["isError"])
        assertTrue("Not logged in to TikTok" in text(loggedOut))

        e.tiktok = null // the app isn't running its TikTok tab
        assertTrue("open the Slidemill app" in text(call("save_draft", obj("slideshow_id" to id))))
        assertTrue("not found" in text(call("save_draft", obj("slideshow_id" to "nope"))))
        assertTrue(tiktok.saved.isEmpty())

        // Without save_draft nothing goes to TikTok.
        e.tiktok = tiktok
        tiktok.user = "tim"
        val plain = create()
        assertEquals(null, row(idOf(plain))["draft"])
        assertEquals(1, plain.objs("content").count { it.str("type") == "text" })
        assertTrue("not saved" !in text(call("list_slideshows")).substringAfter(idOf(plain)).substringBefore("}"))
    }

    @Test fun a_slow_render_still_saves_the_draft_and_reports_it() {
        e.relay.mcp.waitMs = 0 // as if the backgrounds took longer than Claude waits
        val out = create("save_draft" to true)
        val note = text(out)
        assertTrue("Still rendering on the phone" in note && "saved to TikTok drafts as soon as it's rendered" in note, note)
        val id = Regex("slideshow id ([0-9a-f]+)").find(note)!!.groupValues[1]
        repeat(200) { if (row(id).objOrNull("draft")?.str("status") != "saved") Thread.sleep(50) }
        assertEquals("70000000001", row(id).objAt("draft").str("draft_id"))
        e.relay.mcp.waitMs = 45_000
        assertTrue("\"draft_id\":\"70000000001\"" in text(call("view_slideshow", obj("slideshow_id" to id))))
    }

    @Test fun a_slow_upload_answers_in_time_and_records_the_outcome() {
        val id = idOf(create())
        e.relay.mcp.waitMs = 100
        tiktok.delayMs = 600
        assertTrue("still uploading" in text(call("save_draft", obj("slideshow_id" to id))))
        repeat(100) { if (row(id).objAt("draft").str("status") == "saving") Thread.sleep(50) }
        assertEquals("saved", row(id).objAt("draft").str("status"))
    }

    @Test fun missing_backgrounds_are_reported() {
        e.imageGen.override = { _, _ -> throw ImageGenError("pollinations error 429") }
        val out = Json.parseObj(text(create()))
        assertEquals("completed", out.str("status"))
        assertTrue("4 of 4 backgrounds couldn't be found or generated" in out.str("warning"), out.toString())
    }

    @Test fun interrupted_work_is_never_left_pending() {
        val id = idOf(create())
        e.db.tx { get("slideshows", id)!!["draft"] = obj("status" to "saving") }
        e.studio.recoverInterrupted()
        assertTrue("Check TikTok Studio drafts" in row(id).objAt("draft").str("error"))
    }

    @Test fun errors_come_back_readable() {
        val missing = call("view_slideshow", obj("slideshow_id" to "nope"))
        assertEquals(true, missing["isError"])
        assertTrue("not found" in text(missing))
        val bad = call("create_slideshow", obj("plan" to obj("title" to "x")))
        assertEquals(true, bad["isError"])
        assertTrue("slides: Field required" in text(bad), bad.toString())
        assertTrue("library is empty" in text(call("research_library")))
        assertTrue("hook" in text(call("writing_guide")))
        assertTrue("\"crons\"" in text(call("best_times", obj("country" to "us"))))
        assertTrue("No posting-time data" in text(call("best_times", obj("country" to "XX"))))
        val id = idOf(create())
        assertEquals("Deleted.", text(call("delete_slideshow", obj("slideshow_id" to id))))
        assertEquals(true, call("delete_slideshow", obj("slideshow_id" to id))["isError"])
        val status = Json.parseObj(text(call("phone_status")))
        assertEquals("tim", status.str("tiktok"))
        assertTrue("AI only" in status.str("backgrounds"))
    }

    @Test fun relay_link() {
        // Same derivation as the relay (relay/test) and the server (app/mcp_server.token).
        assertEquals("86cc7bb75608ac66e4ffc0eec68deaaa", Relay.claudeToken("ab".repeat(32)))
        assertEquals("https://slidemill-relay.tim.workers.dev", Relay.normalize(" slidemill-relay.tim.workers.dev/ "))
        assertEquals("https://x.workers.dev", Relay.normalize("https://x.workers.dev/mcp/0123"))
        assertEquals("off", e.relay.status().str("state"))
        val claude = e.relay.configure("127.0.0.1:9").str("claude_url") // nothing listens there
        assertTrue(claude.matches(Regex("https://127\\.0\\.0\\.1:9/mcp/[0-9a-f]{32}")), claude)
        assertEquals(claude, e.relay.status().str("claude_url")) // stable across calls
        assertTrue(e.relay.newLink().str("claude_url") != claude) // the old link stops working
        assertEquals("off", e.relay.configure("").str("state"))
    }

    @Test fun the_library_is_filled_and_read_by_claude() {
        val item = CoreTest.WEB_ITEM
        e.library.fetchHtml = { url ->
            assertEquals("https://www.tiktok.com/@glowdaily", url)
            val scope = obj("webapp.user-detail" to obj("userInfo" to obj("user" to obj("uniqueId" to "glowdaily", "signature" to "skincare that works"), "stats" to obj("followerCount" to 120000L))))
            "<script id=\"__UNIVERSAL_DATA_FOR_REHYDRATION__\">" + Json.write(obj("__DEFAULT_SCOPE__" to scope)) + "</script>"
        }
        e.library.downloadImage = { TestKit.jpeg(60, 75) }
        platform.profilePosts["glowdaily"] = (1..4).map { n ->
            item.copyObj().also { it["id"] = "74$n"; it["stats"] = obj("playCount" to 1000L * n) }
        }
        val added = text(call("add_to_library", obj("accounts" to arr("@glowdaily"))))
        assertTrue("@glowdaily: 4 slideshow posts in the library" in added && "4 slideshows have no slide text yet" in added, added)

        val toRead = call("read_library_slides", obj("limit" to 2)).objs("content")
        assertTrue("post_id 744: 3 slides" in toRead[0].str("text"), toRead[0].str("text")) // most viewed first
        assertEquals(6, toRead.count { it.str("type") == "image" })
        assertEquals("Saved.", text(call("save_slide_texts", obj("post_id" to "744", "slide_texts" to arr("things that changed my skin", "1. double cleanse", "2. spf")))))
        val research = Json.parse(text(call("research_library", obj("niche" to "skincare")))) as List<*>
        val top = research[0] as Map<String, Any?>
        assertEquals(listOf("things that changed my skin", "1. double cleanse", "2. spf"), top.strings("slides"))
        assertEquals(true, top["slide_text_read"])
        assertTrue("library is empty" in text(call("research_library", obj("niche" to "cooking"))))
    }

    @Test fun my_posts_reads_the_logged_in_account() {
        platform.profilePosts["tim"] = listOf(CoreTest.WEB_ITEM.copyObj().also { it["author"] = obj("uniqueId" to "tim") })
        val mine = Json.parseObj(text(call("my_posts")))
        assertEquals("tim", mine.str("username"))
        assertEquals(850000L, mine.objs("posts")[0].long("views"))
        assertEquals("https://www.tiktok.com/@tim/photo/7400000000000000001", mine.objs("posts")[0].str("url"))
        tiktok.user = ""
        assertTrue("Not logged in" in text(call("my_posts")))
    }

    @Test fun claude_scores_a_slideshow_itself() {
        java.io.File(e.mediaDir, "library").mkdirs()
        java.io.File(e.mediaDir, "library/c.jpg").writeBytes(TestKit.jpeg(40, 50))
        e.db.tx {
            val prof = insert("library_profiles", obj("username" to "rival"))
            repeat(6) { i ->
                insert("library_posts", obj(
                    "id" to "lp$i", "profile_id" to prof.str("id"), "is_slideshow" to true, "views" to 1000L * (i + 1),
                    "images" to arr("library/c.jpg"), "slide_texts" to arr("rival hook $i", "b", "c"),
                ))
            }
        }
        val id = idOf(create())

        // Step 1: the rules, the slides, and real posts to rank it against, blind.
        val brief = call("predict_virality", obj("slideshow_id" to id)).objs("content")
        val text = brief[0].str("text")
        assertTrue("ANSWER_SCHEMA" in text && "rank_order" in text && "swipe_on" in text, text)
        assertTrue("rival hook" in text && "views" !in text.substringAfter("Comparison:").substringAfter("\n"), text) // no results leaked
        assertEquals(4 + 6, brief.count { it.str("type") == "image" }) // its slides, then the 6 references' covers
        val me = Regex("Slideshow ([A-G]) \\(this one\\)").find(text)!!.groupValues[1]

        // Step 2: Claude's read, ranking itself first.
        val read = obj(
            "hook_score" to 82, "curiosity" to 70, "relatability" to 75, "payoff" to 72, "shareability" to 66,
            "slides" to List(4) { obj("swipe_on" to 85, "visual_pull" to 60, "clarity" to 90, "note" to "reads fast") }.toMutableList<Any?>(),
            "tips" to arr("Say which habit matters most on slide 1"), "better_hook" to "3 night habits I wish I'd started sooner",
            "rank_order" to (listOf(me) + "ABCDEFG".map { it.toString() }.filter { it != me }).toMutableList<Any?>(),
        )
        val scored = Json.parseObj(text(call("predict_virality", obj("slideshow_id" to id, "read" to read))))
        assertTrue(scored.long("viral_potential") in 1..100, scored.toString())
        assertTrue("Claude's read" in scored.str("basis"))
        assertEquals(6L, scored.objAt("rank").long("beats")) // ranked above all 6 real posts
        assertEquals(scored.long("viral_potential"), row(id).objAt("prediction").long("viral_potential"))

        // A read that doesn't match the schema is refused, readably.
        val bad = call("predict_virality", obj("slideshow_id" to id, "read" to obj("hook_score" to 80)))
        assertEquals(true, bad["isError"])
        assertTrue("answer_schema" in text(bad), bad.toString())
    }
}
