package com.slidemill.core

import java.io.File
import java.time.Instant
import java.util.Base64
import kotlin.random.Random

class LibraryError(message: String) : RuntimeException(message)

/**
 * Slideshow library (port of app/library.py): public TikTok accounts and slideshow posts in a
 * searchable database. Post URLs and profile stats come from the public pages over plain HTTP; a
 * profile's post list comes from the platform (the phone's TikTok page) or Apify.
 */
class Library(
    private val db: Db,
    private val store: Store,
    private val llm: Llm,
    private val images: Images,
    private val platform: Platform,
    private val mediaDir: File,
    private val pipeline: Pipeline,
) {
    companion object {
        val REHYDRATION_RE = Regex("<script id=\"__UNIVERSAL_DATA_FOR_REHYDRATION__\"[^>]*>(.*?)</script>", RegexOption.DOT_MATCHES_ALL)
        val POST_URL_RE = Regex("tiktok\\.com/@([\\w.\\-]+)/(?:video|photo)/(\\d+)")
        val PROFILE_URL_RE = Regex("tiktok\\.com/@([\\w.\\-]+)/?(?:\\?|$)")
        val SHORT_URL_RE = Regex("^https?://(?:vm|vt)\\.tiktok\\.com/\\w+")
        const val APIFY_ACTOR = "clockworks~tiktok-scraper"
        const val CLASSIFY_PROMPT = "You categorize TikTok accounts for a creator-inspiration database."
        const val READ_PROMPT = "These images are the slides of one TikTok photo carousel, in order. Transcribe the overlay text on each slide " +
            "exactly as written (keep line breaks as spaces). Use an empty string for slides without overlay text."

        private fun firstUrls(urls: Any?): MutableList<Any?> =
            (urls as? List<*>).orEmpty().filter { it is String && it.startsWith("http") }.toMutableList()

        /** TikTok web itemStruct -> library post. */
        fun normalizeWebItem(item: Map<String, Any?>): Obj {
            val stats = item.objOrNull("statsV2") ?: item.objAt("stats")
            val slides = item.objAt("imagePost").objs("images").map { firstUrls(it.objAt("imageURL")["urlList"]) }
            val author = item.objAt("author")
            val authorStats = item.objAt("authorStats")
            val music = item.objAt("music")
            val video = item.objAt("video")
            val authorOut = obj(
                "nickname" to author.str("nickname"), "avatar" to author.str("avatarLarger"),
                "bio" to author.str("signature"), "verified" to author.bool("verified"),
            )
            mapOf("followers" to "followerCount", "total_likes" to "heartCount", "video_count" to "videoCount").forEach { (k, src) ->
                if (authorStats[src] != null) authorOut[k] = authorStats.long(src)
            }
            val cover = firstUrls(listOf(video["cover"], video["originCover"]))
            return obj(
                "id" to item.str("id"),
                "username" to author.str("uniqueId"),
                "author" to authorOut,
                "description" to item.str("desc"),
                "is_slideshow" to slides.isNotEmpty(),
                "slide_urls" to slides.toMutableList<Any?>(),
                "cover_urls" to cover.ifEmpty { slides.firstOrNull()?.toMutableList() ?: mutableListOf() },
                "music" to listOf(music.str("title"), music.str("authorName")).filter { it.isNotEmpty() }.joinToString(" - "),
                "views" to stats.long("playCount"),
                "likes" to stats.long("diggCount"),
                "comments" to stats.long("commentCount"),
                "shares" to stats.long("shareCount"),
                "saves" to stats.long("collectCount"),
                "posted_at" to item.long("createTime"),
            )
        }

        fun normalizeApifyItem(it: Map<String, Any?>): Obj {
            val author = it.objAt("authorMeta")
            val slides = it.objs("slideshowImageLinks").map { s -> firstUrls(listOf(s["downloadLink"], s["tiktokLink"])) }
            val music = it.objAt("musicMeta")
            var posted = it.long("createTime")
            if (posted == 0L && it.str("createTimeISO").isNotEmpty()) {
                posted = runCatching { Instant.parse(it.str("createTimeISO")).epochSecond }.getOrDefault(0)
            }
            return obj(
                "id" to it.str("id"),
                "username" to author.str("name"),
                "author" to obj(
                    "nickname" to author.str("nickName"), "avatar" to author.str("avatar"), "bio" to author.str("signature"),
                    "bio_link" to author.str("bioLink"), "verified" to author.bool("verified"), "region" to author.str("region"),
                    "followers" to author.long("fans"), "following" to author.long("following"),
                    "total_likes" to author.long("heart"), "video_count" to author.long("video"),
                ),
                "description" to it.str("text"),
                "is_slideshow" to (it.bool("isSlideshow") || slides.isNotEmpty()),
                "slide_urls" to slides.toMutableList<Any?>(),
                "cover_urls" to firstUrls(listOf(it.objAt("videoMeta")["coverUrl"])),
                "music" to listOf(music.str("musicName"), music.str("musicAuthor")).filter { s -> s.isNotEmpty() }.joinToString(" - "),
                "views" to it.long("playCount"), "likes" to it.long("diggCount"), "comments" to it.long("commentCount"),
                "shares" to it.long("shareCount"), "saves" to it.long("collectCount"),
                "posted_at" to posted,
            )
        }

        /** "post" to url | "profile" to username. */
        fun parseItem(raw0: String, resolveShort: (String) -> String = { it }): Pair<String, String> {
            var raw = raw0.trim()
            if (SHORT_URL_RE.containsMatchIn(raw)) raw = resolveShort(raw)
            POST_URL_RE.find(raw)?.let { return "post" to "https://www.tiktok.com/@${it.groupValues[1]}/video/${it.groupValues[2]}" }
            PROFILE_URL_RE.find(raw)?.let { return "profile" to it.groupValues[1] }
            val name = raw.trimStart('@')
            if (Regex("[\\w.\\-]{2,64}").matches(name)) return "profile" to name
            throw LibraryError("Not a TikTok username or URL: '$raw'")
        }

        fun parseScope(html: String): Obj {
            val m = REHYDRATION_RE.find(html) ?: throw LibraryError("TikTok page had no embedded data (blocked or changed layout).")
            return Json.parseObj(m.groupValues[1]).objAt("__DEFAULT_SCOPE__")
        }
    }

    // --- direct HTTP ---------------------------------------------------------------------------

    /** Network seams (tests replace them). */
    @Volatile var fetchHtml: (String) -> String = { url ->
        val resp = Net.get(url, mapOf("User-Agent" to Net.BROWSER_UA, "Accept-Language" to "en-US,en;q=0.9"), timeoutSec = 30)
        if (resp.status != 200) throw LibraryError("TikTok returned ${resp.status} for $url")
        resp.text
    }
    @Volatile var downloadImage: (String) -> ByteArray? = { url ->
        val resp = Net.get(url, mapOf("User-Agent" to Net.BROWSER_UA, "Referer" to "https://www.tiktok.com/"), timeoutSec = 30)
        if (resp.status == 200) resp.body else null
    }
    @Volatile var pause: () -> Unit = { Thread.sleep(Random.nextLong(1000, 2500)) } // be gentle with TikTok

    private fun pageScope(url: String): Obj = parseScope(fetchHtml(url))

    fun fetchProfile(username: String): Obj {
        val detail = pageScope("https://www.tiktok.com/@$username").objAt("webapp.user-detail")
        val info = detail.objAt("userInfo")
        val user = info.objOrNull("user") ?: throw LibraryError("@$username not found or private (status ${detail["statusCode"]}).")
        val stats = info.objAt("stats")
        return obj(
            "tiktok_user_id" to user.str("id"),
            "username" to user.str("uniqueId").ifEmpty { username },
            "nickname" to user.str("nickname"),
            "avatar" to user.str("avatarLarger").ifEmpty { user.str("avatarMedium") },
            "bio" to user.str("signature"),
            "bio_link" to user.objAt("bioLink").str("link"),
            "verified" to user.bool("verified"),
            "region" to user.str("region"),
            "followers" to stats.long("followerCount"),
            "following" to stats.long("followingCount"),
            "total_likes" to stats.long("heartCount").takeIf { it != 0L }.let { it ?: stats.long("heart") },
            "video_count" to stats.long("videoCount"),
        )
    }

    fun fetchPost(url: String): Obj {
        val detail = pageScope(url).objAt("webapp.video-detail")
        val item = detail.objAt("itemInfo").objOrNull("itemStruct") ?: throw LibraryError("Post unavailable (status ${detail["statusCode"]}).")
        return normalizeWebItem(item)
    }

    private fun resolveShort(url: String): String = runCatching { Net.get(url, mapOf("User-Agent" to Net.BROWSER_UA), 20).url }.getOrDefault(url)

    // --- Apify ------------------------------------------------------------------------------------

    fun apifyAvailable(): Boolean = store.get("apify_token").isNotEmpty()

    fun apifyScrape(profiles: List<String>? = null, hashtags: List<String>? = null, queries: List<String>? = null, maxPosts: Int = 30): List<Obj> {
        val token = store.get("apify_token")
        if (token.isEmpty()) throw LibraryError("Discovery by search/hashtag needs an Apify token (Settings -> APIFY_TOKEN).")
        val body = obj("resultsPerPage" to maxPosts, "shouldDownloadVideos" to false, "shouldDownloadCovers" to false)
        profiles?.let { body["profiles"] = it.toMutableList<Any?>() }
        hashtags?.let { body["hashtags"] = it.toMutableList<Any?>() }
        queries?.let { body["searchQueries"] = it.toMutableList<Any?>() }
        val resp = Net.postJson(Net.withQuery("https://api.apify.com/v2/acts/$APIFY_ACTOR/run-sync-get-dataset-items", mapOf("token" to token)), body, timeoutSec = 330)
        if (resp.status >= 400) throw LibraryError("Apify error ${resp.status}: ${resp.text.take(300)}")
        @Suppress("UNCHECKED_CAST")
        return (resp.json() as? List<Any?>).orEmpty().mapNotNull { it as? Obj }.filter { it.str("id").isNotEmpty() }.map { normalizeApifyItem(it) }
    }

    // --- persistence -------------------------------------------------------------------------------

    /** Store the first candidate URL that decodes as an image; "" if none do. */
    private fun download(candidates: List<String>, folder: String): String {
        for (url in candidates) {
            try {
                val data = downloadImage(url) ?: continue
                return images.storeBytes(data, folder).first
            } catch (e: Exception) {
                continue // try the next mirror
            }
        }
        return ""
    }

    fun upsertProfile(data: Map<String, Any?>): String {
        val username = data.str("username")
        val avatar = data.str("avatar")
        val needAvatar = db.tx {
            val p = where("library_profiles") { it.str("username") == username }.firstOrNull()
                ?: insert("library_profiles", obj(
                    "username" to username, "tiktok_user_id" to "", "nickname" to "", "avatar_path" to "", "bio" to "", "bio_link" to "",
                    "followers" to 0L, "following" to 0L, "total_likes" to 0L, "video_count" to 0L, "region" to "", "verified" to false,
                    "niche" to "", "product_medium" to "", "scraped_at" to null,
                ))
            for (f in listOf("tiktok_user_id", "nickname", "bio", "bio_link", "region", "followers", "following", "total_likes", "video_count")) {
                val v = data[f]
                if (v != null && v != "") p[f] = v
            }
            if (data.containsKey("verified")) p["verified"] = data.bool("verified")
            p["scraped_at"] = Db.nowMs()
            p.str("id") to p.str("avatar_path").isEmpty()
        }
        if (avatar.isNotEmpty() && needAvatar.second) {
            val path = download(listOf(avatar), "tiktok/$username")
            db.tx { get("library_profiles", needAvatar.first)?.set("avatar_path", path) }
        }
        return needAvatar.first
    }

    fun upsertPost(profileId: String, post: Map<String, Any?>) {
        val username = db.tx { get("library_profiles", profileId)?.str("username") } ?: return
        val id = post.str("id")
        val isSlideshow = post.bool("is_slideshow")
        val (needImages, needCover) = db.tx {
            val row = get("library_posts", id) ?: insert("library_posts", obj(
                "id" to id, "profile_id" to profileId, "images" to arr(), "cover_path" to "", "slide_texts" to arr(), "posted_at" to null,
            ))
            row["url"] = "https://www.tiktok.com/@$username/${if (isSlideshow) "photo" else "video"}/$id"
            row["description"] = post.str("description")
            row["is_slideshow"] = isSlideshow
            row["music"] = post.str("music")
            for (f in listOf("views", "likes", "comments", "shares", "saves")) row[f] = post.long(f)
            if (post.long("posted_at") > 0) row["posted_at"] = post.long("posted_at") * 1000
            row["scraped_at"] = Db.nowMs()
            (isSlideshow && row.strings("images").isEmpty()) to row.str("cover_path").isEmpty()
        }
        val folder = "tiktok/$username"
        var imgs: List<String>? = null
        if (needImages) {
            @Suppress("UNCHECKED_CAST")
            imgs = post.list("slide_urls").map { c -> download((c as? List<Any?>).orEmpty().map { it.toString() }, folder) }.filter { it.isNotEmpty() }
        }
        db.tx {
            val row = get("library_posts", id) ?: return@tx
            imgs?.let { row["images"] = it.toMutableList<Any?>() }
            if (needCover && row.str("cover_path").isEmpty()) {
                val current = row.strings("images")
                row["cover_path"] = current.firstOrNull() ?: ""
            }
        }
        if (needCover && db.tx { get("library_posts", id)?.str("cover_path") }.isNullOrEmpty()) {
            val cover = download(post.strings("cover_urls"), folder)
            db.tx { get("library_posts", id)?.set("cover_path", cover) }
        }
    }

    /** Persist scraped posts (and their authors). Returns profile ids touched. */
    fun savePosts(posts: List<Map<String, Any?>>, slideshowsOnly: Boolean = false): List<String> {
        val touched = sortedSetOf<String>()
        for (post in posts) {
            if (slideshowsOnly && !post.bool("is_slideshow")) continue
            if (post.str("username").isEmpty()) continue
            val pid = upsertProfile(obj("username" to post.str("username")).also { it.putAll(post.objAt("author")) })
            upsertPost(pid, post)
            touched += pid
        }
        return touched.toList()
    }

    // --- AI enrichment -------------------------------------------------------------------------------

    fun classifyProfile(profileId: String) {
        if (!llm.configured()) return
        val text = db.tx {
            val p = get("library_profiles", profileId) ?: return@tx null
            val captions = where("library_posts") { it.str("profile_id") == profileId }.sortedByDescending { it.long("views") }
                .take(12).map { it.str("description") }.filter { it.isNotEmpty() }
            "@${p.str("username")} (${p.str("nickname")})\nbio: ${p.str("bio")}\nlink: ${p.str("bio_link")}\ntop captions:\n" +
                captions.joinToString("\n") { "- $it" }
        } ?: return
        val result = try { llm.completeJson(CLASSIFY_PROMPT, text, Shapes.PROFILE_CLASSIFICATION) } catch (e: LlmError) {
            platform.log("classification skipped for $profileId: ${e.message}")
            return
        }
        db.tx {
            get("library_profiles", profileId)?.let {
                it["niche"] = result.str("niche").trim().lowercase().take(64)
                it["product_medium"] = result.str("product_medium")
            }
        }
    }

    /** Read slide text off a library slideshow with a vision model. */
    fun analyzePost(postId: String): List<String> {
        val paths = db.tx { get("library_posts", postId)?.strings("images") } ?: throw LibraryError("post not found")
        if (paths.isEmpty()) throw LibraryError("This post has no slide images to read.")
        val cfg = llm.resolve()
        if (cfg == null || !cfg.vision) throw LibraryError("Reading slide text needs a vision-capable AI key (Anthropic, OpenAI, Gemini, xAI, Mistral or OpenRouter).")
        val dataUrls = paths.take(20).map { "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(File(mediaDir, it).readBytes()) }
        val result = try {
            llm.completeJson(READ_PROMPT, "${dataUrls.size} slides.", Shapes.SLIDE_TEXT_READ, images = dataUrls)
        } catch (e: LlmError) {
            throw LibraryError(e.message ?: "AI error")
        }
        val slides = result.strings("slides")
        db.tx { get("library_posts", postId)?.set("slide_texts", slides.toMutableList<Any?>()) }
        return slides
    }

    /** (reference block for the writer, default topic) for remixing a library post. */
    fun referenceText(postId: String): Pair<String, String> = db.tx {
        val post = get("library_posts", postId) ?: throw LibraryError("post not found")
        val slides = post.strings("slide_texts")
        val lines = mutableListOf(
            "caption: ${post.str("description")}",
            "views: ${"%,d".format(post.long("views"))}  likes: ${"%,d".format(post.long("likes"))}  slides: ${post.strings("images").size}",
        )
        slides.forEachIndexed { i, t -> if (t.isNotEmpty()) lines += "slide ${i + 1}: $t" }
        val topic = slides.firstOrNull()?.takeIf { it.isNotEmpty() }
            ?: post.str("description").replace(Regex("#\\S+"), "").trim().ifEmpty { "the same topic" }
        lines.joinToString("\n") to topic
    }

    // --- jobs ----------------------------------------------------------------------------------------

    fun importJob(jobId: String, rawItems: List<String>, maxPosts: Int) {
        pipeline.job(jobId, status = "running", message = "Starting…")
        var done = 0
        val errors = mutableListOf<String>()
        val profileIds = sortedSetOf<String>()
        try {
            for (raw in rawItems) {
                try {
                    val (kind, value) = parseItem(raw, ::resolveShort)
                    if (kind == "post") {
                        pipeline.job(jobId, message = "Fetching $value")
                        profileIds += savePosts(listOf(fetchPost(value)))
                    } else {
                        pipeline.job(jobId, message = "Fetching @$value")
                        profileIds += importProfile(value, maxPosts)
                    }
                    done++
                } catch (e: Exception) {
                    errors += "$raw: ${e.message}"
                }
                pause()
            }
            profileIds.forEach { classifyProfile(it) }
            pipeline.job(
                jobId,
                status = if (done > 0) "completed" else "failed",
                message = "Imported $done/${rawItems.size}" + if (errors.isNotEmpty()) ". Errors: ${errors.joinToString("; ").take(1500)}" else "",
                result = obj("profile_ids" to profileIds.toMutableList<Any?>()),
            )
        } catch (e: Exception) {
            pipeline.job(jobId, status = "failed", message = (e.message ?: e.toString()).take(2000))
        }
    }

    fun importProfile(username: String, maxPosts: Int): Set<String> {
        if (apifyAvailable()) {
            val ids = savePosts(apifyScrape(profiles = listOf(username), maxPosts = maxPosts)).toSet()
            if (ids.isNotEmpty()) return ids
        }
        val info = fetchProfile(username)
        val pid = upsertProfile(info)
        val posts = platform.listProfilePosts(info.str("username"), maxPosts).map { normalizeWebItem(it) }
        savePosts(posts)
        return setOf(pid)
    }

    fun discoverJob(jobId: String, query: String, maxPosts: Int) {
        pipeline.job(jobId, status = "running", message = "Searching TikTok for '$query'…")
        try {
            val q = query.trim()
            val posts = if (q.startsWith("#")) apifyScrape(hashtags = listOf(q.trimStart('#')), maxPosts = maxPosts)
            else apifyScrape(queries = listOf(q), maxPosts = maxPosts)
            pipeline.job(jobId, message = "Saving ${posts.size} posts…")
            val ids = savePosts(posts, slideshowsOnly = true)
            ids.forEach { classifyProfile(it) }
            pipeline.job(jobId, status = "completed", message = "Found ${ids.size} slideshow accounts", result = obj("profile_ids" to ids.toMutableList<Any?>()))
        } catch (e: Exception) {
            pipeline.job(jobId, status = "failed", message = (e.message ?: e.toString()).take(2000))
        }
    }

    /** Daily refresh of tracked profiles (stats + new posts). */
    fun refreshAll(maxPosts: Int = 20) {
        val names = db.tx { all("library_profiles").map { it.str("username") } }
        for (name in names) {
            try { importProfile(name, maxPosts) } catch (e: Exception) { platform.log("refresh of @$name failed: ${e.message}") }
            Thread.sleep(Random.nextLong(2000, 5000))
        }
    }
}
