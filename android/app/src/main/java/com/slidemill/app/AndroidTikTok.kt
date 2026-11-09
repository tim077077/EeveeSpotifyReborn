package com.slidemill.app

import android.content.Context
import com.slidemill.core.DraftError
import com.slidemill.core.Json
import com.slidemill.core.Obj
import com.slidemill.core.TikTokTab
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The engine's view of one TikTok account's tab: asks the logged-in tiktok.com page (tiktok-inject.js) and
 * waits for its answer. Called from Claude's tool calls on background threads, never the main one.
 */
class AndroidTikTok(private val context: Context, override val id: String = TikTokTab.MAIN) : TikTokTab {
    private val web get() = TikTokWeb.of(id)

    companion object {
        private const val READY_MS = 90_000L // a fresh tiktok.com load on mobile data, screen off, after a push wake
        private const val RELOAD_AFTER_MS = 40_000L
        private const val HELLO_MS = 6_000L // the page asks TikTok who is logged in (up to 3 s)
        private const val DRAFT_MS = 4 * 60_000L
        private const val LIST_MS = 60_000L
        private const val USER_LIST_MS = 90_000L
        private const val REPLY_MS = 30_000L
        private const val PROFILE_MS = 15_000L
        private const val VERIFY_MS = 5 * 60_000L // after a timeout: how long to look for the post in TikTok's schedule

    }

    private fun ask(kind: String, payload: JSONObject, timeoutMs: Long): JSONObject? {
        val latch = CountDownLatch(1)
        val out = AtomicReference<JSONObject?>()
        web.ask(kind, payload, timeoutMs) { out.set(it); latch.countDown() }
        latch.await(timeoutMs + 2_000, TimeUnit.MILLISECONDS)
        return out.get()
    }

    /**
     * Wait until the tiktok.com page answers. Reloads it at most once, after RELOAD_AFTER_MS: a page left
     * asleep for hours can come back stuck, but a reload while it's still loading starts the wait over.
     */
    private fun hello(): JSONObject {
        val start = System.currentTimeMillis()
        val until = start + READY_MS
        var reloaded = false
        while (System.currentTimeMillis() < until) {
            if (!reloaded && System.currentTimeMillis() - start > RELOAD_AFTER_MS) {
                reloaded = true
                web.reload(context)
            }
            web.wake(context)
            if (web.pageReady) {
                val hello = ask("hello", JSONObject(), HELLO_MS)
                if (hello != null && hello.optBoolean("ready")) return hello
            }
            Thread.sleep(1_000)
        }
        throw DraftError(
            "tiktok.com isn't answering in the app. Open Slidemill → TikTok once to check the page loads and you're logged in, then try again.",
        )
    }

    override fun username(): String = hello().optString("username")

    override fun saveDraft(title: String, caption: String, slides: List<File>): String =
        run("draft", title, caption, slides, sound = false, at = 0, what = "draft", idKey = "draftId", checkWhere = "TikTok Studio drafts")

    override fun publish(title: String, caption: String, slides: List<File>, sound: Boolean, at: Long): String =
        run("publish", title, caption, slides, sound, at, what = "post", idKey = "itemId", checkWhere = "your TikTok profile and TikTok Studio → Scheduled")

    /** Every published post of the logged-in account, from TikTok Studio's content list (tiktok-inject.js listMine). */
    override fun listMyPosts(max: Int): List<Obj>? {
        if (username().isEmpty()) return null
        val result = ask("listMine", JSONObject().put("max", max), LIST_MS) ?: return null
        if (!result.optBoolean("ok")) throw DraftError("TikTok didn't list your posts: ${result.optString("error", "unknown error")}")
        val posts = Json.parse(result.optJSONArray("posts")?.toString() ?: "[]") as? List<*> ?: return null
        @Suppress("UNCHECKED_CAST")
        return posts.filterIsInstance<Map<*, *>>().map { (it as Map<String, Any?>).toMutableMap() }
    }

    /** Another account's posts, listed by this tab's page itself (tiktok-inject.js listUser); null if it couldn't. */
    override fun listUserPosts(secUid: String, max: Int, known: Set<String>): List<Obj>? {
        hello()
        val payload = JSONObject().put("secUid", secUid).put("max", max).put("known", JSONArray(known.toList()))
        val result = ask("listUser", payload, USER_LIST_MS) ?: return null
        if (!result.optBoolean("ok")) throw DraftError("TikTok didn't list the posts: ${result.optString("error", "unknown error")}")
        val items = Json.parse(result.optJSONArray("items")?.toString() ?: "[]") as? List<*> ?: return null
        @Suppress("UNCHECKED_CAST")
        return items.filterIsInstance<Map<*, *>>().map { (it as Map<String, Any?>).toMutableMap() }
    }

