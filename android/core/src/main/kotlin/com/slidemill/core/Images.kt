package com.slidemill.core

import java.io.File
import java.net.URLEncoder
import java.util.Base64
import kotlin.random.Random

/** AI backgrounds over whichever image key is available (port of app/imagegen.py). */
class ImageGen(private val store: Store, private val llm: Llm) {
    data class Config(val provider: String, val apiKey: String, val model: String)

    companion object {
        val IMAGE_PROVIDERS = linkedMapOf(
            "openai" to "gpt-image-1",
            "gemini" to "gemini-2.5-flash-image",
            "together" to "black-forest-labs/FLUX.1-schnell",
            "pollinations" to "flux",
        )

        // Pixel targets per aspect ratio; providers snap to what they support.
        val SIZES = mapOf("4:5" to (1080 to 1350), "9:16" to (1080 to 1920), "1:1" to (1080 to 1080), "16:9" to (1920 to 1080))

        fun buildPrompt(prompt: String, visualStyle: String = ""): String {
            val parts = mutableListOf(prompt.trim().trimEnd('.'))
            if (visualStyle.isNotEmpty()) parts += visualStyle.trim().trimEnd('.')
            parts += "Photorealistic vertical social-media photo, natural and candid, uncluttered area for overlay text. " +
                "Absolutely no text, letters, captions, watermarks or logos in the image"
            return parts.joinToString(". ") + "."
        }
    }

    /** Pause between free-tier retries (tests shorten it). */
    @Volatile var retryDelayMs = 6_000L

    /** Replaces the network call (tests). */
    @Volatile var override: ((prompt: String, aspectRatio: String) -> ByteArray)? = null

    fun resolve(): Config {
        var provider = store.get("image_provider")
        var key = store.get("image_api_key")
        if (provider.isEmpty() || provider == "auto") {
            val cfg = llm.resolve()
            if (key.isNotEmpty()) {
                provider = Llm.detectProvider(key) ?: "pollinations"
                if (provider !in IMAGE_PROVIDERS) provider = "pollinations"
            } else if (cfg != null && cfg.provider in IMAGE_PROVIDERS) {
                provider = cfg.provider
                key = cfg.apiKey
            } else provider = "pollinations"
        }
        if (provider !in IMAGE_PROVIDERS) throw ImageGenError("Unknown image provider '$provider'")
        if (provider != "pollinations" && key.isEmpty()) {
            val llmCfg = llm.resolve()
            if (llmCfg != null && llmCfg.provider == provider) key = llmCfg.apiKey
            else throw ImageGenError("No API key for image provider $provider.")
        }
        return Config(provider, key, store.get("image_model").ifEmpty { IMAGE_PROVIDERS.getValue(provider) })
    }

    fun describe(): String {
        val cfg = try { resolve() } catch (e: ImageGenError) { return "unavailable: ${e.message}" }
        val note = if (cfg.provider == "pollinations" && cfg.apiKey.isEmpty()) " (free, watermarked)" else ""
        return "${cfg.provider} · ${cfg.model}$note"
    }

    /** Encoded image bytes (PNG/JPEG) for the prompt. */
    fun generate(prompt: String, aspectRatio: String = "4:5", visualStyle: String = "", cfg: Config? = null): ByteArray {
        override?.let { return it(buildPrompt(prompt, visualStyle), aspectRatio) }
        val c = cfg ?: resolve()
        val full = buildPrompt(prompt, visualStyle)
        try {
            return when (c.provider) {
                "openai" -> openai(c, full, aspectRatio)
                "gemini" -> gemini(c, full, aspectRatio)
                "together" -> together(c, full, aspectRatio)
                else -> pollinations(c, full, aspectRatio)
            }
        } catch (e: NetError) {
            throw ImageGenError("${c.provider} image request failed: ${e.message}")
        }
    }

    private fun check(resp: NetResponse, provider: String): Obj {
        if (resp.status >= 400) throw ImageGenError("$provider image error ${resp.status}: ${resp.text.take(300)}")
        return runCatching { resp.obj() }.getOrElse { throw ImageGenError("$provider returned an unexpected response") }
    }

