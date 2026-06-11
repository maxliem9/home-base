import { describe, expect, it } from 'vitest'
import type { AbsSettings, AbsenceState } from '../../types'
import { buildContext, isWorkdayFor, normalizeAbsenceState, personDay, settingsFor, summarize, wouldWork } from './core'

const s = (year: number, over: Partial<AbsSettings> = {}): AbsSettings => ({
  userId: 'alice',
  year,
  state: 'BE',
  allowance: 30,
  carryover: 0,
  carryoverExpires: `${year}-03-31`,
  kindKrankCap: 15,
  ...over,
})

describe('settingsFor (per-year settings, #144)', () => {
  it('returns the exact row when the year has its own settings', () => {
    const all = [s(2025, { carryover: 5 }), s(2026, { carryover: 2 })]
    expect(settingsFor(all, 'alice', 2026).carryover).toBe(2)
    expect(settingsFor(all, 'alice', 2025).carryover).toBe(5)
  })

  it('falls back to hard defaults when the user has no rows at all', () => {
    const d = settingsFor([], 'alice', 2027)
    expect(d).toMatchObject({ userId: 'alice', year: 2027, state: 'BE', allowance: 30, carryover: 0, kindKrankCap: 15 })
  })

  it('inherits stable fields from the nearest earlier year but resets carryover', () => {
    const all = [s(2025, { state: 'BY', allowance: 28, kindKrankCap: 10, carryover: 5 })]
    const got = settingsFor(all, 'alice', 2027)
    expect(got.state).toBe('BY') // inherited
    expect(got.allowance).toBe(28) // inherited
    expect(got.kindKrankCap).toBe(10) // inherited
    expect(got.year).toBe(2027)
    expect(got.carryover).toBe(0) // per-year, not inherited
    expect(got.carryoverExpires).toBe('2027-03-31') // reset to the queried year
  })

  it('inherits from the nearest later year when no earlier year exists', () => {
    const all = [s(2026, { state: 'HH', carryover: 4 })]
    const got = settingsFor(all, 'alice', 2024)
    expect(got.state).toBe('HH')
    expect(got.year).toBe(2024)
    expect(got.carryover).toBe(0)
  })

  it('scopes inheritance to the requested user', () => {
    const all: AbsSettings[] = [
      { ...s(2025, { state: 'BY' }), userId: 'alice' },
      { ...s(2025, { state: 'SN' }), userId: 'bob' },
    ]
    expect(settingsFor(all, 'bob', 2026).state).toBe('SN')
  })
})

describe('summarize uses the displayed year\'s settings (#144)', () => {
  // Same snapshot, no absences taken — the remaining leave must differ per year
  // purely because each year carries its own Resturlaub. This is exactly what the
  // summary card on the calendar shows.
  const state: AbsenceState = {
    users: ['alice'],
    absences: [],
    partTime: [],
    kitaClosures: [],
    customHolidays: [],
    settings: [s(2025, { allowance: 30, carryover: 5 }), s(2026, { allowance: 30, carryover: 2 })],
  }

  it('reflects the per-year carryover in the remaining balance', () => {
    const c2025 = buildContext(state, 2025, ['alice'])
    const c2026 = buildContext(state, 2026, ['alice'])
    // remaining = allowance + carryover - used(0)
    expect(summarize(c2025, 'alice', '2025-06-01').remaining).toBe(35)
    expect(summarize(c2026, 'alice', '2026-06-01').remaining).toBe(32)
  })
})

