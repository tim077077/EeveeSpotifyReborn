"""Best posting times per target country.

Score for posting at a given local time = expected audience activity over the next ~2 hours
(TikTok pushes a new post hardest in its first hours, so posting slightly before a peak wins).

Activity comes from three layers, blended:
  1. A baseline daily curve (workday vs rest day) from published TikTok/social engagement studies,
     shifted for countries with late evenings and using each country's weekend days.
  2. Your library: scraped slideshow posts from accounts in that country, normalized per account
     (a post's views vs that account's median), bucketed by local posting hour.
  3. Your own published posts' views by local hour.
Layers 2-3 are shrunk toward the baseline until they have enough posts to be trusted.
Multi-timezone countries are scored across their zones, weighted by population.
"""

from __future__ import annotations

import math
import statistics
from collections import defaultdict
from dataclasses import dataclass, field
from datetime import datetime, timedelta
from zoneinfo import ZoneInfo

from sqlalchemy import select

from . import db

DAYS = ["mon", "tue", "wed", "thu", "fri", "sat", "sun"]
# Standard cron day-of-week numbers for DAYS (Sunday = 0).
CRON_DOW = {"mon": 1, "tue": 2, "wed": 3, "thu": 4, "fri": 5, "sat": 6, "sun": 0}

# Relative audience activity by local hour (0-23).
WORKDAY = [0.45, 0.30, 0.18, 0.10, 0.07, 0.08, 0.20, 0.45, 0.55, 0.45, 0.40, 0.45,
           0.62, 0.58, 0.48, 0.50, 0.58, 0.66, 0.74, 0.85, 0.95, 1.00, 0.88, 0.65]
RESTDAY = [0.55, 0.40, 0.25, 0.14, 0.08, 0.07, 0.10, 0.20, 0.35, 0.52, 0.65, 0.70,
           0.72, 0.68, 0.66, 0.66, 0.68, 0.72, 0.78, 0.88, 0.97, 1.00, 0.90, 0.70]
# How a post's first hours are weighted: now, +1h, +2h.
RAMP = (0.5, 0.35, 0.15)


@dataclass(frozen=True)
class Country:
    code: str
    name: str
    zones: tuple[tuple[str, float], ...]  # (IANA zone, population share)
    weekend: tuple[str, ...] = ("sat", "sun")
    evening_shift: int = 0  # hours later than the baseline (late-dinner cultures)

    @property
    def primary_zone(self) -> str:
        return max(self.zones, key=lambda z: z[1])[0]


def _c(code, name, zones, weekend=("sat", "sun"), shift=0):
    if isinstance(zones, str):
        zones = ((zones, 1.0),)
    return Country(code, name, tuple(zones), tuple(weekend), shift)


