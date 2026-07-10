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
