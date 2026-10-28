package com.slidemill.app

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.slidemill.core.Json
import com.slidemill.core.Obj
import com.slidemill.core.str
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Lists a public profile's posts (or TikTok's search results) the way the desktop server does with headless Chromium: open the
 * profile in an off-screen WebView (sharing the TikTok tab's cookies), scroll, and read the page's
 * own post-list responses. Those requests are passed through natively so their JSON can be kept.
 */
object LibraryScraper {
    private val main = Handler(Looper.getMainLooper())

    /** Blocking; call off the main thread. Returns raw itemStructs. */
    fun profilePosts(context: Context, username: String, max: Int, via: String = "", timeoutMs: Long = 90_000): List<Obj> =
        scrape(context, "https://www.tiktok.com/@$username", "/api/post/item_list", max, via, timeoutMs)

    /** TikTok's search results for [query] (logged-in search page, scrolled), as raw itemStructs. Blocking. */
    fun searchPosts(context: Context, query: String, max: Int, via: String = "", timeoutMs: Long = 90_000): List<Obj> =
        scrape(context, "https://www.tiktok.com/search?q=" + URLEncoder.encode(query, "UTF-8"), "/api/search/", max, via, timeoutMs)

    /** [via] is the account slot whose login (WebView profile and cookies) the page uses; "" is the main one. */
    private fun scrape(context: Context, url: String, apiPath: String, max: Int, via: String, timeoutMs: Long): List<Obj> {
        val items = ConcurrentHashMap<String, Obj>()
        val done = CountDownLatch(1)
        var web: WebView? = null
        main.post { web = start(context.applicationContext, url, apiPath, max, via, items, done) }
        done.await(timeoutMs, TimeUnit.MILLISECONDS)
        main.post { web?.apply { stopLoading(); destroy() } }
        return items.values.take(max)
    }

    /** Every itemStruct-shaped object (id + author + stats) anywhere in a response: profile and search lists differ. */
    private fun collect(v: Any?, into: ConcurrentHashMap<String, Obj>, depth: Int = 0) {
        if (depth > 6) return
        when (v) {
            is Map<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                val m = v as Obj
                if (m.str("id").isNotEmpty() && m["author"] is Map<*, *> && (m["stats"] is Map<*, *> || m["statsV2"] is Map<*, *>)) {
                    into[m.str("id")] = m
                    return
                }
                m.values.forEach { collect(it, into, depth + 1) }
            }
            is List<*> -> v.forEach { collect(it, into, depth + 1) }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun start(context: Context, pageUrl: String, apiPath: String, max: Int, via: String, items: ConcurrentHashMap<String, Obj>, done: CountDownLatch): WebView {
        val view = WebView(context)
        TikTokWeb.useProfile(view, via)
        val cookies = TikTokWeb.cookies(via)
        view.settings.javaScriptEnabled = true
        view.settings.domStorageEnabled = true
        view.settings.userAgentString = TikTokWeb.DESKTOP_UA
        view.layout(0, 0, 1280, 900)
        view.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(v: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url.toString()
                if (request.method != "GET" || apiPath !in url) return null
                return try {
                    val body = passThrough(url, request.requestHeaders, cookies.getCookie(url))
                    runCatching { collect(Json.parse(String(body)), items) }
                    WebResourceResponse("application/json", "utf-8", 200, "OK", mapOf("Access-Control-Allow-Origin" to "https://www.tiktok.com", "Access-Control-Allow-Credentials" to "true"), ByteArrayInputStream(body))
                } catch (e: Exception) {
                    null
                }
            }
        }
        view.loadUrl(pageUrl)
        var stale = 0
        var last = -1
        val scroll = object : Runnable {
            override fun run() {
                if (done.count == 0L) return
                if (items.size >= max || stale >= 4) {
                    done.countDown()
                    return
                }
                stale = if (items.size == last) stale + 1 else 0
                last = items.size
                view.evaluateJavascript("window.scrollBy(0, 5000)", null)
                main.postDelayed(this, 2500L + (0..1500).random())
            }
        }
        main.postDelayed(scroll, 6000)
        return view
    }

    private fun passThrough(url: String, headers: Map<String, String>, cookie: String?): ByteArray {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            conn.setRequestProperty("User-Agent", TikTokWeb.DESKTOP_UA)
            conn.setRequestProperty("Referer", "https://www.tiktok.com/")
            cookie?.let { conn.setRequestProperty("Cookie", it) }
            return conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }
}
