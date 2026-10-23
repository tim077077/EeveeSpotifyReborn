package com.slidemill.core

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.exp
import kotlin.math.ln

/**
 * Best posting times per target country (port of app/timing.py).
 *
 * Score for posting at a local time = expected audience activity over the next ~2 hours, from a
 * workday/rest-day baseline curve, blended with what your library and your own posts show.
 */
class Timing(private val db: Db) {
    data class Country(
        val code: String,
        val name: String,
        val zones: List<Pair<String, Double>>,
        val weekend: List<String> = listOf("sat", "sun"),
        val eveningShift: Int = 0,
    ) {
        val primaryZone: String get() = zones.maxByOrNull { it.second }!!.first
    }

    /** Per (rest-day?, local hour) performance multipliers learned from real posts. */
    class Evidence {
        private val logSum = HashMap<Pair<Boolean, Int>, Double>()
        private val count = HashMap<Pair<Boolean, Int>, Int>()
        var posts = 0
            private set

        fun add(rest: Boolean, hour: Int, ratio0: Double) {
            val ratio = ratio0.coerceIn(0.1, 10.0)
            val k = rest to hour
            logSum[k] = (logSum[k] ?: 0.0) + ln(ratio)
            count[k] = (count[k] ?: 0) + 1
            posts++
        }

        fun multiplier(rest: Boolean, hour: Int, priorStrength: Double): Double {
            val n = count[rest to hour] ?: return 1.0
            return exp(logSum.getValue(rest to hour) / (n + priorStrength))
        }
    }

