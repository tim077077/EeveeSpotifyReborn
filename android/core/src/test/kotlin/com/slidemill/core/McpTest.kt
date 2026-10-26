package com.slidemill.core

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The phone's MCP server: Claude writes a slideshow, the phone renders, scores and queues it. */
class McpTest {
    private val ai = FakeAi()
    private val e = TestKit.engine(ai)
    private val c = TestKit.Client(e)

    @AfterTest fun tearDown() {
        e.stop()
        ai.stop()
    }

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

    @Test fun handshake_and_tool_list() {
        val init = result("initialize", obj("protocolVersion" to "2025-06-18", "capabilities" to obj(), "clientInfo" to obj("name" to "t", "version" to "1")))
        assertEquals("2025-06-18", init.str("protocolVersion"))
        assertEquals("Slidemill", init.objAt("serverInfo").str("name"))
        assertEquals("2025-11-25", result("initialize", obj("protocolVersion" to "1999-01-01")).str("protocolVersion"))
        val names = result("tools/list").objs("tools").map { it.str("name") }.toSet()
        assertTrue(setOf("writing_guide", "research_library", "create_slideshow", "view_slideshow", "predict_virality", "queue_post", "analytics", "best_times").all { it in names })
        // Notifications get no body; modern-era discovery falls back to the handshake; GET isn't served.
        assertEquals(202, e.relay.mcp.handle("POST", """{"jsonrpc":"2.0","method":"notifications/initialized"}""").status)
        assertEquals(-32601L, Json.parseObj(rpc("server/discover").body).objAt("error").long("code"))
        assertEquals(405, e.relay.mcp.handle("GET", "").status)
        assertEquals(400, e.relay.mcp.handle("POST", "{not json").status)
    }

    @Test fun claude_writes_and_the_phone_renders_scores_and_queues() {
        val out = call("create_slideshow", obj("plan" to plan, "image_source" to "ai"))
        assertEquals(false, out["isError"])
        val content = out.objs("content")
        val info = Json.parseObj(content[0].str("text"))
        assertEquals("completed", info.str("status"), info.toString())
        assertEquals(plan.str("caption"), info.str("caption"))
        val images = content.filter { it.str("type") == "image" }
        assertEquals(4, images.size) // Claude sees what it made
        assertEquals("image/jpeg", images[0].str("mimeType"))
        val id = info.str("id")
        e.db.tx { assertEquals("Written by Claude", get("slideshows", id)!!.str("prompt")) }

        val score = Json.parseObj(call("predict_virality", obj("slideshow_id" to id)).objs("content")[0].str("text"))
        assertTrue(score.long("viral_potential") in 0..100)
        val listed = Json.parse(call("list_slideshows").objs("content")[0].str("text")) as List<*>
        assertEquals(id, (listed[0] as Map<*, *>)["id"])
        val post = Json.parseObj(call("queue_post", obj("slideshow_id" to id)).objs("content")[0].str("text"))
        assertEquals("queued", post.str("status"))
        assertEquals("BROWSER", post.str("post_mode"))
        assertEquals("Deleted.", call("delete_slideshow", obj("slideshow_id" to id)).objs("content")[0].str("text"))
    }

    @Test fun errors_come_back_readable() {
        val missing = call("view_slideshow", obj("slideshow_id" to "nope"))
        assertEquals(true, missing["isError"])
        assertTrue("not found" in missing.objs("content")[0].str("text"))
        val bad = call("create_slideshow", obj("plan" to obj("title" to "x")))
        assertEquals(true, bad["isError"])
        assertTrue("slides: Field required" in bad.objs("content")[0].str("text"), bad.toString())
        assertTrue("library is empty" in call("research_library").objs("content")[0].str("text"))
        assertTrue(Json.write(call("writing_guide")).contains("hook"))
    }

    @Test fun relay_link_and_settings() {
        // Same derivation as the relay (relay/test) and the server (app/mcp_server.token).
        assertEquals("86cc7bb75608ac66e4ffc0eec68deaaa", Relay.claudeToken("ab".repeat(32)))
        assertEquals("https://slidemill-relay.tim.workers.dev", Relay.normalize(" slidemill-relay.tim.workers.dev/ "))
        assertEquals("https://x.workers.dev", Relay.normalize("https://x.workers.dev/mcp/0123"))
        assertEquals("off", c.get("/api/v1/relay").str("state"))
        val on = c.put("/api/v1/relay", obj("url" to "127.0.0.1:9")) // nothing listens there
        val claude = on.str("claude_url")
        assertTrue(claude.matches(Regex("https://127\\.0\\.0\\.1:9/mcp/[0-9a-f]{32}")), claude)
        assertEquals(claude, c.get("/api/v1/relay").str("claude_url")) // stable across calls
        val fresh = c.post("/api/v1/relay/new-link").str("claude_url")
        assertTrue(fresh != claude) // the old link stops working
        assertEquals("off", c.put("/api/v1/relay", obj("url" to "")).str("state"))
    }

    @Test fun slow_renders_answer_in_time_and_post_when_done() {
        e.relay.mcp.waitMs = 0 // as if the backgrounds took longer than Claude waits
        val out = call("create_slideshow", obj("plan" to plan, "image_source" to "ai", "post" to "draft"))
        assertEquals(false, out["isError"])
        val note = out.objs("content")[0].str("text")
        assertTrue("Still rendering on the phone" in note, note)
        val id = Regex("slideshow id ([0-9a-f]+)").find(note)!!.groupValues[1]
        var posts = listOf<Obj>()
        repeat(100) {
            posts = e.db.tx { where("posts") { it.str("slideshow_id") == id }.map { it.copyObj() } }
            if (posts.isEmpty()) Thread.sleep(100)
        }
        e.db.tx { assertEquals("completed", get("slideshows", id)!!.str("status")) }
        assertEquals(1, posts.size) // queued for TikTok once rendered
        assertEquals("draft", posts[0].objAt("options").str("action"))
    }

    @Test fun fast_renders_report_the_queued_post() {
        val content = call("create_slideshow", obj("plan" to plan, "image_source" to "ai", "post" to "draft")).objs("content")
        assertEquals("Queued for TikTok as a draft.", content[1].str("text"))
        assertEquals(4, content.count { it.str("type") == "image" })
    }
}
