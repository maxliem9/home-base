import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  clockTime,
  dayGroupLabel,
  dueLabel,
  fmtClock,
  fmtDurationShort,
  relTime,
  userMeta,
  usernameFromToken,
  weekKey,
  weekLabel,
} from './format'

// All relative-time helpers read "now". Pin it to a known instant so every
// branch is deterministic. 2026-06-15 is a Monday (matters for the week/day
// grouping helpers). Tests run with TZ=UTC (see package.json) so that the
// helpers' local-time arithmetic is reproducible across machines.
const NOW = '2026-06-15T12:00:00Z'

beforeEach(() => {
  vi.useFakeTimers()
  vi.setSystemTime(new Date(NOW))
})

afterEach(() => {
  vi.useRealTimers()
})

describe('dueLabel', () => {
  it('returns null when no date is given', () => {
    expect(dueLabel(undefined)).toBeNull()
    expect(dueLabel('')).toBeNull()
  })

  it('labels today / tomorrow / yesterday', () => {
    expect(dueLabel('2026-06-15')).toEqual({ text: 'Heute', tone: 'today' })
    expect(dueLabel('2026-06-16')).toEqual({ text: 'Morgen', tone: 'soon' })
    expect(dueLabel('2026-06-14')).toEqual({ text: 'Gestern', tone: 'over' })
  })

  it('labels overdue by more than a day', () => {
    expect(dueLabel('2026-06-12')).toEqual({ text: '3 Tage überfällig', tone: 'over' })
  })

  it('labels upcoming days within the week as "soon"', () => {
    expect(dueLabel('2026-06-18')).toEqual({ text: 'In 3 Tagen', tone: 'soon' })
    // diff === 6 is still inside the < 7 window
    expect(dueLabel('2026-06-21')).toEqual({ text: 'In 6 Tagen', tone: 'soon' })
  })

  it('falls back to a calendar date at or beyond 7 days out', () => {
    // diff === 7 crosses the boundary into the "far" branch
    expect(dueLabel('2026-06-22')).toEqual({ text: '22. Juni', tone: 'far' })
    expect(dueLabel('2026-07-01')).toEqual({ text: '1. Juli', tone: 'far' })
  })
})

describe('relTime', () => {
  it('handles the just-now and minute window', () => {
    expect(relTime('2026-06-15T12:00:00Z')).toBe('gerade eben')
    expect(relTime('2026-06-15T11:30:00Z')).toBe('vor 30 Min.')
  })

  it('handles hours, days and weeks', () => {
    expect(relTime('2026-06-15T10:00:00Z')).toBe('vor 2 Std.')
    expect(relTime('2026-06-14T12:00:00Z')).toBe('gestern')
    expect(relTime('2026-06-12T12:00:00Z')).toBe('vor 3 Tagen')
    expect(relTime('2026-06-01T12:00:00Z')).toBe('vor 2 Wo.')
  })
})

describe('fmtClock', () => {
  it('formats seconds as HH:MM:SS', () => {
    expect(fmtClock(0)).toBe('00:00:00')
    expect(fmtClock(5)).toBe('00:00:05')
    expect(fmtClock(65)).toBe('00:01:05')
    expect(fmtClock(3600)).toBe('01:00:00')
    expect(fmtClock(3661)).toBe('01:01:01')
  })

  it('clamps negatives to zero and floors fractions', () => {
    expect(fmtClock(-5)).toBe('00:00:00')
    expect(fmtClock(3.9)).toBe('00:00:03')
  })
})

describe('fmtDurationShort', () => {
  it('omits the hour part below an hour', () => {
    expect(fmtDurationShort(0)).toBe('0 Min')
    expect(fmtDurationShort(720)).toBe('12 Min')
  })

  it('includes hours and minutes at or above an hour', () => {
    expect(fmtDurationShort(3600)).toBe('1 Std 0 Min')
    expect(fmtDurationShort(7500)).toBe('2 Std 5 Min')
  })
})

