// Data layer for the Familienkalender (#427, Phase 1). Loads the three existing reads that the
// month view overlays — todos, the absence snapshot, and the meal-plan range — and keeps them live
// over the existing WS channels. No new endpoint, no new schema: this is a pure read aggregation.
//
// The WS wiring mirrors the rest of the app: each channel's frame just triggers a refetch (the
// payloads are small). The recipes channel is included because deleting a recipe cascades its
// meal-plan entries away server-side but only broadcasts on the recipes channel (see WochenplanView).
import { useCallback, useEffect, useState } from 'react'
import { API_BASE, notifyTransportError, safeFetch } from '../../api'
import type { AbsenceState, MealPlanEntry, Todo } from '../../types'
import { useWebSocket } from '../../hooks/useWebSocket'
import { normalizeAbsenceState } from '../abwesenheit/core'

const WS_SCHEME = window.location.protocol === 'https:' ? 'wss' : 'ws'
const wsUrl = (channel: string) => `${WS_SCHEME}://${window.location.host}/api/v1/ws/${channel}`

const EMPTY_ABSENCE: AbsenceState = {
  users: [], absences: [], partTime: [], kitaClosures: [], customHolidays: [], settings: [],
}

export interface CalendarData {
  todos: Todo[]
  absence: AbsenceState
  meals: MealPlanEntry[]
  loading: boolean
}

/**
 * Loads + live-syncs everything the month grid needs for the visible [from, to] range (inclusive,
 * YYYY-MM-DD). `from`/`to` are kept as strings so the fetch deps stay stable across renders.
 * Todos are fetched whole (the collection is small and already visibility-filtered server-side);
 * the absence snapshot is whole too; only the meal-plan is range-scoped.
 */
export function useCalendarData(token: string, onLogout: () => void, from: string, to: string): CalendarData {
  const [todos, setTodos] = useState<Todo[]>([])
  const [absence, setAbsence] = useState<AbsenceState>(EMPTY_ABSENCE)
  const [meals, setMeals] = useState<MealPlanEntry[]>([])
  const [loading, setLoading] = useState(true)

  const fetchTodos = useCallback(async () => {
    const result = await safeFetch(token, `${API_BASE}/todos`)
    if (!result.ok) { notifyTransportError(); return }
    const { res } = result
    if (res.status === 401) { onLogout(); return }
    if (!res.ok) return
    const list = (await res.json()) as Todo[]
    setTodos(Array.isArray(list) ? list : [])
  }, [token, onLogout])

  const fetchAbsence = useCallback(async () => {
    const result = await safeFetch(token, `${API_BASE}/absence`)
    if (!result.ok) { notifyTransportError(); return }
    const { res } = result
    if (res.status === 401) { onLogout(); return }
    // every snapshot list may be missing when empty (encodeDefaults=false) — normalize once (#54)
    if (res.ok) setAbsence(normalizeAbsenceState(await res.json()))
  }, [token, onLogout])

  const fetchMeals = useCallback(async () => {
    const result = await safeFetch(token, `${API_BASE}/meal-plan?from=${from}&to=${to}`)
    if (!result.ok) { notifyTransportError(); return }
    const { res } = result
    if (res.status === 401) { onLogout(); return }
    if (!res.ok) return
    const list = (await res.json()) as MealPlanEntry[]
    setMeals(Array.isArray(list) ? list : [])
  }, [token, from, to, onLogout])

  // Initial + range-driven loads. Clear the spinner once the first triad settles.
  useEffect(() => {
    let cancelled = false
    setLoading(true)
    Promise.all([fetchTodos(), fetchAbsence(), fetchMeals()]).finally(() => {
      if (!cancelled) setLoading(false)
    })
    return () => { cancelled = true }
  }, [fetchTodos, fetchAbsence, fetchMeals])

  // Live updates — each channel refetches its own slice. recipes drives a meal refetch (a recipe
  // delete cascades plan entries but only broadcasts on the recipes channel; see WochenplanView).
  useWebSocket({ url: wsUrl('todos'), token }, fetchTodos)
  useWebSocket({ url: wsUrl('absence'), token }, fetchAbsence)
  useWebSocket({ url: wsUrl('meal-plan'), token }, fetchMeals)
  useWebSocket({ url: wsUrl('recipes'), token }, fetchMeals)

  return { todos, absence, meals, loading }
}
