package com.slidemill.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Pinterest search, against responses shaped exactly like the live endpoint's (Oct 2026):
 * resource_response.data.results[] with images {170x,236x,474x,736x,orig}, and a bookmark cursor.
 */
class PinterestTest {

    private fun pin(id: String, w: Int, h: Int, promoted: Boolean = false, title: String = "beach") = obj(
        "id" to id, "grid_title" to title, "description" to "a description", "domain" to "example.com",
        "link" to "https://example.com/p/$id", "is_promoted" to promoted,
        "images" to obj(
            "170x" to obj("width" to 170, "height" to 226, "url" to "https://i.pinimg.com/170x/$id.jpg"),
            "236x" to obj("width" to 236, "height" to 314, "url" to "https://i.pinimg.com/236x/$id.jpg"),
            "474x" to obj("width" to 474, "height" to 632, "url" to "https://i.pinimg.com/474x/$id.jpg"),
            "736x" to obj("width" to 736, "height" to 981, "url" to "https://i.pinimg.com/736x/$id.jpg"),
            "orig" to obj("width" to w, "height" to h, "url" to "https://i.pinimg.com/originals/$id.jpg"),
        ),
    )

    private fun page(pins: List<Obj>, bookmark: String = "", status: String = "success") = obj(
        "resource_response" to obj(
            "status" to status, "bookmark" to bookmark, "message" to "",
            "data" to obj("results" to pins.toMutableList<Any?>()),
        ),
    )

    private fun fetcher(vararg pages: Obj, seen: MutableList<String> = mutableListOf()): (String, Map<String, String>) -> NetResponse =
        { url, headers ->
            seen.add(url)
            assertTrue(headers.containsKey("X-Requested-With"), "the XHR headers are what stop the 403")
            assertTrue(headers.containsKey("x-pinterest-pws-handler"))
            val body = pages[minOf(seen.size - 1, pages.size - 1)]
            NetResponse(200, Json.write(body).toByteArray(), mapOf("content-type" to "application/json"), url)
        }

    @Test fun it_reads_pins_the_way_pinterest_lists_them() {
        val seen = mutableListOf<String>()
        val pinterest = Pinterest(fetcher(page(listOf(pin("aaa", 968, 1291), pin("bbb", 736, 736))), seen = seen))
        val pins = pinterest.search("beach", 10, pauseMs = 0)

        assertEquals(1, pins.size) // the square pin isn't a carousel background
        val one = pins[0]
        assertEquals("aaa", one.id)
        assertEquals("https://i.pinimg.com/originals/aaa.jpg", one.url) // orig wins over the crops
        assertEquals("https://i.pinimg.com/236x/aaa.jpg", one.thumb)
        assertEquals(1.334, Math.round(one.aspect * 1000) / 1000.0) // 1291 / 968
        assertEquals("example.com", one.domain)
        assertEquals("https://example.com/p/aaa", one.link) // kept, for attribution

        // The request is the one Pinterest's own app makes.
        assertTrue(seen[0].contains("/resource/BaseSearchResource/get/"))
        assertTrue(seen[0].contains("source_url="))
        assertTrue(seen[0].contains("data="))
        assertTrue(seen[0].contains("beach"))
    }

    @Test fun promoted_pins_and_the_wrong_shape_are_left_out() {
        val pinterest = Pinterest(
            fetcher(
                page(
                    listOf(
                        pin("ad", 736, 1104, promoted = true),
                        pin("tall", 736, 1309), // 1.78, a 9:16 story
                        pin("flat", 1000, 500),
                        pin("ok", 736, 981), // 1.33, 4:5
                    ),
                ),
            ),
        )
        assertEquals(listOf("tall", "ok"), pinterest.search("x", 10, pauseMs = 0).map { it.id })
        // Asking for a story-shaped pin drops the 4:5 one; "any" keeps every orientation.
        assertEquals(listOf("tall"), pinterest.search("x", 10, minAspect = 1.6, pauseMs = 0).map { it.id })
        assertEquals(listOf("tall", "flat", "ok"), pinterest.search("x", 10, portrait = false, pauseMs = 0).map { it.id })
    }

    @Test fun it_follows_the_bookmark_and_stops_at_the_count() {
        val seen = mutableListOf<String>()
        val pinterest = Pinterest(
            fetcher(
                page(listOf(pin("a1", 736, 981)), "cursor-1"),
                page(listOf(pin("a2", 736, 981)), ""),
                seen = seen,
            ),
        )
        assertEquals(listOf("a1", "a2"), pinterest.search("x", 2, pauseMs = 0).map { it.id })
        assertEquals(2, seen.size)
        assertTrue(seen[1].contains("cursor-1"), "the second page asks for the bookmark Pinterest returned")
        // Asking for one pin means one request, not a scroll through every page.
        val one = mutableListOf<String>()
        Pinterest(fetcher(page(listOf(pin("b1", 736, 981)), "more"), seen = one)).search("x", 1, pauseMs = 0)
        assertEquals(1, one.size)
    }

    @Test fun a_refusal_says_what_is_wrong_and_an_error_page_is_not_empty_results() {
        val refused = Pinterest { url, _ -> NetResponse(403, "Invalid Resource Request".toByteArray(), emptyMap(), url) }
        val err = assertFailsWith<ImageSearchError> { refused.search("x", 4, pauseMs = 0) }
        assertTrue(err.message!!.contains("403"), err.message!!)

        val broken = Pinterest(fetcher(page(emptyList(), status = "error")))
        assertFailsWith<ImageSearchError> { broken.search("x", 4, pauseMs = 0) }

        // A pin with no usable image is skipped, not turned into a broken download.
        val noImages = Pinterest(
            fetcher(page(listOf(obj("id" to "nope", "images" to obj()), pin("real", 736, 981)))),
        )
        assertEquals(listOf("real"), noImages.search("x", 4, pauseMs = 0).map { it.id })
    }

    @Test fun a_slideshow_can_take_its_backgrounds_from_pinterest() {
        val e = TestKit.engine()
        val fake = Pinterest { url, _ ->
            if (url.contains("BaseSearchResource")) {
                NetResponse(200, Json.write(page(listOf(pin("p1", 736, 981)))).toByteArray(), emptyMap(), url)
            } else {
                NetResponse(200, TestKit.jpeg(400, 533), emptyMap(), url) // the image itself
            }
        }
        e.images.download = { TestKit.jpeg(400, 533) } // the pin's photo, without touching the network
        val studio = Studio(e.db, e.platform, e.mediaDir, e.imageGen, e.images, e.renderer, fake)
        val spec = obj(
            "aspect_ratio" to "4:5", "title" to "coffee",
            "slides" to arr(
                obj("image_query" to "beach", "text_items" to arr(obj("text" to "one"))),
                obj("image_query" to "beach", "text_items" to arr(obj("text" to "two"))),
            ),
        )
        assertEquals(0, studio.assignImages(spec, "pinterest")) // every slide got a background
        // Read the spec's own maps, the way the renderer does.
        val paths = (spec["slides"] as List<*>).mapNotNull { (it as? Map<*, *>)?.get("image") as? String }
        assertTrue(paths.all { it.isNotEmpty() }, paths.toString())
        assertTrue(paths[0].endsWith(".jpg"))
        // Downloaded once per URL: the second slide reuses the same pin rather than fetching it again.
        assertEquals(1, e.db.tx { where("images") { it.str("source") == "pinterest" }.size })
        assertTrue("pinterest" in studio.sources)
    }
}
