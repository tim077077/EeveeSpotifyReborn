package com.slidemill.core

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

/**
 * In-process replacement for APScheduler (port of app/scheduler.py), driven by [tick] about every
 * 30 seconds from the engine. Schedules are cron in each automation's own timezone. For simple
 * daily/weekly times, generation starts LEAD_MINUTES early and the post is queued for the exact time.
 * Missed runs within 15 minutes still fire (the phone may have dozed); older ones are skipped.
 */
class Scheduler(
    private val db: Db,
    private val platform: Platform,
    private val pipeline: Pipeline,
    private val onPeriodic: (Instant) -> Unit = {},
) {
    companion object {
        val MISFIRE_GRACE: Duration = Duration.ofMinutes(15)
    }

    private val running = ConcurrentHashMap.newKeySet<String>()

    fun zoneOf(automation: Map<String, Any?>): ZoneId = Timing.zone(automation.str("timezone").ifEmpty { platform.defaultTimezone() })

    private fun lastTick(): Instant? = db.tx { get("state", "scheduler")?.get("last_tick") as? Long }?.let { Instant.ofEpochMilli(it) }

    private fun saveTick(now: Instant) = db.tx {
        val row = get("state", "scheduler") ?: insert("state", obj("id" to "scheduler"))
        row["last_tick"] = now.toEpochMilli()
    }

    /** Fire every schedule whose (early) start time passed since the last tick. */
    fun tick(now: Instant = Db.clock()) {
        val since = lastTick()?.takeIf { it.isBefore(now) }?.let { maxOf(it, now.minus(MISFIRE_GRACE)) } ?: now.minusSeconds(1)
        saveTick(now)
        val automations = db.tx { where("automations") { !it.bool("paused") }.map { it.copyObj() } }
        for (a in automations) {
            val zone = zoneOf(a)
            for (sched in a.objs("schedules")) {
                val cronExpr = sched.str("cron")
                val early = Cron.shiftEarlier(cronExpr, Cron.LEAD_MINUTES)
                val trigger = runCatching { Cron.parse(early ?: cronExpr) }.getOrNull() ?: continue
                val fire = trigger.between(since, now, zone, limit = 1).firstOrNull() ?: continue
                val publishAt = if (early != null) {
                    runCatching { Cron.parse(cronExpr).next(fire, zone) }.getOrNull()
                        ?.takeIf { Duration.between(fire, it) <= Duration.ofMinutes((Cron.LEAD_MINUTES + 5).toLong()) }
                } else null
                val key = sched.str("id")
                if (!running.add(key)) continue // coalesce: one run per schedule at a time
                pipeline.executor.submit {
                    try {
                        pipeline.runAutomation(a.str("id"), cronId = key, publishAt = publishAt)
                    } catch (e: Exception) {
                        platform.log("scheduled run failed for automation ${a.str("id")}: $e")
                    } finally {
                        running.remove(key)
                    }
                }
            }
        }
        onPeriodic(now)
    }

    /** Upcoming posting times (not generation start times) for an automation. */
    fun nextRuns(automation: Map<String, Any?>, count: Int = 5, now: Instant = Db.clock()): List<String> {
        val zone = zoneOf(automation)
        val out = mutableListOf<Instant>()
        for (sched in automation.objs("schedules")) {
            val cron = runCatching { Cron.parse(sched.str("cron")) }.getOrNull() ?: continue
            var t = now
            repeat(count) {
                val n = cron.next(t, zone) ?: return@repeat
                out += n
                t = n
            }
        }
        return out.sorted().take(count).map { Db.iso(it) }
    }
}
