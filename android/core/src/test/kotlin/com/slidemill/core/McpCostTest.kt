package com.slidemill.core

import java.io.ByteArrayInputStream
import java.time.Instant
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * What a typical Claude session costs in tokens, tool by tool: text at ~4 characters a token, images at
 * width × height / 750 (Claude's image token formula). Budgets keep the connector from getting expensive again.
 */
class McpCostTest {
    private val platform = JvmPlatform()
    private val e = TestKit.engine(platform)
    private val tiktok = FakeTikTok("tim").also { e.tiktok = it }

    @AfterTest fun tearDown() {
        Db.clock = { Instant.now() }
        e.stop()
    }

    private fun rpc(method: String, params: Obj = obj()): Obj =
        Json.parseObj(e.relay.mcp.handle("POST", Json.write(obj("jsonrpc" to "2.0", "id" to 1, "method" to method, "params" to params))).body).objAt("result")

    /** Tokens of one MCP result: its text plus its images. */
    private fun tokens(result: Obj): Int {
        val content = result.objs("content")
        if (content.isEmpty()) return Json.write(result).length / 4
        return content.sumOf { c ->
            if (c.str("type") == "image") {
                val img = ImageIO.read(ByteArrayInputStream(Base64.getDecoder().decode(c.str("data"))))
                img.width * img.height / 750
            } else c.str("text").length / 4
        }
    }

    private fun call(tool: String, args: Obj = obj()) = rpc("tools/call", obj("name" to tool, "arguments" to args))

    private val plan = obj(
        "title" to "Pricing tricks", "caption" to "4 pricing tricks that sell", "description" to "Price smarter. #vinted #vintedtips", "text_position" to "center",
        "slides" to (1..7).map { i ->
            obj("role" to if (i == 1) "hook" else "body", "image_query" to "clothes rack", "image_prompt" to "a clothes rack",
                "text_items" to arr(obj("text" to "Slide $i: anchor high, drop slow, never below cost", "font" to "tiktok", "font_size" to "large", "text_style" to "outline", "text_color" to "white")))
        }.toMutableList<Any?>(),
    )

    @Test fun a_typical_session_stays_cheap() {
        val now = 1_793_000_000L
        Db.clock = { Instant.ofEpochSecond(now) }
        e.analytics.record("tim", (1..91).map { i ->
            obj("id" to "77$i", "description" to "Here are 4 pricing tricks that actually sell on Vinted, save this! Post $i #vinted #vinteduk #vintedtips #side",
                "is_slideshow" to (i % 10 != 0), "slides" to 7L, "views" to 300L + i * 37 % 2000, "likes" to 10L, "comments" to 1L,
                "shares" to 1L, "saves" to 4L, "posted_at" to now - i * 86_400L / 2)
        })
        val costs = linkedMapOf<String, Int>()
        costs["tools/list + instructions"] = (Json.write(rpc("tools/list")).length + Mcp.INSTRUCTIONS.length) / 4
        costs["writing_guide"] = tokens(call("writing_guide"))
        costs["phone_status"] = tokens(call("phone_status"))
        costs["analytics"] = tokens(call("analytics", obj("refresh" to false)))
        val created = call("create_slideshow", obj("plan" to plan, "image_source" to "ai"))
        costs["create_slideshow (7 slides)"] = tokens(created)
        created.objs("content").firstOrNull { it.str("type") == "image" }?.let { java.io.File("build/sheet-preview.jpg").writeBytes(Base64.getDecoder().decode(it.str("data"))) }
        val id = Json.parseObj(created.objs("content")[0].str("text")).str("id")
        costs["view_slideshow"] = tokens(call("view_slideshow", obj("slideshow_id" to id)))
        costs["predict_virality (brief)"] = tokens(call("predict_virality", obj("slideshow_id" to id)))
        costs["predict_virality (brief, slides seen)"] = tokens(call("predict_virality", obj("slideshow_id" to id, "seen_slides" to true)))
        repeat(9) { call("create_slideshow", obj("plan" to plan, "image_source" to "ai")) }
        costs["list_slideshows (10)"] = tokens(call("list_slideshows"))
        val total = costs.values.sum()
        println("MCP session cost (tokens): " + costs.entries.joinToString { "${it.key}=${it.value}" } + "; total=$total")
        BUDGET.forEach { (k, max) -> assertTrue(costs.getValue(k) <= max, "$k costs ${costs[k]} tokens (budget $max)") }
    }

    companion object {
        // Before slimming (Oct 2026): tools 3924, analytics 5344, create 3451, view 3451, predict 4293, list 335.
        val BUDGET = mapOf(
            "tools/list + instructions" to 3000, "analytics" to 2500, "create_slideshow (7 slides)" to 1500, "view_slideshow" to 1500,
            "predict_virality (brief)" to 2500, "predict_virality (brief, slides seen)" to 1200, "list_slideshows (10)" to 300,
        )
    }
}