    companion object {
        val DAYS = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")
        // Standard cron day-of-week numbers for DAYS (Sunday = 0).
        val CRON_DOW = mapOf("mon" to 1, "tue" to 2, "wed" to 3, "thu" to 4, "fri" to 5, "sat" to 6, "sun" to 0)

        // Relative audience activity by local hour (0-23).
        val WORKDAY = doubleArrayOf(0.45, 0.30, 0.18, 0.10, 0.07, 0.08, 0.20, 0.45, 0.55, 0.45, 0.40, 0.45,
            0.62, 0.58, 0.48, 0.50, 0.58, 0.66, 0.74, 0.85, 0.95, 1.00, 0.88, 0.65)
        val RESTDAY = doubleArrayOf(0.55, 0.40, 0.25, 0.14, 0.08, 0.07, 0.10, 0.20, 0.35, 0.52, 0.65, 0.70,
            0.72, 0.68, 0.66, 0.66, 0.68, 0.72, 0.78, 0.88, 0.97, 1.00, 0.90, 0.70)
        // How a post's first hours are weighted: now, +1h, +2h.
        val RAMP = doubleArrayOf(0.5, 0.35, 0.15)

        private val FRI_SAT = listOf("fri", "sat")
        private fun c(code: String, name: String, zone: String, weekend: List<String> = listOf("sat", "sun"), shift: Int = 0) =
            Country(code, name, listOf(zone to 1.0), weekend, shift)
        private fun cz(code: String, name: String, vararg zones: Pair<String, Double>, shift: Int = 0) =
            Country(code, name, zones.toList(), listOf("sat", "sun"), shift)

        val COUNTRIES: Map<String, Country> = listOf(
            cz("US", "United States", "America/New_York" to .47, "America/Chicago" to .29, "America/Denver" to .07, "America/Los_Angeles" to .17),
            cz("CA", "Canada", "America/Toronto" to .62, "America/Winnipeg" to .07, "America/Edmonton" to .12, "America/Vancouver" to .14, "America/Halifax" to .05),
            cz("MX", "Mexico", "America/Mexico_City" to .80, "America/Tijuana" to .07, "America/Mazatlan" to .08, "America/Cancun" to .05, shift = 1),
            cz("BR", "Brazil", "America/Sao_Paulo" to .93, "America/Manaus" to .05, "America/Rio_Branco" to .02, shift = 1),
            c("AR", "Argentina", "America/Argentina/Buenos_Aires", shift = 1),
            c("CL", "Chile", "America/Santiago", shift = 1),
            c("CO", "Colombia", "America/Bogota"),
            c("PE", "Peru", "America/Lima"),
            c("GB", "United Kingdom", "Europe/London"),
            c("IE", "Ireland", "Europe/Dublin"),
            c("DE", "Germany", "Europe/Berlin"),
            c("AT", "Austria", "Europe/Vienna"),
            c("CH", "Switzerland", "Europe/Zurich"),
            c("FR", "France", "Europe/Paris"),
            c("BE", "Belgium", "Europe/Brussels"),
            c("NL", "Netherlands", "Europe/Amsterdam"),
            c("ES", "Spain", "Europe/Madrid", shift = 1),
            c("PT", "Portugal", "Europe/Lisbon"),
            c("IT", "Italy", "Europe/Rome"),
            c("GR", "Greece", "Europe/Athens", shift = 1),
            c("PL", "Poland", "Europe/Warsaw"),
            c("RO", "Romania", "Europe/Bucharest"),
            c("MD", "Moldova", "Europe/Chisinau"),
            c("HU", "Hungary", "Europe/Budapest"),
            c("CZ", "Czechia", "Europe/Prague"),
            c("SE", "Sweden", "Europe/Stockholm"),
            c("NO", "Norway", "Europe/Oslo"),
            c("DK", "Denmark", "Europe/Copenhagen"),
            c("FI", "Finland", "Europe/Helsinki"),
            c("UA", "Ukraine", "Europe/Kyiv"),
            c("TR", "Turkey", "Europe/Istanbul", shift = 1),
            c("IL", "Israel", "Asia/Jerusalem", FRI_SAT),
            c("SA", "Saudi Arabia", "Asia/Riyadh", FRI_SAT, shift = 2),
            c("AE", "United Arab Emirates", "Asia/Dubai", shift = 1),
            c("EG", "Egypt", "Africa/Cairo", FRI_SAT, shift = 1),
            c("MA", "Morocco", "Africa/Casablanca"),
            c("NG", "Nigeria", "Africa/Lagos"),
            c("KE", "Kenya", "Africa/Nairobi"),
            c("ZA", "South Africa", "Africa/Johannesburg"),
            c("PK", "Pakistan", "Asia/Karachi", shift = 1),
            c("BD", "Bangladesh", "Asia/Dhaka"),
            cz("ID", "Indonesia", "Asia/Jakarta" to .80, "Asia/Makassar" to .17, "Asia/Jayapura" to .03),
            c("MY", "Malaysia", "Asia/Kuala_Lumpur"),
            c("SG", "Singapore", "Asia/Singapore"),
            c("PH", "Philippines", "Asia/Manila"),
            c("TH", "Thailand", "Asia/Bangkok"),
            c("VN", "Vietnam", "Asia/Ho_Chi_Minh"),
            c("JP", "Japan", "Asia/Tokyo"),
            c("KR", "South Korea", "Asia/Seoul"),
            c("TW", "Taiwan", "Asia/Taipei"),
            cz("AU", "Australia", "Australia/Sydney" to .58, "Australia/Brisbane" to .20, "Australia/Perth" to .11, "Australia/Adelaide" to .08, "Australia/Darwin" to .03),
            c("NZ", "New Zealand", "Pacific/Auckland"),
        ).associateBy { it.code }

        private fun activity(country: Country, rest: Boolean, hour: Double): Double {
            val curve = if (rest) RESTDAY else WORKDAY
            val h = ((hour - country.eveningShift) % 24 + 24) % 24
            val lo = h.toInt()
            val frac = h - lo
            return curve[lo] * (1 - frac) + curve[(lo + 1) % 24] * frac
        }

        /** ZoneId.of with fallbacks for zone names older Android tz databases don't know yet. */
        fun zone(id: String): ZoneId = try {
            ZoneId.of(id)
        } catch (e: Exception) {
            ZoneId.of(mapOf("Europe/Kyiv" to "Europe/Kiev").getOrElse(id) { "UTC" })
        }

        private fun dayIndex(t: LocalDateTime) = t.dayOfWeek.value - 1

        /** scores[day][slot] for posting at that local time in the country's primary zone. */
        fun scoreGrid(country: Country, evidence: List<Pair<Evidence, Double>> = emptyList(), stepMinutes: Int = 30): List<DoubleArray> {
            val primary = zone(country.primaryZone)
            // Monday 00:00 of a fixed reference week (avoids DST-transition edge cases).
            val anchor = LocalDateTime.of(2026, 1, 5, 0, 0)
            val slots = 24 * 60 / stepMinutes
            val zones = country.zones.map { zone(it.first) to it.second }
            return (0 until 7).map { d ->
                DoubleArray(slots) { s ->
                    val t = anchor.plusDays(d.toLong()).plusMinutes((s * stepMinutes).toLong())
                    val instant = ZonedDateTime.of(t, primary).toInstant()
                    var total = 0.0
                    for ((zone, weight) in zones) {
                        val local = instant.atZone(zone).toLocalDateTime()
                        for (k in RAMP.indices) {
                            val lt = local.plusHours(k.toLong())
                            val rest = DAYS[dayIndex(lt)] in country.weekend
                            total += weight * RAMP[k] * activity(country, rest, lt.hour + lt.minute / 60.0)
                        }
                    }
                    val restP = DAYS[dayIndex(t)] in country.weekend
                    for ((ev, strength) in evidence) total *= ev.multiplier(restP, t.hour, strength)
                    total
                }
            }
        }

        /** Greedy top slots for one day, spaced so posts don't cannibalize each other. */
        fun pickSlots(row: DoubleArray, n: Int, stepMinutes: Int = 30): List<Int> {
            val gap = maxOf(2.0, minOf(3.0, 16.0 / maxOf(n, 1)))
            val minSep = (gap * 60 / stepMinutes).toInt()
            val chosen = mutableListOf<Int>()
            for (idx in row.indices.sortedBy { -row[it] }) {
                if (chosen.all { c -> minOf(Math.abs(idx - c), row.size - Math.abs(idx - c)) >= minSep }) chosen += idx
                if (chosen.size == n) break
            }
            return chosen.sorted()
        }

        fun median(values: List<Long>): Double {
            val s = values.sorted()
            if (s.isEmpty()) return 0.0
            return if (s.size % 2 == 1) s[s.size / 2].toDouble() else (s[s.size / 2 - 1] + s[s.size / 2]) / 2.0
        }

        fun countryList(): List<Obj> = COUNTRIES.values.sortedBy { it.name }.map {
            obj("code" to it.code, "name" to it.name, "timezone" to it.primaryZone, "multi_zone" to (it.zones.size > 1))
        }
    }

