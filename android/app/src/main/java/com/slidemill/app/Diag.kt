package com.slidemill.app

import java.io.File
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * The phone's last problems (TikTok page warnings and errors, unanswered requests, app restarts), so
 * phone_status can say what went wrong. Kept in a file too: Android can kill and restart the app in
 * the middle of a job, and an in-memory list would come back empty ("none") right after the failure.
 */
object Diag {
    private const val KEEP = 40
    private val lines = ConcurrentLinkedDeque<String>()
    private val clock = DateTimeFormatter.ofPattern("HH:mm:ss")
    @Volatile private var file: File? = null

    fun attach(f: File) {
        if (file != null) return
        file = f
        runCatching { f.readLines().takeLast(KEEP).forEach { lines.addLast(it) } }
        note("app started (process ${android.os.Process.myPid()})")
    }

    fun note(line: String) {
        lines.addLast("${LocalTime.now().format(clock)} ${line.take(300)}")
        while (lines.size > KEEP) lines.pollFirst()
        file?.let { f -> runCatching { f.writeText(lines.joinToString("\n")) } }
    }

    fun recent(): List<String> = lines.toList()

    /**
     * Why Android ended the app's previous process (Android 11+ keeps the reason): a RAM cleaner or
     * "device care" shows as user-requested, memory pressure as low memory, Slidemill's own idle close
     * as a cached process killed. Logged once per start, so the 15-minute restarts explain themselves.
     */
    fun lastExit(context: android.content.Context) {
        if (android.os.Build.VERSION.SDK_INT < 30) return
        runCatching {
            val am = context.getSystemService(android.app.ActivityManager::class.java)
            val e = am.getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull() ?: return
            val reason = when (e.reason) {
                android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> "low memory"
                android.app.ApplicationExitInfo.REASON_USER_REQUESTED -> "user requested (force stop, RAM cleaner, device care or swiping it away)"
                android.app.ApplicationExitInfo.REASON_USER_STOPPED -> "user stopped it"
                android.app.ApplicationExitInfo.REASON_CRASH -> "crash"
                android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash"
                android.app.ApplicationExitInfo.REASON_ANR -> "not responding (ANR)"
                android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "too much CPU or battery in the background"
                android.app.ApplicationExitInfo.REASON_SIGNALED -> "killed by signal ${e.status} (often the system's memory killer or a vendor app killer)"
                android.app.ApplicationExitInfo.REASON_OTHER -> "other (often the vendor's battery or app manager)"
                android.app.ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "a process it depends on died"
                android.app.ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "a permission changed"
                android.app.ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "failed to start"
                android.app.ApplicationExitInfo.REASON_EXIT_SELF -> "it exited by itself"
                else -> "reason ${e.reason}"
            }
            val state = when {
                e.importance <= 100 -> "on screen"
                e.importance <= 125 -> "running its background service"
                e.importance <= 300 -> "in the background (service)"
                else -> "idle in the background (no service)"
            }
            val ago = (System.currentTimeMillis() - e.timestamp) / 60_000
            note("previous run ended ${ago} min ago: $reason, while $state, using ${e.pss / 1024} MB" +
                (e.description?.takeIf { it.isNotBlank() }?.let { " [${it.take(120)}]" } ?: ""))
        }
    }
}
