package com.slidemill.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlarmManager
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.concurrent.Executors

/** Three tabs: the Slidemill dashboard (served by the app itself), tiktok.com (log in once), and the phone poster. */
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
    private var fileCallback: ValueCallback<Array<Uri>>? = null

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
        tabs = listOf("Dashboard", "TikTok", "Phone").map { name ->
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
        EngineService.start(this) // the engine's clock: schedules run even with the app closed
        show(if (intent?.getStringExtra("tab") == "TikTok") "TikTok" else "Dashboard")
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
            else -> content.addView(phoneView())
        }
    }

    // ---- Dashboard ----------------------------------------------------------------------------

    /** Called by the dashboard page (window.SlidemillApp). */
    inner class AppBridge {
        @JavascriptInterface
        fun openTikTok() = main.post { show("TikTok") }

        @JavascriptInterface
        fun saveBackup() {
            io.execute {
                val msg = runCatching {
                    val bytes = Slidemill.engine(this@MainActivity).backup()
                    val name = "slidemill-backup-${java.time.LocalDate.now()}.zip"
                    saveToDownloads(name, "application/zip", bytes)
                    "Saved $name to Downloads"
                }.getOrElse { "Backup failed: ${it.message}" }
                main.post { toast(msg) }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun dashboardView(): View {
        dashboard?.let { (it.parent as? ViewGroup)?.removeView(it); return it }
        val web = WebView(this).apply {
            setBackgroundColor(bg)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            addJavascriptInterface(AppBridge(), "SlidemillApp")
            webViewClient = object : WebViewClient() {
                // The dashboard stays inside the app; links to TikTok or elsewhere open outside.
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (request.url.host == "127.0.0.1") return false
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, request.url)) }
                    return true
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                    fileCallback?.onReceiveValue(null)
                    fileCallback = callback
                    val pick = params.createIntent().apply {
                        if (params.mode == FileChooserParams.MODE_OPEN_MULTIPLE) putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    }
                    return runCatching { startActivityForResult(pick, 42) }.map { true }.getOrElse {
                        fileCallback = null
                        false
                    }
                }
            }
            setDownloadListener { url, _, _, mime, _ -> saveMedia(url, mime) }
        }
        io.execute {
            val url = runCatching { Slidemill.dashboardUrl(this) }.getOrNull()
            main.post { if (url != null) web.loadUrl(url) else toast("Slidemill couldn't start its engine") }
        }
        dashboard = web
        return web
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 42) return
        val result = if (resultCode != RESULT_OK || data == null) null else {
            data.clipData?.let { clip -> Array(clip.itemCount) { clip.getItemAt(it).uri } } ?: data.data?.let { arrayOf(it) }
        }
        fileCallback?.onReceiveValue(result)
        fileCallback = null
    }

    /** "Download" links in the dashboard (slides, MP4) go to the phone's gallery. */
    private fun saveMedia(url: String, mime: String?) {
        val path = Uri.parse(url).path.orEmpty()
        if (!path.startsWith("/media/")) return toast("Can't save that here")
        io.execute {
            val msg = runCatching {
                val e = Slidemill.engine(this)
                val file = File(e.mediaDir, path.removePrefix("/media/")).canonicalFile
                require(file.path.startsWith(e.mediaDir.canonicalPath) && file.isFile) { "file not found" }
                val video = file.name.endsWith(".mp4")
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "slidemill_${file.parentFile?.name}_${file.name}")
                    put(MediaStore.MediaColumns.MIME_TYPE, if (video) "video/mp4" else (mime ?: "image/jpeg"))
                    if (Build.VERSION.SDK_INT >= 29) {
                        put(MediaStore.MediaColumns.RELATIVE_PATH, (if (video) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES) + "/Slidemill")
                    }
                }
                val collection = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                val uri = contentResolver.insert(collection, values) ?: error("gallery refused the file")
                contentResolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
                "Saved to your gallery (Slidemill album)"
            }.getOrElse { "Couldn't save: ${it.message}" }
            main.post { toast(msg) }
        }
    }

    private fun saveToDownloads(name: String, mime: String, bytes: ByteArray) {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("Downloads refused the file")
            contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
        } else {
            @Suppress("DEPRECATION")
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            File(dir, name).writeBytes(bytes)
        }
    }

    // ---- Phone (poster) -------------------------------------------------------------------------

    private fun phoneView(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }
        col.addView(label("This phone", 22f, ink, bold = true))
        col.addView(label(
            "Slidemill runs entirely on this phone: it writes, renders and schedules your slideshows, and posts them from the TikTok tab's logged-in session.",
            13f, muted,
        ))

        statusView = label(statusText(), 14f, ink).apply {
            setBackgroundColor(panel)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        col.addView(statusView, spaced())
        main.post(statusTicker)

        col.addView(Switch(this).apply {
            text = "Post automatically from this phone"
            setTextColor(ink)
            isChecked = Prefs.autoPost
            setOnCheckedChangeListener { _, on ->
                Prefs.autoPost = on
                EngineService.checkNow(this@MainActivity)
            }
        }, spaced())
        col.addView(button("Check the queue now") { EngineService.checkNow(this) }, spaced())

        col.addView(label("Keep it running", 12f, muted, bold = true), spaced(18))
        col.addView(label(
            "Schedules only run while Android lets Slidemill work in the background. Set battery use to \"Unrestricted\", keep " +
                "the notification, and allow exact alarms so posts go out on time. Log in on the TikTok tab once " +
                "(email/username + password; Google login doesn't work inside apps).",
            13f, muted,
        ))
        col.addView(button("Open battery settings") {
            runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }, spaced())
        if (Build.VERSION.SDK_INT >= 31 && !getSystemService(AlarmManager::class.java).canScheduleExactAlarms()) {
            col.addView(button("Allow exact alarms") {
                runCatching { startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName"))) }
            }, spaced())
        }
        col.addView(label(
            "\"Post by itself\" uses TikTok's private web endpoints, which is against TikTok's terms: keep the daily limit low " +
                "(Dashboard → Settings) and use an account you can afford to lose. \"Send to the TikTok app\" just opens TikTok's " +
                "own editor with your slides at the planned time; you add a sound and post.",
            12f, muted,
        ), spaced(18))
        return ScrollView(this).apply { addView(col) }
    }

    private fun statusText(): String {
        val who = EngineService.username.takeIf { it.isNotEmpty() }?.let { "TikTok: @$it\n" } ?: "TikTok: not logged in yet\n"
        return who + if (EngineService.running) EngineService.status else "Starting…"
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

    private fun button(s: String, onClick: () -> Unit) = Button(this).apply {
        text = s
        isAllCaps = false
        setTextColor(Color.WHITE)
        setBackgroundColor(accent)
        setOnClickListener { onClick() }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
