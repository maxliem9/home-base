import { BASE_TARGET_PERIOD, type WorkTarget } from '../types'

// Pure math behind the Dashboard "Wochensoll" peek and TimeView's WeekBalance.
// Extracted so the double-count guard from #531 lives in one unit-tested place and the
// two live-ticking surfaces can't silently drift apart again (the drift *was* #531).

/**
 * Seconds a running timer has accrued *since the forecast snapshot*.
 *
 * The backend forecast already counts the running entry up to the snapshot instant, so
 * live ticking must measure from `forecastAtMs` — NOT from the timer's `startedAt`, which
 * double-counts the `[startedAt, snapshot]` interval and runs every derived figure too
 * high (#531). Returns 0 when no timer runs or no snapshot has been taken yet
 * (`forecastAtMs === 0`), and never goes negative if a fresh snapshot lands before the
 * 1 s `nowMs` tick catches up.
 */
export function liveSecondsSinceSnapshot(isRunning: boolean, nowMs: number, forecastAtMs: number): number {
  if (!isRunning || !forecastAtMs) return 0
  return Math.max(0, Math.floor((nowMs - forecastAtMs) / 1000))
}

export interface WorktargetFigures {
  /** week Ist incl. credits and the live tick */
  weekDone: number
  /** week completion in percent, clamped to 0–100 */
  pct: number
  /** seconds still to work today; ≤ 0 means today's target is already reached */
  todayLeft: number
}

/** Derives the peek's displayed figures from a user forecast plus the live tick. */
export function worktargetFigures(
  f: {
    weekTargetSeconds: number
    weekRecordedSeconds: number
    weekCreditedSeconds: number
    todayTargetSeconds: number
    todayRecordedSeconds: number
  },
  liveSeconds: number,
): WorktargetFigures {
  const weekDone = f.weekRecordedSeconds + f.weekCreditedSeconds + liveSeconds
  const pct = Math.min(100, Math.round((weekDone / f.weekTargetSeconds) * 100))
  const todayLeft = f.todayTargetSeconds - (f.todayRecordedSeconds + liveSeconds)
  return { weekDone, pct, todayLeft }
}

export interface WeekBarSplit {
  /** width of the soll part (user colour) in percent of the bar */
  basePct: number
  /** width of the overtime part (accent colour) in percent of the bar; 0 while under target */
  overPct: number
  /** seconds above the weekly target; 0 while under target */
  overSeconds: number
}

/**
 * Splits the week bar into soll + overtime. Under target the bar fills toward 100 % as
 * before; above target the bar is rescaled to the Ist so the overtime shows as its own
 * segment instead of being swallowed by a capped 100 % bar.
 */
export function weekBarSplit(doneSeconds: number, targetSeconds: number): WeekBarSplit {
  const done = Math.max(0, doneSeconds)
  if (targetSeconds <= 0) return { basePct: 0, overPct: 0, overSeconds: 0 }
  if (done <= targetSeconds) return { basePct: (done / targetSeconds) * 100, overPct: 0, overSeconds: 0 }
  const overSeconds = done - targetSeconds
  const basePct = (targetSeconds / done) * 100
  return { basePct, overPct: 100 - basePct, overSeconds }
}

/**
 * The person's target rows of the period in force for the week starting `weekStart`
 * (YYYY-MM-DD, a Monday): their latest `validFrom` ≤ weekStart, like the backend
 * forecast/credits. Empty for a week before the person's first period.
 */
function periodRows(targets: WorkTarget[], userId: string, weekStart: string): WorkTarget[] {
  const mine = targets.filter((t) => t.userId === userId)
  const period = mine
    .map((t) => t.validFrom ?? BASE_TARGET_PERIOD)
    .filter((p) => p <= weekStart)
    .sort()
    .pop()
  return period ? mine.filter((t) => (t.validFrom ?? BASE_TARGET_PERIOD) === period) : []
}

/**
 * Balance of one person on one project in one week against that week's project target:
 * positive = overtime, negative = hours missing; `null` without a target (nothing to compare).
 *
 * Absence/holiday credits are booked by the backend from the person's TOTAL weekly target
 * (all projects) onto the default project (TimeCredits.kt). So every project counts its
 * share `soll_p / Σsoll` of ALL the person's credits that week (`userCreditedSeconds`) in
 * place of whatever landed on it (`projectCreditedSeconds`): the default project doesn't
 * read a vacation week as "+5 h" for a 40 h + 5 h split, and the 5 h project doesn't read a
 * sick day as minus hours.
 */
export function projectWeekBalance(
  targets: WorkTarget[], userId: string, projectId: string, weekStart: string,
  seconds: number, projectCreditedSeconds: number, userCreditedSeconds: number,
): number | null {
  const rows = periodRows(targets, userId, weekStart)
  const row = rows.find((t) => t.projectId === projectId)
  const target = row ? row.weeklyHours * 3600 : 0
  if (target <= 0) return null
  const total = rows.reduce((s, t) => s + t.weeklyHours * 3600, 0)
  const effective = seconds - projectCreditedSeconds + userCreditedSeconds * (target / total)
  return Math.round(effective - target)
}

/** Displayable overtime / missing hours of one person in one week (seconds, both ≥ 0). */
export interface BalanceParts { over: number; under: number }

/**
 * Splits a week balance into displayable overtime / missing hours; sub-minute differences
 * (< 30 s) count as on target, like the Wochensoll card. Missing hours only for finished
 * weeks — the running week is still open, not "under".
 */
export function balanceParts(balance: number | null, weekFinished: boolean): BalanceParts {
  if (balance === null) return { over: 0, under: 0 }
  return {
    over: balance >= 30 ? balance : 0,
    under: weekFinished && balance <= -30 ? -balance : 0,
  }
}

/**
 * Running balance ("Saldo") per person: overtime minus missing hours, summed over every
 * week whose key (its Monday, YYYY-MM-DD) is ≥ `fromWeekKey`. Whole weeks only — the caller
 * passes the Monday of the week containing the chosen start date, so a half week is never
 * held against a full week's target. Uses the same per-week parts as the Pro-Woche list
 * (running week: overtime only).
 */
export function runningBalance(
  weeks: { key: string; parts: Record<string, BalanceParts> }[],
  fromWeekKey: string,
): Record<string, number> {
  const out: Record<string, number> = {}
  for (const w of weeks) {
    if (w.key < fromWeekKey) continue
    for (const [uid, p] of Object.entries(w.parts)) out[uid] = (out[uid] ?? 0) + p.over - p.under
  }
  return out
}
