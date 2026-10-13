"""Cron scheduling for automations (APScheduler, server timezone)."""

from __future__ import annotations

import logging

from apscheduler.schedulers.background import BackgroundScheduler
from apscheduler.triggers.cron import CronTrigger
from sqlalchemy import select

from . import db, library, pipeline
from .config import settings

log = logging.getLogger("slidemill.scheduler")

scheduler = BackgroundScheduler(timezone=settings.timezone)


def validate_cron(expr: str) -> str:
    """Raise ValueError for an invalid 5-field cron expression."""
    CronTrigger.from_crontab(expr, timezone=settings.timezone)
    return expr.strip()


def _job_id(schedule_id: str) -> str:
    return f"schedule:{schedule_id}"


def _fire(automation_id: str, schedule_id: str) -> None:
    try:
        pipeline.run_automation(automation_id, cron_id=schedule_id)
    except Exception:  # noqa: BLE001 - never let one run kill the scheduler thread
        log.exception("scheduled run failed for automation %s", automation_id)


def sync() -> None:
    """Make scheduler jobs match the schedule_jobs table."""
    with db.session_scope() as session:
        rows = session.scalars(select(db.ScheduleJob)).all()
        wanted = {_job_id(r.id): r for r in rows}
        for job in scheduler.get_jobs():
            if job.id.startswith("schedule:") and job.id not in wanted:
                job.remove()
        for job_id, row in wanted.items():
            trigger = CronTrigger.from_crontab(row.cron, timezone=settings.timezone)
            scheduler.add_job(
                _fire, trigger, id=job_id, args=[row.automation_id, row.id], replace_existing=True,
                max_instances=1, coalesce=True, misfire_grace_time=900,
            )


def next_runs(automation_id: str) -> list[str]:
    out = []
    for job in scheduler.get_jobs():
        # Pending jobs (scheduler not started) have no next_run_time yet.
        next_time = getattr(job, "next_run_time", None)
        if job.args and job.args[0] == automation_id and next_time:
            out.append(next_time.isoformat())
    return sorted(out)


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
