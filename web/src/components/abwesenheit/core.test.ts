import { describe, expect, it } from 'vitest'
import type { AbsSettings, AbsenceState } from '../../types'
import { buildContext, settingsFor, summarize } from './core'

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
