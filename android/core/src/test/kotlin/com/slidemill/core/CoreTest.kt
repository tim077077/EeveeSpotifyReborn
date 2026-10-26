package com.slidemill.core

import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Engine pieces without the HTTP server: AI layer, schedules, timing, queue, library parsing. */
class CoreTest {
    private val ai = FakeAi()

    @AfterTest fun tearDown() = ai.stop()

    @Test fun json_round_trip() {
        val v = Json.parse("""{"a":[1,2.5,"x\né",true,null],"b":{"c":-3e2}}""") as Obj
        assertEquals(1L, v.list("a")[0])
        assertEquals(-300.0, v.objAt("b").dbl("c"))
        assertEquals(v, Json.parse(Json.write(v)))
        assertTrue("\\u003c" in Json.write("</script>"))
    }

    @Test fun provider_detection() {
        assertEquals("anthropic", Llm.detectProvider("sk-ant-api03-x"))
        assertEquals("openrouter", Llm.detectProvider("sk-or-v1-x"))
        assertEquals("groq", Llm.detectProvider("gsk_abc"))
        assertEquals("gemini", Llm.detectProvider("AIzaSy"))
        assertEquals("xai", Llm.detectProvider("xai-1"))
        assertEquals("openai", Llm.detectProvider("sk-proj-1"))
        assertEquals("together", Llm.detectProvider("a".repeat(64)))
        assertNull(Llm.detectProvider("hello"))
    }

    @Test fun openai_compatible_json_with_repair_retry() {
        val e = TestKit.engine(ai)
        ai.badFirst = 1
        val plan = e.generator.generatePlan("my topic")
        assertEquals("Plan: my topic", plan.str("title"))
        assertEquals(89, plan.str("caption").length)
        assertEquals(2, ai.requests.size) // first answer failed validation, second fixed it
        val retry = ai.requests[1].second.objs("messages")
        assertTrue(retry.last().str("content").startsWith("That JSON did not match the schema"))
        assertTrue("response_format" in ai.requests[0].second)
    }

    @Test fun anthropic_structured_outputs_and_fallbacks() {
        val e = TestKit.engine()
        e.store.setMany(mapOf("llm_api_key" to "sk-ant-test", "llm_base_url" to ai.url))
        assertEquals("anthropic", e.llm.resolve()!!.provider)
        assertEquals("claude-opus-5-5", e.llm.resolve()!!.model)
        val campaign = e.generator.planCampaign("sleep tips", 4)
        assertEquals(4, campaign.strings("hooks").size)
        val (path, body) = ai.requests.single()
        assertEquals("/v1/messages", path)
        assertEquals("json_schema", body.objAt("output_config").objAt("format").str("type"))
        assertEquals("medium", body.objAt("output_config").str("effort"))
        assertEquals("default", body.str("fallbacks"))
        assertEquals("server-side-fallback-2026-07-01", ai.headers.single()["anthropic-beta"])
        assertEquals("sk-ant-test", ai.headers.single()["x-api-key"])
        val schema = body.objAt("output_config").objAt("format").objAt("schema")
        assertEquals(false, schema["additionalProperties"])
    }

    @Test fun settings_mask_and_encrypt() {
        val e = TestKit.engine()
        e.store.setMany(mapOf("llm_api_key" to "sk-proj-abcdefghijklmnop"))
        val raw = e.db.tx { get("settings", "llm_api_key")!!.str("value") }
        assertTrue(raw.startsWith("b64:"))
        assertEquals("sk-p…mnop", e.store.publicView().objAt("llm_api_key").str("value"))
        assertFailsWith<IllegalArgumentException> { e.store.setMany(mapOf("nope" to "1")) }
    }

    @Test fun similarity_matches_python_difflib() {
        assertEquals(0.875, Generator.ratio("5 habits that fixed my sleep", "5 habits that quietly fixed my sleep"), 1e-9)
        assertEquals(0.0, Generator.ratio("abc", "xyz"))
        assertEquals(0.8679245283018868, Generator.ratio("things i wish i knew before 25", "things i knew before 25"), 1e-9)
        assertTrue(Generator.tooSimilar("5 habits that quietly fixed my sleep!", listOf(Generator.fingerprint("5 habits that quietly fixed my sleep"))))
    }

    @Test fun cron_weekdays_use_standard_numbering() {
        assertEquals(setOf(1, 2, 3, 4, 5), Cron.parseDow("1-5"))
        assertEquals(setOf(0), Cron.parseDow("7"))
        assertEquals(setOf(5, 6, 0, 1), Cron.parseDow("fri-mon"))
        assertNull(Cron.parseDow("*"))
        assertEquals("15 9 * * 1,2,3,4,5", Cron.shiftEarlier("30 9 * * 1-5", 15))
        assertEquals("50 23 * * 0,1,2,3,4", Cron.shiftEarlier("5 0 * * 1-5", 15))
        assertNull(Cron.shiftEarlier("*/5 9 * * *", 15))
        assertFailsWith<IllegalArgumentException> { Cron.validate("61 9 * * *") }
        assertFailsWith<IllegalArgumentException> { Cron.validate("9 * *") }
        val zone = ZoneId.of("Europe/Berlin")
        // Friday 2026-10-23 20:00 Berlin -> next "0 19 * * 1-5" is Monday 19:00 Berlin.
        val next = Cron.parse("0 19 * * 1-5").next(ZonedDateTime.of(2026, 10, 23, 20, 0, 0, 0, zone).toInstant(), zone)!!
        assertEquals(ZonedDateTime.of(2026, 10, 26, 19, 0, 0, 0, zone).toInstant(), next)
    }

