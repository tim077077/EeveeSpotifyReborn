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
}
