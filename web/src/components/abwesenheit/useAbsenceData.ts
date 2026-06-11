// Shared data layer for the absence calendar (#99). Loads the household snapshot,
// subscribes to the absence WS channel, and exposes the mutation API (each mutator
// refetches on success). Used by BOTH AbwesenheitView (the calendar) and the
// Einstellungen → Abwesenheit subpage, so the two share one source of truth instead
// of each re-implementing the load + WS + write plumbing.
import { useCallback, useEffect, useState } from 'react'
import { API_BASE, errorCode, notifyTransportError, safeFetch } from '../../api'
import type { FetchResult } from '../../api'
import { t, errorText } from '../../i18n'
import type { AbsenceState, AbsenceType, HalfDay } from '../../types'
import { useWebSocket } from '../../hooks/useWebSocket'
import { useErrorToast } from '../../ui/ErrorToast'
import { eachDate, isWorkdayFor, normalizeAbsenceState } from './core'

const WS_SCHEME = window.location.protocol === 'https:' ? 'wss' : 'ws'
const WS_URL = `${WS_SCHEME}://${window.location.host}/api/v1/ws/absence`

const EMPTY: AbsenceState = { users: [], absences: [], partTime: [], kitaClosures: [], customHolidays: [], settings: [] }

/** Mutators against the backend; each refetches the snapshot after the change. */
export interface Api {
  setAbsence: (userId: string, date: string, type: AbsenceType, half: HalfDay | null) => Promise<void>
  clearAbsence: (userId: string, date: string) => Promise<void>
  setAbsenceRange: (userId: string, type: AbsenceType | null, from: string, to: string, half: HalfDay | null) => Promise<void>
  toggleKita: (date: string, label: string | null, keep?: boolean) => Promise<void>
  addKita: (date: string, label: string) => Promise<void>
  addKitaRange: (from: string, to: string, label: string) => Promise<void>
  updateKita: (id: string, patch: { date?: string; label?: string }) => Promise<void>
  removeKita: (id: string) => Promise<void>
  addCustomHoliday: (holiday: { month: number; day: number; half: boolean; label: string }) => Promise<void>
  updateCustomHoliday: (id: string, patch: { month?: number; day?: number; half?: boolean; label?: string }) => Promise<void>
  removeCustomHoliday: (id: string) => Promise<void>
  updateAbsSettings: (userId: string, year: number, patch: Record<string, unknown>) => Promise<void>
  addPartTime: (rule: { userId: string; weekday: number; start: string; end: string | null }) => Promise<void>
  updatePartTime: (id: string, patch: { weekday?: number; start?: string; end?: string | null }) => Promise<void>
  removePartTime: (id: string) => Promise<void>
}

