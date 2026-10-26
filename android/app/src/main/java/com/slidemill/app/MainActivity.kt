package com.slidemill.app

import android.Manifest
import android.app.Activity
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.net.Uri
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.slidemill.core.Engine
import com.slidemill.core.copyObj
import com.slidemill.core.long
import com.slidemill.core.objOrNull
import com.slidemill.core.str
import com.slidemill.core.strings
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

/**
 * Two tabs: Slidemill (connect Claude; Claude's slideshows wait here as drafts: tap one, preview it, POST it live) and
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
        if (current == "preview") return show("Slidemill")
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
            "Ask Claude for slideshows. Claude writes and scores them; this phone renders them and keeps them below as drafts. Tap one to preview it and POST it to TikTok.",
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
        col.addView(button("Web drafts (TikTok Studio)", secondary = true) {
            show("TikTok")
            TikTokWeb.get(this).loadUrl("https://www.tiktok.com/tiktokstudio/content?tab=draft")
        }, spaced(6))
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

        // 5. Scheduling
        col.addView(heading("5. Scheduling"))
        col.addView(CheckBox(this).apply {
            text = "Let Claude schedule posts without asking me"
            setTextColor(ink)
            isChecked = engine.store.get("claude_schedules") == "1"
            setOnCheckedChangeListener { _, on -> io.execute { engine.store.setMany(mapOf("claude_schedules" to if (on) "1" else "")) } }
        }, spaced(6))
        col.addView(label(
            "Off: Claude only proposes a time, and you approve it with one tap (SCHEDULE). On: Claude puts posts on TikTok's schedule itself, so they go live without you seeing them first.",
            12f, muted,
        ))

        // What Claude made
        col.addView(heading("Drafts"))
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
        if (rows.isEmpty()) list.addView(label("Nothing yet. Ask Claude: \"make me a slideshow about …\".", 13f, muted))
        for (r in rows) {
            val state = stateOf(r)
            list.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(panel)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                addView(label(r.str("title").ifEmpty { "Untitled" }, 14f, ink, bold = true))
                addView(label(state, 13f, if (state.startsWith("✓")) good else muted))
                if (r.str("status") == "completed") setOnClickListener { preview(r.str("id")) }
            }, spaced(6))
        }
    }

    private fun stateOf(r: Map<String, Any?>): String {
        val p = r.objOrNull("post")
        return when {
            r.str("status") == "failed" -> "failed to render: ${r.str("error")}"
            r.str("status") != "completed" -> "${r.str("status")}…"
            p?.str("status") == "posted" -> "✓ Live on TikTok"
            p?.str("status") == "scheduled" -> "✓ Scheduled for ${whenText(p.str("scheduled_for"))}"
            p?.str("status") == "posting" -> "posting…"
            p?.str("status") == "failed" -> "✗ not posted: ${p.str("error")} (tap to retry)"
            r.long("suggested_time") > 0 -> "Claude suggests ${whenText(r.long("suggested_time"))}: tap to approve"
            else -> "Draft: tap to preview and post"
        }
    }

    // ---- one draft: preview and POST --------------------------------------------------------------

    private fun preview(id: String) {
        val r = engine.db.tx { get("slideshows", id)?.copyObj() } ?: return show("Slidemill")
        current = "preview"
        tabs.forEach { it.setTextColor(muted) }
        TikTokWeb.detach()
        main.removeCallbacks(ticker)
        content.removeAllViews()

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(18))
        }
        col.addView(button("← Drafts", secondary = true) { show("Slidemill") })
        col.addView(label(r.str("title").ifEmpty { "Untitled" }, 20f, ink, bold = true), spaced(12))

        // The slides, swipe sideways, in posting order.
        val strip = LinearLayout(this)
        val h = dp(380)
        for (rel in r.strings("rendered")) {
            val bmp = runCatching {
                val f = File(engine.mediaDir, rel).path
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { BitmapFactory.decodeFile(f, it) }
                var sample = 1
                while (bounds.outHeight / (sample * 2) >= h) sample *= 2
                BitmapFactory.decodeFile(f, BitmapFactory.Options().apply { inSampleSize = sample })
            }.getOrNull() ?: continue
            strip.addView(ImageView(this).apply {
                setImageBitmap(bmp)
                scaleType = ImageView.ScaleType.FIT_CENTER
                adjustViewBounds = true
            }, LinearLayout.LayoutParams(h * bmp.width / bmp.height, h).apply { rightMargin = dp(8) })
        }
        col.addView(HorizontalScrollView(this).apply { addView(strip) }, spaced(12))
        col.addView(label("${r.strings("rendered").size} slides: swipe to see them all.", 12f, muted), spaced(4))

        col.addView(label("Title on the carousel", 12f, muted), spaced(14))
        col.addView(label(r.str("caption").ifEmpty { r.str("title") }, 14f, ink).apply { setTextIsSelectable(true) })
        col.addView(label("Caption", 12f, muted), spaced(10))
        col.addView(label(r.str("description").ifEmpty { "(none)" }, 14f, ink).apply { setTextIsSelectable(true) })

        val status = label("", 14f, ink)
        val posted = r.objOrNull("post")?.takeIf { it.str("status") == "posted" || it.str("status") == "scheduled" }
        if (posted != null) {
            val scheduled = posted.str("status") == "scheduled"
            status.text = if (scheduled) "✓ Scheduled on TikTok for ${whenText(posted.str("scheduled_for"))}. TikTok publishes it then, phone on or off." else "✓ Live on TikTok"
            status.setTextColor(good)
            col.addView(status, spaced(18))
            if (scheduled) {
                col.addView(button("See it in TikTok Studio") {
                    show("TikTok")
                    TikTokWeb.get(this).loadUrl("https://www.tiktok.com/tiktokstudio/content")
                }, spaced(8))
            } else {
                col.addView(button("Open the post") { openUrl(posted.str("url")) }, spaced(8))
            }
        } else {
            val sound = CheckBox(this).apply {
                text = "Add a sound (TikTok's top suggestion for these slides)"
                setTextColor(ink)
                isChecked = true
            }
            col.addView(sound, spaced(16))
            col.addView(status, spaced(8))
            val buttons = mutableListOf<Button>()
            fun go(at: Long) {
                buttons.forEach { it.isEnabled = false }
                status.setTextColor(ink)
                status.text = (if (at > 0) "Scheduling for ${whenText(at)}…" else "Posting…") +
                    " uploading ${r.strings("rendered").size} slides to TikTok (up to a minute or two)."
                io.execute {
                    val out = runCatching { engine.studio.publish(id, engine.tiktok, sound.isChecked, at) }
                    main.post {
                        if (current != "preview") return@post
                        out.onSuccess { preview(id) }.onFailure {
                            status.text = "✗ ${it.message}"
                            status.setTextColor(accent)
                            buttons.forEach { b -> b.isEnabled = true }
                        }
                    }
                }
            }
            val suggested = r.long("suggested_time")
            if (suggested > 0) {
                col.addView(label("Claude suggests posting ${whenText(suggested)}.", 13f, muted), spaced(8))
                buttons += button("SCHEDULE for ${whenText(suggested)}") { go(suggested) }.apply { textSize = 17f }
                    .also { col.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)).apply { topMargin = dp(8) }) }
                buttons += button("POST now instead", secondary = true) { go(0) }.also { col.addView(it, spaced(8)) }
            } else {
                buttons += button("POST") { go(0) }.apply { textSize = 18f }
                    .also { col.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)).apply { topMargin = dp(8) }) }
            }
            buttons += button("Schedule for another time…", secondary = true) { pickTime { at -> go(at) } }.also { col.addView(it, spaced(8)) }
            col.addView(button("Edit in the TikTok app instead (pick any sound)", secondary = true) { post(r) }, spaced(10))
        }
        col.addView(button("Delete this draft", secondary = true) {
            io.execute { engine.studio.delete(id); main.post { toast("Deleted"); show("Slidemill") } }
        }, spaced(10))
        content.addView(ScrollView(this).apply { addView(col) })
    }

    private val whenFormat = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm")

    private fun whenText(epochS: Long): String = Instant.ofEpochSecond(epochS).atZone(ZoneId.systemDefault()).format(whenFormat)

    private fun whenText(iso: String): String = runCatching { whenText(Instant.parse(iso).epochSecond) }.getOrDefault(iso)

    /** Date, then time, in the phone's time zone; checked against TikTok's 15 minutes to 30 days window. */
    private fun pickTime(then: (Long) -> Unit) {
        val start = java.time.LocalDateTime.now().plusHours(1)
        DatePickerDialog(this, { _, y, m, d ->
            TimePickerDialog(this, { _, h, min ->
                val at = LocalDate.of(y, m + 1, d).atTime(LocalTime.of(h, min)).atZone(ZoneId.systemDefault()).toEpochSecond()
                val problem = com.slidemill.core.Studio.scheduleProblem(at)
                if (problem != null) toast(problem) else then(at)
            }, start.hour, 0, true).show()
        }, start.year, start.monthValue - 1, start.dayOfMonth).show()
    }

    private fun openUrl(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }.onFailure { toast("Couldn't open $url") }
    }

    /** Open TikTok's photo editor with the slides; the caption goes on the clipboard. */
    private fun post(r: Map<String, Any?>) {
        val slides = r.strings("rendered").map { File(engine.mediaDir, it) }
        val caption = r.str("description").ifEmpty { r.str("caption") }
        runCatching { Slidemill.shareToTikTok(this, slides, caption) }
            .onSuccess { toast(if (caption.isEmpty()) "Pick a sound and post." else "Caption copied: paste it, pick a sound and post.") }
            .onFailure { toast("Couldn't open TikTok: ${it.message}") }
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