describe('custom holidays (#51)', () => {
  const base = (over: Partial<AbsenceState> = {}): AbsenceState => ({
    users: ['alice'],
    absences: [],
    partTime: [],
    kitaClosures: [],
    customHolidays: [],
    settings: [s(2026)],
    ...over,
  })

  it('resolves a household-wide custom holiday by month+day, for every year', () => {
    const state = base({ customHolidays: [{ id: 'h1', month: 12, day: 24, half: true, label: 'Heiligabend' }] })
    // matches in 2026…
    const day26 = personDay(buildContext(state, 2026, ['alice']), 'alice', '2026-12-24')
    expect(day26.holiday).toBe('Heiligabend')
    expect(day26.holidayHalf).toBe(true)
    // …and again the following year (recurring), no separate row needed.
    const day27 = personDay(buildContext(state, 2027, ['alice']), 'alice', '2027-12-24')
    expect(day27.holiday).toBe('Heiligabend')
  })

  it('applies regardless of Bundesland (state setting is irrelevant for custom holidays)', () => {
    const state = base({
      settings: [s(2026, { state: 'BY' })],
      customHolidays: [{ id: 'h1', month: 12, day: 31, half: false, label: 'Silvester' }],
    })
    const st = personDay(buildContext(state, 2026, ['alice']), 'alice', '2026-12-31')
    expect(st.holiday).toBe('Silvester')
    expect(st.holidayHalf).toBe(false)
  })

  it('treats a full custom holiday as non-working but a half one as still workable', () => {
    const full = base({ customHolidays: [{ id: 'h1', month: 12, day: 31, half: false, label: 'Silvester' }] })
    const half = base({ customHolidays: [{ id: 'h2', month: 12, day: 24, half: true, label: 'Heiligabend' }] })
    expect(wouldWork(personDay(buildContext(full, 2026, ['alice']), 'alice', '2026-12-31'))).toBe(false)
    expect(wouldWork(personDay(buildContext(half, 2026, ['alice']), 'alice', '2026-12-24'))).toBe(true)
  })

  it('lets a statutory holiday take precedence over a custom one on the same day', () => {
    // 2026-01-01 is Neujahr (statutory, full day) everywhere — a custom half-day on the
    // same date must not downgrade it to a half day.
    const state = base({ customHolidays: [{ id: 'h1', month: 1, day: 1, half: true, label: 'Eigenes Neujahr' }] })
    const st = personDay(buildContext(state, 2026, ['alice']), 'alice', '2026-01-01')
    expect(st.holiday).toBe('Neujahr')
    expect(st.holidayHalf).toBe(false)
  })
})

describe('snapshots with missing lists (#54, encodeDefaults=false)', () => {
  // With encodeDefaults=false the backend omits fields holding their default
  // (CLAUDE.md / issue #46): a fresh household's snapshot can lack absences, partTime,
  // kitaClosures, customHolidays and settings entirely. None of that may throw.

  it('normalizeAbsenceState fills every missing list with []', () => {
    expect(normalizeAbsenceState({})).toEqual({
      users: [],
      absences: [],
      partTime: [],
      kitaClosures: [],
      customHolidays: [],
      settings: [],
    })
  })

  it('buildContext tolerates a snapshot missing all lists', () => {
    const ctx = buildContext({ users: ['alice'] }, 2026, ['alice'])
    expect(ctx.absByUser['alice']).toEqual({})
    expect(ctx.kita).toEqual({})
    expect(ctx.customHol).toEqual({})
    expect(ctx.parttime).toEqual([])
    // settings fall back to the hard defaults
    expect(ctx.settings['alice']).toMatchObject({ userId: 'alice', year: 2026, state: 'BE', allowance: 30 })
  })

  it('summarize yields a plain default summary on an all-empty snapshot', () => {
    const ctx = buildContext({}, 2026, ['alice'])
    const sum = summarize(ctx, 'alice', '2026-06-01')
    expect(sum).toMatchObject({ taken: 0, planned: 0, krank: 0, kind: 0, used: 0, remaining: 30 })
  })

  it('counts absences when every other list is missing', () => {
    // 2026-05-04 is a plain Monday — one taken vacation day, even though partTime,
    // kitaClosures, customHolidays and settings are all omitted from the snapshot.
    const ctx = buildContext(
      { users: ['alice'], absences: [{ id: 'a1', userId: 'alice', date: '2026-05-04', type: 'URLAUB' }] },
      2026,
      ['alice'],
    )
    expect(summarize(ctx, 'alice', '2026-06-01').taken).toBe(1)
  })

  it('personDay still derives weekends and statutory holidays without stored data', () => {
    const ctx = buildContext({}, 2026, ['alice'])
    expect(personDay(ctx, 'alice', '2026-01-01').holiday).toBe('Neujahr')
    expect(personDay(ctx, 'alice', '2026-01-03').weekend).toBe(true) // a Saturday
  })

  it('isWorkdayFor works on a snapshot without settings/partTime/customHolidays', () => {
    expect(isWorkdayFor({}, 'alice', '2026-06-02')).toBe(true) // a plain Tuesday
    expect(isWorkdayFor({}, 'alice', '2026-06-06')).toBe(false) // a Saturday
  })
})
