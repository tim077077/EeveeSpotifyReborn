package com.slidemill.core

import kotlin.math.ln
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FormatsTest {
    @Test fun hook_styles() {
        assertEquals("numbered list (\"5 tips…\")", Formats.hookStyle("5 pricing tricks that sell"))
        assertEquals("numbered list (\"5 tips…\")", Formats.hookStyle("the 3 mistakes killing your sales"))
        assertEquals("POV", Formats.hookStyle("POV: your item finally sold"))
        assertEquals("how-to / how I", Formats.hookStyle("How I made £500 on Vinted"))
        assertEquals("question", Formats.hookStyle("Why isn't your stuff selling?"))
        assertEquals("warning (\"stop…\", \"mistakes\")", Formats.hookStyle("Stop pricing like this"))
        assertEquals(12, Formats.wordsPerSlide(listOf("one two three", "", "a b c d e f g h i j k l m n o p q r s t u")))
    }

    @Test fun groups_need_three_posts_and_the_winner_must_clearly_win() {
        fun row(i: Int, lift: Double, hook: String, slides: Int) = obj(
            "id" to "$i", "username" to "u${i % 3}", "url" to "", "read" to true, "hook" to hook, "lift" to ln(lift),
            "texts" to arr(hook, "body text here", "follow for more"), "images" to (1..slides).map { "p$i-$it.jpg" }.toMutableList<Any?>(),
            "views" to 1000L, "music" to "", "slide_count" to slides.toLong(),
        )
        val rows = (1..5).map { row(it, 3.0, "5 tips for sellers", 7) } + (6..10).map { row(it, 0.8, "my thoughts on selling", 4) }
        val r = Formats.analyze(rows, 3)
        assertEquals(10L, r.long("posts_used"))
        assertTrue("hook_style: numbered list" in r.str("summary"), r.str("summary"))
        assertTrue("slide_count: 7-9 slides" in r.str("summary"))
        assertEquals(3.0, r.objs("examples")[0].dbl("lift"))
        assertEquals("yes", r.objAt("by").objs("ends_with_call_to_action")[0].str("bucket"))
        assertTrue(r.objAt("by").objs("sound").isEmpty()) // no sound info: no group
        assertTrue("never copy" in r.str("how_to_use"))
    }
}
