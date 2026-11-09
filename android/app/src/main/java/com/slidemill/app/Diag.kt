package com.slidemill.app

import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentLinkedDeque

/** The phone's last problems (TikTok page warnings and errors), so phone_status can say what went wrong. */
object Diag {
    private const val KEEP = 30
    private val lines = ConcurrentLinkedDeque<String>()
    private val clock = DateTimeFormatter.ofPattern("HH:mm:ss")

    fun note(line: String) {
        lines.addLast("${LocalTime.now().format(clock)} ${line.take(200)}")
        while (lines.size > KEEP) lines.pollFirst()
    }

    fun recent(): List<String> = lines.toList()
}
