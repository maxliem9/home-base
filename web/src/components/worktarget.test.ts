import { describe, it, expect } from 'vitest'
import { liveSecondsSinceSnapshot, overtimeSeconds, balanceParts, projectWeekBalance, runningBalance, weekBarSplit, weeklyTargetSeconds, worktargetFigures } from './worktarget'
import type { WorkTarget } from '../types'

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

describe('weekBarSplit', () => {
  const h = 3600

  it('fills toward the target without an overtime segment while under target', () => {
    expect(weekBarSplit(20 * h, 40 * h)).toEqual({ basePct: 50, overPct: 0, overSeconds: 0 })
  })

  it('is exactly full with no overtime at the target', () => {
    expect(weekBarSplit(40 * h, 40 * h)).toEqual({ basePct: 100, overPct: 0, overSeconds: 0 })
  })

  it('rescales to the Ist and splits off the overtime above target', () => {
    // 50 h on a 40 h week → soll 80 %, overtime 20 % (10 h)
    const s = weekBarSplit(50 * h, 40 * h)
    expect(s.basePct).toBeCloseTo(80)
    expect(s.overPct).toBeCloseTo(20)
    expect(s.overSeconds).toBe(10 * h)
  })

  it('is empty without a target and never negative', () => {
    expect(weekBarSplit(5 * h, 0)).toEqual({ basePct: 0, overPct: 0, overSeconds: 0 })
    expect(weekBarSplit(-60, 40 * h)).toEqual({ basePct: 0, overPct: 0, overSeconds: 0 })
  })
})

describe('weeklyTargetSeconds', () => {
  const tgt = (p: Partial<WorkTarget>): WorkTarget => ({ userId: 'max', projectId: 'p1', weeklyHours: 40, isDefault: true, ...p })
  // 40 h base period, reduced to 32 h from 1 Sep 2026; chen has her own 20 h
  const targets = [
    tgt({}),
    tgt({ projectId: 'p2', weeklyHours: 5, isDefault: false }),
    tgt({ weeklyHours: 32, validFrom: '2026-09-01' }),
    tgt({ userId: 'chen', weeklyHours: 20 }),
  ]

  it('uses the base period for weeks before a change', () => {
    expect(weeklyTargetSeconds(targets, 'max', 'p1', '2026-08-24')).toBe(40 * 3600)
  })

  it('uses the new period from the week whose Monday is on/after validFrom', () => {
    // week of Mon 31 Aug still starts before 1 Sep → old value
    expect(weeklyTargetSeconds(targets, 'max', 'p1', '2026-08-31')).toBe(40 * 3600)
    expect(weeklyTargetSeconds(targets, 'max', 'p1', '2026-09-07')).toBe(32 * 3600)
  })

  it('is 0 for a project missing from the period in force, and per person', () => {
    // p2 only exists in the base period — the September period has no row for it
    expect(weeklyTargetSeconds(targets, 'max', 'p2', '2026-08-24')).toBe(5 * 3600)
    expect(weeklyTargetSeconds(targets, 'max', 'p2', '2026-09-07')).toBe(0)
    expect(weeklyTargetSeconds(targets, 'chen', 'p1', '2026-09-07')).toBe(20 * 3600)
    expect(weeklyTargetSeconds(targets, 'nobody', 'p1', '2026-09-07')).toBe(0)
  })
})

describe('overtimeSeconds', () => {
  it('is the excess above target, never negative, and 0 without a target', () => {
    expect(overtimeSeconds(45 * 3600, 40 * 3600)).toBe(5 * 3600)
    expect(overtimeSeconds(30 * 3600, 40 * 3600)).toBe(0)
    expect(overtimeSeconds(30 * 3600, 0)).toBe(0)
    expect(overtimeSeconds(40 * 3600 + 20, 40 * 3600)).toBe(0)
  })
})

describe('projectWeekBalance', () => {
  const h = 3600
  const tgt = (p: Partial<WorkTarget>): WorkTarget => ({ userId: 'max', projectId: 'p1', weeklyHours: 40, isDefault: true, ...p })
  const twoProjects = [tgt({}), tgt({ projectId: 'p2', weeklyHours: 5, isDefault: false })]
  const wk = '2026-08-24'

  it('a full vacation week (credited from the 45 h total onto p1) is exactly on target', () => {
    expect(projectWeekBalance(twoProjects, 'max', 'p1', wk, 45 * h, 45 * h)).toBe(0)
  })

  it('only counts p1\'s share of a credit', () => {
    // 33 h worked + one 9 h credited day → p1 share 8 h → 41 h vs 40 h
    expect(projectWeekBalance(twoProjects, 'max', 'p1', wk, 42 * h, 9 * h)).toBe(1 * h)
    // 28 h worked + one credited day → 36 h vs 40 h → 4 h missing
    expect(projectWeekBalance(twoProjects, 'max', 'p1', wk, 37 * h, 9 * h)).toBe(-4 * h)
  })

  it('with a single project the credit counts in full', () => {
    expect(projectWeekBalance([tgt({})], 'max', 'p1', wk, 42 * h, 8 * h)).toBe(2 * h)
  })

  it('is null for a project without a target', () => {
    expect(projectWeekBalance(twoProjects, 'max', 'p3', wk, 50 * h, 0)).toBeNull()
  })
})

describe('balanceParts', () => {
  it('splits into overtime / missing hours with a 30 s dead zone', () => {
    expect(balanceParts(3600, true)).toEqual({ over: 3600, under: 0 })
    expect(balanceParts(-3600, true)).toEqual({ over: 0, under: 3600 })
    expect(balanceParts(20, true)).toEqual({ over: 0, under: 0 })
    expect(balanceParts(-20, true)).toEqual({ over: 0, under: 0 })
    expect(balanceParts(null, true)).toEqual({ over: 0, under: 0 })
  })

  it('never flags the running week as under target, but does show its overtime', () => {
    expect(balanceParts(-3600, false)).toEqual({ over: 0, under: 0 })
    expect(balanceParts(3600, false)).toEqual({ over: 3600, under: 0 })
  })
})

describe('runningBalance', () => {
  const weeks: { key: string; parts: Record<string, { over: number; under: number }> }[] = [
    { key: '2026-10-12', parts: { max: { over: 0, under: 0 } } },
    { key: '2026-10-05', parts: { max: { over: 2 * 3600, under: 0 }, chen: { over: 0, under: 3600 } } },
    { key: '2026-09-28', parts: { max: { over: 0, under: 30 * 60 } } },
    { key: '2026-09-21', parts: { max: { over: 5 * 3600, under: 0 } } },
  ]

  it('sums overtime minus missing hours per person from the start week on', () => {
    expect(runningBalance(weeks, '2026-09-28')).toEqual({ max: 1.5 * 3600, chen: -3600 })
  })

  it('a later start leaves earlier weeks out; people without parts are absent', () => {
    expect(runningBalance(weeks, '2026-10-12')).toEqual({ max: 0 })
    expect(runningBalance(weeks, '2026-11-02')).toEqual({})
  })
})
