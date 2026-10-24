package com.slidemill.core

import com.sun.net.httpserver.HttpServer
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import javax.imageio.ImageIO

/** A fake OpenAI-compatible / Anthropic server answering by which prompt it gets. */
class FakeAi {
    val requests = CopyOnWriteArrayList<Pair<String, Obj>>() // path, body
    val headers = CopyOnWriteArrayList<Map<String, String>>()
    @Volatile var badFirst = 0 // how many invalid answers to give before valid ones
    /** Hook scores the virality fake hands out in order (then 64). */
    val hookScores = java.util.concurrent.ConcurrentLinkedQueue<Int>()
    @Volatile var lastImages = 0
    /** When set, the virality fake scores each read with this (given the prompt) instead of hookScores. */
    @Volatile var scoreFor: ((String) -> Int)? = null
    /** When set, the blind ranker orders slideshows by this value of their slide text (highest first). */
    @Volatile var rankBy: ((String) -> Double)? = null
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val url: String get() = "http://127.0.0.1:${server.address.port}"

    init {
        server.createContext("/") { ex ->
            val body = Json.parseObj(ex.requestBody.readBytes().toString(Charsets.UTF_8))
            requests += ex.requestURI.path to body
            lastImages = Regex("data:image/jpeg;base64").findAll(Json.write(body)).count()
            headers += ex.requestHeaders.entries.associate { it.key.lowercase() to it.value.joinToString(",") }
            val system = if (ex.requestURI.path.endsWith("/v1/messages")) body.str("system") else body.objs("messages").first().str("content")
            val user = body.objs("messages").filter { it.str("role") == "user" }.joinToString("\n") { m ->
                (m["content"] as? String) ?: m.objs("content").joinToString("\n") { it.str("text") }
            }
            val answer = answerFor(system, user)
            val text = if (badFirst > 0) { badFirst--; "{\"title\": 5}" } else Json.write(answer)
            val reply = if (ex.requestURI.path.endsWith("/v1/messages")) {
                obj("type" to "message", "stop_reason" to "end_turn", "content" to arr(obj("type" to "text", "text" to text)))
            } else obj("choices" to arr(obj("message" to obj("role" to "assistant", "content" to "```json\n$text\n```"))))
            val bytes = Json.write(reply).toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    private fun answerFor(system: String, user: String): Obj = when {
        "health check" in system -> obj("ok" to true)
        "You plan TikTok slideshow content" in system -> {
            val n = Regex("Write exactly (\\d+) hooks").find(user)?.groupValues?.get(1)?.toInt() ?: 3
            obj(
                "name" to "Sleep fixes", "format_summary" to "listicle", "style" to "5 slides, casual",
                "visual_style" to "soft morning light", "aspect_ratio" to "4:5",
                "hooks" to (1..n).map { "hook number $it about sleep" }.toMutableList<Any?>(), "language" to "English",
            )
        }
        "You write viral TikTok" in system -> {
            val topic = Regex("<topic>\n(.*?)\n</topic>").find(user)?.groupValues?.get(1) ?: "topic"
            obj(
                "title" to "Plan: $topic", "caption" to "x".repeat(120), "description" to "desc #sleep",
                "text_position" to "center",
                "slides" to arr(
                    obj("role" to "hook", "text_items" to arr(obj("text" to topic, "font" to "tiktok", "font_size" to "large", "text_style" to "outline", "text_color" to "white")), "image_query" to "bed", "image_prompt" to "a bed"),
                    obj("role" to "body", "text_items" to arr(obj("text" to "1. dark room", "font" to "bebas", "font_size" to "medium", "text_style" to "background", "text_color" to "yellow")), "image_query" to "dark", "image_prompt" to "dark room"),
                    obj("role" to "body", "text_items" to arr(obj("text" to "2. 사랑 no screens", "font" to "cormorant", "font_size" to "small", "text_style" to "light_background", "text_color" to "pink")), "image_query" to "phone", "image_prompt" to "phone"),
                ),
            )
        }
        "performance analyst" in system -> {
            val n = Regex("There are (\\d+) slides").find(user)?.groupValues?.get(1)?.toInt() ?: 3
            val hook = scoreFor?.invoke(user) ?: hookScores.poll() ?: 64
            val extra = obj()
            if ("Comparison:" in user) {
                val items = Regex("^Slideshow ([A-G])(?: \\(this one\\))?: (.*)$", RegexOption.MULTILINE).findAll(user).map { it.groupValues[1] to it.groupValues[2] }.toList()
                val by = rankBy
                extra["rank_order"] = (if (by == null) items else items.sortedByDescending { by(it.second) }).map { it.first }.toMutableList<Any?>()
            }
            if ("(unknown: read it off" in user) extra["slide_texts"] = (0 until n).map { "read $it" }.toMutableList<Any?>()
            (extra + obj(
                "hook_score" to hook, "curiosity" to "70", "relatability" to 60, "payoff" to 55, "shareability" to 50,
                "slides" to (1..n).map { obj("swipe_on" to 80, "visual_pull" to 60, "clarity" to 70, "note" to "ok") }.toMutableList<Any?>(),
                "tips" to arr("Make slide 2 more specific."), "better_hook" to "the sleep habit nobody tells you",
            )).toMutableMap()
        }
        "categorize TikTok accounts" in system -> obj("niche" to "Skincare", "product_medium" to "creator")
        "Transcribe the overlay text" in system -> obj("slides" to arr("things that changed my skin", "1. double cleanse", "2. spf daily"))
        else -> obj()
    }

    fun stop() = server.stop(0)
}

object TestKit {
    fun jpeg(w: Int = 300, h: Int = 400, color: Color = Color(200, 170, 190)): ByteArray {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        img.createGraphics().apply { this.color = color; fillRect(0, 0, w, h); dispose() }
        return ByteArrayOutputStream().also { ImageIO.write(img, "jpg", it) }.toByteArray()
    }

    fun engine(ai: FakeAi? = null, platform: JvmPlatform = JvmPlatform()): Engine {
        val dir = Files.createTempDirectory("slidemill").toFile()
        val e = Engine(platform, dir, "test-token")
        if (ai != null) e.store.setMany(mapOf("llm_provider" to "custom", "llm_base_url" to ai.url, "llm_model" to "fake"))
        e.imageGen.override = { _, _ -> jpeg(600, 800) }
        e.library.pause = {}
        return e
    }

    class Client(val e: Engine, val token: String = "test-token") {
        private val http = HttpClient.newHttpClient()
        val base by lazy { "http://127.0.0.1:${e.startServer()}" }

        fun call(method: String, path: String, body: Any? = null, auth: Boolean = true): Pair<Int, Any?> {
            val b = HttpRequest.newBuilder(URI.create(base + path))
            if (auth) b.header("Authorization", "Bearer $token")
            val pub = if (body == null) HttpRequest.BodyPublishers.noBody() else {
                b.header("Content-Type", "application/json")
                HttpRequest.BodyPublishers.ofString(Json.write(body))
            }
            b.method(method, pub)
            val resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString())
            val parsed = runCatching { Json.parse(resp.body()) }.getOrElse { resp.body() }
            return resp.statusCode() to parsed
        }

        fun raw(path: String): HttpResponse<ByteArray> =
            http.send(HttpRequest.newBuilder(URI.create(base + path)).header("Authorization", "Bearer $token").build(), HttpResponse.BodyHandlers.ofByteArray())

        fun upload(path: String, field: String, files: List<Pair<String, ByteArray>>): Pair<Int, Any?> {
            val boundary = "----slidemilltest"
            val out = ByteArrayOutputStream()
            for ((name, data) in files) {
                out.write("--$boundary\r\nContent-Disposition: form-data; name=\"$field\"; filename=\"$name\"\r\nContent-Type: image/jpeg\r\n\r\n".toByteArray())
                out.write(data)
                out.write("\r\n".toByteArray())
            }
            out.write("--$boundary--\r\n".toByteArray())
            val req = HttpRequest.newBuilder(URI.create(base + path)).header("Authorization", "Bearer $token")
                .header("Content-Type", "multipart/form-data; boundary=$boundary")
                .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray())).build()
            val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
            return resp.statusCode() to runCatching { Json.parse(resp.body()) }.getOrElse { resp.body() }
        }

