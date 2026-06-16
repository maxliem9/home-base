import { useState, useEffect, useCallback } from 'react'
import { useTranslation } from 'react-i18next'
import type { TFunction } from 'i18next'
import { API_BASE, errorCode, notifyTransportError, safeFetch } from '../api'
import { errorText } from '../i18n'
import { useErrorToast } from '../ui/ErrorToast'
import { Todo, TodoList, TodoPriority, Subtask, ListVisibility, RecurrenceFreq } from '../types'
import { useWebSocket } from '../hooks/useWebSocket'
import { Icon } from '../ui/Icon'
import {
  Avatar,
  Badge,
  Button,
  Card,
  Checkbox,
  EmptyState,
  Field,
  IconButton,
  Modal,
  PageHead,
  PriorityDot,
  Select,
  TextInput,
} from '../ui/primitives'
import { dueLabel, localDateIso, relTime, userMeta, usernameFromToken } from '../ui/format'
import { useHouseholdUsers } from '../hooks/useHouseholdUsers'

const WS_SCHEME = window.location.protocol === 'https:' ? 'wss' : 'ws'
const WS_URL = import.meta.env.VITE_WS_URL ?? `${WS_SCHEME}://${window.location.host}/api/v1/ws/todos`

// Sentinel tab id for the built-in Inbox tab, which shows all todos without a
// listId (Dashboard quick-add and the Android FAB create those — issue #69).
// Real list ids are UUIDs, so this can never collide.
const INBOX_ID = '__inbox__'
// Cross-list "smart" tabs (#255/#256): like the Inbox they span every list and
// are reachable from the dashboard stat tiles. Sentinel ids never collide with
// the UUID list ids.
const ALL_ID = '__all__'
const TODAY_ID = '__today__'
const TOMORROW_ID = '__tomorrow__'
const DONE_ID = '__done__'
const SMART_IDS = [ALL_ID, TODAY_ID, TOMORROW_ID, DONE_ID]
const isVirtualTab = (id: string | null): boolean => id === INBOX_ID || (!!id && SMART_IDS.includes(id))

// Done todos in the cross-list smart-views (the "Alle" done-section and the
// "Erledigt" tab) are limited to the last N calendar days so the section can't
// grow unbounded across the whole history (#263). One shared window for both:
// it caps the "Alle" done-section AND widens "Erledigt" beyond just today. The
// badge/tile COUNTS stay deliberately on "today" (doneTodayCount) and are not
// touched. N is a named constant (configurable later if needed — issue note).
const DONE_WINDOW_DAYS = 14

// Deep-link target the dashboard can ask the todos view to open (stat tiles).
export type TodosFocus = 'inbox' | 'all' | 'today' | 'tomorrow' | 'done'
const FOCUS_TO_ID: Record<TodosFocus, string> = {
  inbox: INBOX_ID,
  all: ALL_ID,
  today: TODAY_ID,
  tomorrow: TOMORROW_ID,
  done: DONE_ID,
}

// open todos are grouped into these due-date buckets, in this order. Built with the
// reactive `t` inside the view so the labels follow a language switch.
const buildBuckets = (t: TFunction): { key: string; label: string }[] => [
  { key: 'over', label: t('todos.bucketOver') },
  { key: 'today', label: t('todos.bucketToday') },
  { key: 'soon', label: t('todos.bucketSoon') },
  { key: 'far', label: t('todos.bucketFar') },
  { key: 'none', label: t('todos.bucketNone') },
]

interface PlanDraft {
  id: string
  assignee: string
  dueDate: string
  priority: '' | TodoPriority
  listId: string // target list for an inbox todo; '' = stays without a list (#69)
  recurrenceFreq: '' | RecurrenceFreq // '' = no recurrence
  recurrenceInterval: number
}

// short label for the recurrence badge on a todo row, e.g. "wöchentl." or "alle 2 Wochen"
function recurrenceBadge(t: TFunction, rec: { freq: RecurrenceFreq; interval?: number }): string {
  const n = rec.interval ?? 1
  if (n <= 1) {
    return { DAILY: t('todos.recurBadgeDaily'), WEEKLY: t('todos.recurBadgeWeekly'), MONTHLY: t('todos.recurBadgeMonthly') }[rec.freq]
  }
  const unit = { DAILY: t('todos.recurUnitDay'), WEEKLY: t('todos.recurUnitWeek'), MONTHLY: t('todos.recurUnitMonth') }[rec.freq]
  return `${t('todos.recurBadgeEvery')} ${n} ${unit}`
}

interface TodosViewProps {
  token: string
  onLogout: () => void
  // Deep-link from the dashboard stat tiles (#255/#256). The view remounts per
  // visit, so this is read on mount; an effect also re-applies it if it changes.
  initialFocus?: TodosFocus | null
}

