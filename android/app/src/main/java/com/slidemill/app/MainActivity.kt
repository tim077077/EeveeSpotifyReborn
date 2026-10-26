package com.slidemill.app

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
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
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.slidemill.core.Engine
import com.slidemill.core.copyObj
import com.slidemill.core.long
import com.slidemill.core.objOrNull
import com.slidemill.core.str
import java.util.concurrent.Executors

/**
 * Two tabs: Slidemill (connect Claude, see what it made and whether each draft reached TikTok) and
 * TikTok (log in once). Claude does the work through the connector; this screen only sets it up.
 */
class MainActivity : Activity() {

    private val bg = Color.parseColor("#0E0F12")
    private val panel = Color.parseColor("#16181D")
    private val ink = Color.parseColor("#ECEDF0")
    private val muted = Color.parseColor("#9097A3")
    private val accent = Color.parseColor("#FE2C55")
    private val good = Color.parseColor("#12B886")

    private lateinit var content: FrameLayout
    private lateinit var tabs: List<Button>
    private var current = ""
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var engine: Engine

    private var relayState: TextView? = null
    private var claudeUrl: TextView? = null
    private var tiktokState: TextView? = null
    private var showsList: LinearLayout? = null

    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            main.postDelayed(this, 3_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // After a crash, show what happened before starting anything that could crash again.
        val crash = Crash.pending(this)
        if (crash != null) Crash.show(this, crash) { start() } else start()
    }