export function useAbsenceData(token: string, onLogout: () => void) {
  const [data, setData] = useState<AbsenceState>(EMPTY)
  const [loading, setLoading] = useState(true)
  const { flashError, errorToast } = useErrorToast()

  const fetchState = useCallback(async () => {
    const result = await safeFetch(token, `${API_BASE}/absence`)
    // transport reject → fire the global toast once, keep existing data, clear spinner
    if (!result.ok) {
      notifyTransportError()
      setLoading(false)
      return
    }
    const { res } = result
    if (res.status === 401) {
      onLogout()
      return
    }
    // Any snapshot list may be missing when empty (encodeDefaults=false, CLAUDE.md /
    // issue #46) — normalise once at the read so the whole view can rely on arrays (#54).
    if (res.ok) setData(normalizeAbsenceState(await res.json()))
    setLoading(false)
  }, [token, onLogout])

  useEffect(() => {
    fetchState()
  }, [fetchState])

  useWebSocket({ url: WS_URL, token }, () => {
    fetchState()
  })

  // --- API mutators ---------------------------------------------------------
  const json = (body: object): RequestInit => ({
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
  // Run a mutation via safeFetch, then refetch on success. A transport reject
  // (offline/DNS — issue #93) surfaces the German fallback; on 401 → logout; on
  // any other HTTP failure show the reason as an error toast and skip the refetch
  // (the backend cleanly refused the change, so the snapshot is unchanged) — #96.
  const mutate = async (req: () => Promise<FetchResult>, fallback: string): Promise<boolean> => {
    const result = await req()
    if (!result.ok) {
      flashError(errorText(null, fallback))
      return false
    }
    const { res } = result
    if (res.status === 401) {
      onLogout()
      return false
    }
    if (!res.ok) {
      flashError(errorText(await errorCode(res), fallback))
      return false
    }
    await fetchState()
    return true
  }
  const api: Api = {
    setAbsence: async (userId, date, type, half) => {
      await mutate(() => safeFetch(token, `${API_BASE}/absence/entries`, { method: 'POST', ...json({ userId, date, type, half }) }), t.abwesenheit.saveFailed)
    },
    clearAbsence: async (userId, date) => {
      await mutate(() => safeFetch(token, `${API_BASE}/absence/entries?userId=${encodeURIComponent(userId)}&date=${date}`, { method: 'DELETE' }), t.abwesenheit.deleteFailed)
    },
    setAbsenceRange: async (userId, type, from, to, half) => {
      const dates = type
        ? eachDate(from, to).filter((ds) => isWorkdayFor(data, userId, ds))
        : eachDate(from, to)
      await mutate(() => safeFetch(token, `${API_BASE}/absence/entries/batch`, { method: 'POST', ...json({ userId, type, half, dates }) }), t.abwesenheit.saveFailed)
    },
    toggleKita: async (date, label, keep = false) => {
      const existing = data.kitaClosures.find((k) => k.date === date)
      if (label == null) {
        if (existing) await mutate(() => safeFetch(token, `${API_BASE}/absence/kita/${existing.id}`, { method: 'DELETE' }), t.abwesenheit.deleteFailed)
      } else if (keep) {
        if (existing) await mutate(() => safeFetch(token, `${API_BASE}/absence/kita/${existing.id}`, { method: 'PUT', ...json({ label }) }), t.abwesenheit.kitaFailed)
        else await mutate(() => safeFetch(token, `${API_BASE}/absence/kita`, { method: 'POST', ...json({ date, label }) }), t.abwesenheit.kitaFailed)
      } else if (!existing) {
        await mutate(() => safeFetch(token, `${API_BASE}/absence/kita`, { method: 'POST', ...json({ date, label }) }), t.abwesenheit.kitaFailed)
      }
    },
    addKita: async (date, label) => {
      await mutate(() => safeFetch(token, `${API_BASE}/absence/kita`, { method: 'POST', ...json({ date, label }) }), t.abwesenheit.kitaFailed)
    },
    addKitaRange: async (from, to, label) => {
      await mutate(() => safeFetch(token, `${API_BASE}/absence/kita/range`, { method: 'POST', ...json({ from, to, label }) }), t.abwesenheit.kitaFailed)
    },
    updateKita: async (id, patch) => {
      await mutate(() => safeFetch(token, `${API_BASE}/absence/kita/${id}`, { method: 'PUT', ...json(patch) }), t.abwesenheit.kitaFailed)
    },
    removeKita: async (id) => {
      await mutate(() => safeFetch(token, `${API_BASE}/absence/kita/${id}`, { method: 'DELETE' }), t.abwesenheit.deleteFailed)
    },
    addCustomHoliday: async (holiday) => {
      await mutate(() => safeFetch(token, `${API_BASE}/absence/holidays`, { method: 'POST', ...json(holiday) }), t.abwesenheit.holidayFailed)
    },
    updateCustomHoliday: async (id, patch) => {
      await mutate(() => safeFetch(token, `${API_BASE}/absence/holidays/${id}`, { method: 'PUT', ...json(patch) }), t.abwesenheit.holidayFailed)
    },
    removeCustomHoliday: async (id) => {
      await mutate(() => safeFetch(token, `${API_BASE}/absence/holidays/${id}`, { method: 'DELETE' }), t.abwesenheit.deleteFailed)
    },
    updateAbsSettings: async (userId, year, patch) => {
      await mutate(() => safeFetch(token, `${API_BASE}/absence/settings/${encodeURIComponent(userId)}/${year}`, { method: 'PUT', ...json(patch) }), t.abwesenheit.settingsFailed)
    },
    addPartTime: async (rule) => {
      await mutate(() => safeFetch(token, `${API_BASE}/absence/parttime`, { method: 'POST', ...json(rule) }), t.abwesenheit.partTimeFailed)
    },
    updatePartTime: async (id, patch) => {
      const rule = data.partTime.find((r) => r.id === id)
      if (!rule) return
      const body = {
        weekday: patch.weekday ?? rule.weekday,
        start: patch.start ?? rule.start,
        end: 'end' in patch ? patch.end ?? null : rule.end ?? null,
      }
      await mutate(() => safeFetch(token, `${API_BASE}/absence/parttime/${id}`, { method: 'PUT', ...json(body) }), t.abwesenheit.partTimeFailed)
    },
    removePartTime: async (id) => {
      await mutate(() => safeFetch(token, `${API_BASE}/absence/parttime/${id}`, { method: 'DELETE' }), t.abwesenheit.deleteFailed)
    },
  }

  return { data, loading, api, errorToast }
}
