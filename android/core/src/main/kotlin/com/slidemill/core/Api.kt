package com.slidemill.core

import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.time.Duration
import java.time.ZoneId
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class ApiError(val status: Int, val error: String, val code: String) : RuntimeException(error)

/**
 * The dashboard's REST API (/api/v1, same paths and JSON shapes as app/main.py), the dashboard
 * itself and /media, served on 127.0.0.1 inside the app. Every API call needs the per-install token.
 */
class Api(private val e: Engine, private val token: String, port: Int) : NanoHTTPD("127.0.0.1", port) {
    private class Req(val method: String, val path: String, val query: Map<String, String>, val session: IHTTPSession) {
        lateinit var params: List<String>
        // Read the whole body up front (unless it's an upload) so keep-alive connections stay in sync.
        private val raw: String? = if (session.headers["content-type"].orEmpty().startsWith("multipart/")) null else {
            val len = session.headers["content-length"]?.toIntOrNull() ?: 0
            if (len <= 0) null else {
                val buf = ByteArray(len)
                var off = 0
                while (off < len) {
                    val n = session.inputStream.read(buf, off, len - off)
                    if (n < 0) break
                    off += n
                }
                String(buf, 0, off, Charsets.UTF_8)
            }
        }

        fun body(): Any? = raw?.takeIf { it.isNotBlank() }?.let {
            try { Json.parse(it) } catch (x: JsonError) { throw ApiError(422, "invalid JSON body", "VALIDATION_ERROR") }
        }

        fun q(name: String): String? = query[name]?.takeIf { it.isNotEmpty() }
        fun qInt(name: String, default: Int): Int = query[name]?.toIntOrNull() ?: default
    }

    private class Route(val method: String, val pattern: Regex, val handler: (Req) -> Any?, val status: Int = 200)

    private val routes = mutableListOf<Route>()

    private fun route(method: String, path: String, status: Int = 200, handler: (Req) -> Any?) {
        val pattern = Regex("^/api/v1" + path.replace(Regex("\\{[a-z_]+\\}"), "([^/]+)") + "$")
        routes += Route(method, pattern, handler, status)
    }

    private fun notFound(what: String) = ApiError(404, "$what not found", "NOT_FOUND")
    private fun invalid(msg: String) = ApiError(422, msg, "VALIDATION_ERROR")

    override fun serve(session: IHTTPSession): Response {
        val path = session.uri.substringBefore('?')
        val query = session.parameters.mapValues { it.value.firstOrNull() ?: "" }
        return try {
            when {
                path == "/" || path == "/index.html" -> indexPage(query["t"])
                path.startsWith("/static/") -> asset("static/" + path.removePrefix("/static/"))
                path == "/favicon.ico" -> asset("static/favicon.svg")
                path.startsWith("/media/") -> media(path.removePrefix("/media/"), session)
                path == "/healthz" -> json(200, obj("status" to "ok"))
                path.startsWith("/api/v1/") -> api(Req(session.method.name, path, query, session))
                else -> json(404, obj("detail" to "Not Found"))
            }
        } catch (x: ApiError) {
            json(x.status, obj("detail" to obj("error" to x.error, "code" to x.code)))
        } catch (x: ValidationError) {
            json(422, obj("detail" to obj("error" to (x.message ?: "invalid request"), "code" to "VALIDATION_ERROR")))
        } catch (x: Exception) {
            e.platform.log("API error on $path: $x")
            json(500, obj("detail" to obj("error" to (x.message ?: x.toString()), "code" to "INTERNAL")))
        }
    }

    private fun api(req: Req): Response {
        val auth = req.session.headers["authorization"].orEmpty().removePrefix("Bearer ").trim()
        if (!MessageDigest.isEqual(auth.toByteArray(), token.toByteArray())) {
            throw ApiError(401, "Missing or invalid API key", "UNAUTHORIZED")
        }
        var methodMatched = false
        for (r in routes) {
            val m = r.pattern.find(req.path) ?: continue
            methodMatched = true
            if (r.method != req.method) continue
            req.params = m.groupValues.drop(1).map { java.net.URLDecoder.decode(it, "UTF-8") }
            return when (val out = r.handler(req)) {
                is Response -> out
                else -> json(r.status, out)
            }
        }
        if (methodMatched) throw ApiError(405, "Method Not Allowed", "METHOD_NOT_ALLOWED")
        throw ApiError(404, "Not Found", "NOT_FOUND")
    }

    private fun json(status: Int, body: Any?): Response {
        val bytes = Json.write(body).toByteArray()
        return newFixedLengthResponse(statusOf(status), "application/json", ByteArrayInputStream(bytes), bytes.size.toLong()).also { headers(it) }
    }

    private fun headers(r: Response) {
        r.addHeader("X-Content-Type-Options", "nosniff")
        r.addHeader("Referrer-Policy", "same-origin")
        r.addHeader("Cache-Control", "no-store")
    }

    private fun statusOf(code: Int): Response.IStatus = object : Response.IStatus {
        override fun getDescription() = "$code ${Response.Status.lookup(code)?.description?.substringAfter(' ') ?: "Status"}"
        override fun getRequestStatus() = code
    }

    private fun mimeOf(name: String) = when (name.substringAfterLast('.').lowercase()) {
        "html" -> "text/html; charset=utf-8"
        "js" -> "text/javascript; charset=utf-8"
        "css" -> "text/css; charset=utf-8"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "mp4" -> "video/mp4"
        "json" -> "application/json"
        "zip" -> "application/zip"
        else -> "application/octet-stream"
    }

