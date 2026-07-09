// Data layer for the Familienkalender (#427, Phase 1). Loads the existing reads that the month
// view overlays — todos, the absence snapshot, the meal-plan range, and calendar events (#434) —
// and keeps them live over the existing WS channels. No new schema: this is a pure read aggregation.
//
// The WS wiring mirrors the rest of the app: each channel's frame just triggers a refetch (the
// payloads are small). The recipes channel is included because deleting a recipe cascades its
// meal-plan entries away server-side but only broadcasts on the recipes channel (see WochenplanView).
import { useCallback, useEffect, useMemo, useState } from 'react'
import { API_BASE, notifyTransportError, safeFetch } from '../../api'
import type { AbsenceState, CalendarEvent, MealPlanEntry, Todo } from '../../types'
import { useWebSocket } from '../../hooks/useWebSocket'
import { normalizeAbsenceState } from '../abwesenheit/core'

const WS_SCHEME = window.location.protocol === 'https:' ? 'wss' : 'ws'
const wsUrl = (channel: string) => `${WS_SCHEME}://${window.location.host}/api/v1/ws/${channel}`

const EMPTY_ABSENCE: AbsenceState = {
  users: [], absences: [], partTime: [], kitaClosures: [], customHolidays: [], settings: [],
}

// Offline read-cache (#520, rolling out the shopping read-cache #517 to the Familienkalender): mirror the
// last-loaded overlay so a launch/reload while the API is unreachable shows the previous month instead of
// an empty grid. meals + events are range-scoped, so the cached grid start ([from]) is stored with them
// and they are only seeded when it matches the currently-visible month; todos + the absence snapshot are
// whole, seed freely. Best-effort; keyed by browser. NB: fully offline the shell needs the SW (#519).
const CACHE_KEY = 'homebase_calendar_cache'

interface CalendarCache { from: string; todos: Todo[]; absence: AbsenceState; meals: MealPlanEntry[]; events: CalendarEvent[] }

function loadCalendarCache(): CalendarCache | null {
  try {
    const raw = localStorage.getItem(CACHE_KEY)
    if (!raw) return null
    const p = JSON.parse(raw) as Partial<CalendarCache>
    return {
      from: p.from ?? '',
      todos: p.todos ?? [],
      absence: p.absence ? normalizeAbsenceState(p.absence) : EMPTY_ABSENCE,
      meals: p.meals ?? [],
      events: p.events ?? [],
    }
  } catch {
    return null // private-mode / corrupt value → no seed
  }
}

function saveCalendarCache(cache: CalendarCache) {
  try {
    localStorage.setItem(CACHE_KEY, JSON.stringify(cache))
  } catch {
    /* quota / private mode — the in-memory state still works for this session */
  }
}

export interface CalendarData {
  todos: Todo[]
  absence: AbsenceState
  meals: MealPlanEntry[]
  events: CalendarEvent[]
  loading: boolean
}

/**
 * Loads + live-syncs everything the month grid needs for the visible [from, to] range (inclusive,
 * YYYY-MM-DD). `from`/`to` are kept as strings so the fetch deps stay stable across renders.
 * Todos are fetched whole (the collection is small and already visibility-filtered server-side);
 * the absence snapshot is whole too; only the meal-plan is range-scoped.
 */
export function useCalendarData(token: string, onLogout: () => void, from: string, to: string): CalendarData {
  // Seed from the durable read-cache (#520). todos + absence are whole (seed freely); meals + events
  // are range-scoped, so only restore them when the cached grid start equals the one we open on.
  const initialCache = useMemo(() => loadCalendarCache(), [])
  const seedMonthMatch = !!initialCache && initialCache.from === from // first-render `from` = the visible month
  const [todos, setTodos] = useState<Todo[]>(initialCache?.todos ?? [])
  const [absence, setAbsence] = useState<AbsenceState>(initialCache?.absence ?? EMPTY_ABSENCE)
  const [meals, setMeals] = useState<MealPlanEntry[]>(seedMonthMatch ? initialCache!.meals : [])
  const [events, setEvents] = useState<CalendarEvent[]>(seedMonthMatch ? initialCache!.events : [])
  // Skip the spinner when we already have cached content to show — refresh happens underneath.
  const [loading, setLoading] = useState(
    !(initialCache && (initialCache.todos.length > 0 || (seedMonthMatch && (initialCache.meals.length > 0 || initialCache.events.length > 0)))),
  )

  // Mirror the current overlay into the durable read-cache (#520) on every change, tagging meals+events
  // with their grid start so a later launch only restores them for the matching month. Never wipes.
  useEffect(() => {
    saveCalendarCache({ from, todos, absence, meals, events })
  }, [from, todos, absence, meals, events])

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

  const fetchEvents = useCallback(async () => {
    const result = await safeFetch(token, `${API_BASE}/events?from=${from}&to=${to}`)
    if (!result.ok) { notifyTransportError(); return }
    const { res } = result
    if (res.status === 401) { onLogout(); return }
    if (!res.ok) return
    const list = (await res.json()) as CalendarEvent[]
    setEvents(Array.isArray(list) ? list : [])
  }, [token, from, to, onLogout])

  // Initial + range-driven loads. Clear the spinner once the first batch settles.
  useEffect(() => {
    let cancelled = false
    setLoading(true)
    Promise.all([fetchTodos(), fetchAbsence(), fetchMeals(), fetchEvents()]).finally(() => {
      if (!cancelled) setLoading(false)
    })
    return () => { cancelled = true }
  }, [fetchTodos, fetchAbsence, fetchMeals, fetchEvents])

  // Live updates — each channel refetches its own slice. recipes drives a meal refetch (a recipe
  // delete cascades plan entries but only broadcasts on the recipes channel; see WochenplanView).
  useWebSocket({ url: wsUrl('todos'), token }, fetchTodos)
  useWebSocket({ url: wsUrl('absence'), token }, fetchAbsence)
  useWebSocket({ url: wsUrl('meal-plan'), token }, fetchMeals)
  useWebSocket({ url: wsUrl('recipes'), token }, fetchMeals)
  useWebSocket({ url: wsUrl('events'), token }, fetchEvents)

  return { todos, absence, meals, events, loading }
}