FRI_SAT = ("fri", "sat")
COUNTRIES: dict[str, Country] = {c.code: c for c in [
    _c("US", "United States", (("America/New_York", .47), ("America/Chicago", .29), ("America/Denver", .07), ("America/Los_Angeles", .17))),
    _c("CA", "Canada", (("America/Toronto", .62), ("America/Winnipeg", .07), ("America/Edmonton", .12), ("America/Vancouver", .14), ("America/Halifax", .05))),
    _c("MX", "Mexico", (("America/Mexico_City", .80), ("America/Tijuana", .07), ("America/Mazatlan", .08), ("America/Cancun", .05)), shift=1),
    _c("BR", "Brazil", (("America/Sao_Paulo", .93), ("America/Manaus", .05), ("America/Rio_Branco", .02)), shift=1),
    _c("AR", "Argentina", "America/Argentina/Buenos_Aires", shift=1),
    _c("CL", "Chile", "America/Santiago", shift=1),
    _c("CO", "Colombia", "America/Bogota"),
    _c("PE", "Peru", "America/Lima"),
    _c("GB", "United Kingdom", "Europe/London"),
    _c("IE", "Ireland", "Europe/Dublin"),
    _c("DE", "Germany", "Europe/Berlin"),
    _c("AT", "Austria", "Europe/Vienna"),
    _c("CH", "Switzerland", "Europe/Zurich"),
    _c("FR", "France", "Europe/Paris"),
    _c("BE", "Belgium", "Europe/Brussels"),
    _c("NL", "Netherlands", "Europe/Amsterdam"),
    _c("ES", "Spain", "Europe/Madrid", shift=1),
    _c("PT", "Portugal", "Europe/Lisbon"),
    _c("IT", "Italy", "Europe/Rome"),
    _c("GR", "Greece", "Europe/Athens", shift=1),
    _c("PL", "Poland", "Europe/Warsaw"),
    _c("RO", "Romania", "Europe/Bucharest"),
    _c("MD", "Moldova", "Europe/Chisinau"),
    _c("HU", "Hungary", "Europe/Budapest"),
    _c("CZ", "Czechia", "Europe/Prague"),
    _c("SE", "Sweden", "Europe/Stockholm"),
    _c("NO", "Norway", "Europe/Oslo"),
    _c("DK", "Denmark", "Europe/Copenhagen"),
    _c("FI", "Finland", "Europe/Helsinki"),
    _c("UA", "Ukraine", "Europe/Kyiv"),
    _c("TR", "Turkey", "Europe/Istanbul", shift=1),
    _c("IL", "Israel", "Asia/Jerusalem", FRI_SAT),
    _c("SA", "Saudi Arabia", "Asia/Riyadh", FRI_SAT, shift=2),
    _c("AE", "United Arab Emirates", "Asia/Dubai", shift=1),
    _c("EG", "Egypt", "Africa/Cairo", FRI_SAT, shift=1),
    _c("MA", "Morocco", "Africa/Casablanca"),
    _c("NG", "Nigeria", "Africa/Lagos"),
    _c("KE", "Kenya", "Africa/Nairobi"),
    _c("ZA", "South Africa", "Africa/Johannesburg"),
    _c("PK", "Pakistan", "Asia/Karachi", shift=1),
    _c("BD", "Bangladesh", "Asia/Dhaka"),
    _c("ID", "Indonesia", (("Asia/Jakarta", .80), ("Asia/Makassar", .17), ("Asia/Jayapura", .03))),
    _c("MY", "Malaysia", "Asia/Kuala_Lumpur"),
    _c("SG", "Singapore", "Asia/Singapore"),
    _c("PH", "Philippines", "Asia/Manila"),
    _c("TH", "Thailand", "Asia/Bangkok"),
    _c("VN", "Vietnam", "Asia/Ho_Chi_Minh"),
    _c("JP", "Japan", "Asia/Tokyo"),
    _c("KR", "South Korea", "Asia/Seoul"),
    _c("TW", "Taiwan", "Asia/Taipei"),
    _c("AU", "Australia", (("Australia/Sydney", .58), ("Australia/Brisbane", .20), ("Australia/Perth", .11), ("Australia/Adelaide", .08), ("Australia/Darwin", .03))),
    _c("NZ", "New Zealand", "Pacific/Auckland"),
]}


@dataclass
class Evidence:
    """Per (rest-day?, local hour) performance multipliers learned from real posts."""

    log_sum: dict[tuple[bool, int], float] = field(default_factory=lambda: defaultdict(float))
    count: dict[tuple[bool, int], int] = field(default_factory=lambda: defaultdict(int))
    posts: int = 0

    def add(self, rest: bool, hour: int, ratio: float) -> None:
        ratio = min(10.0, max(0.1, ratio))
        self.log_sum[(rest, hour)] += math.log(ratio)
        self.count[(rest, hour)] += 1
        self.posts += 1

    def multiplier(self, rest: bool, hour: int, prior_strength: float) -> float:
        n = self.count.get((rest, hour), 0)
        if not n:
            return 1.0
        return math.exp(self.log_sum[(rest, hour)] / (n + prior_strength))


def _activity(country: Country, rest: bool, hour: float) -> float:
    curve = RESTDAY if rest else WORKDAY
    h = (hour - country.evening_shift) % 24
    lo = int(h)
    frac = h - lo
    return curve[lo] * (1 - frac) + curve[(lo + 1) % 24] * frac


def _week_anchor(tz: ZoneInfo) -> datetime:
    """Monday 00:00 of a fixed reference week (avoids DST-transition edge cases in examples)."""
    return datetime(2026, 1, 5, tzinfo=tz)


def score_grid(country: Country, evidence: list[tuple[Evidence, float]] | None = None, step_minutes: int = 30) -> list[list[float]]:
    """scores[day][slot] for posting at that local time in the country's primary zone."""
    primary = ZoneInfo(country.primary_zone)
    anchor = _week_anchor(primary)
    slots = 24 * 60 // step_minutes
    grid = []
    for d in range(7):
        row = []
        for s in range(slots):
            t = anchor + timedelta(days=d, minutes=s * step_minutes)
            total = 0.0
            for zone, weight in country.zones:
                local = t.astimezone(ZoneInfo(zone))
                for k, w in enumerate(RAMP):
                    lt = local + timedelta(hours=k)
                    rest = DAYS[lt.weekday()] in country.weekend
                    total += weight * w * _activity(country, rest, lt.hour + lt.minute / 60)
            local_p = t
            rest_p = DAYS[local_p.weekday()] in country.weekend
            for ev, strength in evidence or []:
                total *= ev.multiplier(rest_p, local_p.hour, strength)
            row.append(total)
        grid.append(row)
    return grid


