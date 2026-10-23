package com.slidemill.core

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Standard 5-field cron (minute hour day month weekday) evaluated in an automation's own timezone
 * (port of app/scheduler.py). Day of week follows cron: Sunday = 0 (7 also means Sunday).
 * Day-of-month and day-of-week must both match, as in the Python server's scheduler.
 */
class Cron private constructor(
    val expr: String,
    private val minutes: Set<Int>,
    private val hours: Set<Int>,
    private val days: Set<Int>,
    private val months: Set<Int>,
    private val dows: Set<Int>?, // 0-6, Sunday = 0; null = every day
) {
    companion object {
        const val LEAD_MINUTES = 15
        val DOW_NAMES = listOf("sun", "mon", "tue", "wed", "thu", "fri", "sat")
        private val MONTH_NAMES = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

        fun parse(expr: String): Cron {
            val fields = expr.trim().split(Regex("\\s+"))
            if (fields.size != 5) throw IllegalArgumentException("cron needs 5 fields: minute hour day month weekday")
            val (m, h, d, mo, dow) = fields
            return Cron(
                fields.joinToString(" "),
                field(m, 0, 59, null, "minute"),
                field(h, 0, 23, null, "hour"),
                field(d, 1, 31, null, "day"),
                field(mo, 1, 12, MONTH_NAMES, "month"),
                parseDow(dow),
            )
        }

        /** Raise IllegalArgumentException for an invalid expression; returns it normalized. */
        fun validate(expr: String): String = parse(expr).expr

        private fun field(raw: String, lo: Int, hi: Int, names: List<String>?, what: String): Set<Int> {
            val out = sortedSetOf<Int>()
            for (part0 in raw.lowercase().split(",")) {
                var part = part0
                var step = 1
                if ('/' in part) {
                    step = part.substringAfter('/').toIntOrNull() ?: throw IllegalArgumentException("bad step in $what: $raw")
                    if (step < 1) throw IllegalArgumentException("bad step in $what: $raw")
                    part = part.substringBefore('/')
                }
                val (a, b) = when {
                    part == "*" || part.isEmpty() -> lo to hi
                    '-' in part -> value(part.substringBefore('-'), names, lo, hi, what) to value(part.substringAfter('-'), names, lo, hi, what)
                    else -> value(part, names, lo, hi, what).let { v -> v to if (part0.contains('/')) hi else v }
                }
                if (b < a) throw IllegalArgumentException("bad range in $what: $raw")
                for (v in a..b step step) out += v
            }
            return out
        }

        private fun value(token: String, names: List<String>?, lo: Int, hi: Int, what: String): Int {
            names?.indexOf(token.take(3))?.takeIf { it >= 0 }?.let { return it + lo }
            val v = token.toIntOrNull() ?: throw IllegalArgumentException("bad $what value: $token")
            if (v < lo || v > hi) throw IllegalArgumentException("$what out of range: $token")
            return v
        }

        /** Standard cron day-of-week field -> set of 0-6 (Sunday = 0); null means every day. */
        fun parseDow(raw: String): Set<Int>? {
            val f = raw.trim().lowercase()
            if (f == "*" || f == "?") return null
            val days = sortedSetOf<Int>()
            for (part0 in f.split(",")) {
                var part = part0
                var step = 1
                if ('/' in part) {
                    step = part.substringAfter('/').toIntOrNull()?.takeIf { it > 0 } ?: throw IllegalArgumentException("bad step: $raw")
                    part = part.substringBefore('/')
                }
                val (lo, hi) = when {
                    part == "*" || part.isEmpty() -> 0 to 6
                    '-' in part -> dowValue(part.substringBefore('-')) to dowValue(part.substringAfter('-'))
                    else -> dowValue(part).let { it to it }
                }
                val rng = if (hi < lo) (lo..6).toList() + (0..hi).toList() else (lo..hi).toList() // wrap-around like fri-mon
                rng.filterIndexed { i, _ -> i % step == 0 }.forEach { days += it % 7 }
            }
            return days
        }

        private fun dowValue(token: String): Int {
            val t = token.trim()
            DOW_NAMES.indexOf(t.take(3)).takeIf { it >= 0 }?.let { return it }
            val v = t.toIntOrNull() ?: throw IllegalArgumentException("bad day of week: $token")
            if (v !in 0..7) throw IllegalArgumentException("day of week out of range: $token")
            return v % 7
        }

        /** '30 9 * * 1-5' shifted 15 min earlier -> '15 9 * * 1,2,3,4,5'. Null if not a simple time. */
        fun shiftEarlier(expr: String, minutes: Int): String? {
            val f = expr.trim().split(Regex("\\s+"))
            if (f.size != 5 || !f[0].all { it.isDigit() } || !f[1].all { it.isDigit() } || f[0].isEmpty() || f[1].isEmpty()) return null
            if (f[2] != "*" || f[3] != "*") return null
            var total = f[1].toInt() * 60 + f[0].toInt() - minutes
            var days = parseDow(f[4])
            if (total < 0) {
                total += 24 * 60
                days = days?.map { Math.floorMod(it - 1, 7) }?.toSortedSet()
            }
            val dow = days?.sorted()?.joinToString(",") ?: "*"
            return "${total % 60} ${total / 60} * * $dow"
        }
    }

    private fun dayMatches(date: LocalDate): Boolean =
        date.monthValue in months && date.dayOfMonth in days && (dows == null || date.dayOfWeek.value % 7 in dows)

    /** First fire time strictly after `after`, in `zone`; null if none within ~4 years. */
    fun next(after: Instant, zone: ZoneId): Instant? {
        var date = after.atZone(zone).toLocalDate()
        repeat(366 * 4 + 1) {
            if (dayMatches(date)) {
                for (h in hours) for (m in minutes) {
                    val t = ZonedDateTime.ofLocal(LocalDateTime.of(date, java.time.LocalTime.of(h, m)), zone, null).toInstant()
                    if (t.isAfter(after)) return t
                }
            }
            date = date.plusDays(1)
        }
        return null
    }

    /** Fire times in (from, to]. */
    fun between(from: Instant, to: Instant, zone: ZoneId, limit: Int = 50): List<Instant> {
        val out = mutableListOf<Instant>()
        var t = from
        while (out.size < limit) {
            val n = next(t, zone) ?: break
            if (n.isAfter(to)) break
            out += n
            t = n
        }
        return out
    }
}