    /** The dashboard. Opening it with ?t=<token> stores the token for its API calls, then drops it from the URL. */
    private fun indexPage(t: String?): Response {
        val html = e.platform.asset("static/index.html")?.toString(Charsets.UTF_8) ?: throw ApiError(404, "dashboard missing", "NOT_FOUND")
        val inject = if (t != null && MessageDigest.isEqual(t.toByteArray(), token.toByteArray())) {
            "<script>try{localStorage.setItem('slidemill_key',${Json.quote(t)})}catch(e){};history.replaceState(null,'',location.pathname+location.hash)</script>"
        } else ""
        val bytes = html.replace("<head>", "<head>$inject").toByteArray()
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", ByteArrayInputStream(bytes), bytes.size.toLong()).also { headers(it) }
    }

    private fun asset(path: String): Response {
        if (".." in path) throw ApiError(404, "Not Found", "NOT_FOUND")
        val bytes = e.platform.asset(path) ?: return json(404, obj("detail" to "Not Found"))
        return newFixedLengthResponse(Response.Status.OK, mimeOf(path), ByteArrayInputStream(bytes), bytes.size.toLong())
    }

    private fun media(rel: String, session: IHTTPSession): Response {
        val file = File(e.mediaDir, java.net.URLDecoder.decode(rel, "UTF-8")).canonicalFile
        if (!file.path.startsWith(e.mediaDir.canonicalPath + File.separator) || !file.isFile) return json(404, obj("detail" to "Not Found"))
        val mime = mimeOf(file.name)
        val total = file.length()
        val range = session.headers["range"]
        if (range != null && range.startsWith("bytes=")) {
            // Ranged reads let the WebView's video player seek.
            val (a, b) = range.removePrefix("bytes=").split("-", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            val start = a.toLongOrNull() ?: 0
            val end = (b.toLongOrNull() ?: (total - 1)).coerceAtMost(total - 1)
            if (start <= end) {
                val stream = FileInputStream(file).also { it.skip(start) }
                return newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, mime, stream, end - start + 1).also {
                    it.addHeader("Content-Range", "bytes $start-$end/$total")
                    it.addHeader("Accept-Ranges", "bytes")
                }
            }
        }
        return newFixedLengthResponse(Response.Status.OK, mime, FileInputStream(file), total).also { it.addHeader("Accept-Ranges", "bytes") }
    }

    // --- serializers ---------------------------------------------------------------------------

    private fun url(rel: String) = e.pipeline.mediaUrl(rel)

    private fun postOut(p: Map<String, Any?>): Obj = obj(
        "id" to p.str("id"),
        "slideshow_id" to p.str("slideshow_id"),
        "tiktok_account_id" to p["tiktok_account_id"],
        "post_mode" to p.str("post_mode"),
        "status" to p.str("status"),
        "action" to p.objAt("options").str("action", "publish"),
        "scheduled_for" to Db.iso(p["not_before"] as? Long),
        "post_id" to p.strOrNull("post_id"),
        "error" to p.strOrNull("error"),
        "views" to p.long("views"),
        "likes" to p.long("likes"),
        "comments" to p.long("comments"),
        "shares" to p.long("shares"),
        "created_at" to Db.iso(p.long("created_at")),
    )

    private fun slideshowOut(tx: Db.Tx, s: Map<String, Any?>, full: Boolean = false): Obj {
        val out = obj(
            "id" to s.str("id"),
            "title" to s.str("title"),
            "prompt" to s.str("prompt"),
            "status" to s.str("status"),
            "automation_id" to s["automation_id"],
            "created_by_cron_id" to s["created_by_cron_id"],
            "caption" to s.str("caption"),
            "description" to s.str("description"),
            "slides" to s.strings("rendered").map { url(it) }.toMutableList<Any?>(),
            "video_url" to s.strOrNull("video_path")?.let { url(it) },
            "error" to s.strOrNull("error"),
            "created_at" to Db.iso(s.long("created_at")),
            "posts" to tx.where("posts") { it.str("slideshow_id") == s.str("id") }.map { postOut(it) }.toMutableList<Any?>(),
        )
        val prediction = s.objOrNull("prediction")
        out["prediction"] = if (full) prediction else prediction?.let {
            obj(
                "viral_potential" to it["viral_potential"], "hook_score" to it["hook_score"], "hold_rate" to it["hold_rate"],
                "verdict_tag" to it["verdict_tag"], "attention" to it["attention"],
            )
        }
        if (full) out["spec"] = s.objAt("spec")
        return out
    }

    private fun automationOut(a: Map<String, Any?>): Obj = obj(
        "id" to a.str("id"),
        "title" to a.str("title"),
        "tiktok_account_id" to a["tiktok_account_id"],
        "product_id" to a["product_id"],
        "hooks" to a.list("hooks"),
        "style" to a.str("style"),
        "language" to a.str("language", "English"),
        "post_settings" to a.objAt("post_settings"),
        "image_settings" to a.objAt("image_settings"),
        "timezone" to a.str("timezone").ifEmpty { e.platform.defaultTimezone() },
        "target_country" to a.str("target_country"),
        "paused" to a.bool("paused"),
        "favorite" to a.bool("favorite"),
        "schedule" to a.objs("schedules").map { obj("job_id" to it.str("id"), "cron" to it.str("cron")) }.toMutableList<Any?>(),
        "next_runs" to e.scheduler.nextRuns(a).toMutableList<Any?>(),
        "min_viral_score" to a.long("min_viral_score"),
        "created_at" to Db.iso(a.long("created_at")),
    )

    private fun imageOut(i: Map<String, Any?>) = obj(
        "id" to i.str("id"), "path" to i.str("path"), "url" to url(i.str("path")), "source" to i.str("source"), "use_count" to i.long("use_count"),
    )

    private fun libraryPostOut(x: Map<String, Any?>) = obj(
        "id" to x.str("id"),
        "url" to x.str("url"),
        "description" to x.str("description"),
        "is_slideshow" to x.bool("is_slideshow"),
        "images" to x.strings("images").map { url(it) }.toMutableList<Any?>(),
        "cover" to x.strOrNull("cover_path")?.let { url(it) },
        "slide_texts" to x.list("slide_texts"),
        "music" to x.str("music"),
        "views" to x.long("views"), "likes" to x.long("likes"), "comments" to x.long("comments"),
        "shares" to x.long("shares"), "saves" to x.long("saves"),
        "posted_at" to Db.iso(x["posted_at"] as? Long),
    )

