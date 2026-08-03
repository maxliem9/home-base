import { useState, useEffect, useCallback, useId, useMemo, useRef } from 'react'
import { useTranslation } from 'react-i18next'
import type { TFunction } from 'i18next'
import { API_BASE, errorCode, safeFetch } from '../api'
import { errorText } from '../i18n'
import { useErrorToast } from '../ui/ErrorToast'
import { Todo, TodoList, TodoPriority, Subtask, ListVisibility, RecurrenceFreq } from '../types'
import { useSyncedCollection } from '../hooks/useSyncedCollection'
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
  PRIO,
  PriorityDot,
  Select,
  Sheet,
  TextInput,
} from '../ui/primitives'
import { absDateTime, dueLabel, dueTimeLabel, localDateIso, relTime, userMeta, usernameFromToken } from '../ui/format'
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
const OVERDUE_ID = '__overdue__'
const TODAY_ID = '__today__'
const TOMORROW_ID = '__tomorrow__'
const DONE_ID = '__done__'
const SMART_IDS = [ALL_ID, OVERDUE_ID, TODAY_ID, TOMORROW_ID, DONE_ID]
const isVirtualTab = (id: string | null): boolean => id === INBOX_ID || (!!id && SMART_IDS.includes(id))

// Persist the last-active tab so re-entering the view lands where the user left
// off, aligning Web with Android (which keeps a long-lived ViewModel) — issue
// #339. Browser-scoped like the other localStorage keys (notes collapsed folders,
// shopping pending queue); a stored real-list UUID is validated against the
// current lists on load and falls back to the default if that list is gone.
const ACTIVE_TAB_KEY = 'homebase_todos_active_tab'
function loadActiveTab(): string | null {
  try {
    return localStorage.getItem(ACTIVE_TAB_KEY)
  } catch {
    return null // private-mode / unavailable storage → no remembered tab
  }
}

// Done todos in the cross-list smart-views (the "Alle" done-section and the
// "Erledigt" tab) are limited to the last N calendar days so the section can't
// grow unbounded across the whole history (#263). One shared window for both:
// it caps the "Alle" done-section AND widens "Erledigt" beyond just today. The
// badge/tile COUNTS stay deliberately on "today" (doneTodayCount) and are not
// touched. N is now household-configurable in-app (#356, app_settings
// 'done_window_days', fetched below); this constant is only the fallback used
// before the GET lands or when it's absent. A per-device "show all" toggle (#340)
// can still lift the cap to reveal the full history (see DONE_SHOW_ALL_KEY).
const DONE_WINDOW_DAYS_DEFAULT = 14
// Per-device "Alle anzeigen" preference (#340): when on, the Erledigt tab and the
// collapsible done-section show the FULL done history instead of the last N days.
// Browser-local like the other UI prefs (homebase_lang, homebase_notes_*); the
// COUNTS stay on "today" regardless. Default (absent/anything-but-"1") = windowed.
const DONE_SHOW_ALL_KEY = 'homebase_todos_done_show_all'

// Offline read-cache (#520, rolling out the shopping read-cache #517 to the tasks view): mirror the
// last-loaded lists + todos so a launch/reload while the API is unreachable shows the previous state
// instead of an empty screen. Best-effort; keyed by browser, not user (single account per browser).
// NB: fully offline the SPA shell itself may not load (the service worker is push-only, not an asset
// cache) — this covers the flaky-connection case where the shell is served from browser cache but the
// /api fetch fails, and gives an instant first paint online. True offline-shell → #519.
const CACHE_KEY = 'homebase_todos_cache'

interface TodosCache { lists: TodoList[]; todos: Todo[] }

function loadCache(): TodosCache | null {
  try {
    const raw = localStorage.getItem(CACHE_KEY)
    if (!raw) return null
    const parsed = JSON.parse(raw) as Partial<TodosCache>
    return { lists: parsed.lists ?? [], todos: parsed.todos ?? [] }
  } catch {
    return null // private-mode / corrupt value → no seed
  }
}

function saveCache(lists: TodoList[], todos: Todo[]) {
  try {
    localStorage.setItem(CACHE_KEY, JSON.stringify({ lists, todos }))
  } catch {
    /* quota / private mode — the in-memory state still works for this session */
  }
}

// Secondary sort key within a due-date bucket: higher priority first, no
// priority last. Kept in sync with the Android client (Format.prioRank).
const PRIORITY_RANK: Record<string, number> = { HIGH: 0, MEDIUM: 1, LOW: 2 }
const prioRank = (p?: string): number => (p ? PRIORITY_RANK[p] ?? 3 : 3)

// Open-todo ordering: earliest due date first, ties broken by priority
// (high → low → none). Shared by the due-buckets and the flat Heute/Morgen lists.
const byDueThenPriority = (a: Todo, c: Todo): number => {
  const byDate = (a.dueDate ?? '9999').localeCompare(c.dueDate ?? '9999')
  return byDate !== 0 ? byDate : prioRank(a.priority) - prioRank(c.priority)
}