    private fun start() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1) // only for the "running" notice
        }
        engine = Slidemill.engine(this)
        content = FrameLayout(this)
        val bar = LinearLayout(this).apply { setBackgroundColor(panel) }
        tabs = listOf("Slidemill", "TikTok").map { name ->
            Button(this).apply {
                text = name
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
        EngineService.start(this) // keeps the relay and the TikTok tab alive with the app closed
        show("Slidemill")
    }

    override fun onDestroy() {
        TikTokWeb.detach()
        main.removeCallbacks(ticker)
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!::tabs.isInitialized) return super.onBackPressed() // on the crash report
        val web = if (current == "TikTok") TikTokWeb.get(this) else null
        if (web != null && web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    private fun show(name: String) {
        current = name
        tabs.forEach { it.setTextColor(if (it.text == name) accent else muted) }
        TikTokWeb.detach()
        content.removeAllViews()
        main.removeCallbacks(ticker)
        if (name == "TikTok") {
            TikTokWeb.attach(this, content)
        } else {
            content.addView(home())
            main.post(ticker)
            checkTikTok()
        }
    }

    // ---- the Slidemill tab ------------------------------------------------------------------------

    private fun home(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }
        col.addView(label("Slidemill", 22f, ink, bold = true))
        col.addView(label(
            "Ask Claude for slideshows. Claude writes and scores them; this phone renders them and saves them straight to your TikTok Studio drafts.",
            13f, muted,
        ))

        // 1. Claude connector
        col.addView(heading("1. Connect Claude"))
        col.addView(label("Your Slidemill relay address (from the relay deploy):", 13f, muted))
        val relayField = field("slidemill-relay.you.workers.dev").apply { setText(engine.relay.url) }
        col.addView(relayField, spaced(6))
        col.addView(button("Save relay address") {
            val raw = relayField.text.toString()
            io.execute {
                val msg = runCatching { engine.relay.configure(raw); "Saved" }.getOrElse { it.message ?: "Invalid address" }
                main.post { toast(msg); refresh() }
            }
        }, spaced(6))
        relayState = label("", 14f, ink).also { col.addView(it, spaced(10)) }
        claudeUrl = label("", 13f, ink).apply { setTextIsSelectable(true) }.also { col.addView(it, spaced(6)) }
        col.addView(row(
            button("Copy Claude link") {
                val url = engine.relay.status().str("claude_url")
                if (url.isEmpty()) return@button toast("Save the relay address first")
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Slidemill connector", url))
                toast("Copied. In Claude: Settings → Connectors → Add custom connector, paste it.")
            },
            button("New link", secondary = true) {
                io.execute { engine.relay.newLink(); main.post { toast("New link made: the old one stops working. Update it in Claude."); refresh() } }
            },
        ), spaced(6))

        // 2. TikTok
        col.addView(heading("2. Log in to TikTok"))
        tiktokState = label("Checking…", 14f, ink).also { col.addView(it, spaced(6)) }
        col.addView(row(
            button("Open the TikTok tab") { show("TikTok") },
            button("Check again", secondary = true) { checkTikTok() },
        ), spaced(6))
        col.addView(label("Use username/email + password: Google login doesn't work inside apps.", 12f, muted), spaced(6))

        // 3. Backgrounds
        col.addView(heading("3. Real photos (optional)"))
        val hasPexels = engine.store.get("pexels_api_key").isNotEmpty()
        col.addView(label(
            if (hasPexels) "Pexels key saved: backgrounds are real stock photos." else "Without a key, backgrounds are free AI images (slower, watermarked). A free Pexels key (pexels.com/api) gives real photos.",
            13f, muted,
        ))
        val keyField = field(if (hasPexels) "•••• saved (paste to replace)" else "Pexels API key").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        col.addView(keyField, spaced(6))
        col.addView(button("Save key") {
            val key = keyField.text.toString().trim()
            io.execute {
                engine.store.setMany(mapOf("pexels_api_key" to key))
                main.post { toast(if (key.isEmpty()) "Key removed" else "Saved"); show("Slidemill") }
            }
        }, spaced(6))

        // 4. Keep running
        col.addView(heading("4. Keep it reachable"))
        col.addView(label(
            "Claude can only reach the phone while Android lets Slidemill run. Set battery use to \"Unrestricted\" (Samsung: also remove it from sleeping apps).",
            13f, muted,
        ))
        col.addView(button("Open battery settings", secondary = true) {
            runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }, spaced(6))

        // What Claude made
        col.addView(heading("Slideshows"))
        showsList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }.also { col.addView(it, spaced(6)) }

        col.addView(label(
            "Saving drafts uses TikTok's private web endpoints, which is against TikTok's terms. Use an account you can afford to lose.",
            12f, muted,
        ), spaced(24))
        return ScrollView(this).apply { addView(col) }
    }

    private fun refresh() {
        val s = engine.relay.status()
        relayState?.text = when (s.str("state")) {
            "off" -> "Not connected: enter your relay address."
            "connected" -> "✓ Connected: Claude can reach this phone."
            "connecting" -> "Connecting…"
            else -> "Offline: ${s.str("detail")} (retrying)"
        }
        relayState?.setTextColor(if (s.str("state") == "connected") good else ink)
        claudeUrl?.text = s.str("claude_url").ifEmpty { "" }.let { if (it.isEmpty()) "" else "Claude link: $it" }
        val list = showsList ?: return
        val rows = engine.db.tx { all("slideshows").sortedByDescending { it.long("created_at") }.take(15).map { it.copyObj() } }
        list.removeAllViews()
        if (rows.isEmpty()) list.addView(label("Nothing yet. Ask Claude: \"make me a slideshow about … and save it to my TikTok drafts\".", 13f, muted))
        for (r in rows) {
            val d = r.objOrNull("draft")
            val draft = when (d?.str("status")) {
                null -> if (r.str("status") == "completed") "not saved to TikTok" else r.str("status").let { if (it == "failed") "failed: ${r.str("error")}" else "$it…" }
                "saved" -> "✓ in TikTok drafts"
                "saving" -> "saving to TikTok…"
                else -> "✗ not saved: ${d.str("error")}"
            }
            list.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(panel)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                addView(label(r.str("title").ifEmpty { "Untitled" }, 14f, ink, bold = true))
                addView(label(draft, 13f, if (d?.str("status") == "saved") good else muted))
            }, spaced(6))
        }
    }

    private fun checkTikTok() {
        tiktokState?.text = "Checking…"
        io.execute {
            val text = runCatching {
                engine.tiktok!!.username().let { if (it.isEmpty()) "✗ Not logged in: open the TikTok tab and log in." else "✓ Logged in as @$it" }
            }.getOrElse { "✗ ${it.message}" }
            main.post { tiktokState?.text = text; tiktokState?.setTextColor(if (text.startsWith("✓")) good else ink) }
        }
    }

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

    private fun heading(s: String) = label(s, 15f, ink, bold = true).apply {
        layoutParams = spaced(22)
    }

    private fun field(hint: String) = EditText(this).apply {
        this.hint = hint
        setHintTextColor(muted)
        setTextColor(ink)
        setBackgroundColor(panel)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        isSingleLine = true
    }

    private fun button(s: String, secondary: Boolean = false, onClick: () -> Unit) = Button(this).apply {
        text = s
        isAllCaps = false
        setTextColor(Color.WHITE)
        setBackgroundColor(if (secondary) panel else accent)
        setOnClickListener { onClick() }
    }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        views.forEachIndexed { i, v ->
            addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (i > 0) leftMargin = dp(8) })
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