    /** Views-by-posting-hour for scraped posts from accounts in this country. */
    fun libraryEvidence(country: Country): Evidence {
        val ev = Evidence()
        val tz = zone(country.primaryZone)
        db.tx {
            val profiles = where("library_profiles") { it.str("region") == country.code }.map { it.str("id") }.toSet()
            val byProfile = where("library_posts") { it.str("profile_id") in profiles && it["posted_at"] != null && it.long("views") > 0 }
                .groupBy { it.str("profile_id") }
            for (posts in byProfile.values) {
                if (posts.size < 3) continue
                val med = median(posts.map { it.long("views") }).takeIf { it != 0.0 } ?: 1.0
                for (x in posts) {
                    val local = java.time.Instant.ofEpochMilli(x.long("posted_at")).atZone(tz).toLocalDateTime()
                    ev.add(DAYS[dayIndex(local)] in country.weekend, local.hour, x.long("views") / med)
                }
            }
        }
        return ev
    }

    /** Your own published posts: the given TikTok account's, or the phone's own (browser) posts. */
    fun ownEvidence(country: Country, tiktokAccountId: String?): Evidence {
        val ev = Evidence()
        val tz = zone(country.primaryZone)
        db.tx {
            val posts = where("posts") {
                it.str("status") == "published" && it.long("views") > 0 &&
                    (if (tiktokAccountId != null) it.str("tiktok_account_id") == tiktokAccountId else it.str("post_mode") == "BROWSER")
            }
            if (posts.size < 3) return@tx
            val med = median(posts.map { it.long("views") }).takeIf { it != 0.0 } ?: 1.0
            for (p in posts) {
                val local = java.time.Instant.ofEpochMilli(p.long("created_at")).atZone(tz).toLocalDateTime()
                ev.add(DAYS[dayIndex(local)] in country.weekend, local.hour, p.long("views") / med)
            }
        }
        return ev
    }

    /** Throws NoSuchElementException for an unsupported country. */
    fun bestTimes(code: String, postsPerDay0: Int = 3, tiktokAccountId: String? = null): Obj {
        val country = COUNTRIES[code.uppercase()] ?: throw NoSuchElementException(code)
        val postsPerDay = postsPerDay0.coerceIn(1, 6)
        val lib = libraryEvidence(country)
        val own = ownEvidence(country, tiktokAccountId)
        val grid = scoreGrid(country, listOf(lib to 8.0, own to 5.0))
        val hourly = grid.map { row -> DoubleArray(row.size / 2) { row[it * 2] } }
        val lo = hourly.minOf { r -> r.minOrNull()!! }
        val hi = hourly.maxOf { r -> r.maxOrNull()!! }
        val span = (hi - lo).takeIf { it != 0.0 } ?: 1.0

        val schedule = LinkedHashMap<String, Any?>()
        grid.forEachIndexed { d, row ->
            schedule[DAYS[d]] = pickSlots(row, postsPerDay).map { i -> "%02d:%02d".format(i * 30 / 60, i * 30 % 60) }.toMutableList()
        }
        // Group identical times across days into cron lines (standard cron: Sunday = 0).
        val byTime = sortedMapOf<String, MutableList<String>>()
        for ((day, times) in schedule) for (t in times as List<*>) byTime.getOrPut(t as String) { mutableListOf() } += day
        val crons = byTime.map { (t, days) ->
            val (h, m) = t.split(":")
            val dows = days.sortedBy { DAYS.indexOf(it) }.joinToString(",") { CRON_DOW.getValue(it).toString() }
            "${m.toInt()} ${h.toInt()} * * ${if (days.size == 7) "*" else dows}"
        }

        val sources = mutableListOf<Any?>("engagement baseline")
        if (lib.posts > 0) sources += "${lib.posts} library posts from ${country.name} accounts"
        if (own.posts > 0) sources += "${own.posts} of your published posts"
        return obj(
            "country" to country.code,
            "name" to country.name,
            "timezone" to country.primaryZone,
            "zones" to country.zones.map { obj("zone" to it.first, "share" to it.second) }.toMutableList(),
            "weekend" to country.weekend.toMutableList(),
            "posts_per_day" to postsPerDay,
            "schedule" to schedule,
            "crons" to crons.toMutableList(),
            "heatmap" to hourly.map { row -> row.map { Math.round((it - lo) / span * 1000) / 1000.0 }.toMutableList() }.toMutableList(),
            "sources" to sources,
            "confidence" to when {
                own.posts >= 30 -> "high"
                lib.posts + own.posts >= 40 -> "medium"
                else -> "baseline"
            },
        )
    }
}
