import { describe, it, expect } from 'vitest'
import { groupByDay } from './timeGrouping'
import type { TimeEntry } from '../types'

// Tests run with TZ=UTC (see package.json test:unit), so "day" boundaries are UTC midnight.
function entry(partial: Partial<TimeEntry> & Pick<TimeEntry, 'id' | 'startedAt'>): TimeEntry {
  return {
    projectId: 'p1',
    userId: 'u1',
    stoppedAt: undefined,
    durationSeconds: 0,
    createdAt: partial.startedAt,
    updatedAt: partial.startedAt,
    ...partial,
  }
}

describe('groupByDay', () => {
  it('buckets a cross-midnight entry under its start day, not its stop day (#544)', () => {
    const crossMidnight = entry({
      id: 'e1',
      startedAt: '2026-06-03T23:00:00Z',
      stoppedAt: '2026-06-04T01:00:00Z',
      durationSeconds: 7200,
    })
    const groups = groupByDay([crossMidnight])

    expect(groups).toHaveLength(1)
    // start day = June 3 (getMonth is 0-based → month index 5)
    expect(groups[0].key).toBe('2026-5-3')
    expect(groups[0].entries.map((e) => e.id)).toEqual(['e1'])
  })

  it('groups entries by start day and sums their durations', () => {
    const groups = groupByDay([
      entry({ id: 'a', startedAt: '2026-06-03T09:00:00Z', durationSeconds: 3600 }),
      entry({ id: 'b', startedAt: '2026-06-03T23:30:00Z', stoppedAt: '2026-06-04T00:30:00Z', durationSeconds: 3600 }),
      entry({ id: 'c', startedAt: '2026-06-04T08:00:00Z', durationSeconds: 1800 }),
    ])

    expect(groups.map((g) => g.key)).toEqual(['2026-5-3', '2026-5-4'])
    const june3 = groups.find((g) => g.key === '2026-5-3')!
    expect(june3.entries.map((e) => e.id)).toEqual(['a', 'b'])
    expect(june3.seconds).toBe(7200)
    const june4 = groups.find((g) => g.key === '2026-5-4')!
    expect(june4.entries.map((e) => e.id)).toEqual(['c'])
    expect(june4.seconds).toBe(1800)
  })
})
