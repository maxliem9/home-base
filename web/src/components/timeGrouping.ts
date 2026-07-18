import type { TimeEntry } from '../types'
import { dayGroupLabel } from '../ui/format'

export interface DayGroup {
  key: string
  label: string
  seconds: number
  entries: TimeEntry[]
}

// Group stopped entries (already sorted newest-first) into day buckets with a
// separator label and per-day total. Buckets by startedAt (not stoppedAt) so a
// cross-midnight entry lands under its start day — consistent with the "Pro Woche"
// list (#541), the backend forecast, the CSV export and Android (#544).
export function groupByDay(entries: TimeEntry[]): DayGroup[] {
  const groups: DayGroup[] = []
  const map = new Map<string, DayGroup>()
  for (const e of entries) {
    const iso = e.startedAt
    const d = new Date(iso)
    const key = `${d.getFullYear()}-${d.getMonth()}-${d.getDate()}`
    let g = map.get(key)
    if (!g) {
      g = { key, label: dayGroupLabel(iso), seconds: 0, entries: [] }
      map.set(key, g)
      groups.push(g)
    }
    g.entries.push(e)
    g.seconds += e.durationSeconds ?? 0
  }
  return groups
}