        fun get(path: String) = call("GET", path).also { check(it.first in 200..299) { "GET $path -> $it" } }.second as Obj
        fun post(path: String, body: Any? = obj()) = call("POST", path, body).also { check(it.first in 200..299) { "POST $path -> $it" } }.second as Obj
        fun put(path: String, body: Any?) = call("PUT", path, body).also { check(it.first in 200..299) { "PUT $path -> $it" } }.second as Obj
        fun patch(path: String, body: Any?) = call("PATCH", path, body).also { check(it.first in 200..299) { "PATCH $path -> $it" } }.second as Obj

        fun waitJob(id: String, timeoutMs: Long = 20_000): Obj {
            val end = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < end) {
                val job = get("/api/v1/jobs/$id")
                if (job.str("status") in setOf("completed", "failed")) return job
                Thread.sleep(50)
            }
            error("job $id did not finish")
        }

        fun waitShow(id: String, timeoutMs: Long = 20_000): Obj {
            val end = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < end) {
                val s = get("/api/v1/slideshows/$id")
                if (s.str("status") in setOf("completed", "failed")) return s
                Thread.sleep(50)
            }
            error("slideshow $id did not finish")
        }
    }

    fun mediaFile(e: Engine, url: String) = File(e.mediaDir, url.removePrefix("/media/"))
}
