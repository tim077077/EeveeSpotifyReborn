"""Cron scheduling for automations.

Schedules are standard 5-field cron in each automation's own timezone (so "Post at 19:00 for
Germany" means 19:00 Berlin time, whatever the server's clock says). For simple daily/weekly
times, generation starts LEAD_MINUTES early and the post goes out on time.

Note: APScheduler 3 numbers days of week from Monday = 0, unlike cron (Sunday = 0); this module
parses the day-of-week field itself so "1-5" means Monday-Friday as everyone expects.
"""

from __future__ import annotations

import logging
import re
from datetime import datetime, timedelta, timezone

from apscheduler.schedulers.background import BackgroundScheduler
from apscheduler.triggers.cron import CronTrigger
from sqlalchemy import select

from . import db, library, pipeline
from .config import settings

log = logging.getLogger("slidemill.scheduler")

scheduler = BackgroundScheduler(timezone=settings.timezone)
LEAD_MINUTES = 15
DOW_NAMES = ["sun", "mon", "tue", "wed", "thu", "fri", "sat"]
_DOW_ALIASES = {n: i for i, n in enumerate(DOW_NAMES)}

# schedule id -> (automation id, trigger for the actual posting time)
_post_triggers: dict[str, tuple[str, CronTrigger]] = {}


def parse_dow(field: str) -> set[int] | None:
    """Standard cron day-of-week field -> set of 0-6 (Sunday = 0); None means every day."""
    field = field.strip().lower()
    if field in {"*", "?"}:
        return None
    days: set[int] = set()
    for part in field.split(","):
        step = 1
        if "/" in part:
            part, step_s = part.split("/", 1)
            step = int(step_s)
        if part in {"*", ""}:
            lo, hi = 0, 6
        elif "-" in part:
            a, b = part.split("-", 1)
            lo, hi = _dow_value(a), _dow_value(b)
        else:
            lo = hi = _dow_value(part)
        if hi < lo:  # wrap-around range like fri-mon
            rng = list(range(lo, 7)) + list(range(0, hi + 1))
        else:
            rng = list(range(lo, hi + 1))
        days.update(d % 7 for d in rng[::step])
    return days


def _dow_value(token: str) -> int:
    token = token.strip()
    if token[:3] in _DOW_ALIASES:
        return _DOW_ALIASES[token[:3]]
    value = int(token)
    if not 0 <= value <= 7:
        raise ValueError(f"day of week out of range: {token}")
    return value % 7


def cron_trigger(expr: str, tz: str | None = None) -> CronTrigger:
    fields = expr.split()
    if len(fields) != 5:
        raise ValueError("cron needs 5 fields: minute hour day month weekday")
    minute, hour, day, month, dow = fields
    days = parse_dow(dow)
    dow_arg = "*" if days is None else ",".join(DOW_NAMES[d] for d in sorted(days))
    return CronTrigger(minute=minute, hour=hour, day=day, month=month, day_of_week=dow_arg, timezone=tz or settings.timezone)


def validate_cron(expr: str, tz: str | None = None) -> str:
    """Raise ValueError for an invalid 5-field cron expression."""
    try:
        cron_trigger(expr, tz)
    except ValueError:
        raise
    except Exception as exc:  # noqa: BLE001 - APScheduler raises assorted types
        raise ValueError(str(exc)) from exc
    return " ".join(expr.split())


def shift_earlier(expr: str, minutes: int) -> str | None:
    """'30 9 * * 1-5' shifted 15 min earlier -> '15 9 * * 1,2,3,4,5'. None if not a simple time."""
    fields = expr.split()
    if len(fields) != 5 or not re.fullmatch(r"\d+", fields[0]) or not re.fullmatch(r"\d+", fields[1]):
        return None
    if fields[2] != "*" or fields[3] != "*":
        return None
    total = int(fields[1]) * 60 + int(fields[0]) - minutes
    days = parse_dow(fields[4])
    if total < 0:
        total += 24 * 60
        if days is not None:
            days = {(d - 1) % 7 for d in days}
    dow = "*" if days is None else ",".join(str(d) for d in sorted(days))
    return f"{total % 60} {total // 60} * * {dow}"


def _job_id(schedule_id: str) -> str:
    return f"schedule:{schedule_id}"


def _fire(automation_id: str, schedule_id: str, lead: bool) -> None:
    publish_at = None
    if lead and schedule_id in _post_triggers:
        trigger = _post_triggers[schedule_id][1]
        now = datetime.now(timezone.utc)
        nxt = trigger.get_next_fire_time(None, now)
        if nxt and nxt - now <= timedelta(minutes=LEAD_MINUTES + 5):
            publish_at = nxt
    try:
        pipeline.run_automation(automation_id, cron_id=schedule_id, publish_at=publish_at)
    except Exception:  # noqa: BLE001 - never let one run kill the scheduler thread
        log.exception("scheduled run failed for automation %s", automation_id)


def sync() -> None:
    """Make scheduler jobs match the schedule_jobs table."""
    with db.session_scope() as session:
        rows = session.execute(
            select(db.ScheduleJob, db.Automation.timezone).join(db.Automation, db.Automation.id == db.ScheduleJob.automation_id)
        ).all()
        wanted = {_job_id(r.id): (r, tz) for r, tz in rows}
        for job in scheduler.get_jobs():
            if job.id.startswith("schedule:") and job.id not in wanted:
                job.remove()
        _post_triggers.clear()
        for job_id, (row, tz) in wanted.items():
            tz = tz or settings.timezone
            try:
                post_trigger = cron_trigger(row.cron, tz)
            except ValueError as exc:
                log.warning("skipping invalid cron %r: %s", row.cron, exc)
                continue
            _post_triggers[row.id] = (row.automation_id, post_trigger)
            early = shift_earlier(row.cron, LEAD_MINUTES)
            trigger = cron_trigger(early, tz) if early else post_trigger
            scheduler.add_job(
                _fire, trigger, id=job_id, args=[row.automation_id, row.id, bool(early)], replace_existing=True,
                max_instances=1, coalesce=True, misfire_grace_time=900,
            )


def next_runs(automation_id: str, count: int = 5) -> list[str]:
    """Upcoming posting times (not generation start times) for an automation."""
    now = datetime.now(timezone.utc)
    out = []
    for aid, trigger in _post_triggers.values():
        if aid != automation_id:
            continue
        t, prev = now, None
        for _ in range(count):
            t = trigger.get_next_fire_time(prev, t)
            if t is None:
                break
            out.append(t)
            prev, t = t, t + timedelta(seconds=1)
    return [t.isoformat() for t in sorted(out)[:count]]


def start() -> None:
    if scheduler.running:
        return
    scheduler.add_job(pipeline.refresh_posts, "interval", minutes=10, id="refresh_posts", replace_existing=True)
    scheduler.add_job(library.refresh_all, "cron", hour=4, minute=17, id="library_refresh", replace_existing=True)
    sync()
    scheduler.start()


def shutdown() -> None:
    if scheduler.running:
        scheduler.shutdown(wait=False)
