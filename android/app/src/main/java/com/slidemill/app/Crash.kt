package com.slidemill.app

import android.app.Activity
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/** Installs the crash catcher before anything else in the app runs. */
class SlidemillApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Crash.install(this)
    }
}

/**
 * Crash catcher: a crash anywhere in the app is written to a file, and the next launch shows it
 * (with a Copy button) instead of starting the engine again, so it can be reported.
 */
object Crash {
    private fun file(context: Context) = File(context.filesDir, "last-crash.txt")

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
                val version = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull()
                file(app).writeText(
                    "Slidemill $version on Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), " +
                        "${Build.MANUFACTURER} ${Build.MODEL}\nThread: ${thread.name}\n\n$trace",
                )
            }
            previous?.uncaughtException(thread, error)
        }
    }

    fun pending(context: Context): String? = file(context).takeIf { it.isFile }?.readText()

    fun clear(context: Context) {
        file(context).delete()
    }

    /** The crash report screen. `retry` starts the app normally. */
    fun show(activity: Activity, report: String, retry: () -> Unit) {
        val pad = (16 * activity.resources.displayMetrics.density).toInt()
        val text = TextView(activity).apply {
            this.text = report
            setTextColor(Color.parseColor("#ECEDF0"))
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
        }
        val copy = Button(activity).apply {
            this.text = "Copy error"
            setOnClickListener {
                activity.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Slidemill crash", report))
                Toast.makeText(activity, "Copied. Paste it to Claude.", Toast.LENGTH_SHORT).show()
            }
        }
        val again = Button(activity).apply {
            this.text = "Open Slidemill"
            setOnClickListener {
                clear(activity)
                retry()
            }
        }
        activity.setContentView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0E0F12"))
            setPadding(pad, pad * 2, pad, pad)
            addView(TextView(activity).apply {
                this.text = "Slidemill crashed last time"
                setTextColor(Color.parseColor("#ECEDF0"))
                textSize = 20f
                setTypeface(typeface, Typeface.BOLD)
            })
            addView(TextView(activity).apply {
                this.text = "Copy the error below and send it to Claude. Then tap Open Slidemill to try again."
                setTextColor(Color.parseColor("#9097A3"))
                setPadding(0, pad / 2, 0, pad / 2)
            })
            addView(LinearLayout(activity).apply { addView(copy); addView(again) })
            addView(ScrollView(activity).apply { addView(text) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        })
    }
}