def pick_slots(row: list[float], n: int, step_minutes: int = 30) -> list[int]:
    """Greedy top slots for one day, spaced so posts don't cannibalize each other."""
    gap = max(2.0, min(3.0, 16 / max(n, 1)))
    min_sep = int(gap * 60 / step_minutes)
    chosen: list[int] = []
    for idx in sorted(range(len(row)), key=lambda i: -row[i]):
        if all(min(abs(idx - c), len(row) - abs(idx - c)) >= min_sep for c in chosen):
            chosen.append(idx)
        if len(chosen) == n:
            break
    return sorted(chosen)


def library_evidence(country: Country) -> Evidence:
    """Views-by-posting-hour for scraped posts from accounts in this country."""
    ev = Evidence()
    tz = ZoneInfo(country.primary_zone)
    with db.session_scope() as session:
        profiles = session.scalars(select(db.LibraryProfile).where(db.LibraryProfile.region == country.code)).all()
        for p in profiles:
            posts = [x for x in p.posts if x.posted_at and x.views]
            if len(posts) < 3:
                continue
            median = statistics.median(x.views for x in posts) or 1
            for x in posts:
                local = _aware(x.posted_at).astimezone(tz)
                ev.add(DAYS[local.weekday()] in country.weekend, local.hour, x.views / median)
    return ev


def own_evidence(country: Country, tiktok_account_id: str | None) -> Evidence:
    ev = Evidence()
    if not tiktok_account_id:
        return ev
    tz = ZoneInfo(country.primary_zone)
    with db.session_scope() as session:
        posts = session.scalars(
            select(db.Post).where(db.Post.tiktok_account_id == tiktok_account_id, db.Post.status == "published", db.Post.views > 0)
        ).all()
        if len(posts) < 3:
            return ev
        median = statistics.median(p.views for p in posts) or 1
        for p in posts:
            local = _aware(p.created_at).astimezone(tz)
            ev.add(DAYS[local.weekday()] in country.weekend, local.hour, p.views / median)
    return ev


def _aware(dt: datetime) -> datetime:
    return dt if dt.tzinfo else dt.replace(tzinfo=ZoneInfo("UTC"))


def best_times(code: str, posts_per_day: int = 3, tiktok_account_id: str | None = None) -> dict:
    country = COUNTRIES.get(code.upper())
    if country is None:
        raise KeyError(code)
    posts_per_day = max(1, min(posts_per_day, 6))
    lib = library_evidence(country)
    own = own_evidence(country, tiktok_account_id)
    grid = score_grid(country, [(lib, 8.0), (own, 5.0)])
    hourly = [row[::2] for row in grid]
    lo, hi = min(min(r) for r in hourly), max(max(r) for r in hourly)
    span = (hi - lo) or 1.0

    schedule: dict[str, list[str]] = {}
    for d, row in enumerate(grid):
        schedule[DAYS[d]] = [f"{(i * 30) // 60:02d}:{(i * 30) % 60:02d}" for i in pick_slots(row, posts_per_day)]

    # Group identical times across days into cron lines (standard cron: Sunday = 0).
    by_time: dict[str, list[str]] = defaultdict(list)
    for day, times in schedule.items():
        for t in times:
            by_time[t].append(day)
    crons = []
    for t in sorted(by_time):
        h, m = t.split(":")
        dows = ",".join(str(CRON_DOW[d]) for d in sorted(by_time[t], key=DAYS.index))
        crons.append(f"{int(m)} {int(h)} * * {'*' if len(by_time[t]) == 7 else dows}")

    sources = ["engagement baseline"]
    if lib.posts:
        sources.append(f"{lib.posts} library posts from {country.name} accounts")
    if own.posts:
        sources.append(f"{own.posts} of your published posts")
    return {
        "country": country.code,
        "name": country.name,
        "timezone": country.primary_zone,
        "zones": [{"zone": z, "share": w} for z, w in country.zones],
        "weekend": list(country.weekend),
        "posts_per_day": posts_per_day,
        "schedule": schedule,
        "crons": crons,
        "heatmap": [[round((v - lo) / span, 3) for v in row] for row in hourly],  # hourly, scaled 0-1 for display
        "sources": sources,
        "confidence": "high" if own.posts >= 30 else "medium" if lib.posts + own.posts >= 40 else "baseline",
    }


def country_list() -> list[dict]:
    return [{"code": c.code, "name": c.name, "timezone": c.primary_zone, "multi_zone": len(c.zones) > 1}
            for c in sorted(COUNTRIES.values(), key=lambda c: c.name)]
