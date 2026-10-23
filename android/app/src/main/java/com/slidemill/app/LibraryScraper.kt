package com.slidemill.app

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.slidemill.core.Json
import com.slidemill.core.Obj
import com.slidemill.core.objs
import com.slidemill.core.str
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Lists a public profile's posts the way the desktop server does with headless Chromium: open the
 * profile in an off-screen WebView (sharing the TikTok tab's cookies), scroll, and read the page's
 * own post-list responses. Those requests are passed through natively so their JSON can be kept.
 */
object LibraryScraper {
    private val main = Handler(Looper.getMainLooper())

    /** Blocking; call off the main thread. Returns raw itemStructs. */
    fun profilePosts(context: Context, username: String, max: Int, timeoutMs: Long = 90_000): List<Obj> {
        val items = ConcurrentHashMap<String, Obj>()
        val done = CountDownLatch(1)
        var web: WebView? = null
        main.post { web = start(context.applicationContext, username, max, items, done) }
        done.await(timeoutMs, TimeUnit.MILLISECONDS)
        main.post { web?.apply { stopLoading(); destroy() } }
        return items.values.take(max)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun start(context: Context, username: String, max: Int, items: ConcurrentHashMap<String, Obj>, done: CountDownLatch): WebView {
        val view = WebView(context)
        view.settings.javaScriptEnabled = true
        view.settings.domStorageEnabled = true
        view.settings.userAgentString = TikTokWeb.DESKTOP_UA
        view.layout(0, 0, 1280, 900)
        view.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(v: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url.toString()
                if (request.method != "GET" || "/api/post/item_list" !in url) return null
                return try {
                    val body = passThrough(url, request.requestHeaders)
                    runCatching {
                        (Json.parse(String(body)) as? Obj)?.objs("itemList")?.forEach { items[it.str("id")] = it }
                    }
                    WebResourceResponse("application/json", "utf-8", 200, "OK", mapOf("Access-Control-Allow-Origin" to "https://www.tiktok.com", "Access-Control-Allow-Credentials" to "true"), ByteArrayInputStream(body))
                } catch (e: Exception) {
                    null
                }
            }
        }
        view.loadUrl("https://www.tiktok.com/@$username")
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

    private fun passThrough(url: String, headers: Map<String, String>): ByteArray {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            conn.setRequestProperty("User-Agent", TikTokWeb.DESKTOP_UA)
            conn.setRequestProperty("Referer", "https://www.tiktok.com/")
            CookieManager.getInstance().getCookie(url)?.let { conn.setRequestProperty("Cookie", it) }
            return conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }
}