    override fun listScheduled(): List<Obj>? {
        hello()
        val result = ask("listScheduled", JSONObject(), LIST_MS) ?: return null
        if (!result.optBoolean("ok")) throw DraftError("TikTok didn't list your scheduled posts: ${result.optString("error", "unknown error")}")
        lastNote = result.optJSONObject("seen")?.toString().orEmpty()
        val posts = Json.parse(result.optJSONArray("posts")?.toString() ?: "[]") as? List<*> ?: return null
        @Suppress("UNCHECKED_CAST")
        return posts.filterIsInstance<Map<*, *>>().map { (it as Map<String, Any?>).toMutableMap() }
    }

    @Volatile private var lastNote = ""

    override fun scheduleNote(): String = lastNote

    override fun deletePost(itemId: String) {
        hello()
        val result = ask("deletePost", JSONObject().put("itemId", itemId), PROFILE_MS)
            ?: throw DraftError("TikTok didn't answer the delete in time.")
        if (!result.optBoolean("ok")) throw DraftError(result.optString("error", "TikTok refused the delete."))
    }

    /**
     * The scheduled post TikTok really has for this job, if any: same time (TikTok rounds it) and the same
     * caption start. Looked for until [VERIFY_MS], since a slow upload can still be finishing.
     */
    private fun findScheduled(caption: String, at: Long): String? {
        val until = System.currentTimeMillis() + VERIFY_MS
        val start = caption.trim().take(30)
        while (true) {
            val match = runCatching { listScheduled() }.getOrNull()?.firstOrNull { p ->
                val t = (p["schedule_time"] as? Number)?.toLong() ?: 0L
                kotlin.math.abs(t - at) <= 600 && (start.isEmpty() || p["desc"]?.toString().orEmpty().trim().startsWith(start))
            }
            if (match != null) return match["id"]?.toString()
            if (System.currentTimeMillis() > until) return null
            Thread.sleep(20_000)
        }
    }

    /** The comments on one of the account's own posts, read by this tab's logged-in page (tiktok-inject.js comments). */
    override fun comments(itemId: String, max: Int): List<Obj>? {
        if (username().isEmpty()) return null
        val result = ask("comments", JSONObject().put("itemId", itemId).put("max", max), LIST_MS) ?: return null
        if (!result.optBoolean("ok")) throw DraftError("TikTok didn't list the comments: ${result.optString("error", "unknown error")}")
        val items = Json.parse(result.optJSONArray("comments")?.toString() ?: "[]") as? List<*> ?: return null
        @Suppress("UNCHECKED_CAST")
        return items.filterIsInstance<Map<*, *>>().map { (it as Map<String, Any?>).toMutableMap() }
    }

    /** Answer one comment publicly, as the logged-in account (tiktok-inject.js replyComment). */
    override fun replyToComment(itemId: String, cid: String, rootCid: String, text: String): String? {
        val result = ask("replyComment", JSONObject().put("itemId", itemId).put("cid", cid).put("rootCid", rootCid).put("text", text), REPLY_MS) ?: return null
        if (!result.optBoolean("ok")) throw DraftError("TikTok refused the reply: ${result.optString("error", "unknown error")}")
        return result.optString("cid")
    }

    /** Whether that commenter's account is private (tiktok-inject.js userPrivate); null when it can't be told. */
    override fun isPrivate(username: String): Boolean? {
        if (username.isEmpty()) return null
        val result = ask("userPrivate", JSONObject().put("uniqueId", username), PROFILE_MS) ?: return null
        if (!result.optBoolean("ok")) return null
        return result.optBoolean("private")
    }

    /** Hand one job to tiktok-inject.js (upload the slides, then draft or publish) and wait for TikTok's answer. */
    private fun run(action: String, title: String, caption: String, slides: List<File>, sound: Boolean, at: Long, what: String, idKey: String, checkWhere: String): String {
        if (username().isEmpty()) throw DraftError("Not logged in to TikTok in the app: open Slidemill → TikTok and log in (username/email + password).")
        val key = "d" + UUID.randomUUID().toString().replace("-", "").take(12)
        val job = JSONObject()
            .put("id", key).put("action", action)
            .put("title", title).put("caption", caption).put("sound", sound).put("schedule", at)
            .put("images", JSONArray(TikTokWeb.serve(key, slides)))
        try {
            val result = ask("job", JSONObject().put("job", job), DRAFT_MS)
            // No answer, or the page still busy with an earlier upload: look in TikTok's own schedule
            // for what really happened before saying anything.
            val busy = result?.optString("error").orEmpty().contains("still running")
            if (action == "publish" && at > 0 && (result == null || busy)) {
                findScheduled(caption, at)?.let { return it }
                throw DraftError("TikTok didn't finish, and the post isn't in TikTok Studio's schedule after ${(DRAFT_MS + VERIFY_MS) / 60_000} minutes: it wasn't scheduled. Safe to try again.")
            }
            if (result == null) throw DraftError("TikTok didn't finish within ${DRAFT_MS / 60_000} minutes. Check $checkWhere before trying again.")
            if (!result.optBoolean("ok")) throw DraftError("TikTok refused the $what: ${result.optString("error", "unknown error")}")
            return result.optString(idKey).ifEmpty {
                throw DraftError("TikTok answered without an id. Check $checkWhere before trying again.")
            }
        } finally {
            TikTokWeb.unserve(key)
        }
    }
}
