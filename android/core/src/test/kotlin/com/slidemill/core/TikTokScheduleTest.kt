package com.slidemill.core

import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** TikTok's own schedule: deleting a slideshow takes its post off it, and a new slideshow can replace a scheduled one. */
class TikTokScheduleTest {
    private val e = TestKit.engine()
    private val tab = FakeTikTok("vintedbot.org").also { e.tiktok = it }

    init {
        e.analytics.profile = { null }
    }

    @AfterTest fun tearDown() = e.stop()

    private fun call(tool: String, args: Obj = obj()): Obj = Json.parseObj(e.relay.mcp.handle("POST",
        Json.write(obj("jsonrpc" to "2.0", "id" to 1, "method" to "tools/call", "params" to obj("name" to tool, "arguments" to args)))).body).objAt("result")

    private fun text(out: Obj) = out.objs("content")[0].str("text")

    private fun create(desc: String): String {
        val plan = obj("title" to "t", "caption" to "c", "description" to desc,
            "slides" to (1..3).map { obj("image_query" to "q", "image_prompt" to "p", "text_items" to arr(obj("text" to "slide $it"))) }.toMutableList<Any?>())
        val id = Json.parseObj(text(call("create_slideshow", obj("plan" to plan, "image_source" to "ai")))).str("id")
        assertTrue("Passed" in text(call("review_slideshow", obj("slideshow_id" to id, "slides_checked" to 35L, "issues" to arr(), "claims" to arr()))))
        return id
    }

    private val at = Instant.ofEpochSecond((Db.nowMs() / 1000 + 6 * 3600) / 300 * 300).toString()

    @Test fun deleting_a_scheduled_slideshow_takes_it_off_tiktok_too() {
        val id = create("Outdoor jackets #vinted")
        assertTrue("Scheduled" in text(call("schedule_slideshow", obj("slideshow_id" to id, "at" to at))))
        assertEquals(1, tab.onSchedule.size)
        val out = text(call("delete_slideshow", obj("slideshow_id" to id)))
        assertTrue("Deleted from TikTok Studio's schedule" in out, out)
        assertEquals(0, tab.onSchedule.size)
        assertTrue("Nothing scheduled" in text(call("tiktok_schedule")))
    }

    @Test fun a_delete_tiktok_ignores_keeps_the_slideshow_and_says_so() {
        val id = create("Flaws #vinted")
        call("schedule_slideshow", obj("slideshow_id" to id, "at" to at))
        tab.deleteIgnored = true
        val out = call("delete_slideshow", obj("slideshow_id" to id))
        assertEquals(true, out["isError"])
        assertTrue("still in TikTok Studio" in text(out), text(out))
        assertTrue(e.db.tx { get("slideshows", id) } != null) // not forgotten on the phone while TikTok still has it
    }

    @Test fun a_new_slideshow_replaces_a_scheduled_one_at_the_same_time() {
        val old = create("Dresses #vinted")
        call("schedule_slideshow", obj("slideshow_id" to old, "at" to at))
        val oldItem = tab.onSchedule.single().str("id")
        val new = create("Dresses #vinted (new end card)")
        // Without replaces, the 2-hour guard refuses the same time.
        assertTrue("2 hours" in text(call("schedule_slideshow", obj("slideshow_id" to new, "at" to at))))
        val out = text(call("schedule_slideshow", obj("slideshow_id" to new, "at" to at, "replaces" to old)))
        assertTrue("Replaced $old" in out && "Scheduled" in out, out)
        assertEquals(listOf(oldItem), tab.deleted.toList())
        assertEquals(1, tab.onSchedule.size)
        assertTrue("new end card" in tab.onSchedule.single().str("desc"))
        assertEquals(null, e.db.tx { get("slideshows", old) })
    }

    @Test fun tiktok_schedule_lists_and_deletes_a_post_the_phone_forgot() {
        val id = create("Bad review #vinted")
        call("schedule_slideshow", obj("slideshow_id" to id, "at" to at))
        e.studio.delete(id) // the old phone-only delete: TikTok still has it
        val item = tab.onSchedule.single().str("id")
        assertTrue("no slideshow on the phone" in text(call("tiktok_schedule")))
        val out = text(call("tiktok_schedule", obj("delete" to item)))
        assertTrue("Deleted post $item" in out && "Nothing scheduled" in out, out)
    }
}
