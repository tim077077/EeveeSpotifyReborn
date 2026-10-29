package com.slidemill.core

import kotlin.random.Random

class LibraryError(message: String) : RuntimeException(message)

/**
 * Slideshow library (port of app/library.py): public TikTok accounts and slideshow posts in a
 * searchable database. Post URLs and profile stats come from the public pages over plain HTTP; a
 * profile's post list comes from the platform (the phone's TikTok page). Claude reads the slide text
 * off the images ([unread] / [saveSlideTexts]); no AI key is involved.
 */
class Library(
    private val db: Db,
    private val images: Images,
    private val platform: Platform,
) {
    companion object {
        /** A profile read this recently isn't read again (the daily refresh still gets through). */
        const val FRESH_MS = 20 * 3_600_000L
        val REHYDRATION_RE = Regex("<script id=\"__UNIVERSAL_DATA_FOR_REHYDRATION__\"[^>]*>(.*?)</script>", RegexOption.DOT_MATCHES_ALL)
        val POST_URL_RE = Regex("tiktok\\.com/@([\\w.\\-]+)/(?:video|photo)/(\\d+)")
        val PROFILE_URL_RE = Regex("tiktok\\.com/@([\\w.\\-]+)/?(?:\\?|$)")
        val SHORT_URL_RE = Regex("^https?://(?:vm|vt)\\.tiktok\\.com/\\w+")

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
    /** The account slot whose TikTok session does research reads ("" = the main one); set by the engine. */
    @Volatile var via: () -> String = { "" }

    /** The research account's tab, which can ask TikTok for a profile's posts directly (null = no login). */
    @Volatile var researchTab: () -> TikTokTab? = { null }

    /** What [discover] is doing right now ("" when idle). */
    @Volatile var progress = ""

    @Volatile var pause: () -> Unit = { Thread.sleep(Random.nextLong(1000, 2500)) } // be gentle with TikTok

    private fun pageScope(url: String): Obj = parseScope(fetchHtml(url))

    fun fetchProfile(username: String): Obj {
        val detail = pageScope("https://www.tiktok.com/@$username").objAt("webapp.user-detail")
        val info = detail.objAt("userInfo")
        val user = info.objOrNull("user") ?: throw LibraryError("@$username not found or private (status ${detail["statusCode"]}).")
        val stats = info.objAt("stats")
        return obj(
            "tiktok_user_id" to user.str("id"),
            "sec_uid" to user.str("secUid"),
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

    /** A post's slide images, downloaded fresh from its public page into [folder]; relative paths, in order. */
    fun postSlides(url: String, folder: String): List<String> = downloadSlides(fetchPost(url).list("slide_urls"), folder)

    /** Download slides given as one list of mirror URLs per slide; relative paths of those that worked, in order. */
    fun downloadSlides(slideUrls: List<*>, folder: String): List<String> =
        slideUrls.map { urls -> download((urls as? List<*>).orEmpty().map { it.toString() }, folder) }.filter { it.isNotEmpty() }

    fun upsertProfile(data: Map<String, Any?>): String {
        val username = data.str("username")
        val avatar = data.str("avatar")
        val needAvatar = db.tx {
            val p = where("library_profiles") { it.str("username") == username }.firstOrNull()
                ?: insert("library_profiles", obj(
                    "username" to username, "tiktok_user_id" to "", "nickname" to "", "avatar_path" to "", "bio" to "", "bio_link" to "",
                    "followers" to 0L, "following" to 0L, "total_likes" to 0L, "video_count" to 0L, "region" to "", "verified" to false,
                    "niche" to "", "product_medium" to "", "scraped_at" to null, "sec_uid" to "",
                ))
            for (f in listOf("tiktok_user_id", "sec_uid", "nickname", "bio", "bio_link", "region", "followers", "following", "total_likes", "video_count")) {
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

    // --- slide text, read by Claude --------------------------------------------------------------

    /** Library slideshows whose slide text nobody has read yet, most viewed first. */
    fun unread(limit: Int): List<Obj> = db.tx {
        where("library_posts") { it.bool("is_slideshow") && it.strings("images").isNotEmpty() && it.strings("slide_texts").none { t -> t.isNotBlank() } }
            .sortedByDescending { it.long("views") }.take(limit).map { it.copyObj() }
    }

    /** Store what Claude read off a library post's slides (one string per slide, in order). */
    fun saveSlideTexts(postId: String, texts: List<String>) = db.tx {
        val post = get("library_posts", postId) ?: throw LibraryError("Library post $postId not found.")
        post["slide_texts"] = texts.map { it.trim() }.toMutableList<Any?>()
    }

    // --- importing ------------------------------------------------------------------------------

    /** Import TikTok accounts (usernames or URLs) or single post URLs. Returns a line per item. */
    fun add(rawItems: List<String>, maxPosts: Int): List<String> = rawItems.map { raw ->
        try {
            val (kind, value) = parseItem(raw, ::resolveShort)
            if (kind == "post") {
                savePosts(listOf(fetchPost(value)))
                "$raw: imported the post"
            } else {
                importProfile(value, maxPosts)
                val n = db.tx {
                    val pid = where("library_profiles") { it.str("username") == value }.firstOrNull()?.str("id")
                    where("library_posts") { it.str("profile_id") == pid && it.bool("is_slideshow") }.size
                }
                val how = db.tx { where("library_profiles") { it.str("username") == value }.firstOrNull()?.str("read_via") }.orEmpty()
                "@$value: $n slideshow posts in the library" + if (how.isEmpty()) "" else " (read by $how)"
            }
        } catch (e: Exception) {
            "$raw: failed (${e.message})"
        }.also { pause() }
    }

    /**
     * Read [username]'s profile and newest posts into the library. Skipped when its posts were read in
     * the last [FRESH_MS] (unless [force]), and paging stops at posts the library already has, so a
     * re-import costs a request or two instead of a full scroll.
     */
    fun importProfile(username: String, maxPosts: Int, force: Boolean = false): Set<String> {
        val had = db.tx { where("library_profiles") { it.str("username").equals(username, ignoreCase = true) }.firstOrNull()?.copyObj() }
        if (!force && had != null && Db.nowMs() - had.long("posts_read_at") < FRESH_MS) return setOf(had.str("id"))
        val info = fetchProfile(username)
        val pid = upsertProfile(info)
        val known = db.tx { where("library_posts") { it.str("profile_id") == pid }.map { it.str("id") }.toSet() }
        // The research tab's page asks for the post list itself; scrolling the profile is the fallback.
        val direct = info.str("sec_uid").takeIf { it.isNotEmpty() }?.let { sec ->
            researchTab()?.let { tab ->
                runCatching { tab.listUserPosts(sec, maxPosts, known) }
                    .onFailure { platform.log("direct post list of @$username failed, scrolling instead: ${it.message}") }.getOrNull()
            }
        }?.takeIf { it.isNotEmpty() }
        val raw = direct ?: platform.listProfilePosts(info.str("username"), maxPosts, via(), known)
        savePosts(raw.map { normalizeWebItem(it) })
        // How the posts were read, shown in results so a real run says whether the direct list works.
        db.tx { get("library_profiles", pid)?.also { it["posts_read_at"] = Db.nowMs(); it["read_via"] = if (direct != null) "direct" else "scroll" } }
        return setOf(pid)
    }

    /**
     * Explore: search TikTok for each query, keep the slideshows, and import the [accounts] accounts behind
     * the ones that reached furthest beyond their own followers (views per follower), with up to
     * [postsPerAccount] posts each. A whole account is needed: lift compares a post with its own
     * account's usual views. Accounts already in the library are skipped.
     */
    fun discover(queries: List<String>, accounts: Int, postsPerAccount: Int, rankBy: String = "reach"): Obj {
        // "views": the biggest hits, whoever posted them (to borrow a proven structure from any niche);
        // "reach": views per follower, the posts that went furthest beyond their own audience.
        val score: (Obj) -> Double = if (rankBy == "views") { p -> p.long("views").toDouble() } else ::reach
        val searched = arr()
        val found = mutableListOf<Obj>()
        val slot = via()
        val qs = queries.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        for ((i, q) in qs.withIndex()) {
            progress = "searching \"$q\" (${i + 1} of ${qs.size})"
            val posts = try {
                platform.searchPosts(q, 60, slot).map { normalizeWebItem(it) }
            } catch (e: Exception) {
                searched += obj("query" to q, "error" to (e.message ?: e.toString()))
                continue
            }
            val slideshows = posts.filter { it.bool("is_slideshow") && it.str("username").isNotEmpty() }
            slideshows.forEach { it["query"] = q }
            found += slideshows
            searched += obj("query" to q, "results" to posts.size.toLong(), "slideshows" to slideshows.size.toLong())
            pause()
        }
        val known = db.tx {
            where("library_posts") { it.bool("is_slideshow") }.groupBy { it.str("profile_id") }.filter { it.value.size >= 3 }
                .keys.mapNotNull { get("library_profiles", it)?.str("username") }.toSet()
        }
        // The carousels themselves go in the library too, so their slides can be read even if their account isn't imported.
        val unique = found.distinctBy { it.str("id") }
        runCatching { savePosts(unique) }.onFailure { platform.log("saving search hits failed: ${it.message}") }
        val top = unique.sortedByDescending(score).take(10).map { p ->
            obj("post_id" to p.str("id"), "username" to p.str("username"), "views" to p.long("views"), "followers" to p.objAt("author").long("followers"),
                "likes" to p.long("likes"), "saves" to p.long("saves"), "slides" to p.strings("slide_urls").size.toLong(), "caption" to p.str("description").take(120))
        }
        val ranked = found.groupBy { it.str("username") }.map { (user, ps) -> Triple(user, ps, ps.maxOf(score)) }
            .sortedByDescending { it.third }
        val imported = arr()
        val picks = ranked.filter { it.first !in known }.take(accounts.coerceIn(0, 10))
        for ((n, pick) in picks.withIndex()) {
            val (user, ps, r) = pick
            progress = "reading @$user's posts (${n + 1} of ${picks.size})"
            val best = ps.maxBy(score)
            val line = obj(
                "username" to user, "found_by" to ps.map { it.str("query") }.distinct().toMutableList<Any?>(),
                "best_hit_views" to best.long("views"), "followers" to best.objAt("author").long("followers"),
                "views_per_follower" to Methods.round2(reach(best)),
            )
            try {
                importProfile(user, postsPerAccount)
                db.tx {
                    where("library_profiles") { it.str("username") == user }.firstOrNull()?.let { p ->
                        if (p.str("niche").isEmpty()) p["niche"] = ps.first().str("query")
                        line["slideshows_in_library"] = where("library_posts") { it.str("profile_id") == p.str("id") && it.bool("is_slideshow") }.size.toLong()
                        line["read_via"] = p.str("read_via")
                    }
                }
            } catch (e: Exception) {
                line["error"] = e.message ?: e.toString()
            }
            imported += line
            pause()
        }
        val note = if (slot == Accounts.NO_LOGIN && found.isEmpty()) "Nothing found with no login: TikTok hides search results and photo posts from " +
            "logged-out visitors. In the Slidemill app, pick a spare TikTok account for research (Slidemill tab → Research with it), then try again." else ""
        return obj(
            "note" to note,
            "searched" to searched,
            "slideshows_found" to unique.size.toLong(),
            "ranked_by" to rankBy,
            "top_slideshows" to top.toMutableList<Any?>(),
            "accounts_found" to ranked.size.toLong(),
            "already_in_library" to ranked.count { it.first in known }.toLong(),
            "imported" to imported,
        )
    }

    private fun reach(p: Obj): Double = p.long("views").toDouble() / p.objAt("author").long("followers").coerceAtLeast(100)

    /** Daily refresh of tracked profiles (stats + new posts). */
    fun refreshAll(maxPosts: Int = 20) {
        val names = db.tx { all("library_profiles").map { it.str("username") } }
        for (name in names) {
            try { importProfile(name, maxPosts) } catch (e: Exception) { platform.log("refresh of @$name failed: ${e.message}") }
            Thread.sleep(Random.nextLong(2000, 5000))
        }
    }
}