    private fun profileOut(p: Map<String, Any?>, posts: List<Map<String, Any?>>? = null): Obj {
        val out = obj(
            "id" to p.str("id"),
            "username" to p.str("username"),
            "nickname" to p.str("nickname"),
            "avatar" to p.strOrNull("avatar_path")?.let { url(it) },
            "bio" to p.str("bio"),
            "link_in_bio" to p.str("bio_link"),
            "followers" to p.long("followers"),
            "following_count" to p.long("following"),
            "total_likes" to p.long("total_likes"),
            "video_count" to p.long("video_count"),
            "region" to p.str("region"),
            "verified" to p.bool("verified"),
            "niche" to p.str("niche"),
            "product_medium" to p.str("product_medium"),
            "url" to "https://www.tiktok.com/@${p.str("username")}",
            "scraped_at" to Db.iso(p["scraped_at"] as? Long),
        )
        if (posts != null) out["slideshows"] = posts.map { libraryPostOut(it) }.toMutableList<Any?>()
        return out
    }

    // --- validation helpers ---------------------------------------------------------------------

    private fun checkTimezone(tz: String) {
        if (tz.isEmpty()) return
        try { ZoneId.of(tz) } catch (x: Exception) { throw invalid("unknown timezone '$tz'") }
    }

    private fun schedules(crons: List<String>): MutableList<Any?> = crons.map {
        val c = try { Cron.validate(it) } catch (x: IllegalArgumentException) { throw invalid("invalid cron: ${x.message}") }
        obj("id" to Db.newId(), "cron" to c)
    }.toMutableList()

    private fun requirePublishConsent(auto: Boolean, privacy: String?, consent: Boolean, branded: Boolean) {
        if (!auto) return
        if (privacy == null) throw invalid("Choose who can see the automatic posts.")
        if (!consent) throw invalid("Agree to TikTok's Music Usage Confirmation to post automatically.")
        if (branded && privacy == "SELF_ONLY") throw invalid("Branded content can't be private.")
    }

    private val phone get() = e.platform.name == "android"

