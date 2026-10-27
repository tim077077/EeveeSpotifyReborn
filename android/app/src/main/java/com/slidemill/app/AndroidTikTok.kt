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
 * The engine's view of the TikTok tab: asks the logged-in tiktok.com page (tiktok-inject.js) and
 * waits for its answer. Called from Claude's tool calls on background threads, never the main one.
 */
class AndroidTikTok(private val context: Context) : TikTokTab {
    companion object {
        private const val READY_MS = 30_000L
        private const val HELLO_MS = 4_000L
        private const val DRAFT_MS = 4 * 60_000L
        private const val LIST_MS = 60_000L

        /** The last logged-in username the page reported (for the app's screen). */
        @Volatile var lastUsername = ""
            private set
    }

    private fun ask(kind: String, payload: JSONObject, timeoutMs: Long): JSONObject? {
        val latch = CountDownLatch(1)
        val out = AtomicReference<JSONObject?>()
        TikTokWeb.ask(kind, payload, timeoutMs) { out.set(it); latch.countDown() }
        latch.await(timeoutMs + 2_000, TimeUnit.MILLISECONDS)
        return out.get()
    }

    /**
     * Wait until the tiktok.com page answers. Never reloads it: a reload while it's still loading starts
     * the wait over, and repeated calls would keep it from ever getting ready.
     */
    private fun hello(): JSONObject {
        val until = System.currentTimeMillis() + READY_MS
        while (System.currentTimeMillis() < until) {
            TikTokWeb.wake(context)
            if (TikTokWeb.pageReady) {
                val hello = ask("hello", JSONObject(), HELLO_MS)
                if (hello != null && hello.optBoolean("ready")) return hello
            }
            Thread.sleep(1_000)
        }
        throw DraftError(
            "tiktok.com isn't answering in the app. Open Slidemill → TikTok once to check the page loads and you're logged in, then try again.",
        )
    }

    override fun username(): String = hello().optString("username").also { lastUsername = it }

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
                ?: throw DraftError("TikTok didn't finish within ${DRAFT_MS / 60_000} minutes. Check $checkWhere before trying again.")
            if (!result.optBoolean("ok")) throw DraftError("TikTok refused the $what: ${result.optString("error", "unknown error")}")
            return result.optString(idKey).ifEmpty {
                throw DraftError("TikTok answered without an id. Check $checkWhere before trying again.")
            }
        } finally {
            TikTokWeb.unserve(key)
        }
    }
}
