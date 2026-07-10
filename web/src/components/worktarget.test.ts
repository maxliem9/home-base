import { describe, it, expect } from 'vitest'
import { liveSecondsSinceSnapshot, worktargetFigures } from './worktarget'

describe('liveSecondsSinceSnapshot', () => {
  const forecastAt = 1_000_000

  it('is 0 when no timer runs, whatever the clock says', () => {
    expect(liveSecondsSinceSnapshot(false, forecastAt + 3_600_000, forecastAt)).toBe(0)
  })

  it('is 0 before the first forecast snapshot (forecastAtMs === 0)', () => {
    expect(liveSecondsSinceSnapshot(true, 5_000_000, 0)).toBe(0)
  })

  it('counts whole seconds since the snapshot, not since the timer start', () => {
    // 90 min after the snapshot → 5400 s, regardless of how long the timer has run
    expect(liveSecondsSinceSnapshot(true, forecastAt + 90 * 60_000, forecastAt)).toBe(5400)
  })

  it('floors sub-second remainders', () => {
    expect(liveSecondsSinceSnapshot(true, forecastAt + 1_999, forecastAt)).toBe(1)
  })

  it('never goes negative if a fresh snapshot lands ahead of the nowMs tick', () => {
    expect(liveSecondsSinceSnapshot(true, forecastAt - 500, forecastAt)).toBe(0)
  })
})

describe('worktargetFigures', () => {
  // 40 h week, 8 h/day; 10 h already recorded this week, 1 h today.
  const forecast = {
    weekTargetSeconds: 40 * 3600,
    weekRecordedSeconds: 10 * 3600,
    weekCreditedSeconds: 0,
    todayTargetSeconds: 8 * 3600,
    todayRecordedSeconds: 1 * 3600,
  }

  it('does not double-count a running timer — a fresh snapshot adds ~0 (the #531 bug)', () => {
    // The regression: measuring from startedAt would add the timer's full runtime here.
    // With snapshot-relative live seconds, a just-taken snapshot contributes nothing.
    const f = worktargetFigures(forecast, 0)
    expect(f.weekDone).toBe(10 * 3600) // 10 h, NOT 10 h + timer runtime
    expect(f.pct).toBe(25) // 10 / 40, NOT 30 %
    expect(f.todayLeft).toBe(7 * 3600) // 8 − 1, NOT 8 − 1 − timer runtime
  })

  it('adds only the seconds accrued since the snapshot to the live figures', () => {
    const live = 2 * 3600 // two hours since the snapshot
    const f = worktargetFigures(forecast, live)
    expect(f.weekDone).toBe(12 * 3600)
    expect(f.pct).toBe(30)
    expect(f.todayLeft).toBe(5 * 3600)
  })

  it('folds credited seconds into the week total', () => {
    const f = worktargetFigures({ ...forecast, weekCreditedSeconds: 4 * 3600 }, 0)
    expect(f.weekDone).toBe(14 * 3600)
    expect(f.pct).toBe(35)
  })

  it('clamps pct at 100 once the week target is met or exceeded', () => {
    const f = worktargetFigures({ ...forecast, weekRecordedSeconds: 45 * 3600 }, 0)
    expect(f.pct).toBe(100)
  })

  it('reports todayLeft ≤ 0 once today’s target is reached', () => {
    const f = worktargetFigures({ ...forecast, todayRecordedSeconds: 8 * 3600 }, 0)
    expect(f.todayLeft).toBeLessThanOrEqual(0)
  })
})