describe('clockTime', () => {
  it('formats the wall-clock time as HH:MM', () => {
    expect(clockTime('2026-06-15T08:05:00Z')).toBe('08:05')
    expect(clockTime('2026-06-15T23:59:00Z')).toBe('23:59')
  })
})

describe('dayGroupLabel', () => {
  it('names the recent days relative to today', () => {
    expect(dayGroupLabel('2026-06-15T09:00:00Z')).toBe('Heute')
    expect(dayGroupLabel('2026-06-14T09:00:00Z')).toBe('Gestern')
    expect(dayGroupLabel('2026-06-13T09:00:00Z')).toBe('Vorgestern')
  })

  it('uses the weekday name within the last week', () => {
    // 2026-06-12 is the Friday before the pinned Monday
    expect(dayGroupLabel('2026-06-12T09:00:00Z')).toBe('Freitag')
    // 2026-06-09 is the Tuesday before
    expect(dayGroupLabel('2026-06-09T09:00:00Z')).toBe('Dienstag')
  })

  it('falls back to a calendar date once 7+ days old', () => {
    expect(dayGroupLabel('2026-06-08T09:00:00Z')).toBe('8. Juni')
  })

  it('uses a calendar date for future days', () => {
    expect(dayGroupLabel('2026-06-20T09:00:00Z')).toBe('20. Juni')
  })
})

describe('weekKey (Monday-based)', () => {
  it('maps every day of a week to the same Monday key', () => {
    expect(weekKey('2026-06-15')).toBe('2026-06-15') // Monday
    expect(weekKey('2026-06-17')).toBe('2026-06-15') // Wednesday
    expect(weekKey('2026-06-21')).toBe('2026-06-15') // Sunday
  })

  it('rolls to the next Monday for the following week', () => {
    expect(weekKey('2026-06-22')).toBe('2026-06-22')
  })
})

describe('weekLabel', () => {
  it('labels the current and previous week', () => {
    expect(weekLabel('2026-06-17')).toEqual({ label: 'Diese Woche', range: '15.–21. Juni' })
    expect(weekLabel('2026-06-10')).toEqual({ label: 'Letzte Woche', range: '8.–14. Juni' })
  })

  it('renders a cross-month range without a relative label', () => {
    expect(weekLabel('2026-07-01')).toEqual({ label: null, range: '29. Juni – 5. Juli' })
  })
})

describe('userMeta', () => {
  it('returns null for missing usernames', () => {
    expect(userMeta(undefined)).toBeNull()
    expect(userMeta(null)).toBeNull()
  })

  it('capitalises the name and uses the first letter as the initial', () => {
    expect(userMeta('max')).toMatchObject({ name: 'Max', initials: 'M' })
    expect(userMeta('chen')).toMatchObject({ name: 'Chen', initials: 'C' })
  })

  it('derives a stable, case-insensitive hue and initials for any user', () => {
    const a = userMeta('Bob')
    const b = userMeta('bob')
    expect(a?.name).toBe('Bob')
    expect(a?.initials).toBe('B')
    expect(a?.hue).toBe(b?.hue) // case-insensitive, stable
    expect(a?.hue).toBeGreaterThanOrEqual(0)
    expect(a?.hue).toBeLessThan(360)
  })
})

describe('usernameFromToken', () => {
  const makeToken = (payload: unknown) =>
    `header.${btoa(JSON.stringify(payload))}.signature`

  it('decodes the username claim from the payload', () => {
    expect(usernameFromToken(makeToken({ username: 'max' }))).toBe('max')
  })

  it('returns null when the claim is absent', () => {
    expect(usernameFromToken(makeToken({ sub: 'max' }))).toBeNull()
  })

  it('returns null for malformed tokens instead of throwing', () => {
    expect(usernameFromToken('not-a-jwt')).toBeNull()
    expect(usernameFromToken('')).toBeNull()
    expect(usernameFromToken('a.!!!not-base64!!!.c')).toBeNull()
  })
})
