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
import com.slidemill.core.TikTokTab
import com.slidemill.core.copyObj
import com.slidemill.core.long
import com.slidemill.core.objAt
import com.slidemill.core.objs
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
 * Three tabs: Slidemill (connect Claude; Claude's slideshows wait here as drafts: tap one, preview it, POST it live),
 * Analytics, and TikTok (log in once per account). Claude does the work through the connector; this screen only sets it up.
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

    /** The account the TikTok tab shows, and the one the Analytics tab reports on ("" = the default). */
    private var webSlot = TikTokTab.MAIN
    private var statsUser = ""

    private fun web() = TikTokWeb.of(webSlot)

    private fun detachAll() = TikTokWeb.slots(this).forEach { TikTokWeb.of(it).detach() }

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
        tabs = listOf("Slidemill", "Analytics", "TikTok").map { name ->
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
        if (::tabs.isInitialized) detachAll()
        main.removeCallbacks(ticker)
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!::tabs.isInitialized) return super.onBackPressed() // on the crash report
        if (current == "preview" || current == "Analytics") return show("Slidemill")
        val web = if (current == "TikTok") web().get(this) else null
        if (web != null && web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    private fun show(name: String) {
        current = name
        tabs.forEach { it.setTextColor(if (it.text == name) accent else muted) }
        detachAll()
        content.removeAllViews()
        main.removeCallbacks(ticker)
        if (name == "TikTok") {
            content.addView(tiktokTab())
        } else if (name == "Analytics") {
            content.addView(analytics())
            // Fresh numbers when the last refresh is old; the tab redraws when they arrive.
            if (System.currentTimeMillis() - engine.analytics.lastRefreshMs(statsUser) > com.slidemill.core.Analytics.MIN_REFRESH_MS) refreshAnalytics(force = false)
        } else {
            content.addView(home())
            main.post(ticker)
            checkTikTok()
        }
    }

    // ---- the TikTok tab: one logged-in page per account ---------------------------------------------

    private fun accountName(slot: String): String =
        engine.accounts.cached(slot).let { if (it.isNotEmpty()) "@$it" else if (slot == TikTokTab.MAIN) "Main account" else "New account" }

    private fun tiktokTab(): View {
        if (webSlot !in TikTokWeb.slots(this)) webSlot = TikTokTab.MAIN
        val chips = LinearLayout(this).apply { setPadding(dp(8), dp(6), dp(8), dp(6)) }
        for (slot in TikTokWeb.slots(this)) {
            chips.addView(button(accountName(slot), secondary = slot != webSlot) { webSlot = slot; show("TikTok") },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)).apply { rightMargin = dp(6) })
        }
        chips.addView(button("+ Add account", secondary = true) { addAccount() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)).apply { rightMargin = dp(6) })
        if (webSlot != TikTokTab.MAIN) {
            chips.addView(button("Remove", secondary = true) { removeAccount(webSlot) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)))
        }
        val page = FrameLayout(this)
        web().attach(this, page)
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(HorizontalScrollView(this@MainActivity).apply { setBackgroundColor(panel); addView(chips) })
            addView(page, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
    }

    /** A new account: its own WebView profile (cookies apart from the others), opened for logging in. */
    private fun addAccount() {
        val slot = runCatching { TikTokWeb.add(this) }.getOrElse { return toast(it.message ?: "Couldn't add an account") }
        engine.tabs[slot] = AndroidTikTok(applicationContext, slot)
        webSlot = slot
        show("TikTok")
        toast("Log in to the other TikTok account here. Each account stays logged in separately.")
    }

    private fun removeAccount(slot: String) {
        android.app.AlertDialog.Builder(this)
            .setMessage("Remove ${accountName(slot)} from Slidemill? It's logged out on this phone; posts already on TikTok stay.")
            .setPositiveButton("Remove") { _, _ ->
                engine.tabs.remove(slot)
                TikTokWeb.remove(this, slot)
                io.execute {
                    // Settings that named this account fall back to the main one.
                    val clear = listOf("default_account", "research_account").filter { engine.store.get(it) == slot }.associateWith { "" }
                    if (clear.isNotEmpty()) engine.store.setMany(clear)
                }
                webSlot = TikTokTab.MAIN
                show("TikTok")
            }
            .setNegativeButton("Cancel", null)
            .show()
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
        col.addView(label("One account, or several: add each one in the TikTok tab (+ Add account). Claude posts to the default unless you tell it which.", 13f, muted))
        tiktokState = label("Checking…", 14f, ink).also { col.addView(it, spaced(6)) }
        col.addView(row(
            button("Open the TikTok tab") { show("TikTok") },
            button("Check again", secondary = true) { checkTikTok() },
        ), spaced(6))
        col.addView(button("Web drafts (TikTok Studio)", secondary = true) {
            show("TikTok")
            web().get(this).loadUrl("https://www.tiktok.com/tiktokstudio/content?tab=draft")
        }, spaced(6))
        col.addView(accountSettings(), spaced(10))
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
            isChecked = engine.store.get("claude_schedules") != "0"
            setOnCheckedChangeListener { _, on -> io.execute { engine.store.setMany(mapOf("claude_schedules" to if (on) "1" else "0")) } }
        }, spaced(6))
        col.addView(label(
            "On (autopilot, the default): Claude puts posts on TikTok's schedule itself and they go live without you seeing them first, at least 2 hours apart and at most 4 a day. Off: Claude only proposes a time, and you approve it with one tap (SCHEDULE).",
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

    /** Which account Claude posts to by default, and which one does the research (search, other accounts' posts). */
    private fun accountSettings(): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val default = engine.store.get("default_account").ifEmpty { TikTokTab.MAIN }
        val research = engine.store.get("research_account").ifEmpty { TikTokTab.MAIN }
        for (slot in TikTokWeb.slots(this)) {
            val tags = listOfNotNull("default".takeIf { slot == default }, "research".takeIf { slot == research }).joinToString(", ")
            box.addView(label(accountName(slot) + if (tags.isEmpty()) "" else " ($tags)", 14f, ink, bold = true), spaced(8))
            box.addView(row(
                button("Post here by default", secondary = slot != default) { setAccount("default_account", slot) },
                button("Research with it", secondary = slot != research) { setAccount("research_account", slot) },
            ), spaced(4))
        }
        box.addView(label("No login" + if (research == com.slidemill.core.Accounts.NO_LOGIN) " (research)" else "", 14f, ink, bold = true), spaced(8))
        box.addView(button("Research with no login", secondary = research != com.slidemill.core.Accounts.NO_LOGIN) {
            setAccount("research_account", com.slidemill.core.Accounts.NO_LOGIN)
        }, spaced(4))
        box.addView(label(
            "Research searches TikTok and opens other accounts' profiles, which is the bot-like part. Give it a spare account you don't post from, so a flag lands there instead. " +
                "No login risks no account, but TikTok shows logged-out visitors no search results and few photo posts, so it often finds nothing.",
            12f, muted,
        ), spaced(6))
        return box
    }

    private fun setAccount(key: String, slot: String) = io.execute {
        engine.store.setMany(mapOf(key to if (slot == TikTokTab.MAIN) "" else slot))
        main.post {
            // Redraw in place (not back at the top) and say what changed, so the tap visibly did something.
            val y = (content.getChildAt(0) as? ScrollView)?.scrollY ?: 0
            show("Slidemill")
            (content.getChildAt(0) as? ScrollView)?.post { (content.getChildAt(0) as? ScrollView)?.scrollTo(0, y) }
            val who = if (slot == com.slidemill.core.Accounts.NO_LOGIN) "no login" else accountName(slot)
            toast(if (key == "research_account") "Research now runs on $who." else "Claude now posts to $who by default.")
        }
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
                if (engine.tabs.size > 1 && r.str("account").isNotEmpty()) addView(label("@${r.str("account")}", 12f, muted))
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

    // ---- the Analytics tab --------------------------------------------------------------------------

    private var analyticsStatus: TextView? = null

    private fun refreshAnalytics(force: Boolean) {
        analyticsStatus?.text = "Reading your posts from TikTok… (up to a minute)"
        io.execute {
            val err = runCatching { engine.analytics.refresh(engine.accounts.tab(statsUser), force) }.exceptionOrNull()
            main.post {
                if (current != "Analytics") return@post
                if (err != null) {
                    analyticsStatus?.text = "✗ ${err.message}"
                    analyticsStatus?.setTextColor(accent)
                } else show("Analytics")
            }
        }
    }

    private fun analytics(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }
        val r = engine.analytics.report(weeks = 8, postsLimit = 100, predicted = engine.predictedByPost(), username = statsUser)
        col.addView(row(
            label("Analytics", 22f, ink, bold = true),
            button("Refresh", secondary = true) { refreshAnalytics(force = true) },
        ))
        // One report per account; the default account's until another is picked.
        val users = engine.analytics.usernames()
        if (users.size > 1) {
            val shown = (r["account"] as? Map<*, *>)?.get("username")?.toString().orEmpty()
            val chips = LinearLayout(this)
            for (u in users) chips.addView(button("@$u", secondary = !u.equals(shown, ignoreCase = true)) { statsUser = u; show("Analytics") },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)).apply { rightMargin = dp(6) })
            col.addView(HorizontalScrollView(this).apply { addView(chips) }, spaced(8))
        }
        analyticsStatus = label("", 13f, muted).also { col.addView(it, spaced(6)) }
        if (r["account"] == null) {
            analyticsStatus?.text = "No numbers yet. Tap Refresh (you need to be logged in on the TikTok tab)."
            return ScrollView(this).apply { addView(col) }
        }
        val acc = r.objAt("account")
        val updated = com.slidemill.core.Db.epochS(acc.str("last_refresh"))?.let { (System.currentTimeMillis() / 1000 - it) / 60 }
        analyticsStatus?.text = "@${acc.str("username")} · updated ${if (updated == null) "?" else if (updated < 1) "just now" else "$updated min ago"} · refreshes itself every 3 hours"

        val totals = r.objAt("totals")
        val last7 = r.objAt("last_7_days")
        val prev7 = r.objAt("previous_7_days")
        fun n(v: Any?) = (v as? Number)?.toLong()
        val gained7 = n(last7["views_gained"])
        val followerDelta = n(acc["followers_gained_7d"])
        col.addView(row(
            tile("Views, last 7 days", gained7?.let { Fmt.compact(it) } ?: "—", gained7?.let { g -> n(prev7["views_gained"])?.let { p -> change(g, p) } } ?: "after a week of tracking"),
            tile("Followers", n(acc["followers"])?.let { Fmt.compact(it) } ?: "—", followerDelta?.let { (if (it >= 0) "+" else "") + Fmt.compact(it) + " this week" } ?: ""),
        ), spaced(14))
        col.addView(row(
            tile("Total views", Fmt.compact(totals.long("views")), "${totals.long("posts")} posts"),
            tile("Avg views / post", Fmt.compact(totals.long("avg_views")), "median ${Fmt.compact(totals.long("median_views"))}"),
        ), spaced(8))
        col.addView(row(
            tile("Engagement", (totals["engagement_rate_pct"] as? Number)?.let { "$it%" } ?: "—", "likes+comments+shares+saves / views"),
            tile("Likes · comments", "${Fmt.compact(totals.long("likes"))} · ${Fmt.compact(totals.long("comments"))}", "${Fmt.compact(totals.long("shares"))} shares · ${Fmt.compact(totals.long("saves"))} saves"),
        ), spaced(8))

        // Views per week
        val weeks = r.objs("weeks")
        val gainedKnown = weeks.all { it["views_gained"] != null }
        col.addView(heading(if (gainedKnown) "Views gained per week" else "Views per week (by the week posts went up)"))
        col.addView(BarChart(this, accent, ink, muted).apply {
            set(weeks.map { n(if (gainedKnown) it["views_gained"] else it["views_on_posts_published"]) ?: 0L },
                weeks.map { it.str("week_of").drop(5).replace("-", "/") })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(170)).apply { topMargin = dp(8) })
        col.addView(label(
            if (gainedKnown) "Each bar: views all your posts gained that week."
            else "Each bar: the views (so far) of the posts published that week. \"Views gained per week\" appears once Slidemill has tracked a full 8 weeks; until then see the posts below.",
            12f, muted,
        ), spaced(4))
        col.addView(label(
            "Last 7 days: ${last7.long("posts_published")} posts" + (gained7?.let { ", +${Fmt.compact(it)} views" } ?: "") +
                " · 7 days before: ${prev7.long("posts_published")} posts" + (n(prev7["views_gained"])?.let { ", +${Fmt.compact(it)} views" } ?: ""),
            13f, ink,
        ), spaced(8))

        // Slideshows vs videos
        val byType = r.objAt("by_type")
        if (byType.isNotEmpty()) {
            col.addView(heading("Slideshows vs videos"))
            for ((type, v0) in byType) {
                @Suppress("UNCHECKED_CAST") val v = v0 as Map<String, Any?>
                col.addView(label("${type.replaceFirstChar { it.uppercase() }}s: ${v.long("posts")} posts · avg ${Fmt.compact(v.long("avg_views"))} views · median ${Fmt.compact(v.long("median_views"))} · ${(v["engagement_rate_pct"] as? Number) ?: "—"}% engagement", 13f, ink), spaced(4))
            }
        }

        // What does better on this account (Claude reads the same thing before writing)
        val ww = r.objAt("what_works")
        col.addView(heading("What works for you"))
        col.addView(label(ww.str("summary"), 13f, ink), spaced(4))
        val by = ww["by"] as? Map<*, *>
        if (by != null) {
            for ((dim, title) in listOf("slide_count" to "Slide count", "posting_time" to "Time of day", "weekday" to "Day", "format" to "Format", "hashtag" to "Hashtags")) {
                @Suppress("UNCHECKED_CAST") val rows = (by[dim] as? List<Map<String, Any?>>).orEmpty().filter { it["enough_data"] == true }
                if (rows.size < 2) continue
                col.addView(label(title, 12f, muted), spaced(8))
                for (b in rows.take(4)) {
                    val x = b["vs_your_median"] as? Number ?: continue
                    col.addView(label(
                        "${if (x.toDouble() >= 1.0) "▲" else "▼"} ${b.str("bucket")}: ${x}× your median (${b.long("posts")} posts)",
                        13f, if (x.toDouble() >= 1.2) good else if (x.toDouble() < 0.8) accent else ink,
                    ))
                }
            }
        }

        // Every post
        col.addView(heading("Posts (newest first)"))
        for (p in r.objs("posts")) col.addView(postCard(p), spaced(6))
        val check = r.objAt("prediction_check")
        col.addView(heading("Is the virality score right?"))
        col.addView(label(check.str("verdict"), 13f, muted), spaced(4))
        return ScrollView(this).apply { addView(col) }
    }

    private fun change(now: Long, before: Long): String = if (before <= 0) "vs 0 the week before" else {
        val pct = Math.round((now - before) * 100.0 / before)
        (if (pct >= 0) "▲ $pct%" else "▼ ${-pct}%") + " vs the week before"
    }

    private fun tile(title: String, value: String, sub: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(panel)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        addView(label(title, 12f, muted))
        addView(label(value, 22f, ink, bold = true))
        if (sub.isNotEmpty()) addView(label(sub, 11f, muted))
    }

    private fun postCard(p: Map<String, Any?>) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(panel)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        val first = p.str("description").lineSequence().firstOrNull().orEmpty().ifEmpty { "(no caption)" }
        addView(label(first, 14f, ink, bold = true).apply { maxLines = 2 })
        val date = com.slidemill.core.Db.epochS(p.str("posted_at"))?.let { whenText(it) } ?: "?"
        val made = (p["slidemill"] as? Map<*, *>)?.let { m -> " · Slidemill" + ((m["predicted_score"] as? Number)?.let { " (scored $it)" } ?: "") } ?: ""
        addView(label("${p.str("type")} · $date$made", 12f, muted))
        addView(label(
            "👁 ${Fmt.compact(p.long("views"))}   ❤ ${Fmt.compact(p.long("likes"))}   💬 ${Fmt.compact(p.long("comments"))}   ↗ ${Fmt.compact(p.long("shares"))}   🔖 ${Fmt.compact(p.long("saves"))}",
            14f, ink,
        ), spaced(6))
        val last24 = (p["views_last_24h"] as? Number)?.toLong()
        addView(label(
            listOfNotNull(
                (p["engagement_rate_pct"] as? Number)?.let { "$it% engagement" },
                (p["views_per_day"] as? Number)?.let { "${Fmt.compact(it.toLong())} views/day" },
                last24?.let { "+${Fmt.compact(it)} in 24h" },
            ).joinToString(" · "),
            12f, if (last24 != null && last24 > 0) good else muted,
        ))
        setOnClickListener { openUrl(p.str("url")) }
    }

    // ---- one draft: preview and POST --------------------------------------------------------------

    private fun preview(id: String) {
        val r = engine.db.tx { get("slideshows", id)?.copyObj() } ?: return show("Slidemill")
        current = "preview"
        tabs.forEach { it.setTextColor(muted) }
        detachAll()
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
                    r.str("account").let { a -> engine.tabs.values.firstOrNull { engine.accounts.cached(it.id).equals(a, ignoreCase = true) }?.let { webSlot = it.id } }
                    show("TikTok")
                    web().get(this).loadUrl("https://www.tiktok.com/tiktokstudio/content")
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
            // Which account it goes to: Claude's pick (or the default), changeable here.
            var target = r.str("account")
            if (engine.tabs.size > 1) {
                // Cached names only: asking a page waits on the main thread, which this is.
                val names = TikTokWeb.slots(this).map { engine.accounts.cached(it) }.filter { it.isNotEmpty() }
                val defaultSlot = engine.store.get("default_account").takeIf { engine.tabs.containsKey(it) } ?: TikTokTab.MAIN
                if (target.isEmpty()) target = engine.accounts.cached(defaultSlot)
                val chips = LinearLayout(this)
                val chipButtons = mutableListOf<Button>()
                for (u in names) chipButtons += button("@$u", secondary = !u.equals(target, ignoreCase = true)) {
                    target = u
                    chipButtons.forEach { b -> b.setBackgroundColor(if (b.text == "@$u") accent else panel) }
                }.also { chips.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)).apply { rightMargin = dp(6) }) }
                col.addView(label("Post to", 12f, muted), spaced(12))
                col.addView(HorizontalScrollView(this).apply { addView(chips) }, spaced(4))
            }
            col.addView(status, spaced(8))
            val buttons = mutableListOf<Button>()
            fun go(at: Long) {
                buttons.forEach { it.isEnabled = false }
                status.setTextColor(ink)
                status.text = (if (at > 0) "Scheduling for ${whenText(at)}…" else "Posting…") +
                    " uploading ${r.strings("rendered").size} slides to TikTok (up to a minute or two)."
                io.execute {
                    val out = runCatching { engine.studio.publish(id, engine.accounts.tab(target), sound.isChecked, at) }
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

    private fun whenText(iso: String): String = com.slidemill.core.Db.epochS(iso)?.let { whenText(it) } ?: iso

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
            val tabs = engine.tabs.values.sortedBy { if (it.id == TikTokTab.MAIN) "" else it.id }
            val lines = tabs.map { t ->
                runCatching {
                    engine.accounts.usernameOf(t).let { if (it.isEmpty()) "✗ ${accountName(t.id)}: not logged in. Open the TikTok tab and log in." else "✓ Logged in as @$it" }
                }.getOrElse { "✗ ${it.message}" }
            }
            val text = lines.joinToString("\n").ifEmpty { "✗ The TikTok tab isn't running." }
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