    init {
        // --- status ---
        route("GET", "/status") {
            val cfg = e.llm.resolve()
            obj(
                "ai" to (cfg != null),
                "ai_provider" to cfg?.provider,
                "model" to cfg?.model,
                "image_generation" to e.imageGen.describe(),
                "image_providers" to e.images.providers().toMutableList<Any?>(),
                "apify" to e.library.apifyAvailable(),
                "tiktok" to false,
                "public_base_url" to "",
                "timezone" to e.platform.defaultTimezone(),
                "platform" to e.platform.name,
                "poster" to e.posterStatus(),
            )
        }

        // --- slideshows ---
        route("POST", "/slideshows/generate", 201) { r ->
            val body = Models.GENERATE.of(r.body())
            var style = ""
            var imageSettings = Models.IMAGE_SETTINGS.of(null)
            val automationId = body.strOrNull("automation_id")
            var language = body.strOrNull("language") ?: "English"
            if (automationId != null) {
                val a = e.db.tx { get("automations", automationId)?.copyObj() } ?: throw notFound("automation")
                style = a.str("style")
                imageSettings = Models.IMAGE_SETTINGS.of(a.objAt("image_settings"))
                language = body.strOrNull("language") ?: a.str("language", "English")
            }
            body.strOrNull("image_source")?.let { imageSettings["source"] = it }
            val show = try {
                e.db.tx { e.pipeline.createPending(this, body.str("prompt"), automationId) }
            } catch (x: Pipeline.ConcurrentLimit) {
                throw ApiError(429, x.message ?: "busy", "CONCURRENT_LIMIT")
            }
            val id = show.str("id")
            e.pipeline.executor.submit {
                e.pipeline.generateInto(id, style, language, imageSettings, explicitImages = body.strings("images"), aspectRatio = body.strOrNull("aspect_ratio"))
            }
            obj("slideshow_id" to id, "status" to show.str("status"))
        }

        route("POST", "/slideshows/create", 201) { r ->
            val spec = Models.SPEC.of(r.body())
            if (spec.objs("slides").isEmpty()) throw invalid("at least one slide is required")
            val id = e.db.tx {
                insert("slideshows", obj(
                    "automation_id" to null, "created_by_cron_id" to null, "title" to spec.str("title"), "prompt" to "manual",
                    "status" to "rendering", "spec" to spec, "caption" to "", "description" to "", "rendered" to arr(),
                    "video_path" to "", "fingerprint" to "", "error" to "",
                ))
            }.str("id")
            renderAndMark(id)
            e.db.tx { slideshowOut(this, get("slideshows", id)!!, full = true) }
        }

        route("GET", "/slideshows") { r ->
            val limit = r.qInt("limit", 20).coerceIn(1, 100)
            val offset = maxOf(0, r.qInt("offset", 0))
            e.db.tx {
                val rows = where("slideshows") {
                    (r.q("automation_id") == null || it.str("automation_id") == r.q("automation_id")) &&
                        (r.q("status") == null || it.str("status") == r.q("status"))
                }.sortedByDescending { it.long("created_at") }
                obj(
                    "slideshows" to rows.drop(offset).take(limit).map { slideshowOut(this, it) }.toMutableList<Any?>(),
                    "total" to rows.size, "limit" to limit, "offset" to offset,
                )
            }
        }

        route("GET", "/slideshows/{id}") { r ->
            e.db.tx { slideshowOut(this, get("slideshows", r.params[0]) ?: throw notFound("slideshow"), full = true) }
        }

        route("GET", "/slideshows/{id}/status") { r ->
            e.db.tx {
                val s = get("slideshows", r.params[0]) ?: throw notFound("slideshow")
                obj("status" to s.str("status"), "error" to s.strOrNull("error"), "video_url" to s.strOrNull("video_path")?.let { url(it) })
            }
        }

        route("PUT", "/slideshows/{id}") { r ->
            val id = r.params[0]
            val body = r.body() as? Map<*, *> ?: throw invalid("body: expected an object")
            var rerender = false
            e.db.tx {
                val s = get("slideshows", id) ?: throw notFound("slideshow")
                if (s.str("status") in setOf("generating", "rendering")) throw ApiError(409, "slideshow is still generating", "CONFLICT")
                if (body.containsKey("caption")) s["caption"] = body["caption"].toString().take(89)
                if (body.containsKey("description")) s["description"] = body["description"].toString().take(4000)
                if (body.containsKey("spec")) {
                    val spec = Models.SPEC.of(body["spec"])
                    s["spec"] = spec
                    s["title"] = spec.str("title")
                    rerender = true
                }
            }
            if (rerender) renderAndMark(id)
            e.db.tx { slideshowOut(this, get("slideshows", id)!!, full = true) }
        }

        route("POST", "/slideshows/{id}/video") { r ->
            val id = r.params[0]
            if (e.db.tx { get("slideshows", id) } == null) throw notFound("slideshow")
            val seconds = r.q("seconds_per_slide")?.toDoubleOrNull() ?: 3.0
            val rel = try { e.pipeline.exportVideo(id, seconds) } catch (x: PipelineError) { throw invalid(x.message ?: "video failed") }
            obj("video_url" to url(rel))
        }

        route("POST", "/slideshows/{id}/predict") { r ->
            if (e.db.tx { get("slideshows", r.params[0]) } == null) throw notFound("slideshow")
            try {
                e.virality.predict(r.params[0])
            } catch (x: LlmError) {
                throw invalid(x.message ?: "AI error")
            } catch (x: PipelineError) {
                throw invalid(x.message ?: "can't predict")
            }
        }

        route("GET", "/virality/calibration") { _ -> e.virality.calibration() }

        route("POST", "/virality/backtest", 202) { r ->
            if (!e.llm.configured()) throw invalid("Add an AI key in Settings first.")
            val limit = (r.q("limit")?.toIntOrNull() ?: 20).coerceIn(8, 40)
            val jobId = e.pipeline.newJob("backtest")
            e.pipeline.jobExecutor.submit {
                e.pipeline.job(jobId, status = "running", message = "Scoring library slideshows…")
                try {
                    val out = e.virality.backtest(limit) { done, total ->
                        e.pipeline.job(jobId, message = "Scored $done of $total library slideshows…")
                    }
                    e.pipeline.job(jobId, status = "completed", message = out.str("summary"), result = obj("backtest" to out))
                } catch (x: Exception) {
                    e.pipeline.job(jobId, status = "failed", message = x.message ?: "Backtest failed.")
                }
            }
            obj("job_id" to jobId)
        }

        route("DELETE", "/slideshows/{id}") { r ->
            val s = e.db.tx { delete("slideshows", r.params[0]) } ?: throw notFound("slideshow")
            (s.strings("rendered") + listOfNotNull(s.strOrNull("video_path"))).forEach { e.images.deleteFile(it) }
            e.db.tx { deleteWhere("posts") { it.str("slideshow_id") == s.str("id") } }
            obj("deleted" to true)
        }

        route("POST", "/slideshows/{id}/publish", 202) { r ->
            val id = r.params[0]
            val body = Models.PUBLISH.of(r.body())
            val (show, ps) = e.db.tx {
                val s = get("slideshows", id)?.copyObj() ?: throw notFound("slideshow")
                val a = s.strOrNull("automation_id")?.let { get("automations", it) }
                s to a?.let { Models.POST_SETTINGS.of(it.objAt("post_settings")) }
            }
            val mode = body.strOrNull("post_mode") ?: ps?.str("post_mode") ?: "BROWSER"
            if (mode != "BROWSER" && !phone) throw invalid("Only \"post from this phone\" is available here.")
            val postId = try {
                e.pipeline.publish(
                    show.str("id"), ps, postMode = mode, caption = body.strOrNull("caption"), description = body.strOrNull("description"),
                    browserAction = body.str("browser_action", "publish"), sound = body.bool("sound", true),
                )
            } catch (x: Pipeline.PublishLimit) {
                throw ApiError(429, x.message ?: "limit", "RATE_LIMITED")
            } catch (x: PipelineError) {
                throw invalid(x.message ?: "cannot publish")
            }
            e.onQueued()
            e.db.tx { postOut(get("posts", postId)!!) }
        }

        // --- automations ---
        route("POST", "/automations", 201) { r ->
            val body = Models.AUTOMATION_IN.of(r.body())
            checkTimezone(body.str("timezone"))
            val scheds = schedules(body.strings("schedule"))
            val a = e.db.tx {
                insert("automations", obj(
                    "title" to body.str("title"), "tiktok_account_id" to body["tiktok_account_id"], "product_id" to body["product_id"],
                    "hooks" to body.list("hooks"), "style" to body.str("style"), "language" to body.str("language"),
                    "post_settings" to body.objAt("post_settings"), "image_settings" to body.objAt("image_settings"),
                    "timezone" to body.str("timezone"), "target_country" to body.str("target_country").uppercase(),
                    "paused" to false, "favorite" to false, "schedules" to scheds, "min_viral_score" to body.long("min_viral_score"),
                )).copyObj()
            }
            automationOut(a)
        }

        route("GET", "/automations") { _ ->
            val rows = e.db.tx { all("automations").map { it.copyObj() } }
                .sortedWith(compareByDescending<Obj> { it.bool("favorite") }.thenByDescending { it.long("created_at") })
            obj("automations" to rows.map { automationOut(it) }.toMutableList<Any?>())
        }

        route("GET", "/automations/{id}") { r ->
            automationOut(e.db.tx { get("automations", r.params[0])?.copyObj() } ?: throw notFound("automation"))
        }

        route("PATCH", "/automations/{id}") { r ->
            val data = Models.AUTOMATION_PATCH.partial(r.body())
            checkTimezone(data.strOrNull("timezone") ?: "")
            val schedule = data.remove("schedule")
            @Suppress("UNCHECKED_CAST")
            val scheds = (schedule as? List<String>)?.let { schedules(it) }
            val a = e.db.tx {
                val a = get("automations", r.params[0]) ?: throw notFound("automation")
                for ((k, v) in data) {
                    if (v == null && k !in setOf("tiktok_account_id", "product_id")) continue
                    a[k] = if (k == "target_country") (v as String).uppercase() else v
                }
                scheds?.let { a["schedules"] = it }
                a.copyObj()
            }
            automationOut(a)
        }

        route("DELETE", "/automations/{id}") { r ->
            e.db.tx { delete("automations", r.params[0]) } ?: throw notFound("automation")
            obj("deleted" to true)
        }

        route("POST", "/automations/{id}/run", 202) { r ->
            val id = r.params[0]
            val body = Models.RUN.of(r.body())
            if (e.db.tx { get("automations", id) } == null) throw notFound("automation")
            e.pipeline.executor.submit {
                try {
                    e.pipeline.runAutomation(id, body.strOrNull("hook"), body.str("mode", "export"))
                    e.onQueued()
                } catch (x: Exception) {
                    e.platform.log("run failed: $x")
                }
            }
            obj("queued" to true)
        }

        route("POST", "/automations/{id}/apply-timing") { r ->
            val id = r.params[0]
            val body = Models.APPLY_TIMING.of(r.body())
            val viaBrowser = body.bool("via_browser") || phone
            if (body.str("country").uppercase() !in Timing.COUNTRIES) throw ApiError(404, "unsupported country '${body.str("country")}'", "NOT_FOUND")
            if (!viaBrowser) requirePublishConsent(body.bool("auto_publish"), body.strOrNull("privacy_level"), body.bool("consent"), body.bool("branded_content"))
            val accountId = e.db.tx { get("automations", id)?.strOrNull("tiktok_account_id") ?: "" }
            val plan = e.timing.bestTimes(body.str("country"), body.int("posts_per_day", 3), accountId.ifEmpty { null })
            val scheds = schedules(plan.strings("crons"))
            val a = e.db.tx {
                val a = get("automations", id) ?: throw notFound("automation")
                a["timezone"] = plan.str("timezone")
                a["target_country"] = plan.str("country")
                val post = Models.POST_SETTINGS.of(a.objAt("post_settings"))
                post["auto_post"] = true
                if (viaBrowser) {
                    post["post_mode"] = "BROWSER"
                    post["browser_action"] = body.str("browser_action", "publish")
                } else {
                    post["post_mode"] = if (body.bool("auto_publish")) "DIRECT_POST" else "MEDIA_UPLOAD"
                    if (body.bool("auto_publish")) {
                        post["visibility"] = body.str("privacy_level")
                        post["consented"] = true
                        for (k in listOf("allow_comments", "brand_organic", "branded_content")) post[k] = body.bool(k)
                    }
                }
                a["post_settings"] = post
                a["paused"] = false
                a["schedules"] = scheds
                a.copyObj()
            }
            val warnings = mutableListOf<Any?>()
            if (viaBrowser) {
                val (perDay, _) = e.queue.limits()
                if (body.str("browser_action") == "draft") {
                    warnings += "At each time you'll get a notification to open the slideshow in the TikTok app, pick a sound and post."
                } else {
                    if (body.int("posts_per_day") > perDay) warnings += "Posting is capped at $perDay posts a day (Settings); extra ones wait in the queue."
                    warnings += if (phone) "Log in on the TikTok tab once and keep auto-posting on, or posts wait in the queue."
                    else "Keep a logged-in tiktok.com tab open with the Slidemill extension, or posts wait in the queue."
                }
            }
            obj("automation" to automationOut(a), "plan" to plan, "warnings" to warnings)
        }

        // --- collections & images ---
        route("GET", "/collections") { _ ->
            e.db.tx {
                val counts = all("images").groupingBy { it.str("collection_id") }.eachCount()
                obj("collections" to all("collections").sortedByDescending { it.long("created_at") }
                    .map { obj("id" to it.str("id"), "name" to it.str("name"), "image_count" to (counts[it.str("id")] ?: 0)) }.toMutableList<Any?>())
            }
        }

        route("POST", "/collections", 201) { r ->
            val body = Models.COLLECTION.of(r.body())
            val c = e.db.tx { insert("collections", obj("name" to body.str("name"))) }
            obj("id" to c.str("id"), "name" to c.str("name"), "image_count" to 0)
        }

        route("DELETE", "/collections/{id}") { r ->
            val id = r.params[0]
            e.db.tx { delete("collections", id) } ?: throw notFound("collection")
            val imgs = e.db.tx { where("images") { it.str("collection_id") == id }.map { it.copyObj() } }
            imgs.forEach { e.images.deleteFile(it.str("path")) }
            e.db.tx { deleteWhere("images") { it.str("collection_id") == id } }
            obj("deleted" to true)
        }

        route("GET", "/collections/{id}/images") { r ->
            val limit = r.qInt("limit", 100).coerceIn(1, 500)
            val offset = maxOf(0, r.qInt("offset", 0))
            e.db.tx {
                obj("images" to where("images") { it.str("collection_id") == r.params[0] }.sortedByDescending { it.long("created_at") }
                    .drop(offset).take(limit).map { imageOut(it) }.toMutableList<Any?>())
            }
        }

        route("POST", "/collections/{id}/upload", 201) { r ->
            val id = r.params[0]
            if (e.db.tx { get("collections", id) } == null) throw notFound("collection")
            val files = HashMap<String, String>()
            r.session.parseBody(files)
            val names = r.session.parameters["files"].orEmpty()
            val out = mutableListOf<Any?>()
            files.keys.filter { it.startsWith("files") }.sortedBy { it.removePrefix("files").toIntOrNull() ?: 0 }.forEachIndexed { i, key ->
                val data = File(files.getValue(key)).readBytes()
                val (rel, w, h) = try {
                    e.images.storeBytes(data, "library/$id")
                } catch (x: Exception) {
                    throw invalid("${names.getOrElse(i) { "file" }}: not an image (${x.message})")
                }
                out += imageOut(e.db.tx {
                    insert("images", obj("collection_id" to id, "path" to rel, "source" to "upload", "source_url" to "", "query" to "", "width" to w, "height" to h, "use_count" to 0L))
                })
            }
            if (out.isEmpty()) throw invalid("no files uploaded")
            obj("images" to out)
        }

        route("POST", "/collections/{id}/import", 201) { r ->
            val id = r.params[0]
            val body = Models.IMPORT_IMAGES.of(r.body())
            if (e.db.tx { get("collections", id) } == null) throw notFound("collection")
            val found = try { e.images.search(body.str("query"), body.int("count")) } catch (x: ImageSearchError) { throw invalid(x.message ?: "search failed") }
            obj("images" to found.take(body.int("count")).map { imageOut(e.images.downloadInto(it, id, body.str("query"))) }.toMutableList<Any?>())
        }

        route("DELETE", "/images/{id}") { r ->
            val img = e.db.tx { delete("images", r.params[0]) } ?: throw notFound("image")
            e.images.deleteFile(img.str("path"))
            obj("deleted" to true)
        }

        route("GET", "/images/search") { r ->
            val found = try { e.images.search(r.q("q") ?: "", 24, page = r.qInt("page", 1)) } catch (x: ImageSearchError) { throw invalid(x.message ?: "search failed") }
            obj("images" to found.map { it.toObj() }.toMutableList<Any?>())
        }

        // --- products ---
        route("GET", "/products") { _ ->
            e.db.tx {
                obj("products" to all("products").sortedByDescending { it.long("created_at") }
                    .map { obj("id" to it.str("id"), "name" to it.str("name"), "description" to it.str("description"), "url" to it.str("url")) }.toMutableList<Any?>())
            }
        }

        route("POST", "/products", 201) { r ->
            val body = Models.PRODUCT.of(r.body())
            val p = e.db.tx { insert("products", body.copyObj()) }
            obj("id" to p.str("id")).also { it.putAll(body) }
        }

        route("DELETE", "/products/{id}") { r ->
            e.db.tx { delete("products", r.params[0]) } ?: throw notFound("product")
            obj("deleted" to true)
        }

        // --- TikTok API accounts: not on the phone (the app posts through your logged-in TikTok tab) ---
        route("GET", "/tiktok/accounts") { _ -> obj("accounts" to arr()) }
        route("GET", "/tiktok/connect") { _ -> throw invalid("The phone app posts through the TikTok tab; no developer app needed.") }

        // --- posts & analytics ---
        route("GET", "/posts") { r ->
            val days = r.q("days")?.toIntOrNull() ?: 30
            val limit = r.qInt("limit", 50).coerceIn(1, 200)
            val since = if (days > 0) Db.clock().minus(Duration.ofDays(days.toLong())).toEpochMilli() else Long.MIN_VALUE
            val sortKey = r.q("sort").takeIf { it in setOf("views", "likes", "shares", "comments") } ?: "created_at"
            e.db.tx {
                val rows = where("posts") { it.long("created_at") >= since && (r.q("tiktok_account_id") == null || it.str("tiktok_account_id") == r.q("tiktok_account_id")) }
                    .sortedByDescending { it.long(sortKey) }
                val stats = obj(
                    "total_posts" to rows.size,
                    "views" to rows.sumOf { it.long("views") },
                    "likes" to rows.sumOf { it.long("likes") },
                    "comments" to rows.sumOf { it.long("comments") },
                    "shares" to rows.sumOf { it.long("shares") },
                )
                val out = rows.take(limit).map { p ->
                    val s = get("slideshows", p.str("slideshow_id"))
                    postOut(p).also {
                        it["title"] = s?.str("title") ?: ""
                        it["thumb"] = s?.strings("rendered")?.firstOrNull()?.let { rel -> url(rel) }
                    }
                }
                obj("posts" to out.toMutableList<Any?>(), "statistics" to stats)
            }
        }

        route("POST", "/posts/refresh", 202) { _ ->
            e.pipeline.executor.submit { e.refreshMetrics() }
            obj("queued" to true)
        }

        route("POST", "/posts/{id}/share") { r ->
            // "Send to TikTok app": open the share sheet now (the dashboard's button).
            val post = e.db.tx { get("posts", r.params[0])?.copyObj() } ?: throw notFound("post")
            e.sharePost(post.str("id"))
            obj("status" to "sent_to_drafts")
        }

        route("DELETE", "/posts/{id}") { r ->
            val p = e.db.tx { get("posts", r.params[0])?.copyObj() } ?: throw notFound("post")
            if (p.str("status") == "posting") throw ApiError(409, "this post is being posted right now", "CONFLICT")
            e.db.tx { delete("posts", p.str("id")) }
            obj("deleted" to true)
        }

        // --- Claude connector (through the user's relay) ---
        route("GET", "/relay") { _ -> e.relay.status() }

        route("PUT", "/relay") { r ->
            val body = r.body() as? Map<*, *> ?: throw invalid("body: expected an object")
            e.relay.configure(body["url"]?.toString().orEmpty())
        }

        route("POST", "/relay/new-link") { _ -> e.relay.newLink() }

        // --- posting times ---
        route("GET", "/timing/countries") { _ -> obj("countries" to Timing.countryList().toMutableList<Any?>()) }

        route("GET", "/timing/best") { r ->
            val ppd = r.qInt("posts_per_day", 3)
            if (ppd !in 1..6) throw invalid("posts_per_day: Input should be between 1 and 6")
            try {
                e.timing.bestTimes(r.q("country") ?: "", ppd, r.q("tiktok_account_id"))
            } catch (x: NoSuchElementException) {
                throw ApiError(404, "unsupported country '${r.q("country")}'", "NOT_FOUND")
            }
        }

        // --- poster queue (the extension / an external poster can still use these) ---
        route("GET", "/extension/next") { r ->
            e.queue.claimNext(client = mapOf("version" to (r.q("version") ?: ""), "username" to (r.q("username") ?: ""), "draft" to (r.q("draft") ?: "0")))
        }

        route("POST", "/extension/jobs/{id}/result") { r ->
            val body = Models.EXTENSION_RESULT.of(r.body())
            val post = e.queue.recordResult(r.params[0], body.bool("ok"), body.str("item_id"), body.str("username"), body.str("error"), body.bool("unsupported"))
                ?: throw ApiError(404, "no post in progress with that id", "NOT_FOUND")
            obj("status" to post.str("status"))
        }

        route("GET", "/extension/status") { _ -> e.queue.status(shareMode = phone) }

        // --- settings ---
        route("GET", "/settings") { _ -> settingsOut() }

        route("PUT", "/settings") { r ->
            val body = r.body() as? Map<*, *> ?: throw invalid("body: expected an object")
            val values = (body["values"] as? Map<*, *> ?: throw invalid("values: field required"))
                .entries.associate { it.key.toString() to (it.value?.toString() ?: "") }.toMutableMap()
            if (values["llm_api_key"].orEmpty().isNotEmpty() && values["llm_provider"].isNullOrEmpty()) {
                Llm.detectProvider(values.getValue("llm_api_key"))?.let { values["llm_provider"] = it }
            }
            try { e.store.setMany(values) } catch (x: IllegalArgumentException) { throw invalid(x.message ?: "unknown setting") }
            settingsOut()
        }

        route("POST", "/settings/test") { _ ->
            val ai = try { obj("ok" to true, "detail" to e.llm.test()) } catch (x: LlmError) { obj("ok" to false, "detail" to x.message) }
            val desc = e.imageGen.describe()
            obj("ai" to ai, "images" to obj("ok" to !desc.startsWith("unavailable"), "detail" to desc))
        }

        // --- backup ---
        route("GET", "/backup") { _ ->
            val bytes = e.backup()
            newFixedLengthResponse(Response.Status.OK, "application/zip", ByteArrayInputStream(bytes), bytes.size.toLong()).also {
                it.addHeader("Content-Disposition", "attachment; filename=\"slidemill-backup.zip\"")
            }
        }

        route("POST", "/backup/restore") { r ->
            val files = HashMap<String, String>()
            r.session.parseBody(files)
            val key = files.keys.firstOrNull { it.startsWith("file") } ?: throw invalid("choose a backup .zip")
            e.restore(File(files.getValue(key)).readBytes())
            obj("restored" to true)
        }

        // --- jobs ---
        route("GET", "/jobs/{id}") { r ->
            e.db.tx {
                val job = get("jobs", r.params[0]) ?: throw notFound("job")
                val out = obj("id" to job.str("id"), "kind" to job.str("kind"), "status" to job.str("status"), "message" to job.str("message"), "result" to job.objAt("result"))
                val ids = job.objAt("result").strings("slideshow_ids")
                if (ids.isNotEmpty()) out["slideshows"] = ids.mapNotNull { get("slideshows", it) }.map { slideshowOut(this, it) }.toMutableList<Any?>()
                out
            }
        }

        // --- studio ---
        route("POST", "/studio", 202) { r ->
            val body = Models.STUDIO.of(r.body())
            if (!e.llm.configured()) throw invalid("Add an AI key in Settings first.")
            val schedule = body.strings("schedule").map { try { Cron.validate(it) } catch (x: IllegalArgumentException) { throw invalid("invalid cron: ${x.message}") } }
            if (!body.bool("via_browser") && !phone) requirePublishConsent(body.bool("auto_publish"), body.strOrNull("privacy_level"), body.bool("consent"), false)
            body.strOrNull("target_country")?.let { if (it.uppercase() !in Timing.COUNTRIES) throw ApiError(404, "unsupported country '$it'", "NOT_FOUND") }
            val jobId = e.pipeline.newJob("studio")
            e.pipeline.jobExecutor.submit { e.pipeline.runStudio(jobId, body, schedule) }
            obj("job_id" to jobId)
        }

        // --- library ---
        route("GET", "/library/niches") { _ ->
            e.db.tx {
                obj("niches" to all("library_profiles").map { it.str("niche") }.filter { it.isNotEmpty() }.groupingBy { it }.eachCount()
                    .entries.sortedByDescending { it.value }.map { obj("niche" to it.key, "profile_count" to it.value) }.toMutableList<Any?>())
            }
        }

        route("GET", "/library") { r ->
            val q = r.q("q")?.lowercase()
            val limit = r.qInt("limit", 24).coerceIn(1, 100)
            val offset = maxOf(0, r.qInt("offset", 0))
            e.db.tx {
                val posts = all("library_posts").groupBy { it.str("profile_id") }
                val rows = all("library_profiles").filter { p ->
                    (q == null || listOf("username", "nickname", "bio").any { p.str(it).lowercase().contains(q) } ||
                        posts[p.str("id")].orEmpty().any { it.str("description").lowercase().contains(q) }) &&
                        (r.q("niche") == null || p.str("niche") == r.q("niche")) &&
                        (r.q("product_medium") == null || p.str("product_medium") == r.q("product_medium")) &&
                        (r.q("region") == null || p.str("region") == r.q("region")!!.uppercase())
                }.sortedByDescending {
                    when (r.q("sort")) {
                        "recent" -> it.long("scraped_at")
                        "likes" -> it.long("total_likes")
                        else -> it.long("followers")
                    }
                }
                obj(
                    "profiles" to rows.drop(offset).take(limit).map { p ->
                        val shows = posts[p.str("id")].orEmpty().filter { it.bool("is_slideshow") }
                        profileOut(p).also {
                            it["slideshow_count"] = shows.size
                            it["top_slideshows"] = shows.sortedByDescending { x -> x.long("views") }.take(4).map { x -> libraryPostOut(x) }.toMutableList<Any?>()
                        }
                    }.toMutableList<Any?>(),
                    "total" to rows.size,
                )
            }
        }

        route("GET", "/library/profiles/{id}") { r ->
            e.db.tx {
                val p = get("library_profiles", r.params[0]) ?: throw notFound("profile")
                val posts = where("library_posts") { it.str("profile_id") == p.str("id") }
                    .sortedWith(compareBy<Obj> { !it.bool("is_slideshow") }.thenByDescending { it.long("views") })
                profileOut(p, posts)
            }
        }

        route("DELETE", "/library/profiles/{id}") { r ->
            val id = r.params[0]
            e.db.tx { delete("library_profiles", id) } ?: throw notFound("profile")
            val posts = e.db.tx { where("library_posts") { it.str("profile_id") == id }.map { it.copyObj() } }
            posts.forEach { x -> (x.strings("images") + listOfNotNull(x.strOrNull("cover_path"))).forEach { e.images.deleteFile(it) } }
            e.db.tx { deleteWhere("library_posts") { it.str("profile_id") == id } }
            obj("deleted" to true)
        }

        route("POST", "/library/import", 202) { r ->
            val body = Models.LIBRARY_IMPORT.of(r.body())
            val items = body.strings("items").filter { it.isNotBlank() }
            if (items.isEmpty()) throw invalid("Paste at least one TikTok username or URL.")
            val jobId = e.pipeline.newJob("library_import")
            e.pipeline.jobExecutor.submit { e.library.importJob(jobId, items, body.int("max_posts", 30)) }
            obj("job_id" to jobId)
        }

        route("POST", "/library/discover", 202) { r ->
            val body = Models.LIBRARY_DISCOVER.of(r.body())
            if (!e.library.apifyAvailable()) throw invalid("Search/hashtag discovery needs an Apify token in Settings.")
            val jobId = e.pipeline.newJob("library_discover")
            e.pipeline.jobExecutor.submit { e.library.discoverJob(jobId, body.str("query"), body.int("max_posts", 50)) }
            obj("job_id" to jobId)
        }

        route("POST", "/library/posts/{id}/analyze") { r ->
            try { obj("slide_texts" to e.library.analyzePost(r.params[0]).toMutableList<Any?>()) } catch (x: LibraryError) { throw invalid(x.message ?: "failed") }
        }

        route("POST", "/library/posts/{id}/remix", 202) { r ->
            val id = r.params[0]
            val body = Models.REMIX.of(r.body())
            val post = e.db.tx { get("library_posts", id)?.copyObj() } ?: throw notFound("post")
            if (post.strings("slide_texts").isEmpty() && post.strings("images").isNotEmpty()) {
                try { e.library.analyzePost(id) } catch (x: LibraryError) { /* caption-only reference still works */ }
            }
            val (reference, defaultTopic) = e.library.referenceText(id)
            val topic = body.strOrNull("topic") ?: defaultTopic
            val settings = Models.IMAGE_SETTINGS.of(obj("source" to body.str("image_source", "auto")))
            val ids = e.db.tx { (0 until body.int("count", 1)).map { e.pipeline.createPending(this, topic, queued = true).str("id") } }
            for (sid in ids) {
                e.pipeline.executor.submit { e.pipeline.generateInto(sid, imageSettings = settings, aspectRatio = body.strOrNull("aspect_ratio"), reference = reference) }
            }
            obj("slideshow_ids" to ids.toMutableList<Any?>())
        }
    }