    private fun openai(cfg: Config, prompt: String, aspect: String): ByteArray {
        val size = mapOf("16:9" to "1536x1024", "1:1" to "1024x1024")[aspect] ?: "1024x1536"
        val data = check(
            Net.postJson(
                "https://api.openai.com/v1/images/generations",
                obj("model" to cfg.model, "prompt" to prompt, "size" to size, "n" to 1),
                mapOf("Authorization" to "Bearer ${cfg.apiKey}"),
            ),
            "openai",
        )
        val item = data.objs("data").firstOrNull() ?: throw ImageGenError("openai returned no image")
        item.strOrNull("b64_json")?.let { return Base64.getDecoder().decode(it) }
        return Net.get(item.str("url"), timeoutSec = 60).body
    }

    private fun gemini(cfg: Config, prompt: String, aspect: String): ByteArray {
        val data = check(
            Net.postJson(
                "https://generativelanguage.googleapis.com/v1beta/models/${cfg.model}:generateContent",
                obj(
                    "contents" to arr(obj("parts" to arr(obj("text" to prompt)))),
                    "generationConfig" to obj("responseModalities" to arr("IMAGE"), "imageConfig" to obj("aspectRatio" to aspect)),
                ),
                mapOf("x-goog-api-key" to cfg.apiKey),
            ),
            "gemini",
        )
        for (cand in data.objs("candidates")) {
            for (part in cand.objAt("content").objs("parts")) {
                val inline = part.objOrNull("inlineData") ?: part.objOrNull("inline_data")
                inline?.strOrNull("data")?.let { return Base64.getDecoder().decode(it) }
            }
        }
        throw ImageGenError("Gemini returned no image (it may have declined the prompt).")
    }

    private fun together(cfg: Config, prompt: String, aspect: String): ByteArray {
        val (w, h) = SIZES[aspect] ?: SIZES.getValue("4:5")
        val scale = 1024.0 / maxOf(w, h)
        val data = check(
            Net.postJson(
                "https://api.together.xyz/v1/images/generations",
                obj(
                    "model" to cfg.model, "prompt" to prompt, "n" to 1, "steps" to 4, "response_format" to "base64",
                    "width" to (w * scale).toInt() / 16 * 16, "height" to (h * scale).toInt() / 16 * 16,
                ),
                mapOf("Authorization" to "Bearer ${cfg.apiKey}"),
            ),
            "together",
        )
        return Base64.getDecoder().decode(data.objs("data").first().str("b64_json"))
    }

    private fun pollinations(cfg: Config, prompt: String, aspect: String): ByteArray {
        val (w, h) = SIZES[aspect] ?: SIZES.getValue("4:5")
        val params = linkedMapOf<String, Any?>("width" to w, "height" to h, "nologo" to "true", "model" to cfg.model, "seed" to Random.nextInt(1, 1_000_000_000))
        if (cfg.apiKey.isNotEmpty()) params["token"] = cfg.apiKey
        val path = URLEncoder.encode(prompt.take(1500), "UTF-8").replace("+", "%20")
        // The free tier rate-limits back-to-back requests (one at a time per IP): back off and retry.
        var last = 0
        for (attempt in 0 until 4) {
            val resp = Net.get(Net.withQuery("https://image.pollinations.ai/prompt/$path", params), timeoutSec = 180)
            if (resp.status < 400 && resp.header("content-type").startsWith("image/")) return resp.body
            last = resp.status
            Thread.sleep(retryDelayMs * (attempt + 1))
        }
        throw ImageGenError("pollinations error $last (rate-limited; an image key avoids this)")
    }
}

class ImageGenError(message: String) : RuntimeException(message)

/** Stock-photo search, collections and least-used picking (port of app/images.py). */
class Images(private val db: Db, private val store: Store, private val platform: Platform, private val mediaDir: File) {
    data class StockPhoto(val url: String, val thumb: String, val width: Int, val height: Int, val source: String, val credit: String = "") {
        fun toObj(): Obj = obj("url" to url, "thumb" to thumb, "width" to width, "height" to height, "source" to source, "credit" to credit)
    }

    fun providers(): List<String> = buildList {
        if (store.get("pexels_api_key").isNotEmpty()) add("pexels")
        if (store.get("unsplash_access_key").isNotEmpty()) add("unsplash")
    }

    fun search(query: String, count: Int = 12, orientation: String = "portrait", page: Int = 1): List<StockPhoto> {
        val available = providers()
        if (available.isEmpty()) throw ImageSearchError("No image provider configured. Add a Pexels or Unsplash key in Settings.")
        return if (available[0] == "pexels") pexels(query, count, orientation, page) else unsplash(query, count, orientation, page)
    }

