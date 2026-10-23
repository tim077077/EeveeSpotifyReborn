package com.slidemill.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.util.concurrent.Executors

/** Three tabs: the Slidemill dashboard, tiktok.com (log in once), and the poster's status/settings. */
class MainActivity : Activity() {

    private val bg = Color.parseColor("#0E0F12")
    private val panel = Color.parseColor("#16181D")
    private val ink = Color.parseColor("#ECEDF0")
    private val muted = Color.parseColor("#9097A3")
    private val accent = Color.parseColor("#FE2C55")

    private lateinit var content: FrameLayout
    private lateinit var tabs: List<Button>
    private var dashboard: WebView? = null
    private var current = ""
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var statusView: TextView? = null

    private val statusTicker = object : Runnable {
        override fun run() {
            statusView?.text = statusText()
            main.postDelayed(this, 2_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        content = FrameLayout(this)
        val bar = LinearLayout(this).apply { setBackgroundColor(panel) }
        tabs = listOf("Dashboard", "TikTok", "Poster").map { name ->
            Button(this).apply {
                this.text = name
                isAllCaps = false
                setTextColor(muted)
                setBackgroundColor(Color.TRANSPARENT)
                setOnClickListener { show(name) }
                bar.addView(this, LinearLayout.LayoutParams(0, dp(52), 1f))
            }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        })
        if (Prefs.autoPost && Prefs.configured) PosterService.start(this)
        show(if (Prefs.configured) "Dashboard" else "Poster")
    }

    override fun onDestroy() {
        TikTokWeb.detach()
        main.removeCallbacks(statusTicker)
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val web = when (current) {
            "Dashboard" -> dashboard
            "TikTok" -> TikTokWeb.get(this)
            else -> null
        }
        if (web != null && web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    private fun show(name: String) {
        current = name
        tabs.forEach { it.setTextColor(if (it.text == name) accent else muted) }
        TikTokWeb.detach()
        content.removeAllViews()
        main.removeCallbacks(statusTicker)
        statusView = null
        when (name) {
            "Dashboard" -> content.addView(dashboardView())
            "TikTok" -> TikTokWeb.attach(this, content)
            else -> content.addView(posterView())
        }
    }

    // ---- Dashboard ----------------------------------------------------------------------------

    @SuppressLint("SetJavaScriptEnabled")
    private fun dashboardView(): View {
        if (!Prefs.configured) return message("Set the Slidemill address and password in the Poster tab first.")
        dashboard?.let { (it.parent as? ViewGroup)?.removeView(it); return it }
        val web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webChromeClient = WebChromeClient()
            webViewClient = WebViewClient()
        }
        // Store the password where the dashboard looks for it, on the server's own origin, then open it.
        val bootstrap = "<script>try{localStorage.setItem('slidemill_key', ${JSONObject.quote(Prefs.key)})}catch(e){};location.replace('/')</script>"
        web.loadDataWithBaseURL(Prefs.server + "/", bootstrap, "text/html", "utf-8", null)
        dashboard = web
        return web
    }

    // ---- Poster -------------------------------------------------------------------------------

    private fun posterView(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }
        col.addView(label("Slidemill poster", 22f, ink, bold = true))
        col.addView(label("Posts your Slidemill queue from the TikTok tab's logged-in session, at Slidemill's scheduled times.", 13f, muted))

        statusView = label(statusText(), 14f, ink).apply {
            setBackgroundColor(panel)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        col.addView(statusView, spaced())
        main.post(statusTicker)

        val auto = Switch(this).apply {
            this.text = "Post automatically"
            setTextColor(ink)
            isChecked = Prefs.autoPost
            setOnCheckedChangeListener { _, on ->
                if (on && !Prefs.configured) {
                    isChecked = false
                    toast("Save the Slidemill address and password first")
                    return@setOnCheckedChangeListener
                }
                Prefs.autoPost = on
                if (on) PosterService.start(this@MainActivity) else PosterService.stop(this@MainActivity)
            }
        }
        col.addView(auto, spaced())
        col.addView(button("Check now") {
            if (!PosterService.running) PosterService.start(this) else PosterService.checkNow(this)
        }, spaced())

        col.addView(label("Slidemill address", 12f, muted, bold = true), spaced(16))
        val server = input(Prefs.server, "http://192.168.1.20:8000 or https://slides.yourname.com", InputType.TYPE_TEXT_VARIATION_URI)
        col.addView(server)
        col.addView(label("Slidemill password", 12f, muted, bold = true), spaced(10))
        val key = input(Prefs.key, "SLIDEMILL_API_KEY", InputType.TYPE_TEXT_VARIATION_PASSWORD)
        col.addView(key)
        col.addView(button("Save & test connection") {
            Prefs.server = server.text.toString()
            Prefs.key = key.text.toString()
            dashboard = null
            io.execute {
                val msg = try {
                    val s = Http.api("GET", "/api/v1/extension/status")
                    "✓ Connected · ${s.optInt("queued")} queued · ${s.optInt("done_last_24h")}/${s.optInt("posts_per_day")} posted in 24h"
                } catch (e: Exception) {
                    "✗ ${e.message ?: e.javaClass.simpleName}"
                }
                main.post { toast(msg) }
            }
        }, spaced())

        col.addView(label("Keep it running", 12f, muted, bold = true), spaced(18))
        col.addView(label(
            "Android stops background apps to save battery. Set Slidemill to \"Unrestricted\" battery use, and keep " +
                "the notification. Log in on the TikTok tab once (email/username + password; Google login doesn't work in apps).",
            13f, muted,
        ))
        col.addView(button("Open battery settings") {
            runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }, spaced())
        col.addView(label(
            "Posting this way uses TikTok's private web endpoints and is against TikTok's terms. Keep Slidemill's daily " +
                "limit low and use an account you can afford to lose.",
            12f, muted,
        ), spaced(18))
        return ScrollView(this).apply { addView(col) }
    }

    private fun statusText(): String =
        if (PosterService.running) PosterService.status else if (Prefs.autoPost) "Starting…" else "Auto-posting is off"

    // ---- small view helpers -------------------------------------------------------------------

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun spaced(top: Int = 12) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) }

    private fun label(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun input(value: String, hint: String, variation: Int) = EditText(this).apply {
        setText(value)
        this.hint = hint
        setHintTextColor(muted)
        setTextColor(ink)
        setSingleLine()
        inputType = InputType.TYPE_CLASS_TEXT or variation
    }

    private fun button(s: String, onClick: () -> Unit) = Button(this).apply {
        text = s
        isAllCaps = false
        setTextColor(Color.WHITE)
        setBackgroundColor(accent)
        setOnClickListener { onClick() }
    }

    private fun message(s: String) = label(s, 15f, ink).apply {
        gravity = Gravity.CENTER
        setPadding(dp(24), dp(24), dp(24), dp(24))
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