export function TodosView({ token, onLogout, initialFocus }: TodosViewProps) {
  const { t } = useTranslation()
  const me = usernameFromToken(token)
  const householdUsers = useHouseholdUsers(token)
  const [todos, setTodos] = useState<Todo[]>([])
  const [lists, setLists] = useState<TodoList[]>([])
  const [loading, setLoading] = useState(true)
  const [activeId, setActiveId] = useState<string | null>(initialFocus ? FOCUS_TO_ID[initialFocus] : null)
  const [newTitle, setNewTitle] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [plan, setPlan] = useState<PlanDraft | null>(null)
  const [expanded, setExpanded] = useState<Set<string>>(new Set())
  const [subDrafts, setSubDrafts] = useState<Record<string, string>>({})
  const [doneOpen, setDoneOpen] = useState(false)
  const [newListOpen, setNewListOpen] = useState(false)
  const [editListOpen, setEditListOpen] = useState(false)
  const [confirmDelete, setConfirmDelete] = useState(false)
  const { flashError, errorToast } = useErrorToast()

  const fetchTodos = useCallback(async () => {
    try {
      const [todoResult, listResult] = await Promise.all([
        safeFetch(token, `${API_BASE}/todos`),
        safeFetch(token, `${API_BASE}/todos/lists`),
      ])
      // a transport reject on either → fire the global toast once, keep existing data
      if (!todoResult.ok || !listResult.ok) {
        notifyTransportError()
        return
      }
      const { res: todoRes } = todoResult
      const { res: listRes } = listResult
      if (todoRes.status === 401 || listRes.status === 401) {
        onLogout()
        return
      }
      if (todoRes.ok) setTodos(await todoRes.json())
      if (listRes.ok) setLists(await listRes.json())
    } finally {
      setLoading(false)
    }
  }, [onLogout, token])

  useEffect(() => { fetchTodos() }, [fetchTodos])

  // Keep an active tab selected as lists load / change. The first list stays
  // the default tab; the Inbox is only auto-selected when there is no list at
  // all (quick-add still works there, so list-less inbox todos stay reachable
  // on a fresh household — #69). An explicitly chosen Inbox tab is never
  // overridden. Gated on `loading` so the initial empty `lists` state doesn't
  // park the view on the Inbox before the first fetch lands.
  useEffect(() => {
    if (loading || isVirtualTab(activeId)) return
    if (lists.length === 0) {
      setActiveId(INBOX_ID)
    } else if (!activeId || !lists.some((l) => l.id === activeId)) {
      setActiveId(lists[0].id)
    }
  }, [lists, activeId, loading])

  // Apply a dashboard deep-link (stat tiles → #255/#256). Runs on mount and if
  // the requested focus changes while mounted; plain nav passes null (no-op).
  useEffect(() => {
    if (initialFocus) setActiveId(FOCUS_TO_ID[initialFocus])
  }, [initialFocus])

  useWebSocket({ url: WS_URL, token }, (raw) => {
    try {
      const msg = JSON.parse(raw)
      if (!msg.payload) return
      switch (msg.type) {
        case 'TODO_CREATED':
          setTodos((prev) => (prev.some((x) => x.id === msg.payload.id) ? prev : [msg.payload, ...prev]))
          break
        case 'TODO_UPDATED':
          setTodos((prev) =>
            prev.some((x) => x.id === msg.payload.id)
              ? prev.map((x) => (x.id === msg.payload.id ? msg.payload : x))
              : [msg.payload, ...prev],
          )
          break
        case 'TODO_DELETED':
          setTodos((prev) => prev.filter((x) => x.id !== msg.payload.id))
          break
        case 'TODO_LIST_CREATED':
          setLists((prev) => (prev.some((x) => x.id === msg.payload.id) ? prev : [...prev, msg.payload]))
          break
        case 'TODO_LIST_UPDATED':
          setLists((prev) => prev.map((x) => (x.id === msg.payload.id ? msg.payload : x)))
          break
        case 'TODO_LIST_DELETED':
          // A shared→private flip is broadcast as a delete whose payload is the now-PRIVATE list.
          // For its owner that means "keep it, just hide it from the other user" — so mark it private
          // instead of dropping it. A genuine delete always carries a SHARED list; everyone else drops
          // it either way (they lost access). See issue #75 / the private-list visibility model.
          if (msg.payload.visibility === 'PRIVATE' && msg.payload.createdBy === me) {
            setLists((prev) => prev.map((x) => (x.id === msg.payload.id ? msg.payload : x)))
          } else {
            setLists((prev) => prev.filter((x) => x.id !== msg.payload.id))
            setTodos((prev) => prev.filter((x) => x.listId !== msg.payload.id))
          }
          break
      }
    } catch {
      // ignore malformed frames
    }
  })

  // Returns true on success so callers (e.g. the plan modal) can decide whether
  // to close. On failure a toast is shown and the call resolves false.
  const patchTodo = async (id: string, body: Record<string, unknown>, fallback = t('todos.saveFailed')): Promise<boolean> => {
    const result = await safeFetch(token, `${API_BASE}/todos/${id}`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    })
    if (!result.ok) {
      flashError(errorText(null, fallback))
      return false
    }
    const { res } = result
    if (res.status === 401) {
      onLogout()
      return false
    }
    if (res.ok) {
      const updated: Todo = await res.json()
      setTodos((prev) => prev.map((x) => (x.id === updated.id ? updated : x)))
      return true
    }
    flashError(errorText(await errorCode(res), fallback))
    return false
  }

  const handleAdd = async () => {
    if (!newTitle.trim() || (!active && !inboxActive)) return
    setSubmitting(true)
    try {
      const result = await safeFetch(token, `${API_BASE}/todos`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        // In the Inbox tab the POST carries no listId at all — the backend
        // then creates a plain INBOX todo (same contract as the Dashboard
        // quick-add and the Android FAB).
        body: JSON.stringify({ title: newTitle.trim(), ...(active ? { listId: active.id } : {}) }),
      })
      if (!result.ok) return flashError(errorText(null, t('todos.addFailed')))
      const { res } = result
      if (res.status === 401) return onLogout()
      if (res.ok) {
        const created: Todo = await res.json()
        // Dedupe against the WS echo: when TODO_CREATED lands before this REST
        // response is applied, the todo is already in the list and would show
        // twice until the next reload (#61).
        setTodos((prev) => (prev.some((x) => x.id === created.id) ? prev : [created, ...prev]))
        setNewTitle('')
      } else {
        flashError(errorText(await errorCode(res), t('todos.addFailed')))
      }
    } finally {
      setSubmitting(false)
    }
  }

  const toggleDone = (todo: Todo) => {
    if (todo.status === 'DONE') {
      patchTodo(todo.id, { status: todo.dueDate || todo.assignee ? 'PLANNED' : 'INBOX' })
    } else {
      patchTodo(todo.id, { status: 'DONE' })
    }
  }

  const handlePlan = async () => {
    if (!plan) return
    if (!plan.assignee.trim() && !plan.dueDate) return
    // a recurrence needs a due date as its schedule anchor (backend enforces this too)
    if (plan.recurrenceFreq && !plan.dueDate) return
    const ok = await patchTodo(plan.id, {
      status: 'PLANNED',
      assignee: plan.assignee.trim() || undefined,
      dueDate: plan.dueDate || undefined,
      priority: plan.priority || undefined,
      // Only sent when a target list was picked (inbox todos, #69) AND the todo
      // is still list-less right now — if the partner moved it into a list while
      // the modal was open, the stale pick must not overwrite that move. An
      // absent listId means "unchanged" on the backend, so list todos stay put.
      listId: plan.listId && !todos.find((x) => x.id === plan.id)?.listId ? plan.listId : undefined,
      // freq "NONE" clears any existing rule; otherwise set/replace it
      recurrence: plan.recurrenceFreq
        ? { freq: plan.recurrenceFreq, interval: plan.recurrenceInterval }
        : { freq: 'NONE' },
    })
    // keep the modal open on failure (toast shows the reason) so the user can retry
    if (ok) setPlan(null)
  }

  const deleteTodo = async (id: string) => {
    setTodos((prev) => prev.filter((x) => x.id !== id))
    const result = await safeFetch(token, `${API_BASE}/todos/${id}`, { method: 'DELETE' })
    // On failure (transport reject or HTTP error) refetch to resync rather than
    // restoring a captured snapshot, which could clobber a concurrent WS update.
    if (!result.ok) {
      await fetchTodos()
      return flashError(errorText(null, t('todos.deleteFailed')))
    }
    const { res } = result
    if (res.status === 401) return onLogout()
    if (!res.ok) {
      await fetchTodos()
      flashError(errorText(await errorCode(res), t('todos.deleteFailed')))
    }
  }

  // --- Subtasks ---
  const applyTodo = (updated: Todo) => setTodos((prev) => prev.map((x) => (x.id === updated.id ? updated : x)))

  const addSubtask = async (todoId: string) => {
    const title = (subDrafts[todoId] ?? '').trim()
    if (!title) return
    const result = await safeFetch(token, `${API_BASE}/todos/${todoId}/subtasks`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ title }),
    })
    if (!result.ok) return flashError(errorText(null, t('todos.subAddFailed')))
    const { res } = result
    if (res.status === 401) return onLogout()
    if (res.ok) {
      applyTodo(await res.json())
      setSubDrafts((d) => ({ ...d, [todoId]: '' }))
    } else {
      flashError(errorText(await errorCode(res), t('todos.subAddFailed')))
    }
  }

  const toggleSubtask = async (todoId: string, sub: Subtask) => {
    const result = await safeFetch(token, `${API_BASE}/todos/${todoId}/subtasks/${sub.id}`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ done: !sub.done }),
    })
    if (!result.ok) return flashError(errorText(null, t('todos.subSaveFailed')))
    const { res } = result
    if (res.status === 401) return onLogout()
    if (res.ok) applyTodo(await res.json())
    else flashError(errorText(await errorCode(res), t('todos.subSaveFailed')))
  }

  const deleteSubtask = async (todoId: string, subId: string) => {
    const result = await safeFetch(token, `${API_BASE}/todos/${todoId}/subtasks/${subId}`, { method: 'DELETE' })
    if (!result.ok) return flashError(errorText(null, t('todos.subDeleteFailed')))
    const { res } = result
    if (res.status === 401) return onLogout()
    if (res.ok) applyTodo(await res.json())
    else flashError(errorText(await errorCode(res), t('todos.subDeleteFailed')))
  }

  const toggleExpand = (id: string) =>
    setExpanded((prev) => {
      const next = new Set(prev)
      next.has(id) ? next.delete(id) : next.add(id)
      return next
    })

  // --- Lists ---
  // Modal-based create/edit return an error message (null on success) so the
  // modal can show it inline and stay open for a retry — mirrors TimeView's
  // ManualEntryModal (issue #96).
  const createList = async (name: string, visibility: ListVisibility): Promise<string | null> => {
    const result = await safeFetch(token, `${API_BASE}/todos/lists`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name, visibility }),
    })
    // transport reject → no Response; surface the inline create error so the modal stays open
    if (!result.ok) return errorText(null, t('todos.listCreateFailed'))
    const { res } = result
    if (res.status === 401) {
      onLogout()
      return null
    }
    if (!res.ok) return errorText(await errorCode(res), t('todos.listCreateFailed'))
    const created: TodoList = await res.json()
    setLists((prev) => (prev.some((x) => x.id === created.id) ? prev : [...prev, created]))
    setActiveId(created.id)
    setNewListOpen(false)
    return null
  }

  // rename and/or change a list's visibility. private→shared reveals the list (and its todos via the
  // backend replay) to the other user; shared→private hides it again. (issue #75)
  const updateList = async (name: string, visibility: ListVisibility): Promise<string | null> => {
    if (!active) return null
    const result = await safeFetch(token, `${API_BASE}/todos/lists/${active.id}`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name, visibility }),
    })
    // transport reject → no Response; surface the inline edit error so the modal stays open
    if (!result.ok) return errorText(null, t('todos.listSaveFailed'))
    const { res } = result
    if (res.status === 401) {
      onLogout()
      return null
    }
    if (!res.ok) return errorText(await errorCode(res), t('todos.listSaveFailed'))
    const updated: TodoList = await res.json()
    setLists((prev) => prev.map((x) => (x.id === updated.id ? updated : x)))
    setEditListOpen(false)
    return null
  }

  // confirmed via the delete-list modal — removes the list and its todos (backend cascades)
  const removeList = async () => {
    if (!active || lists.length <= 1) return
    const removedId = active.id
    const idx = lists.findIndex((l) => l.id === removedId)
    const next = lists[idx + 1] ?? lists[idx - 1]
    setConfirmDelete(false)
    setLists((prev) => prev.filter((l) => l.id !== removedId))
    setTodos((prev) => prev.filter((x) => x.listId !== removedId))
    setActiveId(next ? next.id : null)
    const result = await safeFetch(token, `${API_BASE}/todos/lists/${removedId}`, { method: 'DELETE' })
    // On failure refetch to resync rather than restoring a captured snapshot,
    // which could clobber a concurrent WS update.
    if (!result.ok) {
      await fetchTodos()
      return flashError(errorText(null, t('todos.listDeleteFailed')))
    }
    const { res } = result
    if (res.status === 401) return onLogout()
    if (!res.ok) {
      await fetchTodos()
      flashError(errorText(await errorCode(res), t('todos.listDeleteFailed')))
    }
  }

  const inboxActive = activeId === INBOX_ID
  const allActive = activeId === ALL_ID
  const todayActive = activeId === TODAY_ID
  const tomorrowActive = activeId === TOMORROW_ID
  const doneActive = activeId === DONE_ID
  // Cross-list views (inbox + smart tabs) span every list, so their rows show the
  // origin list as meta and they offer no single quick-add target.
  const crossList = inboxActive || allActive || todayActive || tomorrowActive || doneActive
  const active = crossList ? null : lists.find((l) => l.id === activeId) ?? null
  const openCount = (id: string) => todos.filter((x) => x.listId === id && x.status !== 'DONE').length

  const todayIso = localDateIso()
  const tomorrowDate = new Date()
  tomorrowDate.setDate(tomorrowDate.getDate() + 1)
  const tomorrowIso = localDateIso(tomorrowDate)
  // Inclusive lower bound of the done window: today minus (N-1) days, so a window
  // of N days spans today and the previous N-1 calendar days. Local-date semantics
  // throughout (localDateIso), and ISO YYYY-MM-DD strings compare lexically.
  const doneWindowStart = new Date()
  doneWindowStart.setDate(doneWindowStart.getDate() - (DONE_WINDOW_DAYS - 1))
  const doneWindowStartIso = localDateIso(doneWindowStart)
  const isDueToday = (x: Todo) => x.status !== 'DONE' && dueLabel(x.dueDate)?.tone === 'today'
  const isDueTomorrow = (x: Todo) => x.status !== 'DONE' && x.dueDate === tomorrowIso
  const isDoneToday = (x: Todo) => x.status === 'DONE' && !!x.doneAt && localDateIso(new Date(x.doneAt)) === todayIso
  // Done within the shared "last N days" window (#263) — used by the "Erledigt"
  // tab and the cross-list/list done-section. A done todo without doneAt (rare,
  // pre-migration) is excluded from the window, like the today-only filter above.
  const isDoneInWindow = (x: Todo) => x.status === 'DONE' && !!x.doneAt && localDateIso(new Date(x.doneAt)) >= doneWindowStartIso

  // Inbox = alles Unverplante: Status INBOX zählt auch dann, wenn das Todo schon
  // in einer Liste liegt (Entscheidung #71 — gleiche Semantik wie die
  // Dashboard-Kachel). Listen-lose Todos bleiben unabhängig vom Status drin,
  // damit nichts unerreichbar wird (#69). `listId` may be missing entirely
  // (encodeDefaults=false drops nulls, #46).
  const inboxTodos = todos.filter((x) => x.status === 'INBOX' || !x.listId)
  // badge counts unplanned todos — the exact rule of the dashboard's inbox tile
  const inboxOpenCount = todos.filter((x) => x.status === 'INBOX').length
  // smart-tab counts — mirror the dashboard stat tiles exactly (#256)
  const allOpenCount = todos.filter((x) => x.status !== 'DONE').length
  const todayCount = todos.filter(isDueToday).length
  const tomorrowCount = todos.filter(isDueTomorrow).length
  const doneTodayCount = todos.filter(isDoneToday).length

  // todos shown in the active view
  const viewTodos = inboxActive
    ? inboxTodos
    : allActive
      ? todos
      : todayActive
        ? todos.filter(isDueToday)
        : tomorrowActive
          ? todos.filter(isDueTomorrow)
          : doneActive
            ? todos.filter(isDoneInWindow) // "Erledigt"-Tab: letzte N Tage statt nur heute (#263)
            : active
              ? todos.filter((x) => x.listId === active.id)
              : []
  const openTodos = viewTodos.filter((x) => x.status !== 'DONE')
  // Done section/tab is limited to the shared "last N days" window (#263): caps the
  // "Alle" (and per-list) collapsible done-section and is the Erledigt tab's content.
  // doneTodayCount above stays on "today" — counts are intentionally unchanged.
  const done = viewTodos
    .filter(isDoneInWindow)
    .sort((a, b) => (b.doneAt ?? '').localeCompare(a.doneAt ?? ''))

  // view-shape flags
  const showQuickAdd = inboxActive || !!active // only where the add target is unambiguous
  const useBuckets = inboxActive || allActive || !!active // group open todos by due bucket
  const hasDoneSection = (inboxActive || allActive || !!active) && done.length > 0
  // suppress the "empty" card when a cross-list view has only done todos (the
  // done section carries them); list views keep the existing "Alles erledigt".
  const showOpenEmpty = openTodos.length === 0 && !((inboxActive || allActive) && done.length > 0)
  const smartTabs = [
    { id: ALL_ID, label: t('todos.tabAll'), icon: 'archive', count: allOpenCount },
    { id: TODAY_ID, label: t('todos.tabToday'), icon: 'calendar', count: todayCount },
    { id: TOMORROW_ID, label: t('todos.tabTomorrow'), icon: 'clock', count: tomorrowCount },
    { id: DONE_ID, label: t('todos.tabDone'), icon: 'checkCircle', count: doneTodayCount },
  ]

  // bucket open todos by due tone, each bucket sorted by date
  const buckets: Record<string, Todo[]> = { over: [], today: [], soon: [], far: [], none: [] }
  openTodos.forEach((todo) => {
    const d = dueLabel(todo.dueDate)
    buckets[d ? d.tone : 'none'].push(todo)
  })
  Object.values(buckets).forEach((b) =>
    b.sort((a, c) => (a.dueDate ?? '9999').localeCompare(c.dueDate ?? '9999')),
  )
  const groups = buildBuckets(t).filter((g) => buckets[g.key].length)

  // the todo currently in the plan modal — inbox todos (no listId) additionally
  // get a list picker there, so planning can file them into a list (#69)
  const planTodo = plan ? todos.find((x) => x.id === plan.id) ?? null : null

  // One row renderer for every section (buckets, flat smart lists, done) so the
  // long prop wiring lives in a single place. Cross-list views tag each row with
  // its origin list (#71/#256).
  const renderRow = (todo: Todo) => (
    <TodoRow
      key={todo.id}
      todo={todo}
      open={expanded.has(todo.id)}
      draft={subDrafts[todo.id] ?? ''}
      listName={crossList && todo.listId ? lists.find((l) => l.id === todo.listId)?.name : undefined}
      onToggleDone={() => toggleDone(todo)}
      onToggleExpand={() => toggleExpand(todo.id)}
      onPlan={() => setPlan({ id: todo.id, assignee: todo.assignee ?? '', dueDate: todo.dueDate ?? '', priority: todo.priority ?? '', listId: '', recurrenceFreq: todo.recurrence?.freq ?? '', recurrenceInterval: todo.recurrence?.interval ?? 1 })}
      onDelete={() => deleteTodo(todo.id)}
      onToggleSub={(s) => toggleSubtask(todo.id, s)}
      onDeleteSub={(sid) => deleteSubtask(todo.id, sid)}
      onDraft={(v) => setSubDrafts((d) => ({ ...d, [todo.id]: v }))}
      onAddSub={() => addSubtask(todo.id)}
    />
  )

  return (
    <div className="hb-page">
      <PageHead eyebrow={t('todos.eyebrow')} title={t('todos.title')} />

      {/* Listen-Tabs */}
      <div className="hb-tabs" role="tablist">
        <button
          role="tab"
          aria-selected={inboxActive}
          className={`hb-tab${inboxActive ? ' is-active' : ''}`}
          onClick={() => setActiveId(INBOX_ID)}
        >
          <Icon name="inbox" size={14} stroke={2} />
          {t('inbox.tab')}
          {inboxOpenCount > 0 && <span className="hb-tab__count">{inboxOpenCount}</span>}
        </button>
        {smartTabs.map((s) => (
          <button
            key={s.id}
            role="tab"
            aria-selected={activeId === s.id}
            className={`hb-tab${activeId === s.id ? ' is-active' : ''}`}
            onClick={() => setActiveId(s.id)}
          >
            <Icon name={s.icon} size={14} stroke={2} />
            {s.label}
            {s.count > 0 && <span className="hb-tab__count">{s.count}</span>}
          </button>
        ))}
        {lists.map((l) => (
          <button
            key={l.id}
            role="tab"
            aria-selected={active?.id === l.id}
            className={`hb-tab${active?.id === l.id ? ' is-active' : ''}`}
            onClick={() => setActiveId(l.id)}
          >
            {l.visibility === 'PRIVATE' && <Icon name="lock" size={13} stroke={2} style={{ opacity: 0.7 }} />}
            {l.name}
            {openCount(l.id) > 0 && <span className="hb-tab__count">{openCount(l.id)}</span>}
          </button>
        ))}
        <button className="hb-tab hb-tab--add" onClick={() => setNewListOpen(true)}>
          <Icon name="plus" size={16} stroke={2.2} />
          {t('todos.newList')}
        </button>
      </div>

      {loading ? (
        <p className="hb-muted" style={{ textAlign: 'center', padding: 24 }}>{t('common.loading')}</p>
      ) : !active && !crossList ? null : ( // the effect above always selects a tab right after loading
        <>
          {showQuickAdd && (
            <div className="hb-quickadd" style={{ marginBottom: 24 }}>
              <Icon name="plus" size={19} stroke={2} style={{ color: 'var(--ink-3)' }} />
              <input
                value={newTitle}
                placeholder={active ? `${t('todos.quickAddPlaceholder').replace(' …', '')} in „${active.name}" …` : t('inbox.quickAddPlaceholder')}
                onChange={(e) => setNewTitle(e.target.value)}
                onKeyDown={(e) => e.key === 'Enter' && handleAdd()}
              />
              <Button size="sm" icon="plus" onClick={handleAdd} disabled={submitting || !newTitle.trim()}>
                {t('todos.addTask')}
              </Button>
            </div>
          )}

          {doneActive ? (
            // "Erledigt"-Tab: über alle Listen abgehakte Todos der letzten N Tage,
            // flach + neueste zuerst (#263; die Tab-/Kachel-Zählung bleibt "heute")
            done.length === 0 ? (
              <Card className="hb-card--pad">
                <EmptyState icon="checkCircle" title={t('todos.doneViewEmpty')} hint={t('todos.doneViewEmptyHint', { n: DONE_WINDOW_DAYS })} />
              </Card>
            ) : (
              <>
                <div className="hb-sectionlabel">{t('todos.doneWindowNote', { n: DONE_WINDOW_DAYS })}</div>
                <Card className="hb-card--pad" style={{ paddingTop: 6, paddingBottom: 6 }}>
                  <div className="hb-list">{done.map(renderRow)}</div>
                </Card>
              </>
            )
          ) : openTodos.length > 0 ? (
            useBuckets ? (
              groups.map((g) => (
                <div key={g.key} style={{ marginBottom: 22 }}>
                  <div className="hb-sectionlabel">
                    {g.label}{' '}
                    <span style={{ fontFamily: 'var(--font-mono)', color: 'var(--ink-3)', fontWeight: 500 }}>{buckets[g.key].length}</span>
                  </div>
                  <Card className="hb-card--pad" style={{ paddingTop: 6, paddingBottom: 6 }}>
                    <div className="hb-list">{buckets[g.key].map(renderRow)}</div>
                  </Card>
                </div>
              ))
            ) : (
              // "Heute"/"Morgen": ein einziger Fälligkeits-Tag → flache Liste statt Buckets
              <Card className="hb-card--pad" style={{ paddingTop: 6, paddingBottom: 6 }}>
                <div className="hb-list">{openTodos.map(renderRow)}</div>
              </Card>
            )
          ) : showOpenEmpty ? (
            <Card className="hb-card--pad">
              {inboxActive ? (
                <EmptyState icon="inbox" title={t('inbox.empty')} hint={t('inbox.emptyHint')} />
              ) : allActive ? (
                <EmptyState icon="checkCircle" title={t('todos.allEmpty')} hint={t('todos.allEmptyHint')} />
              ) : todayActive ? (
                <EmptyState icon="calendar" title={t('todos.todayEmpty')} hint={t('todos.todayEmptyHint')} />
              ) : tomorrowActive ? (
                <EmptyState icon="clock" title={t('todos.tomorrowEmpty')} hint={t('todos.tomorrowEmptyHint')} />
              ) : (
                <EmptyState icon="checkCircle" title={t('todos.allDone')} hint={t('todos.allDoneHint')} />
              )}
            </Card>
          ) : null}

          {hasDoneSection && (
            <div style={{ marginTop: 30 }}>
              <button className={`hb-donehead${doneOpen ? ' is-open' : ''}`} onClick={() => setDoneOpen((v) => !v)}>
                <Icon name="chevronDown" size={16} stroke={2.4} className="hb-donehead__chev" />
                <span className="hb-sectionlabel" style={{ margin: 0 }}>{t('todos.doneSection')}</span>
                <span className="hb-donehead__c">{done.length}</span>
                {/* the done-section is windowed to the last N days (#263) — count reflects that */}
                <span className="hb-muted" style={{ fontSize: 12 }}>{t('todos.doneWindowNote', { n: DONE_WINDOW_DAYS })}</span>
              </button>
              {doneOpen && (
                <Card className="hb-card--pad" style={{ paddingTop: 6, paddingBottom: 6, marginTop: 12 }}>
                  <div className="hb-list">{done.map(renderRow)}</div>
                </Card>
              )}
            </div>
          )}

          {active && (
            <div style={{ marginTop: 26, display: 'flex', gap: 20, alignItems: 'center' }}>
              <button className="hb-link" onClick={() => setEditListOpen(true)}>
                <Icon name="edit" size={14} stroke={2} style={{ verticalAlign: '-2px', marginRight: 5 }} />
                {t('todos.editListNamed', { name: active.name })}
              </button>
              {lists.length > 1 && (
                <button className="hb-link hb-link--danger" onClick={() => setConfirmDelete(true)}>
                  <Icon name="trash" size={14} stroke={2} style={{ verticalAlign: '-2px', marginRight: 5 }} />
                  {t('todos.deleteListNamed', { name: active.name })}
                </button>
              )}
            </div>
          )}
        </>
      )}

      <Modal
        open={!!plan}
        onClose={() => setPlan(null)}
        title={t('todos.planTitle')}
        footer={
          <>
            <Button variant="ghost" onClick={() => setPlan(null)}>{t('common.cancel')}</Button>
            <Button
              onClick={handlePlan}
              disabled={!plan || (!plan.assignee.trim() && !plan.dueDate) || (!!plan.recurrenceFreq && !plan.dueDate)}
            >
              {t('todos.plan')}
            </Button>
          </>
        }
      >
        {plan && (
          <>
            <p className="hb-muted" style={{ margin: 0, fontSize: 13.5 }}>{t('todos.planHint')}</p>
            {!planTodo?.listId && lists.length > 0 && (
              <Field label={t('todos.planList')}>
                <Select value={plan.listId} onChange={(v) => setPlan({ ...plan, listId: v })}>
                  <option value="">{t('todos.planListInbox')}</option>
                  {lists.map((l) => (
                    <option key={l.id} value={l.id}>{l.name}</option>
                  ))}
                </Select>
              </Field>
            )}
            <Field label={t('todos.assignee')}>
              <AssigneePicker value={plan.assignee} users={householdUsers} onChange={(v) => setPlan({ ...plan, assignee: v })} />
            </Field>
            <Field label={t('todos.dueDate')}>
              <TextInput type="date" value={plan.dueDate} onChange={(v) => setPlan({ ...plan, dueDate: v })} />
            </Field>
            <Field label={t('todos.priority')}>
              <Select value={plan.priority} onChange={(v) => setPlan({ ...plan, priority: v as PlanDraft['priority'] })}>
                <option value="">{t('todos.priorityNone')}</option>
                <option value="LOW">LOW</option>
                <option value="MEDIUM">MEDIUM</option>
                <option value="HIGH">HIGH</option>
              </Select>
            </Field>
            <Field
              label={t('todos.recurrence')}
              hint={plan.recurrenceFreq && !plan.dueDate ? t('todos.recurrenceNeedsDue') : undefined}
            >
              <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
                <Select
                  value={plan.recurrenceFreq}
                  onChange={(v) => setPlan({ ...plan, recurrenceFreq: v as PlanDraft['recurrenceFreq'] })}
                >
                  <option value="">{t('todos.recurrenceNone')}</option>
                  <option value="DAILY">{t('todos.recurrenceDaily')}</option>
                  <option value="WEEKLY">{t('todos.recurrenceWeekly')}</option>
                  <option value="MONTHLY">{t('todos.recurrenceMonthly')}</option>
                </Select>
                {plan.recurrenceFreq && (
                  <>
                    <span className="hb-muted" style={{ fontSize: 13.5, whiteSpace: 'nowrap' }}>{t('todos.recurrenceEvery')}</span>
                    <TextInput
                      type="number"
                      value={String(plan.recurrenceInterval)}
                      onChange={(v) => setPlan({ ...plan, recurrenceInterval: Math.max(1, Math.min(1000, Number(v) || 1)) })}
                      style={{ width: 72 }}
                    />
                    <span className="hb-muted" style={{ fontSize: 13.5, whiteSpace: 'nowrap' }}>
                      {{ DAILY: t('todos.recurUnitDay'), WEEKLY: t('todos.recurUnitWeek'), MONTHLY: t('todos.recurUnitMonth') }[plan.recurrenceFreq]}
                    </span>
                  </>
                )}
              </div>
            </Field>
          </>
        )}
      </Modal>

      {newListOpen && <NewListModal onClose={() => setNewListOpen(false)} onCreate={createList} />}

      {editListOpen && active && (
        <EditListModal list={active} onClose={() => setEditListOpen(false)} onSave={updateList} />
      )}

      <Modal
        open={confirmDelete && !!active}
        onClose={() => setConfirmDelete(false)}
        title={t('todos.deleteListTitle')}
        width={440}
        footer={
          <>
            <Button variant="ghost" onClick={() => setConfirmDelete(false)}>{t('common.cancel')}</Button>
            <Button variant="danger" icon="trash" onClick={removeList}>{t('todos.deleteListConfirm')}</Button>
          </>
        }
      >
        {active && (
          <p className="hb-muted" style={{ margin: 0, fontSize: 14, lineHeight: 1.55 }}>
            {viewTodos.length === 0 ? (
              <>Die leere Liste „<strong>{active.name}</strong>" wird gelöscht.</>
            ) : (
              <>
                „<strong>{active.name}</strong>" und{' '}
                <strong>{viewTodos.length} {viewTodos.length === 1 ? t('todos.taskOne') : t('todos.taskMany')}</strong>{' '}
                darin werden gelöscht. {t('todos.deleteListWarn')}
              </>
            )}
          </p>
        )}
      </Modal>

      {errorToast}
    </div>
  )
}