    @Test fun best_times_match_the_python_engine() {
        val t = TestKit.engine().timing
        val golden = mapOf(
            ("US" to 3) to listOf("0 13 * * 6,0", "0 15 * * 1,2,3,4,5", "0 18 * * *", "0 21 * * *"),
            ("RO" to 2) to listOf("0 17 * * *", "0 20 * * *"),
            ("SA" to 4) to listOf("0 1 * * 1,2,3,4,0", "0 13 * * 5,6", "0 14 * * 1,2,3,4,0", "0 16 * * 5,6", "0 19 * * *", "0 22 * * *"),
            ("NZ" to 1) to listOf("0 20 * * *"),
            ("AU" to 6) to listOf("0 8 * * 1,2,3,4,5", "30 9 * * 6,0", "0 12 * * *", "0 15 * * *", "30 17 * * *", "0 20 * * *", "30 22 * * *"),
        )
        for ((key, crons) in golden) {
            val plan = t.bestTimes(key.first, key.second)
            assertEquals(crons, plan.strings("crons"), "crons for $key")
        }
        val us = t.bestTimes("US", 3)
        val heat = (us.list("heatmap")[2] as List<*>).take(6) + (us.list("heatmap")[5] as List<*>)[20]
        assertEquals(listOf(0.513, 0.324, 0.166, 0.058, 0.01, 0.037, 0.991), heat)
        assertEquals("America/New_York", us.str("timezone"))
        assertEquals(listOf("fri", "sat"), t.bestTimes("SA", 2).strings("weekend"))
        assertFailsWith<NoSuchElementException> { t.bestTimes("XX", 2) }
        assertEquals(52, Timing.countryList().size)
    }

    @Test fun library_parsing() {
        val post = Library.normalizeWebItem(WEB_ITEM)
        assertTrue(post.bool("is_slideshow"))
        assertEquals(3, post.list("slide_urls").size)
        assertEquals(850000L, post.long("views"))
        assertEquals(22000L, post.long("saves"))
        assertEquals("glowdaily", post.str("username"))
        assertEquals(120000L, post.objAt("author").long("followers"))
        assertEquals("profile" to "glowdaily", Library.parseItem("@glowdaily"))
        assertEquals("profile" to "glowdaily", Library.parseItem("https://www.tiktok.com/@glowdaily"))
        assertEquals(
            "post" to "https://www.tiktok.com/@glowdaily/video/7400000000000000001",
            Library.parseItem("https://www.tiktok.com/@glowdaily/photo/7400000000000000001?lang=en"),
        )
        assertFailsWith<LibraryError> { Library.parseItem("not a name!") }
        val html = "<html><script id=\"__UNIVERSAL_DATA_FOR_REHYDRATION__\" type=\"application/json\">" +
            Json.write(obj("__DEFAULT_SCOPE__" to obj("webapp.video-detail" to obj("itemInfo" to obj("itemStruct" to WEB_ITEM))))) + "</script></html>"
        assertEquals("7400000000000000001", Library.parseScope(html).objAt("webapp.video-detail").objAt("itemInfo").objAt("itemStruct").str("id"))
    }

    @Test fun models_apply_defaults_and_reject_bad_values() {
        val spec = Models.SPEC.of(obj("slides" to arr(obj("text_items" to arr(obj("text" to "a"))))))
        assertEquals("4:5", spec.str("aspect_ratio"))
        assertEquals("outline", spec.objs("slides")[0].objs("text_items")[0].str("text_style"))
        assertFailsWith<ValidationError> { Models.SPEC.of(obj("aspect_ratio" to "3:2")) }
        assertFailsWith<ValidationError> { Models.SPEC.of(obj("slides" to (1..21).map { obj() }.toMutableList<Any?>())) }
        assertFailsWith<ValidationError> { Models.STUDIO.of(obj("brief" to "x", "count" to 11)) }
    }

    companion object {
        val WEB_ITEM: Obj = obj(
            "id" to "7400000000000000001",
            "desc" to "things that changed my skin #skincare",
            "createTime" to 1760000000L,
            "author" to obj("uniqueId" to "glowdaily", "nickname" to "Glow Daily", "signature" to "skincare that works", "verified" to false),
            "authorStats" to obj("followerCount" to 120000L, "heartCount" to 3400000L, "videoCount" to 210L),
            "stats" to obj("playCount" to 850000L, "diggCount" to 61000L, "commentCount" to 300L, "shareCount" to 4000L, "collectCount" to 22000L),
            "imagePost" to obj("images" to (0 until 3).map { obj("imageURL" to obj("urlList" to arr("https://cdn.example/slide$it.jpeg"))) }.toMutableList<Any?>()),
            "music" to obj("title" to "original sound", "authorName" to "glowdaily"),
        )
    }

    /**
     * Android's regex engine (ICU) rejects a bare "}" that desktop Java accepts, which crashed the app
     * on launch. Every regex literal in the engine must escape braces that aren't quantifiers.
     */
    @Test fun regexes_compile_on_android() {
        val literal = Regex("Regex\\(\"((?:[^\"\\\\]|\\\\.)*)\"")
        val bad = java.io.File("src/main/kotlin").walk().filter { it.extension == "kt" }.flatMap { f ->
            literal.findAll(f.readText()).map { f.name to it.groupValues[1].replace("\\\\", "\\") }
        }.filter { (_, re) ->
            val rest = re.replace(Regex("\\\\[pP]\\{[^}]*\\}"), "")
                .replace(Regex("\\\\."), "")
                .replace(Regex("\\{\\d+(,\\d*)?\\}"), "")
            '{' in rest || '}' in rest
        }.toList()
        assertEquals(emptyList(), bad)
    }
}