    private fun settingsOut(): Obj = obj(
        "values" to e.store.publicView(),
        "llm_providers" to LinkedHashMap<String, Any?>().also { m ->
            Llm.PRESETS.forEach { (k, v) -> m[k] = obj("label" to v.label, "default_model" to v.defaultModel, "vision" to v.vision) }
        },
        "image_providers" to LinkedHashMap<String, Any?>(ImageGen.IMAGE_PROVIDERS),
    )

    private fun renderAndMark(id: String) {
        try {
            e.pipeline.render(id)
            e.db.tx { get("slideshows", id)?.let { it["status"] = "completed"; it["error"] = "" } }
        } catch (x: Exception) {
            e.db.tx { get("slideshows", id)?.let { it["status"] = "failed"; it["error"] = x.message ?: x.toString() } }
        }
    }

    companion object {
        /** Zip a directory (backups). */
        fun zipDir(dir: File, skip: (File) -> Boolean = { false }): ByteArray {
            val buf = ByteArrayOutputStream()
            ZipOutputStream(buf).use { zip ->
                dir.walkTopDown().filter { it.isFile && !skip(it) }.forEach { f ->
                    zip.putNextEntry(ZipEntry(f.relativeTo(dir).path.replace(File.separatorChar, '/')))
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            return buf.toByteArray()
        }

        /** Unzip into `dir`, refusing entries that would land outside it. */
        fun unzipInto(bytes: ByteArray, dir: File) {
            val root = dir.canonicalPath + File.separator
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val target = File(dir, entry.name).canonicalFile
                    if (!target.path.startsWith(root)) throw IllegalArgumentException("bad entry ${entry.name}")
                    if (entry.isDirectory) target.mkdirs() else {
                        target.parentFile.mkdirs()
                        target.outputStream().use { zip.copyTo(it) }
                    }
                }
            }
        }
    }
}