    private fun pexels(query: String, count: Int, orientation: String, page: Int): List<StockPhoto> {
        val resp = Net.get(
            Net.withQuery("https://api.pexels.com/v1/search", mapOf("query" to query, "per_page" to minOf(count, 80), "orientation" to orientation, "page" to page)),
            mapOf("Authorization" to store.get("pexels_api_key")), timeoutSec = 20,
        )
        if (resp.status >= 400) throw ImageSearchError("Pexels error ${resp.status}")
        return resp.obj().objs("photos").map { p ->
            val src = p.objAt("src")
            StockPhoto(src.strOrNull("large2x") ?: src.str("original"), src.str("medium"), p.int("width"), p.int("height"), "pexels", p.str("photographer"))
        }
    }

    private fun unsplash(query: String, count: Int, orientation: String, page: Int): List<StockPhoto> {
        val resp = Net.get(
            Net.withQuery("https://api.unsplash.com/search/photos", mapOf("query" to query, "per_page" to minOf(count, 30), "orientation" to orientation, "page" to page)),
            mapOf("Authorization" to "Client-ID ${store.get("unsplash_access_key")}"), timeoutSec = 20,
        )
        if (resp.status >= 400) throw ImageSearchError("Unsplash error ${resp.status}")
        return resp.obj().objs("results").map { r ->
            val urls = r.objAt("urls")
            StockPhoto(urls.str("regular"), urls.str("small"), r.int("width"), r.int("height"), "unsplash", r.objAt("user").str("name"))
        }
    }

    /** Normalize to JPEG under media/<folder>; returns (relative path, width, height). */
    fun storeBytes(data: ByteArray, folder: String): Triple<String, Int, Int> {
        val img = platform.imaging.normalize(data, 2160, 2700, 90)
        val name = "${Db.newId()}.jpg"
        val target = File(mediaDir, "$folder/$name")
        target.parentFile.mkdirs()
        target.writeBytes(img.jpeg)
        return Triple("$folder/$name", img.width, img.height)
    }

    fun downloadInto(photo: StockPhoto, collectionId: String?, query: String = ""): Obj {
        val resp = Net.get(photo.url, timeoutSec = 30)
        if (resp.status >= 400) throw ImageSearchError("download failed (${resp.status})")
        val (rel, w, h) = storeBytes(resp.body, "library/${collectionId ?: "auto"}")
        return db.tx {
            insert("images", obj(
                "collection_id" to collectionId, "path" to rel, "source" to photo.source, "source_url" to photo.url,
                "query" to query, "width" to w, "height" to h, "use_count" to 0,
            ))
        }.copyObj()
    }

    /** Least-used image first so a collection is exhausted before anything repeats. */
    fun pickFromCollection(collectionId: String, exclude: Set<String>): Obj? = db.tx {
        val rows = where("images") { it.str("collection_id") == collectionId }
        val candidates = rows.filter { it.str("id") !in exclude }.ifEmpty { rows }
        if (candidates.isEmpty()) return@tx null
        val low = candidates.minOf { it.long("use_count") }
        val choice = candidates.filter { it.long("use_count") == low }.random()
        choice["use_count"] = choice.long("use_count") + 1
        choice.copyObj()
    }

    /** Search stock photos for a slide and cache the pick in the 'auto' pool. */
    fun autoImage(query: String, orientation: String, usedUrls: MutableSet<String>): Obj? {
        val results = try { search(query, 15, orientation) } catch (e: Exception) { return null }
        val fresh = results.filter { it.url !in usedUrls }
        // Reuse beyond the first few results keeps slideshows from looking identical.
        val pool = fresh.take(8).ifEmpty { results.take(8) }
        if (pool.isEmpty()) return null
        val photo = pool.random()
        usedUrls += photo.url
        val existing = db.tx {
            where("images") { it.str("source_url") == photo.url }.firstOrNull()?.also {
                it["use_count"] = it.long("use_count") + 1
            }?.copyObj()
        }
        if (existing != null) return existing
        val image = try { downloadInto(photo, null, query) } catch (e: Exception) { return null }
        db.tx { get("images", image.str("id"))?.set("use_count", 1L) }
        return image
    }

    fun deleteFile(rel: String) {
        if (rel.isEmpty()) return
        val path = File(mediaDir, rel).canonicalFile
        if (path.path.startsWith(mediaDir.canonicalPath + File.separator) && path.isFile) path.delete()
    }

    fun orientationFor(aspectRatio: String): String = mapOf("16:9" to "landscape", "1:1" to "square")[aspectRatio] ?: "portrait"
}

class ImageSearchError(message: String) : RuntimeException(message)
