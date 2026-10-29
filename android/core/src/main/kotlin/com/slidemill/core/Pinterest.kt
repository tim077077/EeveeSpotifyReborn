package com.slidemill.core

/**
 * Pinterest search, read the way Pinterest's own web app reads it.
 *
 * There is no public Pinterest API, so this is the same request a logged-out browser makes:
 * `GET /resource/BaseSearchResource/get/?source_url=…&data=…`, which answers 403 "Invalid Resource
 * Request" unless the XHR headers below are present (observed: 403 without them, 200 with them).
 *
 * Response shape, verified against the live endpoint (Oct 2026):
 *   resource_response.data.results[]   the pins (18 per page)
 *   resource_response.bookmark         the cursor for the next page
 *   pin.images                         {170x, 236x, 474x, 736x, orig}; orig is the full-size original
 *
 * Images are other people's photographs, so each pin keeps its [Pin.link] and [Pin.domain] for
 * attribution, and this only ever reads public search results.
 */
class Pinterest(private val get: (String, Map<String, String>) -> NetResponse = { url, headers -> Net.get(url, headers, 40) }) {

    data class Pin(
        val id: String,
        val title: String,
        val description: String,
        val domain: String,
        val link: String,
        val width: Int,
        val height: Int,
        /** The biggest image on offer (orig, else 736x/474x). */
        val url: String,
        val thumb: String,
        val promoted: Boolean = false,
    ) {
        /** height / width: 1.33 is 4:5, 1.78 is 9:16, 1.0 is square. */
        val aspect: Double get() = if (width > 0) height.toDouble() / width else 0.0

        fun toStock(): Images.StockPhoto = Images.StockPhoto(
            url = url, thumb = thumb, width = width, height = height,
            source = "pinterest", credit = domain.ifEmpty { "Pinterest" },
        )
    }

    companion object {
        const val ENDPOINT = "https://www.pinterest.com/resource/BaseSearchResource/get/"

        /** Pinterest's page declares this; the endpoint only requires that it isn't empty. */
        const val APP_VERSION = "cb1c9c2"

        val HEADERS = mapOf(
            "Accept" to "application/json, text/javascript, */*; q=0.01",
            "Accept-Language" to "en-US,en;q=0.9",
            "X-Requested-With" to "XMLHttpRequest",
            "X-APP-VERSION" to APP_VERSION,
            "X-Pinterest-AppState" to "active",
            "x-pinterest-pws-handler" to "www/search/[scope].js",
            "Referer" to "https://www.pinterest.com/search/pins/",
            "User-Agent" to Net.BROWSER_UA,
        )

        /** Biggest first: originals are the full-size photo, the numbered sizes are crops. */
        private val SIZES = listOf("orig", "736x", "474x", "236x", "170x")
    }

    /**
     * Portrait pins for [query], biggest first pages. [minAspect]/[maxAspect] filter by shape
     * (1.25 = 4:5, 1.78 = 9:16), and promoted pins are left out: an ad isn't a background.
     */
    fun search(
        query: String,
        count: Int = 18,
        portrait: Boolean = true,
        minAspect: Double = 0.0,
        maxAspect: Double = 3.0,
        pauseMs: Long = 350,
    ): List<Pin> {
        val want = count.coerceIn(1, 100)
        val text = query.trim()
        if (text.isEmpty()) return emptyList()
        val out = ArrayList<Pin>()
        var bookmark = ""
        for (page in 0 until 6) {
            if (out.size >= want) break
            if (page > 0 && pauseMs > 0) Thread.sleep(pauseMs + (0..400).random())
            val data = obj(
                "options" to obj(
                    "query" to text, "scope" to "pins", "page" to null,
                    "bookmarks" to arr(bookmark), "page_size" to 25,
                ),
                "context" to obj(),
            )
            val url = Net.withQuery(
                ENDPOINT,
                mapOf(
                    "source_url" to "/search/pins/?q=" + java.net.URLEncoder.encode(text, "UTF-8") + "&rs=typed",
                    "data" to Json.write(data),
                    "_" to System.currentTimeMillis().toString(),
                ),
            )
            val resp = get(url, HEADERS)
            if (resp.status == 403) throw ImageSearchError("Pinterest refused the search (403): it wants a browser-shaped request.")
            if (resp.status >= 400) throw ImageSearchError("Pinterest search failed (${resp.status})")
            val rr = resp.obj().objAt("resource_response")
            if (rr.str("status") == "error") throw ImageSearchError("Pinterest search failed: ${rr.str("message")}")
            for (row in rr.objAt("data").objs("results")) {
                val pin = parse(row) ?: continue
                if (pin.promoted) continue
                if (portrait && pin.aspect <= 1.0) continue
                if (pin.aspect > 0 && (pin.aspect < minAspect || pin.aspect > maxAspect)) continue
                if (out.any { it.id == pin.id }) continue
                out += pin
                if (out.size >= want) break
            }
            val next = rr.str("bookmark")
            if (next.isEmpty() || next == bookmark) break
            bookmark = next
        }
        return out
    }

    /** One pin row from the response, or null when it carries no usable image. */
    private fun parse(row: Obj): Pin? {
        val id = row.str("id")
        if (id.isEmpty()) return null
        val images = row.objAt("images")
        var size = ""
        var best = ""
        for (candidate in SIZES) {
            val url = images.objOrNull(candidate)?.strOrNull("url") ?: continue
            size = candidate
            best = url
            break
        }
        if (best.isEmpty()) return null
        val img = images.objAt(size)
        val thumb = images.objOrNull("236x")?.str("url").orEmpty().ifEmpty { best }
        return Pin(
            id = id,
            title = row.str("grid_title").ifEmpty { row.str("title") }.ifEmpty { row.str("description").take(60) },
            description = row.str("description"),
            domain = row.str("domain"),
            link = row.str("link"),
            width = img.int("width").takeIf { it > 0 } ?: 736,
            height = img.int("height").takeIf { it > 0 } ?: 981,
            url = best,
            thumb = thumb,
            promoted = row.bool("is_promoted"),
        )
    }
}
