package com.slidemill.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * One long-lived tiktok.com WebView shared by the TikTok tab (where you log in) and Claude's
 * draft saves ([AndroidTikTok], in the background). It runs the same tiktok-inject.js as the browser
 * extension; android-bridge.js plays the extension bridge's role, and native code does what the
 * extension's background worker did (CDN uploads with TikTok's Origin/Referer).
 */
object TikTokWeb {
    const val HOME = "https://www.tiktok.com/"
    private const val SERVE_PREFIX = "https://www.tiktok.com/__slidemill/"
    // Desktop Chrome: TikTok's web uploader (and the endpoints the poster uses) is the desktop site.
    const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
    private val TIKTOK_HOSTS = listOf("tiktok.com", "tiktokv.com", "tiktokcdn.com", "tiktokcdn-eu.com", "byteimg.com", "ttwstatic.com")

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newCachedThreadPool()
    private val pending = ConcurrentHashMap<String, (JSONObject?) -> Unit>()
    private val served = ConcurrentHashMap<String, File>()

    private var web: WebView? = null
    private lateinit var wrapper: MutableContextWrapper
    private lateinit var appContext: Context

    @Volatile var pageReady = false
        private set

    /** Get (creating if needed) the shared WebView. Main thread only. */
    @SuppressLint("SetJavaScriptEnabled")
    fun get(context: Context): WebView {
        web?.let { return it }
        appContext = context.applicationContext
        wrapper = MutableContextWrapper(appContext)
        val view = WebView(wrapper)
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            userAgentString = DESKTOP_UA
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = true
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
        view.webChromeClient = WebChromeClient()
        view.webViewClient = Client
        view.addJavascriptInterface(Native, "SlidemillNative")
        view.loadUrl(HOME)
        web = view
        return view
    }

    /** Show the WebView inside an activity (it keeps running when detached). */
    fun attach(activity: Context, parent: ViewGroup) {
        val view = get(activity)
        wrapper.baseContext = activity
        (view.parent as? ViewGroup)?.removeView(view)
        parent.addView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    fun detach() {
        val view = web ?: return
        (view.parent as? ViewGroup)?.removeView(view)
        wrapper.baseContext = appContext
    }

    fun reload() = main.post { web?.loadUrl(HOME) }

    private fun isTikTok(uri: Uri?): Boolean {
        val host = uri?.host ?: return false
        return TIKTOK_HOSTS.any { host == it || host.endsWith(".$it") }
    }

    private object Client : WebViewClient() {
        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            pageReady = false
        }

        override fun onPageFinished(view: WebView, url: String?) {
            if (!isTikTok(Uri.parse(url ?: ""))) return
            val assets = view.context.assets
            val bridge = assets.open("android-bridge.js").bufferedReader().use { it.readText() }
            val poster = assets.open("tiktok-inject.js").bufferedReader().use { it.readText() }
            view.evaluateJavascript(bridge, null)
            view.evaluateJavascript("if (!window.VBTikTok) {\n$poster\n}", null)
            pageReady = true
        }

        // Keep the logged-in session (and the JS bridge) on TikTok; open anything else in the browser.
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (!request.isForMainFrame || isTikTok(request.url)) return false
            runCatching {
                view.context.startActivity(Intent(Intent.ACTION_VIEW, request.url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            return true
        }

        // Slides are served same-origin at https://www.tiktok.com/__slidemill/... so the page can fetch them.
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val url = request.url.toString()
            if (!url.startsWith(SERVE_PREFIX)) return null
            val file = served[url] ?: return WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), "".byteInputStream())
            return WebResourceResponse("image/jpeg", null, 200, "OK", mapOf("Cache-Control" to "no-store"), file.inputStream())
        }
    }

    /** Make local slide files fetchable by the page; returns their in-page URLs. */
    fun serve(postId: String, files: List<File>): List<String> = files.mapIndexed { i, f ->
        val url = "$SERVE_PREFIX$postId/${i + 1}.jpg"
        served[url] = f
        url
    }

    fun unserve(postId: String) = served.keys.removeIf { it.startsWith("$SERVE_PREFIX$postId/") }

    /** Ask the page something ("hello" or "job"); the callback gets null on timeout. */
    fun ask(kind: String, payload: JSONObject, timeoutMs: Long, callback: (JSONObject?) -> Unit) {
        val id = "and" + UUID.randomUUID().toString().replace("-", "")
        pending[id] = callback
        main.post {
            val view = web
            if (view == null) {
                pending.remove(id)?.invoke(null)
                return@post
            }
            val js = "window.__slidemillAsk && window.__slidemillAsk(${JSONObject.quote(kind)}, ${JSONObject.quote(id)}, ${JSONObject.quote(payload.toString())})"
            view.evaluateJavascript(js, null)
        }
        main.postDelayed({ pending.remove(id)?.invoke(null) }, timeoutMs)
    }

    /** Methods the page can call. Only TikTok pages are ever loaded in this WebView. */
    private object Native {
        @JavascriptInterface
        fun onResult(id: String, json: String) {
            val result = runCatching { if (json == "null") null else JSONObject(json) }.getOrNull()
            pending.remove(id)?.invoke(result)
        }

        @JavascriptInterface
        fun upload(id: String, url: String, crc: String, storageU: String, b64: String) {
            io.execute {
                val reply = try {
                    Http.uploadToCdn(url, crc, storageU, b64)
                } catch (e: Exception) {
                    JSONObject().put("json", JSONObject().put("message", "native upload error: ${e.message}"))
                }
                reply.put("__vbtt", "uploadResult").put("id", id)
                main.post { web?.evaluateJavascript("window.postMessage($reply, '*')", null) }
            }
        }
    }
}