function TodoRow({
  todo,
  open,
  draft,
  listName,
  onToggleDone,
  onToggleExpand,
  onPlan,
  onDelete,
  onToggleSub,
  onDeleteSub,
  onDraft,
  onAddSub,
}: {
  todo: Todo
  open: boolean
  draft: string
  // source list shown in the row meta — set in the Inbox tab for unplanned
  // list todos (#71), so they are distinguishable from list-less ones
  listName?: string
  onToggleDone: () => void
  onToggleExpand: () => void
  onPlan: () => void
  onDelete: () => void
  onToggleSub: (s: Subtask) => void
  onDeleteSub: (subId: string) => void
  onDraft: (v: string) => void
  onAddSub: () => void
}) {
  const { t } = useTranslation()
  const due = dueLabel(todo.dueDate)
  const subs = todo.subtasks ?? []
  const doneCount = subs.filter((s) => s.done).length
  const isDone = todo.status === 'DONE'

  return (
    <div className="hb-todo">
      <div className={`hb-row${isDone ? ' hb-row--done' : ''}`}>
        <Checkbox checked={isDone} hue={todo.assignee ? userMeta(todo.assignee)?.hue : undefined} onChange={onToggleDone} />
        <div className="hb-row__main">
          <div className="hb-row__title">{todo.title}</div>
          <div className="hb-row__meta">
            {listName && (
              <span style={{ display: 'inline-flex', alignItems: 'center', gap: 4, maxWidth: 180, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                <Icon name="folder" size={12} stroke={2} />
                {listName}
              </span>
            )}
            {todo.description && (
              <span style={{ maxWidth: 280, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{todo.description}</span>
            )}
            {todo.priority && !isDone && <PriorityDot priority={todo.priority} withLabel />}
            {todo.recurrence && !isDone && (
              <span style={{ display: 'inline-flex', alignItems: 'center', gap: 4 }}>
                <Icon name="repeat" size={12} stroke={2} />
                {recurrenceBadge(t, todo.recurrence)}
              </span>
            )}
            {isDone && todo.doneAt && <span>{t('todos.markDone').toLowerCase()} {relTime(todo.doneAt)}</span>}
          </div>
        </div>
        <div className="hb-row__right">
          <button
            className={`hb-subtoggle${open ? ' is-open' : ''}${subs.length ? '' : ' is-empty'}`}
            onClick={onToggleExpand}
            title={t('todos.subtasks')}
            aria-label={t('todos.subtasks')}
          >
            <Icon name="checkCircle" size={14} stroke={2} />
            {subs.length > 0 && <span className="hb-subtoggle__c">{doneCount}/{subs.length}</span>}
            <Icon name="chevronDown" size={13} stroke={2.4} className="hb-subtoggle__chev" />
          </button>
          {due && !isDone && <Badge tone={due.tone}>{due.text}</Badge>}
          {todo.assignee ? (
            <Avatar user={todo.assignee} size={28} />
          ) : !isDone && !todo.dueDate ? (
            <Button size="sm" variant="soft" icon="calendar" onClick={onPlan}>{t('todos.plan')}</Button>
          ) : (
            <Avatar user={null} size={28} />
          )}
          <div className="hb-row__actions">
            {!isDone && <IconButton icon="edit" label={t('common.edit')} size={16} onClick={onPlan} />}
            <IconButton icon="trash" label={t('common.delete')} danger size={16} onClick={onDelete} />
          </div>
        </div>
      </div>

      {open && (
        <div className="hb-subtasks">
          {subs.map((s) => (
            <div key={s.id} className={`hb-subtask${s.done ? ' hb-subtask--done' : ''}`}>
              <Checkbox checked={s.done} onChange={() => onToggleSub(s)} />
              <span className="hb-subtask__title">{s.title}</span>
              <IconButton icon="trash" label={t('common.delete')} danger size={15} onClick={() => onDeleteSub(s.id)} />
            </div>
          ))}
          <div className="hb-subadd">
            <Icon name="plus" size={15} stroke={2.2} style={{ color: 'var(--ink-3)' }} />
            <input
              value={draft}
              placeholder={t('todos.addSubtask')}
              onChange={(e) => onDraft(e.target.value)}
              onKeyDown={(e) => e.key === 'Enter' && onAddSub()}
            />
          </div>
        </div>
      )}
    </div>
  )
}

function NewListModal({ onClose, onCreate }: { onClose: () => void; onCreate: (name: string, visibility: ListVisibility) => Promise<string | null> }) {
  const { t } = useTranslation()
  const [name, setName] = useState('')
  const [visibility, setVisibility] = useState<ListVisibility>('SHARED')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  // On failure the modal stays open and shows the reason inline (issue #96).
  const create = async () => {
    if (!name.trim() || busy) return
    setBusy(true)
    setError(null)
    try {
      const err = await onCreate(name.trim(), visibility)
      if (err) setError(err)
    } catch {
      setError(t('todos.listCreateFailed'))
    } finally {
      setBusy(false)
    }
  }

  return (
    <Modal
      open
      onClose={onClose}
      title={t('todos.newListTitle')}
      width={440}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>{t('common.cancel')}</Button>
          <Button variant="primary" icon="check" onClick={create} disabled={!name.trim() || busy}>{t('todos.createList')}</Button>
        </>
      }
    >
      <Field label={t('todos.listName')}>
        <TextInput value={name} onChange={setName} placeholder={t('todos.listNamePlaceholder')} autoFocus onKeyDown={(e) => e.key === 'Enter' && create()} />
      </Field>
      <Field label={t('todos.visibility')}>
        <VisibilityPicker visibility={visibility} onChange={setVisibility} />
      </Field>
      {error && <p className="hb-modal-error">{error}</p>}
    </Modal>
  )
}

function EditListModal({
  list,
  onClose,
  onSave,
}: {
  list: TodoList
  onClose: () => void
  onSave: (name: string, visibility: ListVisibility) => Promise<string | null>
}) {
  const { t } = useTranslation()
  const [name, setName] = useState(list.name)
  const [visibility, setVisibility] = useState<ListVisibility>(list.visibility)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const dirty = name.trim() !== list.name || visibility !== list.visibility
  // On failure the modal stays open and shows the reason inline (issue #96).
  const save = async () => {
    if (!name.trim() || !dirty || busy) return
    setBusy(true)
    setError(null)
    try {
      const err = await onSave(name.trim(), visibility)
      if (err) setError(err)
    } catch {
      setError(t('todos.listSaveFailed'))
    } finally {
      setBusy(false)
    }
  }

  return (
    <Modal
      open
      onClose={onClose}
      title={t('todos.editListTitle')}
      width={440}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>{t('common.cancel')}</Button>
          <Button variant="primary" icon="check" onClick={save} disabled={!name.trim() || !dirty || busy}>{t('todos.saveList')}</Button>
        </>
      }
    >
      <Field label={t('todos.listName')}>
        <TextInput value={name} onChange={setName} placeholder={t('todos.listNamePlaceholder')} autoFocus onKeyDown={(e) => e.key === 'Enter' && save()} />
      </Field>
      <Field label={t('todos.visibility')} hint={visibility === 'SHARED' ? t('todos.visSharedHint') : t('todos.visPrivateHint')}>
        <VisibilityPicker visibility={visibility} onChange={setVisibility} />
      </Field>
      {error && <p className="hb-modal-error">{error}</p>}
    </Modal>
  )
}

function VisibilityPicker({ visibility, onChange }: { visibility: ListVisibility; onChange: (v: ListVisibility) => void }) {
  const { t } = useTranslation()
  return (
    <div className="hb-pickrow">
      <button className={`hb-pick${visibility === 'SHARED' ? ' is-active' : ''}`} onClick={() => onChange('SHARED')}>
        <Icon name="users" size={16} stroke={2} /> {t('todos.visShared')}
      </button>
      <button className={`hb-pick${visibility === 'PRIVATE' ? ' is-active' : ''}`} onClick={() => onChange('PRIVATE')}>
        <Icon name="lock" size={16} stroke={2} /> {t('todos.visPrivate')}
      </button>
    </div>
  )
}

// Assignee chips for the plan modal — one per household member plus "Niemand"
// (clears it); mirrors the Android picker (AufgabenScreen). Clicking the active
// chip toggles it off. An assignee that isn't a household member (legacy
// free-text) is still shown so it stays selectable and isn't dropped on save.
function AssigneePicker({ value, users: household, onChange }: { value: string; users: string[]; onChange: (v: string) => void }) {
  const { t } = useTranslation()
  const known = household.some((u) => u.toLowerCase() === value.toLowerCase())
  const users = value && !known ? [...household, value] : household
  return (
    <div className="hb-pickrow">
      {users.map((u) => {
        const active = !!value && value.toLowerCase() === u.toLowerCase()
        return (
          <button key={u} className={`hb-pick${active ? ' is-active' : ''}`} onClick={() => onChange(active ? '' : u)}>
            <Avatar user={u} size={20} /> {userMeta(u)?.name ?? u}
          </button>
        )
      })}
      <button className={`hb-pick${!value ? ' is-active' : ''}`} onClick={() => onChange('')}>
        {t('todos.assigneeNone')}
      </button>
    </div>
  )
}
