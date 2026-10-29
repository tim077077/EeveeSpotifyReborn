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
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.slidemill.core.TikTokTab
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * One long-lived tiktok.com WebView per TikTok account, shared by the TikTok tab (where you log in) and
 * Claude's posts ([AndroidTikTok], in the background). It runs the same tiktok-inject.js as the browser
 * extension; android-bridge.js plays the extension bridge's role, and native code does what the
 * extension's background worker did (CDN uploads with TikTok's Origin/Referer).
 *
 * The main account uses the WebView's default profile (so an existing login carries over); every other
 * account gets its own WebView profile, with its own cookies and storage, so the logins never mix.
 */
class TikTokWeb private constructor(val slot: String) {
    companion object {
        const val HOME = "https://www.tiktok.com/"
        private const val SERVE_PREFIX = "https://www.tiktok.com/__slidemill/"
        // Desktop Chrome: TikTok's web uploader (and the endpoints the poster uses) is the desktop site.
        const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
        private val TIKTOK_HOSTS = listOf("tiktok.com", "tiktokv.com", "tiktokcdn.com", "tiktokcdn-eu.com", "byteimg.com", "ttwstatic.com")
        private const val PREFS = "tiktok_accounts"

        private val main = Handler(Looper.getMainLooper())
        private val io = Executors.newCachedThreadPool()
        private val served = ConcurrentHashMap<String, File>()
        private val all = ConcurrentHashMap<String, TikTokWeb>()

        /** The account in [slot]'s WebView (created on first use). */
        fun of(slot: String): TikTokWeb = all.getOrPut(slot) { TikTokWeb(slot) }

        /** Whether this phone's WebView can keep separate logins (Android System WebView 110+ or so). */
        fun multiSupported(): Boolean = runCatching { WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) }.getOrDefault(false)

        /** Every account slot, main first. */
        fun slots(context: Context): List<String> =
            listOf(TikTokTab.MAIN) + prefs(context).getString("slots", "").orEmpty().split(",").filter { it.isNotBlank() && it != TikTokTab.MAIN }

        /** A new empty slot for one more account. */
        fun add(context: Context): String {
            if (!multiSupported()) throw IllegalStateException("This phone's WebView can't keep two TikTok logins apart. Update \"Android System WebView\" in the Play Store, then try again.")
            val slot = "acct-" + UUID.randomUUID().toString().take(6)
            prefs(context).edit().putString("slots", (slots(context).drop(1) + slot).joinToString(",")).apply()
            return slot
        }

        /** Log out and forget an extra account (its cookies are deleted with its profile). Main thread. */
        fun remove(context: Context, slot: String) {
            require(slot != TikTokTab.MAIN) { "The main account can't be removed: log out in its TikTok tab instead." }
            prefs(context).edit().putString("slots", slots(context).drop(1).filter { it != slot }.joinToString(",")).apply()
            all.remove(slot)?.destroy()
            runCatching { ProfileStore.getInstance().deleteProfile(profileName(slot)) }
        }

        private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        private fun profileName(slot: String) = "slidemill-$slot"

        /** The cookie jar of [slot]'s account (for native requests made on its behalf). Main thread. */
        fun cookies(slot: String): CookieManager =
            if (slot.isEmpty() || slot == TikTokTab.MAIN || !multiSupported()) CookieManager.getInstance()
            else ProfileStore.getInstance().getOrCreateProfile(profileName(slot)).cookieManager

        /** Put a WebView on [slot]'s profile; must happen before it loads anything. Main thread. */
        fun useProfile(view: WebView, slot: String) {
            if (slot.isEmpty() || slot == TikTokTab.MAIN || !multiSupported()) return
            ProfileStore.getInstance().getOrCreateProfile(profileName(slot))
            WebViewCompat.setProfile(view, profileName(slot))
        }

        private fun isTikTok(uri: Uri?): Boolean {
            val host = uri?.host ?: return false
            return TIKTOK_HOSTS.any { host == it || host.endsWith(".$it") }
        }

        /** Make local slide files fetchable by the page; returns their in-page URLs. */
        fun serve(postId: String, files: List<File>): List<String> = files.mapIndexed { i, f ->
            val url = "$SERVE_PREFIX$postId/${i + 1}.jpg"
            served[url] = f
            url
        }

        fun unserve(postId: String) = served.keys.removeIf { it.startsWith("$SERVE_PREFIX$postId/") }
    }

    private val pending = ConcurrentHashMap<String, (JSONObject?) -> Unit>()
    private var web: WebView? = null
    private lateinit var wrapper: MutableContextWrapper
    private lateinit var appContext: Context

    @Volatile var pageReady = false
        private set

    /** Get (creating if needed) this account's WebView. Main thread only. */
    @SuppressLint("SetJavaScriptEnabled")
    fun get(context: Context): WebView {
        web?.let { return it }
        appContext = context.applicationContext
        wrapper = MutableContextWrapper(appContext)
        val view = WebView(wrapper)
        useProfile(view, slot)
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
        cookies(slot).setAcceptCookie(true)
        cookies(slot).setAcceptThirdPartyCookies(view, true)
        // Detached and invisible, the page's renderer would be first in line when Android frees memory.
        view.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
        view.webChromeClient = WebChromeClient()
        view.webViewClient = client
        view.addJavascriptInterface(Native(), "SlidemillNative")
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

    private fun destroy() = main.post {
        detach()
        web?.destroy()
        web = null
    }

    /** Run the bridge and tiktok-inject.js in the current page (both skip themselves if already there). */
    private fun inject(view: WebView, url: String?) {
        if (!isTikTok(Uri.parse(url ?: ""))) return
        val assets = view.context.assets
        val bridge = assets.open("android-bridge.js").bufferedReader().use { it.readText() }
        val poster = assets.open("tiktok-inject.js").bufferedReader().use { it.readText() }
        view.evaluateJavascript(bridge, null)
        view.evaluateJavascript("if (!window.VBTikTok) {\n$poster\n}", null)
        pageReady = true
    }

    /** Make sure the page is running and has the scripts (main thread posted). */
    fun wake(context: Context) = main.post {
        val view = get(context)
        view.onResume()
        view.resumeTimers()
        inject(view, view.url)
    }

    private val client = object : WebViewClient() {
        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            pageReady = false
        }

        // Inject as soon as the page is visible: a heavy tiktok.com page (video feed) can take a long
        // time to "finish", or never, while its scripts are already running.
        override fun onPageCommitVisible(view: WebView, url: String?) = inject(view, url)

        // Android killed the page's renderer (memory, or the screen was off a long time): drop the dead
        // view and answer what was waiting on it; the next use loads tiktok.com again with the same login.
        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            if (web === view) {
                pageReady = false
                (view.parent as? ViewGroup)?.removeView(view)
                view.destroy()
                web = null
                pending.keys.toList().forEach { id -> pending.remove(id)?.invoke(null) }
            }
            return true // handled: don't take the whole app down
        }

        override fun onPageFinished(view: WebView, url: String?) = inject(view, url)

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

    /** Ask the page something ("hello", "job", "listMine"); the callback gets null on timeout. */
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
    private inner class Native {
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
