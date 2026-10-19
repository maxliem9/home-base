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
 * Weekly target (seconds) of one person on one project for the week starting `weekStart`
 * (YYYY-MM-DD, a Monday) — taken from the period in force then: the person's latest
 * `validFrom` ≤ weekStart, like the backend forecast/credits. A project without a row in
 * that period (or a week before the person's first period) has no target → 0.
 */
export function weeklyTargetSeconds(targets: WorkTarget[], userId: string, projectId: string, weekStart: string): number {
  const mine = targets.filter((t) => t.userId === userId)
  const period = mine
    .map((t) => t.validFrom ?? BASE_TARGET_PERIOD)
    .filter((p) => p <= weekStart)
    .sort()
    .pop()
  if (!period) return 0
  const row = mine.find((t) => t.projectId === projectId && (t.validFrom ?? BASE_TARGET_PERIOD) === period)
  return row ? Math.round(row.weeklyHours * 3600) : 0
}

/**
 * Seconds above the weekly target; 0 without a target (no soll → no overtime) and for
 * sub-minute excess (< 30 s), which would only render as "+0 Min." — same threshold as the
 * Wochensoll card's badge.
 */
export function overtimeSeconds(seconds: number, targetSeconds: number): number {
  if (targetSeconds <= 0) return 0
  const over = seconds - targetSeconds
  return over >= 30 ? over : 0
}