// Deep-link target the dashboard can ask the todos view to open (stat tiles).
export type TodosFocus = 'inbox' | 'all' | 'overdue' | 'today' | 'tomorrow' | 'done'
const FOCUS_TO_ID: Record<TodosFocus, string> = {
  inbox: INBOX_ID,
  all: ALL_ID,
  overdue: OVERDUE_ID,
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

// Compare two assignee sets order-insensitively — so an untouched picker never PUTs
// `assignees` and can't clobber a concurrent partner change (mirrors the #468/#409 guard).
const sameAssignees = (a: string[], b: string[]): boolean =>
  a.length === b.length && [...a].sort().join('\u0000') === [...b].sort().join('\u0000')

interface PlanDraft {
  id: string
  title: string
  description: string
  assignees: string[]
  assigneesOriginal: string[] // assignees at open time — only PUT when the set actually changed
  dueDate: string
  dueDateOriginal: string // due date at open time — only PUT '' (clear) when it was actually emptied (#468)
  dueTime: string // "HH:mm" or '' (#429)
  priority: '' | TodoPriority
  listId: string // target list; '' = no list / inbox (#69; move between lists #409)
  listIdOriginal: string // list at open time — only PUT listId on an actual change (#409)
  recurrenceFreq: '' | RecurrenceFreq // '' = no recurrence
  recurrenceInterval: number
}

// Live auto-save in the plan sheet (edits persist automatically ~1s after the last change + on close;
// parity with the notes editor and the Android edit sheet). Debounce window in ms.
const PLAN_AUTOSAVE_DELAY = 900

// The PUT body for a plan-sheet save (shared by the debounced save + the flush-on-close).
// Conflict-avoidance (#265/#406/#409/#468): assignees/dueDate/listId are only sent when they differ
// from the *Original baseline (rebased after each save), so an untouched field never clobbers a
// concurrent partner edit; title/description/dueTime/priority/recurrence are always sent.
function planBody(p: PlanDraft): Record<string, unknown> {
  return {
    // an assignee or due date makes it PLANNED; with neither it belongs in the INBOX
    status: p.assignees.length > 0 || p.dueDate ? 'PLANNED' : 'INBOX',
    title: p.title.trim(),
    description: p.description.trim(),
    assignees: sameAssignees(p.assignees, p.assigneesOriginal) ? undefined : p.assignees,
    // #468: send '' (clear) only when there actually was a date at open time; a value sets it.
    dueDate: p.dueDate || (p.dueDateOriginal ? '' : undefined),
    // a time without a date is meaningless — force-clear it when the date is gone.
    dueTime: p.dueDate ? p.dueTime : '',
    priority: p.priority || undefined,
    listId: p.listId !== p.listIdOriginal ? p.listId : undefined,
    // freq "NONE" clears any existing rule; otherwise set/replace it
    recurrence: p.recurrenceFreq ? { freq: p.recurrenceFreq, interval: p.recurrenceInterval } : { freq: 'NONE' },
  }
}

// Fingerprint of the *editable* fields (excludes the *Original conflict baselines) — the dirty check
// so the debounce never fires a redundant save. Assignees are order-insensitive (like sameAssignees).
function planFingerprint(p: PlanDraft): string {
  return JSON.stringify([
    p.title,
    p.description,
    [...p.assignees].sort(),
    p.dueDate,
    p.dueTime,
    p.priority,
    p.listId,
    p.recurrenceFreq,
    p.recurrenceInterval,
  ])
}

// A draft may be saved only with a non-blank title and — if it recurs — a due date as the anchor.
const planValid = (p: PlanDraft): boolean => !!p.title.trim() && !(p.recurrenceFreq && !p.dueDate)

// Optional planning fields the quick-add "Details" panel can carry on create. Each is omitted from
// the POST when empty; an assignee or dueDate makes the backend create the todo as PLANNED.
interface QuickAddExtra {
  assignees?: string[]
  dueDate?: string
  dueTime?: string
  priority?: TodoPriority
  description?: string
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
  // Seed from the durable read-cache (#520) so a launch with a flaky/absent connection shows the last
  // known lists + todos instead of an empty screen; a successful fetch replaces them below. Read once
  // (useMemo, not a per-render localStorage hit) — it only feeds the initial state below.
  const initialCache = useMemo(() => loadCache(), [])
  // Skip the full-screen spinner when we already have cached content to show — refresh happens underneath.
  const hasCachedContent = !!(initialCache && (initialCache.todos.length > 0 || initialCache.lists.length > 0))

  // Household-configurable "Erledigt"-window length (#356, app_settings). Starts at the
  // fallback and is replaced by the fetched value (effect below); "Alle anzeigen" still overrides it.
  const [doneWindowDays, setDoneWindowDays] = useState(DONE_WINDOW_DAYS_DEFAULT)
  // "Alle anzeigen" für die Erledigt-Historie (#340): per-device, lifts the windowed
  // cap on the Erledigt tab + done-section. Seeded from localStorage; counts unchanged.
  const [doneShowAll, setDoneShowAll] = useState(() => {
    try {
      return localStorage.getItem(DONE_SHOW_ALL_KEY) === '1'
    } catch {
      return false
    }
  })
  // Inclusive lower bound of the done window: today minus (N-1) days, so a window of N days spans
  // today and the previous N-1 calendar days. Local-date semantics (localDateIso), ISO YYYY-MM-DD
  // strings compare lexically. Computed fresh each render (never memoized) so it can't go stale across
  // midnight; feeds both the server-side fetch window (#591) and the local isDoneInWindow filter below.
  const doneWindowStart = new Date()
  doneWindowStart.setDate(doneWindowStart.getDate() - (doneWindowDays - 1))
  const doneWindowStartIso = localDateIso(doneWindowStart)
  // Server-side "Erledigt"-Fenster (#591/#559): hang the window start on the /todos fetch as
  // ?doneSince=, so the backend drops DONE todos before that day before they hit the wire (open todos
  // always come back). In "Alle anzeigen" mode we drop the param and refetch the full history. The
  // string content is stable within a day, so useSyncedCollection's mount fetch (its `refresh` depends
  // on `endpoint`) doesn't churn across renders — but it DOES re-run when the toggle flips or the
  // configured window lands, which is exactly the refetch those transitions need. WS still pushes
  // single DONE todos outside the window; the local upsert tolerates that (idempotent) and isDoneShown
  // hides them from the view.
  const todosEndpoint = doneShowAll
    ? `${API_BASE}/todos`
    : `${API_BASE}/todos?doneSince=${doneWindowStartIso}`

  // Todos + lists share the `todos` WS channel but are two independent collections, so they are two
  // useSyncedCollection instances (#550) on the same socket (one connection thanks to #551). The hook
  // owns fetch-on-mount, 401→logout, the transport toast and the standard upsert/delete reducers;
  // optimistic updates keep using the exposed setters, and the combined read-cache stays in this view
  // (saveCache effect below). The TODO_LIST_DELETED private-flip (#75) — a shared→private flip that the
  // owner keeps but everyone else drops — is not a plain delete, so it is routed to onOtherMessage.
  const {
    items: todos,
    setItems: setTodos,
    loading: todosLoading,
    refresh: refreshTodos,
  } = useSyncedCollection<Todo>({
    token,
    endpoint: todosEndpoint,
    wsUrl: WS_URL,
    events: { created: 'TODO_CREATED', updated: 'TODO_UPDATED', deleted: 'TODO_DELETED' },
    onLogout,
    initial: initialCache?.todos ?? [],
    skipInitialLoading: hasCachedContent,
    onOtherMessage: (msg) => {
      // A genuine list delete (a SHARED list, or one that isn't mine) also drops that list's todos; a
      // shared→private flip (PRIVATE + mine) keeps them — the list only hides from the partner.
      if (msg.type === 'TODO_LIST_DELETED') {
        const p = msg.payload as TodoList | undefined
        if (!p) return
        const privateFlipForMe = p.visibility === 'PRIVATE' && p.createdBy === me
        if (!privateFlipForMe) setTodos((prev) => prev.filter((x) => x.listId !== p.id))
      }
    },
  })

  const {
    items: lists,
    setItems: setLists,
    loading: listsLoading,
    refresh: refreshLists,
  } = useSyncedCollection<TodoList>({
    token,
    endpoint: `${API_BASE}/todos/lists`,
    wsUrl: WS_URL,
    // Lists are returned oldest-first (createdAt ASC), so a new one appends; an update never inserts a
    // list we don't hold (the pre-hook reducer only mapped in place). No `deleted`: TODO_LIST_DELETED is
    // the private-flip special case, handled in onOtherMessage.
    events: { created: 'TODO_LIST_CREATED', updated: 'TODO_LIST_UPDATED', insertAt: 'end', upsertOnUpdate: false },
    onLogout,
    initial: initialCache?.lists ?? [],
    skipInitialLoading: hasCachedContent,
    onOtherMessage: (msg) => {
      if (msg.type === 'TODO_LIST_DELETED') {
        const p = msg.payload as TodoList | undefined
        if (!p) return
        if (p.visibility === 'PRIVATE' && p.createdBy === me) {
          // keep it for the owner, just flipped to private
          setLists((prev) => prev.map((x) => (x.id === p.id ? p : x)))
        } else {
          setLists((prev) => prev.filter((x) => x.id !== p.id))
        }
      }
    },
  })

  const loading = todosLoading || listsLoading
  // Refetch both collections together (the pre-hook fetchTodos loaded /todos + /todos/lists as a pair).
  const refreshTodosAndLists = useCallback(async () => {
    await Promise.all([refreshTodos(), refreshLists()])
  }, [refreshTodos, refreshLists])
  // Tab precedence on mount: an explicit dashboard deep-link wins; otherwise
  // restore the last-active tab from localStorage (#339); otherwise the
  // post-lists-load effect picks the default. A restored real-list UUID is
  // validated there once the lists arrive (stale id → default).
  const [activeId, setActiveId] = useState<string | null>(initialFocus ? FOCUS_TO_ID[initialFocus] : loadActiveTab())
  const [submitting, setSubmitting] = useState(false)
  const [plan, setPlan] = useState<PlanDraft | null>(null)
  // Plan-sheet live auto-save (parity with the notes editor + Android edit sheet): a debounced save
  // fires ~1s after the last edit and again on close. `planStatus` drives the footer chip; the refs
  // let async callbacks (debounce / flush-on-close) read the latest draft and serialize saves.
  const [planStatus, setPlanStatus] = useState<'idle' | 'saving' | 'saved' | 'error'>('idle')
  const planRef = useRef<PlanDraft | null>(null)
  const planSavedRef = useRef<string | null>(null) // fingerprint last persisted (dirty check)
  const planSavingRef = useRef(false) // serializes saves so two ticks can't double-PUT
  const planTimer = useRef<ReturnType<typeof setTimeout>>()
  // The latest draft stashed by closePlan when a save is already in flight — so the resolving save
  // still drains the final keystroke after the sheet closed and planRef was nulled (close-race fix).
  const planFlushRef = useRef<PlanDraft | null>(null)
  // the live todo behind the open plan sheet — source of the read-only metadata block (#502)
  const planTodo = plan ? todos.find((x) => x.id === plan.id) ?? null : null
  // Per-field quick-edit popovers opened straight from a row: the due date/time only, or the
  // assignees only — a lighter path than the full plan sheet. Only the edited field is captured;
  // the *other* anchor (and the DONE state) is re-read live from `todos` on save so a concurrent
  // partner change can't be clobbered by a stale snapshot.
  const [dateEdit, setDateEdit] = useState<
    { id: string; dueDate: string; dueDateOriginal: string; dueTime: string } | null
  >(null)
  const [assigneeEdit, setAssigneeEdit] = useState<{ id: string; assignees: string[] } | null>(null)
  // #628: a recurring todo needs its due date as the schedule anchor — the backend rejects an empty
  // date with INVALID_RECURRENCE. Block the invalid draft upfront (like the plan sheet does) instead
  // of letting the user run into a dead end.
  const dateEditRecurs = dateEdit ? !!todos.find((x) => x.id === dateEdit.id)?.recurrence : false
  const dateEditInvalid = dateEditRecurs && !dateEdit?.dueDate
  const [expanded, setExpanded] = useState<Set<string>>(new Set())
  const [subDrafts, setSubDrafts] = useState<Record<string, string>>({})
  const [doneOpen, setDoneOpen] = useState(false)
  const [newListOpen, setNewListOpen] = useState(false)
  const [editListOpen, setEditListOpen] = useState(false)
  const [confirmDelete, setConfirmDelete] = useState(false)
  const { flashError, errorToast } = useErrorToast()

  // Flip the "Alle anzeigen" preference and persist it per-device (#340). Best-effort:
  // a write failure (private mode/quota) still toggles for this session.
  const toggleDoneShowAll = useCallback(() => {
    setDoneShowAll((v) => {
      const next = !v
      try {
        localStorage.setItem(DONE_SHOW_ALL_KEY, next ? '1' : '0')
      } catch {
        /* storage unavailable → in-memory only for this session */
      }
      return next
    })
  }, [])

  // Mirror the current lists + todos into the durable read-cache (#520) on every change — server
  // fetches and optimistic edits alike — so the next launch can show the last state offline. The
  // initial run re-writes the seeded cache (harmless); it never wipes it, since the state was seeded
  // from that same cache rather than starting empty.
  useEffect(() => {
    saveCache(lists, todos)
  }, [lists, todos])

  // Load the household-configured "Erledigt"-window length (#356) once on mount. Best-effort:
  // any failure (transport, 401-handled-elsewhere, absent field) leaves the fallback in place,
  // so the view behaves exactly as before this setting existed. encodeDefaults=false means the
  // `days` field can be missing → `?? DONE_WINDOW_DAYS_DEFAULT`.
  useEffect(() => {
    let alive = true
    safeFetch(token, `${API_BASE}/config/done-window`).then(async (result) => {
      if (!alive || !result.ok || !result.res.ok) return
      const data: { days?: number } = await result.res.json()
      if (typeof data.days === 'number') setDoneWindowDays(data.days)
    })
    return () => { alive = false }
  }, [token])

  // Keep an active tab selected as lists load / change. The first list stays
  // the default tab; the Inbox is only auto-selected when there is no list at
  // all (quick-add still works there, so list-less inbox todos stay reachable
  // on a fresh household — #69). An explicitly chosen Inbox tab is never
  // overridden. Gated on `loading` so the initial empty `lists` state doesn't
  // park the view on the Inbox before the first fetch lands. This also validates
  // a restored tab (#339): a virtual sentinel passes the guard, but a stored
  // real-list UUID whose list no longer exists falls through to the default.
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

  // Remember the last-active tab across re-entry/reload (#339). Persisted only
  // after the default is resolved (skip the initial null) so we never store a
  // transient empty value over a good remembered tab.
  useEffect(() => {
    if (activeId === null) return
    try {
      localStorage.setItem(ACTIVE_TAB_KEY, activeId)
    } catch {
      /* quota / private mode — remembering is best-effort, the session still works */
    }
  }, [activeId])

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

  // Create a todo from the quick-add bar. `extra` carries the optional planning fields set in the
  // expandable Details panel; the backend promotes the todo to PLANNED when an assignee or due date
  // is present, else it stays INBOX. Returns true on success so QuickAdd can reset its
  // fields; the title-clear/restore for the #384 fast-capture race lives in QuickAdd.submit().
  const addTodo = async (title: string, extra: QuickAddExtra): Promise<boolean> => {
    if (!active && !inboxActive) return false
    setSubmitting(true)
    try {
      const result = await safeFetch(token, `${API_BASE}/todos`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        // In the Inbox tab the POST carries no listId at all — the backend then creates a plain
        // INBOX todo (same contract as the Dashboard quick-add and the Android FAB). A list tab
        // files it into that list. Planning fields are only sent when set.
        body: JSON.stringify({
          title,
          ...(active ? { listId: active.id } : {}),
          ...(extra.assignees && extra.assignees.length ? { assignees: extra.assignees } : {}),
          ...(extra.dueDate ? { dueDate: extra.dueDate } : {}),
          ...(extra.dueTime ? { dueTime: extra.dueTime } : {}),
          ...(extra.priority ? { priority: extra.priority } : {}),
          ...(extra.description ? { description: extra.description } : {}),
        }),
      })
      if (!result.ok) {
        flashError(errorText(null, t('todos.addFailed')))
        return false
      }
      const { res } = result
      if (res.status === 401) {
        onLogout()
        return false
      }
      if (res.ok) {
        const created: Todo = await res.json()
        // Dedupe against the WS echo: when TODO_CREATED lands before this REST
        // response is applied, the todo is already in the list and would show
        // twice until the next reload (#61).
        setTodos((prev) => (prev.some((x) => x.id === created.id) ? prev : [created, ...prev]))
        return true
      }
      flashError(errorText(await errorCode(res), t('todos.addFailed')))
      return false
    } finally {
      setSubmitting(false)
    }
  }

  const toggleDone = (todo: Todo) => {
    if (todo.status === 'DONE') {
      patchTodo(todo.id, { status: todo.dueDate || (todo.assignees?.length ?? 0) > 0 ? 'PLANNED' : 'INBOX' })
    } else {
      patchTodo(todo.id, { status: 'DONE' })
    }
  }

  // Live auto-save for the plan sheet: PUT the current draft (dirty + valid only), rebase
  // the conflict-avoidance baselines to what we saved, and — if the draft changed mid-PUT — persist the
  // newer version (mirrors the Android save loop). Invoked via savePlanRef from the debounce + on close.
  const savePlan = async (explicit?: PlanDraft) => {
    const p = explicit ?? planRef.current
    if (!p || planSavingRef.current || !planValid(p)) return
    const fp = planFingerprint(p)
    if (fp === planSavedRef.current) return // not dirty → no redundant save
    planSavingRef.current = true
    setPlanStatus('saving')
    const ok = await patchTodo(p.id, planBody(p))
    planSavingRef.current = false
    if (!ok) return setPlanStatus('error') // toast already shown; keep the sheet open to retry
    planSavedRef.current = fp
    setPlanStatus('saved')
    // Rebase the *Original baselines to the just-saved values so a later save doesn't re-send an
    // unchanged field (and can't clobber a concurrent partner edit). Only the baselines change —
    // never the user's in-progress editable values.
    setPlan((cur) =>
      cur && cur.id === p.id
        ? { ...cur, assigneesOriginal: p.assignees, dueDateOriginal: p.dueDate, listIdOriginal: p.listId }
        : cur,
    )
    // A keystroke that landed mid-PUT is still dirty → persist the newer version. The newest draft of
    // THIS todo is either the live one (still editing) or the one stashed by closePlan (sheet closed
    // mid-save) — pick whichever matches p.id so the last edit survives the close race. Settles once
    // the draft stops changing.
    const stashed = planFlushRef.current
    planFlushRef.current = null
    const now = [planRef.current, stashed].find((d): d is PlanDraft => !!d && d.id === p.id)
    if (now && planFingerprint(now) !== fp) savePlan(now)
  }

  // Keep a live handle to the latest savePlan closure so the debounce / close (which subscribe only to
  // `plan`) always call the current one without re-arming their timers every render.
  const savePlanRef = useRef(savePlan)
  useEffect(() => {
    savePlanRef.current = savePlan
  })
  // Mirror the draft into a ref so async callbacks read the latest without re-subscribing.
  useEffect(() => {
    planRef.current = plan
  }, [plan])
  // Seed the dirty baseline + reset status when a different todo's sheet opens (not on every keystroke).
  useEffect(() => {
    if (!plan) return
    planSavedRef.current = planFingerprint(plan)
    planSavingRef.current = false
    planFlushRef.current = null
    setPlanStatus('idle')
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [plan?.id])
  // Debounced live save: ~900ms after the last edit (parity with the notes editor + Android sheet).
  useEffect(() => {
    if (!plan) return
    clearTimeout(planTimer.current)
    planTimer.current = setTimeout(() => savePlanRef.current(), PLAN_AUTOSAVE_DELAY)
    return () => clearTimeout(planTimer.current)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [plan])

  // Closing the sheet (✕ / outside / Esc / "Fertig") flushes a pending save, then closes. The PUT runs
  // to completion even after the sheet is gone (patchTodo isn't tied to the sheet), so the last edit is
  // never lost; a failure still surfaces via the toast.
  const closePlan = () => {
    clearTimeout(planTimer.current)
    // If a save is already in flight, stash the latest draft so THAT save drains the final keystroke
    // after we null the plan below; otherwise flush now. Either way the last edit is never lost.
    if (planSavingRef.current) planFlushRef.current = planRef.current
    else void savePlanRef.current()
    setPlan(null)
    setPlanStatus('idle')
  }

  // Quick-edit: save just the due date/time from the row popover. Recomputes status the same way the
  // plan sheet does — clearing the date on an assignee-less todo drops it back to the INBOX. The
  // other anchor + DONE state are re-read live (not snapshotted) so a DONE todo stays DONE and a
  // concurrent assignee change isn't clobbered.
  const handleDateEdit = async () => {
    if (!dateEdit) return
    const cur = todos.find((x) => x.id === dateEdit.id)
    if (!cur) return setDateEdit(null) // vanished under us (WS delete) → just close
    // #628: clearing the anchor of a recurring todo would be rejected (INVALID_RECURRENCE). Save is
    // already disabled for that draft; this guard catches the WS race (the recurrence arrived while
    // the dialog was open) and says why instead of doing nothing.
    if (cur.recurrence && !dateEdit.dueDate) return flashError(t('todos.recurrenceKeepsDue'))
    const hasAssignee = (cur.assignees?.length ?? 0) > 0
    const ok = await patchTodo(dateEdit.id, {
      status: cur.status === 'DONE' ? undefined : dateEdit.dueDate || hasAssignee ? 'PLANNED' : 'INBOX',
      // #468: send '' (clear) only when there actually was a date at open time; a value sets it.
      dueDate: dateEdit.dueDate || (dateEdit.dueDateOriginal ? '' : undefined),
      // a time without a date is meaningless — force-clear it when the date is gone.
      dueTime: dateEdit.dueDate ? dateEdit.dueTime : '',
    })
    if (ok) setDateEdit(null)
  }

  // Quick-edit: save just the assignee set from the row popover (live re-read as above).
  const handleAssigneeEdit = async () => {
    if (!assigneeEdit) return
    const cur = todos.find((x) => x.id === assigneeEdit.id)
    if (!cur) return setAssigneeEdit(null)
    const ok = await patchTodo(assigneeEdit.id, {
      status: cur.status === 'DONE' ? undefined : assigneeEdit.assignees.length > 0 || !!cur.dueDate ? 'PLANNED' : 'INBOX',
      assignees: assigneeEdit.assignees,
    })
    if (ok) setAssigneeEdit(null)
  }

  const deleteTodo = async (id: string) => {
    setTodos((prev) => prev.filter((x) => x.id !== id))
    const result = await safeFetch(token, `${API_BASE}/todos/${id}`, { method: 'DELETE' })
    // On failure (transport reject or HTTP error) refetch to resync rather than
    // restoring a captured snapshot, which could clobber a concurrent WS update.
    if (!result.ok) {
      await refreshTodosAndLists()
      return flashError(errorText(null, t('todos.deleteFailed')))
    }
    const { res } = result
    if (res.status === 401) return onLogout()
    if (!res.ok) {
      await refreshTodosAndLists()
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
      await refreshTodosAndLists()
      return flashError(errorText(null, t('todos.listDeleteFailed')))
    }
    const { res } = result
    if (res.status === 401) return onLogout()
    if (!res.ok) {
      await refreshTodosAndLists()
      flashError(errorText(await errorCode(res), t('todos.listDeleteFailed')))
    }
  }

  const inboxActive = activeId === INBOX_ID
  const allActive = activeId === ALL_ID
  const overdueActive = activeId === OVERDUE_ID
  const todayActive = activeId === TODAY_ID
  const tomorrowActive = activeId === TOMORROW_ID
  const doneActive = activeId === DONE_ID
  // Cross-list views (inbox + smart tabs) span every list, so their rows show the
  // origin list as meta and they offer no single quick-add target.
  const crossList = inboxActive || allActive || overdueActive || todayActive || tomorrowActive || doneActive
  const active = crossList ? null : lists.find((l) => l.id === activeId) ?? null
  const openCount = (id: string) => todos.filter((x) => x.listId === id && x.status !== 'DONE').length

  const todayIso = localDateIso()
  const tomorrowDate = new Date()
  tomorrowDate.setDate(tomorrowDate.getDate() + 1)
  const tomorrowIso = localDateIso(tomorrowDate)
  const isOverdue = (x: Todo) => x.status !== 'DONE' && dueLabel(x.dueDate)?.tone === 'over'
  const isDueToday = (x: Todo) => x.status !== 'DONE' && dueLabel(x.dueDate)?.tone === 'today'
  const isDueTomorrow = (x: Todo) => x.status !== 'DONE' && x.dueDate === tomorrowIso
  const isDoneToday = (x: Todo) => x.status === 'DONE' && !!x.doneAt && localDateIso(new Date(x.doneAt)) === todayIso
  // Done within the shared "last N days" window (#263). A done todo without doneAt
  // (rare, pre-migration) is excluded from the window, like the today-only filter above.
  const isDoneInWindow = (x: Todo) => x.status === 'DONE' && !!x.doneAt && localDateIso(new Date(x.doneAt)) >= doneWindowStartIso
  // What the "Erledigt" tab and the cross-list/list done-section actually show:
  // the windowed set by default, or — when "Alle anzeigen" is on (#340) — every DONE
  // todo regardless of age. (A DONE todo without doneAt still appears in show-all mode;
  // it just sorts last by the empty-string doneAt key.) The COUNTS stay on "today".
  const isDoneShown = (x: Todo) => (doneShowAll ? x.status === 'DONE' : isDoneInWindow(x))

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
  const overdueCount = todos.filter(isOverdue).length
  const todayCount = todos.filter(isDueToday).length
  const tomorrowCount = todos.filter(isDueTomorrow).length
  const doneTodayCount = todos.filter(isDoneToday).length

  // todos shown in the active view
  const viewTodos = inboxActive
    ? inboxTodos
    : allActive
      ? todos
      : overdueActive
        ? todos.filter(isOverdue)
        : todayActive
          ? todos.filter(isDueToday)
          : tomorrowActive
            ? todos.filter(isDueTomorrow)
            : doneActive
              ? todos.filter(isDoneShown) // "Erledigt"-Tab: letzte N Tage (#263) bzw. alles bei "Alle anzeigen" (#340)
              : active
                ? todos.filter((x) => x.listId === active.id)
                : []
  const openTodos = viewTodos.filter((x) => x.status !== 'DONE')
  // Done section/tab content: the shared "last N days" window (#263), or — with
  // "Alle anzeigen" (#340) — the full history. Caps the "Alle" (and per-list)
  // collapsible done-section and is the Erledigt tab's content. Sorted by doneAt
  // desc (newest first). doneTodayCount above stays on "today" — counts unchanged.
  const done = viewTodos
    .filter(isDoneShown)
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
    { id: OVERDUE_ID, label: t('todos.tabOverdue'), icon: 'flag', count: overdueCount },
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
  // earliest due date first, ties broken by priority (high → low → none)
  Object.values(buckets).forEach((b) => b.sort(byDueThenPriority))
  // Inbox-Tab (#306): die unverplanten, undatierten Todos ("Ohne Datum" — wo
  // Status-INBOX-Quick-Adds landen) gehören nach oben, darin neueste zuerst
  // (createdAt desc). Andere Tabs ("Alle"/Listen) behalten die übliche
  // Reihenfolge (Überfällig zuerst) und ihre dueDate-asc-Sortierung.
  if (inboxActive) {
    buckets.none.sort((a, c) => (c.createdAt ?? '').localeCompare(a.createdAt ?? ''))
  }
  const allBuckets = buildBuckets(t)
  const orderedBuckets = inboxActive
    ? [...allBuckets.filter((g) => g.key === 'none'), ...allBuckets.filter((g) => g.key !== 'none')]
    : allBuckets
  const groups = orderedBuckets.filter((g) => buckets[g.key].length)

  // "Alle anzeigen" ↔ "Nur letzte N Tage" toggle for the done UI (#340). Rendered both
  // in the Erledigt tab (next to the window note) and the collapsible done-section header.
  const doneShowAllToggle = (
    <button
      type="button"
      className="hb-link"
      onClick={toggleDoneShowAll}
      title={doneShowAll ? t('todos.doneShowWindow', { n: doneWindowDays }) : t('todos.doneShowAll')}
    >
      <Icon name="chevronDown" size={14} stroke={2.2} style={doneShowAll ? { transform: 'rotate(180deg)' } : undefined} />
      {doneShowAll ? t('todos.doneShowWindow', { n: doneWindowDays }) : t('todos.doneShowAll')}
    </button>
  )

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
      onPlan={() => setPlan({ id: todo.id, title: todo.title, description: todo.description ?? '', assignees: todo.assignees ?? [], assigneesOriginal: todo.assignees ?? [], dueDate: todo.dueDate ?? '', dueDateOriginal: todo.dueDate ?? '', dueTime: dueTimeLabel(todo.dueTime) ?? '', priority: todo.priority ?? '', listId: todo.listId ?? '', listIdOriginal: todo.listId ?? '', recurrenceFreq: todo.recurrence?.freq ?? '', recurrenceInterval: todo.recurrence?.interval ?? 1 })}
      onEditDate={() => setDateEdit({ id: todo.id, dueDate: todo.dueDate ?? '', dueDateOriginal: todo.dueDate ?? '', dueTime: dueTimeLabel(todo.dueTime) ?? '' })}
      onEditAssignee={() => setAssigneeEdit({ id: todo.id, assignees: todo.assignees ?? [] })}
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

      {/* Filter-Tabs (listenübergreifend): Inbox + Smart-Views auf eigener Zeile,
          getrennt von den projektbasierten Listen-Tabs darunter. */}
      <div className="hb-tabs hb-tabs--filters" role="tablist" aria-label={t('todos.filtersAria')}>
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
      </div>

      {/* Listen-Tabs (projektbasiert) */}
      <div className="hb-tabs" role="tablist" aria-label={t('todos.listsAria')}>
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
            <QuickAdd
              placeholder={active ? `${t('todos.quickAddPlaceholder').replace(' …', '')} in „${active.name}" …` : t('inbox.quickAddPlaceholder')}
              users={householdUsers}
              submitting={submitting}
              onAdd={addTodo}
            />
          )}

          {doneActive ? (
            // "Erledigt"-Tab: über alle Listen abgehakte Todos der letzten N Tage —
            // bzw. die ganze Historie bei "Alle anzeigen" (#340) — flach + neueste
            // zuerst (#263; die Tab-/Kachel-Zählung bleibt bewusst "heute").
            done.length === 0 ? (
              <Card className="hb-card--pad">
                <EmptyState
                  icon="checkCircle"
                  title={t('todos.doneViewEmpty')}
                  hint={doneShowAll ? t('todos.doneViewEmptyAllHint') : t('todos.doneViewEmptyHint', { n: doneWindowDays })}
                />
                {/* even with nothing in the window, let the user flip to/from the full history */}
                <div style={{ marginTop: 14, display: 'flex', justifyContent: 'center' }}>{doneShowAllToggle}</div>
              </Card>
            ) : (
              <>
                <div className="hb-donewindowbar">
                  <span className="hb-sectionlabel" style={{ margin: 0 }}>
                    {doneShowAll ? t('todos.doneShowingAll') : t('todos.doneWindowNote', { n: doneWindowDays })}
                  </span>
                  {doneShowAllToggle}
                </div>
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
                <div className="hb-list">{[...openTodos].sort(byDueThenPriority).map(renderRow)}</div>
              </Card>
            )
          ) : showOpenEmpty ? (
            <Card className="hb-card--pad">
              {inboxActive ? (
                <EmptyState icon="inbox" title={t('inbox.empty')} hint={t('inbox.emptyHint')} />
              ) : allActive ? (
                <EmptyState icon="checkCircle" title={t('todos.allEmpty')} hint={t('todos.allEmptyHint')} />
              ) : overdueActive ? (
                <EmptyState icon="flag" title={t('todos.overdueEmpty')} hint={t('todos.overdueEmptyHint')} />
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
                {/* windowed to the last N days (#263), or the full history when "Alle anzeigen" is on (#340) */}
                <span className="hb-muted" style={{ fontSize: 12 }}>
                  {doneShowAll ? t('todos.doneShowingAll') : t('todos.doneWindowNote', { n: doneWindowDays })}
                </span>
              </button>
              {doneOpen && (
                <>
                  {/* sibling of the collapse button (a button can't nest a button) (#340) */}
                  <div style={{ margin: '8px 0 0 2px' }}>{doneShowAllToggle}</div>
                  <Card className="hb-card--pad" style={{ paddingTop: 6, paddingBottom: 6, marginTop: 12 }}>
                    <div className="hb-list">{done.map(renderRow)}</div>
                  </Card>
                </>
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

      <Sheet
        open={!!plan}
        onClose={closePlan}
        title={t('todos.planTitle')}
        footer={
          <>
            {/* Auto-save: no Save/Cancel — edits persist live. A status chip on the left, "Fertig" to close. */}
            <span
              className="hb-muted"
              aria-live="polite"
              style={{ marginRight: 'auto', fontSize: 13, display: 'inline-flex', alignItems: 'center', gap: 6 }}
            >
              {planStatus === 'saving' && t('todos.autosaveSaving')}
              {planStatus === 'saved' && (
                <>
                  <Icon name="check" size={13} stroke={2.4} /> {t('todos.autosaveSaved')}
                </>
              )}
              {planStatus === 'error' && (
                <span style={{ color: 'var(--clay)' }}>{t('todos.autosaveError')}</span>
              )}
            </span>
            <Button onClick={closePlan}>{t('todos.planDone')}</Button>
          </>
        }
      >
        {plan && (
          <>
            <p className="hb-muted" style={{ margin: 0, fontSize: 13.5 }}>{t('todos.planHint')}</p>
            <Field label={t('todos.titleLabel')}>
              <TextInput value={plan.title} onChange={(v) => setPlan({ ...plan, title: v })} />
            </Field>
            {lists.length > 0 && (
              <Field label={t('todos.planList')}>
                <Select value={plan.listId} onChange={(v) => setPlan({ ...plan, listId: v })}>
                  <option value="">{t('todos.planListInbox')}</option>
                  {lists.map((l) => (
                    <option key={l.id} value={l.id}>{l.name}</option>
                  ))}
                </Select>
              </Field>
            )}
            <Field label={t('todos.description')}>
              <textarea
                className="hb-input"
                rows={2}
                value={plan.description}
                placeholder={t('todos.descriptionPlaceholder')}
                onChange={(e) => setPlan({ ...plan, description: e.target.value })}
                style={{ resize: 'vertical', lineHeight: 1.5 }}
              />
            </Field>
            <Field label={t('todos.assignee')} group>
              <AssigneePicker value={plan.assignees} users={householdUsers} onChange={(v) => setPlan({ ...plan, assignees: v })} />
            </Field>
            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 14 }}>
              <Field label={t('todos.dueDate')}>
                <TextInput type="date" value={plan.dueDate} onChange={(v) => setPlan({ ...plan, dueDate: v })} />
              </Field>
              {/* Optional time-of-day (#429) — only meaningful with a date, so disabled without one. */}
              <Field label={t('todos.dueTime')}>
                <TextInput type="time" value={plan.dueTime} disabled={!plan.dueDate} onChange={(v) => setPlan({ ...plan, dueTime: v })} />
              </Field>
            </div>
            <Field label={t('todos.priority')} group>
              {/* Chip row (#407) — same affordance as the quick-add Details panel; clicking the active
                  chip toggles priority back to none. Replaces the former raw LOW/MEDIUM/HIGH <Select>. */}
              <div className="hb-pickrow">
                {(Object.keys(PRIO) as TodoPriority[]).map((k) => (
                  <button
                    key={k}
                    type="button"
                    className={`hb-pick${plan.priority === k ? ' is-active' : ''}`}
                    onClick={() => setPlan({ ...plan, priority: plan.priority === k ? '' : k })}
                  >
                    <span className="hb-prio__dot" style={{ background: `oklch(0.6 0.13 ${PRIO[k].hue})` }} />
                    {t(PRIO[k].labelKey)}
                  </button>
                ))}
              </div>
            </Field>
            <Field
              label={t('todos.recurrence')}
              hint={plan.recurrenceFreq && !plan.dueDate ? t('todos.recurrenceNeedsDue') : undefined}
              // group, not <label>: once a frequency is picked this holds a <Select> *and* an
              // interval <input> — two controls, so one wrapping <label> is ambiguous (#426).
              // Always-on (not conditional on freq) so the wrapper element never swaps label↔div
              // mid-interaction, which would remount the Select and drop focus right after the pick.
              group
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
            {planTodo && <TodoMeta todo={planTodo} />}
          </>
        )}
      </Sheet>

      {/* Quick-edit: due date/time only, opened by clicking the date badge on a row (#) */}
      <Modal
        open={!!dateEdit}
        onClose={() => setDateEdit(null)}
        title={t('todos.editDateTitle')}
        footer={
          <>
            <Button variant="ghost" onClick={() => setDateEdit(null)}>{t('common.cancel')}</Button>
            <Button onClick={handleDateEdit} disabled={dateEditInvalid}>{t('common.save')}</Button>
          </>
        }
      >
        {dateEdit && (
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 14 }}>
            <Field
              label={t('todos.dueDate')}
              hint={dateEditRecurs ? t('todos.recurrenceKeepsDue') : undefined}
            >
              <TextInput type="date" value={dateEdit.dueDate} onChange={(v) => setDateEdit({ ...dateEdit, dueDate: v })} />
            </Field>
            <Field label={t('todos.dueTime')}>
              <TextInput type="time" value={dateEdit.dueTime} disabled={!dateEdit.dueDate} onChange={(v) => setDateEdit({ ...dateEdit, dueTime: v })} />
            </Field>
          </div>
        )}
      </Modal>

      {/* Quick-edit: assignees only, opened by clicking the assignee avatars on a row */}
      <Modal
        open={!!assigneeEdit}
        onClose={() => setAssigneeEdit(null)}
        title={t('todos.editAssigneeTitle')}
        footer={
          <>
            <Button variant="ghost" onClick={() => setAssigneeEdit(null)}>{t('common.cancel')}</Button>
            <Button onClick={handleAssigneeEdit}>{t('common.save')}</Button>
          </>
        }
      >
        {assigneeEdit && (
          <Field label={t('todos.assignee')} group>
            <AssigneePicker value={assigneeEdit.assignees} users={householdUsers} onChange={(v) => setAssigneeEdit({ ...assigneeEdit, assignees: v })} />
          </Field>
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

// Quick-add bar with an opt-in "Details" panel (design handoff). The title input is
// always visible for fast title-only capture (Enter or "Erfassen" → INBOX). Expanding Details lets
// the user set assignee/due/priority/description inline before capturing, so the todo is created
// already PLANNED. An accent dot on the toggle signals that hidden fields are set even when the panel
// is collapsed; everything resets after a successful capture.
function QuickAdd({
  placeholder,
  users,
  submitting,
  onAdd,
}: {
  placeholder: string
  users: string[]
  submitting: boolean
  onAdd: (title: string, extra: QuickAddExtra) => Promise<boolean>
}) {
  const { t } = useTranslation()
  const panelId = useId()
  const [title, setTitle] = useState('')
  const [open, setOpen] = useState(false)
  const [assignees, setAssignees] = useState<string[]>([])
  const [dueDate, setDueDate] = useState('')
  const [dueTime, setDueTime] = useState('')
  const [priority, setPriority] = useState<'' | TodoPriority>('')
  const [description, setDescription] = useState('')

  // Any hidden field set lights the accent dot on the toggle, so collapsed-panel state stays visible.
  const hasDetails = !!(assignees.length || dueDate || dueTime || priority || description.trim())

  // Clear the detail fields after a successful capture but KEEP the panel open, so several
  // planned todos can be entered in a row without re-opening Details (#408). Esc and the
  // toggle button still collapse it.
  const clearDetails = () => {
    setAssignees([])
    setDueDate('')
    setDueTime('')
    setPriority('')
    setDescription('')
  }

  const submit = async () => {
    const trimmed = title.trim()
    if (!trimmed) return
    // Clear the title synchronously, BEFORE the await — the field is controlled, so leaving the old
    // text in lets fast follow-up keystrokes append and the next Enter post the merged value (#384).
    // Deliberately not gated on `submitting`: an in-flight first POST must not block a quick second
    // capture. Each submit captures its own `title`, so the two POSTs stay independent.
    setTitle('')
    const ok = await onAdd(trimmed, {
      assignees: assignees.length ? assignees : undefined,
      dueDate: dueDate || undefined,
      // a time without a date is meaningless (and rejected server-side) — only carry it with a date
      dueTime: (dueDate && dueTime) || undefined,
      priority: priority || undefined,
      description: description.trim() || undefined,
    })
    // success: clear the detail fields but keep the panel open (#408); failure: restore the
    // title only if still untouched.
    if (ok) clearDetails()
    else setTitle((cur) => (cur ? cur : trimmed))
  }

  return (
    <div
      className={`hb-qa${open ? ' is-open' : ''}`}
      style={{ marginBottom: 24 }}
      // Escape closes the Details panel without clearing the title (handoff spec). On the outer
      // container so it also fires from the description textarea / chips, not just the title input.
      onKeyDown={(e) => {
        if (e.key === 'Escape' && open) setOpen(false)
      }}
    >
      <div className="hb-quickadd">
        <Icon name="plus" size={19} stroke={2} style={{ color: 'var(--ink-3)' }} />
        <input
          value={title}
          placeholder={placeholder}
          onChange={(e) => setTitle(e.target.value)}
          onKeyDown={(e) => e.key === 'Enter' && submit()}
        />
        <button
          type="button"
          className={`hb-qa__toggle${open ? ' is-active' : ''}${hasDetails ? ' has-set' : ''}`}
          onClick={() => setOpen((v) => !v)}
          aria-expanded={open}
          aria-controls={panelId}
          title={t('todos.quickAddDetails')}
          // The label text is hidden on narrow screens (icon-only, #395), so name the
          // button explicitly; append a note when hidden fields are set so the accent
          // dot's meaning reaches assistive tech instead of being purely visual.
          aria-label={hasDetails ? `${t('todos.quickAddDetails')}, ${t('todos.quickAddHasDetailsSr')}` : t('todos.quickAddDetails')}
        >
          <Icon name="calendar" size={15} stroke={2} />
          <span className="hb-qa__toggle-label">{t('todos.quickAddDetails')}</span>
          {hasDetails && <span className="hb-qa__dot" aria-hidden="true" />}
          <Icon name="chevronDown" size={13} stroke={2.4} className="hb-qa__chev" />
        </button>
        <Button size="sm" icon="plus" onClick={submit} disabled={submitting || !title.trim()}>
          {t('todos.addTask')}
        </Button>
      </div>

      {open && (
        <div className="hb-qa__panel" id={panelId}>
          <Field label={t('todos.description')}>
            <textarea
              className="hb-input"
              rows={2}
              value={description}
              placeholder={t('todos.descriptionPlaceholder')}
              onChange={(e) => setDescription(e.target.value)}
              style={{ resize: 'vertical', lineHeight: 1.5 }}
            />
          </Field>
          <Field label={t('todos.assignee')} group>
            <AssigneePicker value={assignees} users={users} onChange={setAssignees} />
          </Field>
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 14 }}>
            <Field label={t('todos.dueDate')}>
              <TextInput type="date" value={dueDate} onChange={setDueDate} />
            </Field>
            <Field label={t('todos.dueTime')}>
              <TextInput type="time" value={dueTime} disabled={!dueDate} onChange={setDueTime} />
            </Field>
          </div>
          <Field label={t('todos.priority')} group>
            <div className="hb-pickrow">
              {(Object.keys(PRIO) as TodoPriority[]).map((k) => (
                <button
                  key={k}
                  type="button"
                  className={`hb-pick${priority === k ? ' is-active' : ''}`}
                  onClick={() => setPriority(priority === k ? '' : k)}
                >
                  <span className="hb-prio__dot" style={{ background: `oklch(0.6 0.13 ${PRIO[k].hue})` }} />
                  {t(PRIO[k].labelKey)}
                </button>
              ))}
            </div>
          </Field>
        </div>
      )}
    </div>
  )
}

// Read-only provenance line for a todo: who created it + when, last edit (only when it differs
// from creation), and completion time. Shown both in a row's expanded panel and in the edit sheet
// (#). Relative wording on the surface, absolute date+time in the hover title.
function TodoMeta({ todo }: { todo: Todo }) {
  const { t } = useTranslation()
  const creator = userMeta(todo.createdBy)?.name ?? todo.createdBy
  const wasEdited = !!todo.updatedAt && todo.updatedAt !== todo.createdAt
  return (
    <div
      className="hb-muted"
      style={{
        display: 'flex',
        flexWrap: 'wrap',
        gap: '2px 14px',
        marginTop: 10,
        paddingTop: 10,
        borderTop: '1px solid var(--line-soft)',
        fontSize: 12,
      }}
    >
      <span title={absDateTime(todo.createdAt)}>{t('todos.metaCreated', { who: creator })} · {relTime(todo.createdAt)}</span>
      {wasEdited && <span title={absDateTime(todo.updatedAt)}>{t('todos.metaUpdated')} · {relTime(todo.updatedAt)}</span>}
      {todo.doneAt && <span title={absDateTime(todo.doneAt)}>{t('todos.metaDone')} · {relTime(todo.doneAt)}</span>}
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
  onEditDate,
  onEditAssignee,
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
  onEditDate: () => void
  onEditAssignee: () => void
  onDelete: () => void
  onToggleSub: (s: Subtask) => void
  onDeleteSub: (subId: string) => void
  onDraft: (v: string) => void
  onAddSub: () => void
}) {
  const { t } = useTranslation()
  const due = dueLabel(todo.dueDate)
  const dueTime = dueTimeLabel(todo.dueTime)
  const subs = todo.subtasks ?? []
  const doneCount = subs.filter((s) => s.done).length
  const isDone = todo.status === 'DONE'
  const assignees = todo.assignees ?? []

  return (
    <div className="hb-todo">
      <div className={`hb-row${isDone ? ' hb-row--done' : ''}`}>
        <Checkbox checked={isDone} hue={assignees[0] ? userMeta(assignees[0])?.hue : undefined} onChange={onToggleDone} />
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
          {/* Click the due badge to quick-edit just the date/time (#) */}
          {due && !isDone && (
            <button type="button" className="hb-row__chip" onClick={onEditDate} title={t('todos.editDateTitle')} aria-label={t('todos.editDateTitle')}>
              <Badge tone={due.tone}>{dueTime ? `${due.text} · ${dueTime}` : due.text}</Badge>
            </button>
          )}
          {assignees.length > 0 ? (
            // earlier avatars sit on top so each initial stays fully readable under the overlap
            isDone ? (
              // a completed todo's assignees are read-only — never a click target that could reopen it
              <span className="hb-avstack">
                {assignees.map((u, i) => (
                  <span key={u} style={{ zIndex: assignees.length - i }}><Avatar user={u} size={28} /></span>
                ))}
              </span>
            ) : (
              // Click the avatars to quick-edit just the assignees
              <button type="button" className="hb-row__chip hb-avstack" onClick={onEditAssignee} title={t('todos.editAssigneeTitle')} aria-label={t('todos.editAssigneeTitle')}>
                {assignees.map((u, i) => (
                  <span key={u} style={{ zIndex: assignees.length - i }}><Avatar user={u} size={28} /></span>
                ))}
              </button>
            )
          ) : !isDone && !todo.dueDate ? (
            <Button size="sm" variant="soft" icon="calendar" onClick={onPlan}>{t('todos.plan')}</Button>
          ) : !isDone ? (
            <button type="button" className="hb-row__chip" onClick={onEditAssignee} title={t('todos.editAssigneeTitle')} aria-label={t('todos.editAssigneeTitle')}>
              <Avatar user={null} size={28} />
            </button>
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
          <TodoMeta todo={todo} />
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

// Multi-select assignee chips (V39) for the plan sheet + quick-edit — one per household member plus
// "Niemand" (clears the whole set); mirrors the Android picker (AufgabenScreen). Tapping a chip
// toggles that user in/out, so several can be active at once ("both" = select both). An assignee
// that isn't a household member (legacy free-text) is still shown so it stays selectable and isn't
// dropped on save. Order-insensitive membership check keeps it stable across a re-sorted payload.
function AssigneePicker({ value, users: household, onChange }: { value: string[]; users: string[]; onChange: (v: string[]) => void }) {
  const { t } = useTranslation()
  const lower = value.map((v) => v.toLowerCase())
  const extras = value.filter((v) => !household.some((u) => u.toLowerCase() === v.toLowerCase()))
  const users = [...household, ...extras]
  const toggle = (u: string) => {
    const isOn = lower.includes(u.toLowerCase())
    onChange(isOn ? value.filter((v) => v.toLowerCase() !== u.toLowerCase()) : [...value, u])
  }
  return (
    <div className="hb-pickrow">
      {users.map((u) => {
        const active = lower.includes(u.toLowerCase())
        return (
          <button key={u} className={`hb-pick${active ? ' is-active' : ''}`} onClick={() => toggle(u)}>
            <Avatar user={u} size={20} /> {userMeta(u)?.name ?? u}
          </button>
        )
      })}
      <button className={`hb-pick${value.length === 0 ? ' is-active' : ''}`} onClick={() => onChange([])}>
        {t('todos.assigneeNone')}
      </button>
    </div>
  )
}
