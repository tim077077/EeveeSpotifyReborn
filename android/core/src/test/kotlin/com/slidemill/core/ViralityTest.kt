package com.slidemill.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The scoring maths (Claude's own read is tested end to end in McpTest). */
class ViralityTest {
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

    @Test fun similar_posts_finds_the_near_duplicate() {
        val rows = listOf("5 morning habits that fixed my skin", "what I eat in a day as a nurse", "my night routine for deep sleep",
            "things nobody tells you about sleep", "how I save money on groceries").mapIndexed { i, t ->
            obj("id" to "$i", "texts" to arr(t), "lift" to 0.0, "hook" to t, "url" to "", "username" to "u")
        }
        assertEquals("2", Methods.similarPosts("the night routine that gave me deep sleep", rows)[0].second.str("id"))
        assertNull(Methods.similarEvidence("the night routine that gave me deep sleep", rows)) // needs 3 matches
    }
}
