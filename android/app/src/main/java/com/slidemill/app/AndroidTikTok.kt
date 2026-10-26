package com.slidemill.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.slidemill.core.DraftError
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
        private const val LOAD_WAIT_MS = 25_000L
        private const val HELLO_MS = 8_000L
        private const val DRAFT_MS = 4 * 60_000L

        /** The last logged-in username the page reported (for the app's screen). */
        @Volatile var lastUsername = ""
            private set
    }

    private val main = Handler(Looper.getMainLooper())

    private fun ask(kind: String, payload: JSONObject, timeoutMs: Long): JSONObject? {
        val latch = CountDownLatch(1)
        val out = AtomicReference<JSONObject?>()
        TikTokWeb.ask(kind, payload, timeoutMs) { out.set(it); latch.countDown() }
        latch.await(timeoutMs + 2_000, TimeUnit.MILLISECONDS)
        return out.get()
    }

    /** Make sure tiktok.com is loaded, running (not paused) and answering. */
    private fun ready() {
        val created = CountDownLatch(1)
        main.post {
            runCatching { TikTokWeb.get(context).also { it.onResume(); it.resumeTimers() } }
            created.countDown()
        }
        created.await(5, TimeUnit.SECONDS)
        val until = System.currentTimeMillis() + LOAD_WAIT_MS
        while (!TikTokWeb.pageReady && System.currentTimeMillis() < until) Thread.sleep(250)
        if (!TikTokWeb.pageReady) {
            TikTokWeb.reload()
            throw DraftError("tiktok.com didn't finish loading in the app (no connection?). Try again in a minute.")
        }
    }

    override fun username(): String {
        ready()
        val hello = ask("hello", JSONObject(), HELLO_MS)
        if (hello == null || !hello.optBoolean("ready")) {
            TikTokWeb.reload()
            throw DraftError("The TikTok tab didn't answer; it's reloading. Try again in a minute.")
        }
        return hello.optString("username").also { lastUsername = it }
    }

    override fun saveDraft(title: String, caption: String, slides: List<File>): String {
        if (username().isEmpty()) throw DraftError("Not logged in to TikTok in the app: open Slidemill → TikTok and log in (username/email + password).")
        val key = "d" + UUID.randomUUID().toString().replace("-", "").take(12)
        val job = JSONObject()
            .put("id", key).put("action", "draft")
            .put("title", title).put("caption", caption)
            .put("images", JSONArray(TikTokWeb.serve(key, slides)))
        try {
            val result = ask("job", JSONObject().put("job", job), DRAFT_MS)
                ?: throw DraftError("TikTok didn't finish within ${DRAFT_MS / 60_000} minutes. Check TikTok Studio drafts before saving again.")
            if (!result.optBoolean("ok")) throw DraftError("TikTok refused the draft: ${result.optString("error", "unknown error")}")
            return result.optString("draftId").ifEmpty {
                throw DraftError("TikTok answered without a draft id. Check TikTok Studio drafts before saving again.")
            }
        } finally {
            TikTokWeb.unserve(key)
        }
    }
}
