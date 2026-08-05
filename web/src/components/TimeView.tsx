import { Fragment, useState, useEffect, useCallback, useMemo, useRef } from 'react'
import { useTranslation } from 'react-i18next'
import { API_BASE, authFetch, errorCode, notifyTransportError, safeFetch } from '../api'
import { errorText } from '../i18n'
import { Project, TimeCredit, TimeEntry, TimeForecast, User, UserForecast } from '../types'
import { useWebSocket } from '../hooks/useWebSocket'
import { Icon } from '../ui/Icon'
import { Avatar, Button, Card, ConfirmDialog, EmptyState, Field, IconButton, Modal, PageHead, Select, Sheet, TextInput } from '../ui/primitives'
import { clockTime, dayGroupLabel, fmtClock, fmtDurationShort, parseLocaleNumber, userMeta, usernameFromToken, weekKey, weekLabel } from '../ui/format'
import { groupByDay } from './timeGrouping'
import { liveSecondsSinceSnapshot } from './worktarget'

const WS_SCHEME = window.location.protocol === 'https:' ? 'wss' : 'ws'
const WS_URL = import.meta.env.VITE_WS_URL_TIME ?? `${WS_SCHEME}://${window.location.host}/api/v1/ws/time`

export const COLOR_CHOICES = ['#B4654A', '#C98A3B', '#4F7A52', '#3F7C8C', '#6E5AA6', '#A6537A', '#7A8B57', '#64748B']

interface TimeViewProps {
  token: string
  onLogout: () => void
  // Deep-link into Einstellungen → Zeiterfassung, where Wochensoll/projects are
  // configured (the tracker only displays the balance now, #99).
  onOpenSettings: () => void
}

function elapsedSeconds(startedAt: string, nowMs: number): number {
  return Math.max(0, Math.floor((nowMs - new Date(startedAt).getTime()) / 1000))
}

function dayKey(d: Date): string {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

/** Compact "h:mm" for soll/ist figures (e.g. 38:00, 7:30). Negative input is clamped. */
function hm(seconds: number): string {
  const totalMin = Math.round(Math.max(0, seconds) / 60)
  return `${Math.floor(totalMin / 60)}:${String(totalMin % 60).padStart(2, '0')}`
}

// Format an ISO timestamp as the local `YYYY-MM-DDTHH:mm` a <input type="datetime-local">
// expects. `new Date(value)` parses that back as local time, so a round-trip preserves
// the wall-clock the user sees.
function toLocalInput(iso: string): string {
  const d = new Date(iso)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`
}

export interface ProjectDraft {
  id?: string
  name: string
  color: string
}

// Offline read-cache (#520, rolling out the shopping read-cache #517 to the time tracker): mirror the
// last-loaded projects + entries + users so a launch/reload while the API is unreachable shows the
// previous entries instead of an empty screen. The forecast (Wochenbilanz) is deliberately not cached —
// it hides gracefully without one and its live tick keys off a fetch timestamp we can't restore. The
// running timer is derived from the entries. Best-effort; keyed by browser. NB: fully offline the shell
// needs the service worker (#519); this covers the flaky-connection case + instant first paint.
const CACHE_KEY = 'homebase_time_cache'

interface TimeCache { projects: Project[]; entries: TimeEntry[]; users: string[] }

function loadTimeCache(): TimeCache | null {
  try {
    const raw = localStorage.getItem(CACHE_KEY)
    if (!raw) return null
    const p = JSON.parse(raw) as Partial<TimeCache>
    return { projects: p.projects ?? [], entries: p.entries ?? [], users: p.users ?? [] }
  } catch {
    return null // private-mode / corrupt value → no seed
  }
}

function saveTimeCache(cache: TimeCache) {
  try {
    localStorage.setItem(CACHE_KEY, JSON.stringify(cache))
  } catch {
    /* quota / private mode — the in-memory state still works for this session */
  }
}

export function TimeView({ token, onLogout, onOpenSettings }: TimeViewProps) {
  const { t } = useTranslation()
  const me = useMemo(() => usernameFromToken(token), [token])
  // Seed from the durable read-cache (#520) so a launch with a flaky/absent connection shows the last
  // known projects + entries instead of an empty screen; a successful fetch replaces them below.
  const initialTimeCache = useMemo(() => loadTimeCache(), [])
  const [projects, setProjects] = useState<Project[]>(initialTimeCache?.projects ?? [])
  const [entries, setEntries] = useState<TimeEntry[]>(initialTimeCache?.entries ?? [])
  const [users, setUsers] = useState<string[]>(initialTimeCache?.users ?? [])
  // Skip the full-screen spinner when we already have cached content to show — refresh happens underneath.
  const [loading, setLoading] = useState(!(initialTimeCache && (initialTimeCache.entries.length > 0 || initialTimeCache.projects.length > 0)))
  const [nowMs, setNowMs] = useState(() => Date.now())
  const [projectDraft, setProjectDraft] = useState<ProjectDraft | null>(null)
  const [showManual, setShowManual] = useState(false)
  const [editEntry, setEditEntry] = useState<TimeEntry | null>(null)
  const [splitEntry, setSplitEntry] = useState<TimeEntry | null>(null)
  const [detailProject, setDetailProject] = useState<Project | null>(null)
  const [desc, setDesc] = useState('')
  const [toast, setToast] = useState<string | null>(null)
  // Wochensoll & Forecast (#31)
  const [forecast, setForecast] = useState<TimeForecast | null>(null)
  // when the forecast snapshot was taken — lets a running timer tick the displayed
  // soll/ist live instead of freezing it at fetch time (#59)
  const [forecastAtMs, setForecastAtMs] = useState(0)
  // Absence/holiday work credits over the tracked-entry span (#31) — the Projekt-Detail
  // per-week breakdown folds these in so past weeks show sick/vacation/holiday hours the
  // same way the live Wochenbilanz credits the current week. Non-critical read.
  const [credits, setCredits] = useState<TimeCredit[]>([])
  // Pending cross-person action: both users may manage each other's entries and
  // timers, but anything touching the partner's data confirms first — via a custom
  // ConfirmDialog, never window.confirm() (#125/#129).
  const [partnerConfirm, setPartnerConfirm] = useState<{ message: string; run: () => void; danger?: boolean } | null>(null)

  // Surface a write failure to the user. The backend cleanly rejects the
  // mutation (no data loss), but without this the action would just silently
  // not happen — see issue #84.
  const flashError = useCallback((msg: string) => {
    setToast(msg)
    setTimeout(() => setToast(null), 3500)
  }, [])

  // Apply a write's HTTP response to local state immediately — same convention as
  // every other view (Todos/Notes/Shopping). The WebSocket echo of our own action
  // then dedupes to a no-op (it only exists to sync the *other* user). Without this,
  // our own change wouldn't show until the next reload if the echo is missed/delayed.
  const upsertEntry = useCallback((e: TimeEntry) => {
    setEntries((prev) => (prev.some((x) => x.id === e.id) ? prev.map((x) => (x.id === e.id ? e : x)) : [e, ...prev]))
  }, [])
  const upsertProject = useCallback((p: Project) => {
    setProjects((prev) => (prev.some((x) => x.id === p.id) ? prev.map((x) => (x.id === p.id ? p : x)) : [...prev, p]))
  }, [])

  const projectsById = useMemo(() => Object.fromEntries(projects.map((p) => [p.id, p])), [projects])
  const running = useMemo(() => entries.find((e) => !e.stoppedAt && (!me || e.userId === me)) ?? null, [entries, me])
  // Live timers of the *other* household member(s) — shown in the partner strip.
  // Guard on `me` so a momentarily-unknown user doesn't mislabel own timer as a partner's.
  const othersRunning = useMemo(() => (me ? entries.filter((e) => !e.stoppedAt && e.userId !== me) : []), [entries, me])
  // Other household members, so we can offer "start a timer for them" even while idle.
  const others = useMemo(() => users.filter((u) => u !== me), [users, me])

  // Forecast + targets are non-critical reads (#31): on failure the soll/forecast UI
  // simply stays hidden — the tracker itself keeps working.
  const fetchForecast = useCallback(async () => {
    const result = await safeFetch(token, `${API_BASE}/time/forecast`)
    if (!result.ok) return
    if (result.res.status === 401) return onLogout()
    if (result.res.ok) {
      setForecast(await result.res.json())
      setForecastAtMs(Date.now())
    }
  }, [onLogout, token])

  const fetchAll = useCallback(async () => {
    try {
      const [pResult, eResult, uResult] = await Promise.all([
        safeFetch(token, `${API_BASE}/time/projects`),
        safeFetch(token, `${API_BASE}/time/entries`),
        safeFetch(token, `${API_BASE}/users`),
        fetchForecast(),
      ])
      // a transport reject on either core read → fire the global toast once, keep existing data
      if (!pResult.ok || !eResult.ok) {
        notifyTransportError()
        return
      }
      const { res: pRes } = pResult
      const { res: eRes } = eResult
      if (pRes.status === 401 || eRes.status === 401) {
        onLogout()
        return
      }
      if (pRes.ok) setProjects(await pRes.json())
      if (eRes.ok) setEntries(await eRes.json())
      // users is non-critical (only enables "start for partner"); ignore its failure quietly
      if (uResult.ok && uResult.res.ok) setUsers((await uResult.res.json()).map((u: User) => u.username))
    } finally {
      setLoading(false)
    }
  }, [onLogout, token])

  useEffect(() => { fetchAll() }, [fetchAll])

  // Earliest tracked day: the start of the window the per-week credits cover. A string
  // primitive so the fetch below only re-fires when the earliest date actually shifts
  // (e.g. a historical entry is added), not on every running-timer tick.
  const creditFrom = useMemo(() => {
    let min: string | null = null
    for (const e of entries) {
      const d = dayKey(new Date(e.startedAt))
      if (!min || d < min) min = d
    }
    return min
  }, [entries])

  // Absence/holiday credits for [creditFrom, today] (best-effort; the Projekt-Detail
  // per-week breakdown is the only consumer). Absences entered in the calendar while
  // this view is open are picked up on the next load — historical data isn't live.
  useEffect(() => {
    if (!creditFrom) { setCredits([]); return }
    const to = dayKey(new Date())
    let cancelled = false
    void (async () => {
      const result = await safeFetch(token, `${API_BASE}/time/credits?from=${creditFrom}&to=${to}`)
      if (cancelled || !result.ok || !result.res.ok) return
      setCredits(await result.res.json())
    })()
    return () => { cancelled = true }
  }, [creditFrom, token])

  // Mirror the current projects + entries + users into the durable read-cache (#520) on every change
  // so the next launch can show the last state offline. Never wipes: seeded from that same cache.
  useEffect(() => {
    saveTimeCache({ projects, entries, users })
  }, [projects, entries, users])

  // keep the hero description input in sync with the running entry
  useEffect(() => { setDesc(running?.description ?? '') }, [running?.id])

  // tick the live clock while any timer runs (own or a partner's)
  useEffect(() => {
    if (!running && othersRunning.length === 0) return
    const id = setInterval(() => setNowMs(Date.now()), 1000)
    return () => clearInterval(id)
  }, [running, othersRunning.length])

  useWebSocket({ url: WS_URL, token }, (raw) => {
    try {
      const msg = JSON.parse(raw)
      if (msg.project) {
        const p: Project = msg.project
        if (msg.type === 'PROJECT_CREATED') setProjects((prev) => (prev.some((x) => x.id === p.id) ? prev : [...prev, p]))
        else if (msg.type === 'PROJECT_UPDATED') setProjects((prev) => prev.map((x) => (x.id === p.id ? p : x)))
      } else if (msg.entry) {
        const e: TimeEntry = msg.entry
        if (msg.type === 'ENTRY_CREATED') setEntries((prev) => (prev.some((x) => x.id === e.id) ? prev.map((x) => (x.id === e.id ? e : x)) : [e, ...prev]))
        else if (msg.type === 'ENTRY_UPDATED') setEntries((prev) => prev.map((x) => (x.id === e.id ? e : x)))
        else if (msg.type === 'ENTRY_DELETED') setEntries((prev) => prev.filter((x) => x.id !== e.id))
        // any entry change shifts the forecast (recorded time, expected end)
        fetchForecast()
      } else if (msg.type === 'TARGET_UPDATED') {
        // a target change shifts the forecast (credits, daily target) the tracker shows
        fetchForecast()
      }
    } catch {
      // ignore malformed frames
    }
  })

  const partnerName = (userId: string) => userMeta(userId)?.name ?? userId
  const isPartnerEntry = (entry: TimeEntry) => !!me && entry.userId !== me

  // The three click-driven write paths use safeFetch so a rejected fetch
  // (offline/DNS/aborted — issue #93) shows the per-action fallback toast
  // instead of an unhandled rejection. On a transport failure no backend code
  // exists, so errorText(null, fallback) resolves to the German fallback.
  // `userId` starts the timer on behalf of the partner; omitted → self.
  // Acting on the partner's timer is a cross-person action — confirm first (#129).
  const startTimer = (projectId: string, description = '', userId?: string) => {
    if (userId && userId !== me) {
      setPartnerConfirm({
        message: t('time.confirmStartForPartner', { name: partnerName(userId) }),
        run: () => void doStartTimer(projectId, description, userId),
      })
      return
    }
    void doStartTimer(projectId, description, userId)
  }

  const doStartTimer = async (projectId: string, description = '', userId?: string) => {
    const result = await safeFetch(token, `${API_BASE}/time/entries/start`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ projectId, description: description.trim() || undefined, userId }),
    })
    if (!result.ok) return flashError(errorText(null, t('time.startFailed')))
    const { res } = result
    if (res.status === 401) return onLogout()
    if (!res.ok) return flashError(errorText(await errorCode(res), t('time.startFailed')))
    // Show the new timer right away. Starting auto-stops any running timer for this
    // user on the server (at the same instant), so mirror that locally too — otherwise
    // the previous entry would linger as "running" until the WS echo arrives.
    const created: TimeEntry = await res.json()
    setEntries((prev) => {
      const stopped = prev.map((e) =>
        !e.stoppedAt && e.userId === created.userId && e.id !== created.id
          ? { ...e, stoppedAt: created.startedAt, durationSeconds: elapsedSeconds(e.startedAt, new Date(created.startedAt).getTime()) }
          : e,
      )
      return stopped.some((x) => x.id === created.id) ? stopped.map((x) => (x.id === created.id ? created : x)) : [created, ...stopped]
    })
    fetchForecast()
  }

  // `userId` stops the partner's timer; omitted → own timer (no body).
  const stopTimer = (userId?: string) => {
    if (userId && userId !== me) {
      setPartnerConfirm({
        message: t('time.confirmStopPartner', { name: partnerName(userId) }),
        run: () => void doStopTimer(userId),
      })
      return
    }
    void doStopTimer(userId)
  }

  const doStopTimer = async (userId?: string) => {
    const init: RequestInit = userId
      ? { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ userId }) }
      : { method: 'POST' }
    const result = await safeFetch(token, `${API_BASE}/time/entries/stop`, init)
    if (!result.ok) return flashError(errorText(null, t('time.stopFailed')))
    const { res } = result
    if (res.status === 401) return onLogout()
    if (!res.ok) return flashError(errorText(await errorCode(res), t('time.stopFailed')))
    upsertEntry(await res.json())
    fetchForecast()
  }

  const saveDescription = async () => {
    if (!running || desc === (running.description ?? '')) return
    const result = await safeFetch(token, `${API_BASE}/time/entries/${running.id}`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ description: desc }),
    })
    if (!result.ok) return flashError(errorText(null, t('time.saveFailed')))
    const { res } = result
    if (res.status === 401) return onLogout()
    if (!res.ok) return flashError(errorText(await errorCode(res), t('time.saveFailed')))
    upsertEntry(await res.json())
  }

  const deleteEntry = async (id: string) => {
    setEntries((prev) => prev.filter((e) => e.id !== id))
    const result = await safeFetch(token, `${API_BASE}/time/entries/${id}`, { method: 'DELETE' })
    // On failure refetch to resync (the optimistic removal may be wrong) and toast.
    if (!result.ok) {
      await fetchAll()
      return flashError(errorText(null, t('time.deleteFailed')))
    }
    const { res } = result
    if (res.status === 401) return onLogout()
    if (!res.ok) {
      await fetchAll()
      flashError(errorText(await errorCode(res), t('time.deleteFailed')))
      return
    }
    fetchForecast()
  }

  // Edit/split/delete are offered on both users' entries; anything targeting the
  // partner's entry runs through the confirm dialog first (#129).
  const withPartnerConfirm = (entry: TimeEntry, message: string, run: () => void, danger?: boolean) => {
    if (isPartnerEntry(entry)) setPartnerConfirm({ message: message.replace('{name}', partnerName(entry.userId)), run, danger })
    else run()
  }
  const requestEdit = (entry: TimeEntry) => withPartnerConfirm(entry, t('time.confirmEditPartner'), () => setEditEntry(entry))
  const requestSplit = (entry: TimeEntry) => withPartnerConfirm(entry, t('time.confirmSplitPartner'), () => setSplitEntry(entry))
  const requestDelete = (entry: TimeEntry) => withPartnerConfirm(entry, t('time.confirmDeletePartner'), () => void deleteEntry(entry.id), true)

  const saveProject = async (d: ProjectDraft) => {
    if (!d.name.trim()) return
    const body = JSON.stringify({ name: d.name.trim(), color: d.color })
    const result = d.id
      ? await safeFetch(token, `${API_BASE}/time/projects/${d.id}`, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body })
      : await safeFetch(token, `${API_BASE}/time/projects`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body })
    if (!result.ok) return flashError(errorText(null, t('time.saveFailed')))
    const { res } = result
    if (res.status === 401) return onLogout()
    if (!res.ok) return flashError(errorText(await errorCode(res), t('time.saveFailed')))
    upsertProject(await res.json())
    setProjectDraft(null)
  }

  // Returns null on success, or an error message the modal shows inline (so it
  // stays open for a retry) — e.g. 400 INVALID_RANGE or 409 PROJECT_ARCHIVED.
  // An entry recorded *for the partner* confirms first. Confirming *commits*:
  // the sheet closes immediately (so the in-flight POST can't be double-submitted
  // by a second Speichern click) and a late failure surfaces as a toast instead
  // of the sheet's inline error. Cancelling leaves the sheet open to edit/retry.
  const createManual = async (body: ManualEntryBody): Promise<string | null> => {
    if (body.userId && me && body.userId !== me) {
      setPartnerConfirm({
        message: t('time.confirmCreateForPartner', { name: partnerName(body.userId) }),
        run: () => {
          setShowManual(false)
          void doCreateManual(body).then((err) => { if (err) flashError(err) })
        },
      })
      return null
    }
    return doCreateManual(body)
  }

  const doCreateManual = async (body: ManualEntryBody): Promise<string | null> => {
    const res = await authFetch(token, `${API_BASE}/time/entries`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    })
    if (res.status === 401) {
      onLogout()
      return null
    }
    if (!res.ok) return errorText(await errorCode(res), t('time.saveFailed'))
    upsertEntry(await res.json())
    setShowManual(false)
    fetchForecast()
    return null
  }

  // Edit an existing entry (start/stop/project/description) or a running timer's
  // start time. Same inline-error convention as createManual: returns null on
  // success, or a message the modal shows while staying open for a retry.
  const updateEntry = async (id: string, body: object): Promise<string | null> => {
    const res = await authFetch(token, `${API_BASE}/time/entries/${id}`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    })
    if (res.status === 401) {
      onLogout()
      return null
    }
    if (!res.ok) return errorText(await errorCode(res), t('time.saveFailed'))
    upsertEntry(await res.json())
    setEditEntry(null)
    fetchForecast()
    return null
  }

  // Split an entry at a cut time, optionally with an untracked break (#62).
  // Inline-error convention as in the other modals: null on success, else a
  // message the modal shows while staying open.
  const splitEntryAction = async (id: string, body: object): Promise<string | null> => {
    const res = await authFetch(token, `${API_BASE}/time/entries/${id}/split`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    })
    if (res.status === 401) {
      onLogout()
      return null
    }
    if (!res.ok) return errorText(await errorCode(res), t('time.splitFailed'))
    const { first, second }: { first: TimeEntry; second: TimeEntry } = await res.json()
    upsertEntry(first)
    upsertEntry(second)
    setSplitEntry(null)
    fetchForecast()
    return null
  }

  // Day + week saldo per project for the tiles (#59): today's / this week's sums —
  // or, when the current day/week has no entries yet, the last active day / week
  // (e.g. on Sunday show Friday's saldo if the weekend is empty). Running timers
  // count their elapsed time, so the figures tick live via nowMs.
  const projectStats = useMemo(() => {
    const todayKey = dayKey(new Date(nowMs))
    const thisWeek = weekKey(new Date(nowMs).toISOString())
    const byProj: Record<string, { days: Map<string, number>; weeks: Map<string, number> }> = {}
    for (const e of entries) {
      const secs = e.stoppedAt ? (e.durationSeconds ?? 0) : elapsedSeconds(e.startedAt, nowMs)
      const slot = (byProj[e.projectId] ??= { days: new Map(), weeks: new Map() })
      const d = dayKey(new Date(e.startedAt))
      const w = weekKey(e.startedAt)
      slot.days.set(d, (slot.days.get(d) ?? 0) + secs)
      slot.weeks.set(w, (slot.weeks.get(w) ?? 0) + secs)
    }
    const stats: Record<string, { daySeconds: number; dayLabel: string; weekSeconds: number; weekLabel: string }> = {}
    for (const [pid, slot] of Object.entries(byProj)) {
      // fall back to the latest key before today / this week (future-dated entries don't count)
      const dayK = slot.days.has(todayKey) ? todayKey : [...slot.days.keys()].filter((k) => k < todayKey).sort().pop()
      const weekK = slot.weeks.has(thisWeek) ? thisWeek : [...slot.weeks.keys()].filter((k) => k < thisWeek).sort().pop()
      const wl = weekK && weekK !== thisWeek ? weekLabel(`${weekK}T12:00:00`) : null
      stats[pid] = {
        daySeconds: dayK ? slot.days.get(dayK)! : 0,
        dayLabel: dayK && dayK !== todayKey ? dayGroupLabel(`${dayK}T12:00:00`) : t('time.today'),
        weekSeconds: weekK ? slot.weeks.get(weekK)! : 0,
        weekLabel: wl ? (wl.label ?? wl.range) : t('time.thisWeek'),
      }
    }
    return stats
  }, [entries, nowMs])

  const activeProjects = projects.filter((p) => !p.archived)

  const recent = useMemo(
    () => entries.filter((e) => e.stoppedAt).sort((a, b) => new Date(b.startedAt).getTime() - new Date(a.startedAt).getTime()).slice(0, 40),
    [entries],
  )

  const runningProject = running ? projectsById[running.projectId] : undefined

  // Per-user forecast (#31), only meaningful once a weekly target is configured.
  // Keyed by the *entry's* user (not `me`) so the partner strip works too.
  const forecastByUser = useMemo(() => {
    const m: Record<string, UserForecast> = {}
    for (const u of forecast?.users ?? []) if (u.weekTargetSeconds > 0) m[u.userId] = u
    return m
  }, [forecast])
  const weekUsers = useMemo(() => (forecast?.users ?? []).filter((u) => u.weekTargetSeconds > 0), [forecast])
  const runningForecast = running ? forecastByUser[running.userId] : undefined

  // The currently shown detail project, re-read from the live list so its name/color
  // stay in sync after an edit; falls back to the captured snapshot if it was archived
  // away or deleted while open.
  const detailLive = detailProject ? (projectsById[detailProject.id] ?? detailProject) : null

  // Shared modals — rendered in BOTH the overview and the project-detail page so the
  // entry-edit modal opens over a single layer (the detail used to be a modal itself,
  // stacking two modals and burying this one behind it — issue #32).
  const sharedModals = (
    <>
      {editEntry && (
        <EditEntryModal
          key={editEntry.id}
          entry={editEntry}
          projects={projects}
          onSave={updateEntry}
          onClose={() => setEditEntry(null)}
        />
      )}

      {splitEntry && (
        <SplitEntryModal
          key={splitEntry.id}
          entry={splitEntry}
          nowMs={nowMs}
          onSave={splitEntryAction}
          onClose={() => setSplitEntry(null)}
        />
      )}

      {partnerConfirm && (
        <ConfirmDialog
          title={t('time.partnerActionTitle')}
          message={partnerConfirm.message}
          danger={partnerConfirm.danger}
          onConfirm={partnerConfirm.run}
          onClose={() => setPartnerConfirm(null)}
        />
      )}

      {toast && (
        <div className="hb-toast hb-toast--error" role="alert">
          <Icon name="x" size={18} stroke={2.4} />
          {toast}
        </div>
      )}
    </>
  )

  // Project detail as its own full-width page (not a modal) — the entry-edit modal
  // then opens over a normal page instead of behind a second modal layer (#32, #29).
  if (detailLive) {
    return (
      <div className="hb-page">
        <ProjectDetail
          project={detailLive}
          entries={entries}
          credits={credits}
          projectsById={projectsById}
          onDelete={requestDelete}
          onEdit={requestEdit}
          onSplit={requestSplit}
          onBack={() => setDetailProject(null)}
        />
        {sharedModals}
      </div>
    )
  }

  return (
    <div className="hb-page">
      <PageHead
        eyebrow={running ? t('time.running') : t('time.projectsLabel')}
        title={t('time.title')}
        actions={
          <>
            <Button icon="calendar" onClick={() => setShowManual(true)}>{t('time.recordEntry')}</Button>
            <Button variant="secondary" size="sm" icon="plus" onClick={() => setProjectDraft({ name: '', color: COLOR_CHOICES[0] })}>{t('time.newProject')}</Button>
          </>
        }
      />

      {/* Timer hero */}
      {running ? (
        <Card className="hb-card--pad hb-timerhero is-running">
          <div className="hb-timerhero__left">
            <span className="hb-timerhero__live"><span className="hb-livedot" /> {t('time.running')}</span>
            <div className="hb-timerhero__proj">
              <span className="hb-pdot" style={{ background: runningProject?.color ?? 'var(--ink-3)' }} />
              {runningProject?.name ?? t('time.project')}
              <IconButton icon="edit" label={t('time.editRunning')} size={16} onClick={() => setEditEntry(running)} />
              {/* split the *running* timer: part one closes, the timer keeps running
                  after the break — for the forgotten break (#634) */}
              <IconButton icon="scissors" label={t('time.splitRunning')} size={16} onClick={() => setSplitEntry(running)} />
            </div>
            <input
              className="hb-timerhero__desc"
              value={desc}
              placeholder={t('time.descPlaceholder')}
              onChange={(e) => setDesc(e.target.value)}
              onBlur={saveDescription}
              onKeyDown={(e) => e.key === 'Enter' && (e.target as HTMLInputElement).blur()}
            />
          </div>
          <div className="hb-timerhero__right">
            <div className="hb-timerhero__clock hb-mono">{fmtClock(elapsedSeconds(running.startedAt, nowMs))}</div>
            <EtaLine eta={runningForecast?.expectedEndAt} nowMs={nowMs} />
            <Button variant="secondary" icon="stop" onClick={() => stopTimer()}>{t('time.stop')}</Button>
          </div>
        </Card>
      ) : (
        <Card className="hb-card--pad hb-timerhero">
          <div className="hb-timerhero__left">
            <span className="hb-timerhero__live" style={{ color: 'var(--ink-3)' }}>{t('time.noTimer')}</span>
            {activeProjects.length === 0 ? (
              <div className="hb-muted">{t('time.noProjectsHint')}</div>
            ) : (
              <>
                <div className="hb-muted">{t('time.startPrompt')}</div>
                <div className="hb-pickrow">
                  {activeProjects.map((p) => (
                    <button key={p.id} className="hb-pick" onClick={() => startTimer(p.id)}>
                      <span className="hb-pdot" style={{ background: p.color }} />
                      {p.name}
                    </button>
                  ))}
                </div>
              </>
            )}
          </div>
          <div className="hb-timerhero__right">
            <div className="hb-timerhero__clock hb-mono" style={{ color: 'var(--ink-3)' }}>00:00:00</div>
          </div>
        </Card>
      )}

      {/* Partner strip — the other household member's timer: see & stop their
          running timer, or start one on their behalf. */}
      {others.length > 0 && (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 10, marginTop: 12 }}>
          {others.map((u) => (
            <PartnerTimer
              key={u}
              user={u}
              running={othersRunning.find((e) => e.userId === u) ?? null}
              projectsById={projectsById}
              nowMs={nowMs}
              projects={activeProjects}
              eta={forecastByUser[u]?.expectedEndAt}
              onStop={() => stopTimer(u)}
              onStart={(pid) => startTimer(pid, '', u)}
            />
          ))}
        </div>
      )}

      {/* Wochensoll (#31): per-person week balance — recorded+credited vs. target,
          today's redistributed share, and the per-project saldo. Hidden until a
          weekly target is configured. */}
      {weekUsers.length > 0 && (
        <Card className="hb-card--pad hb-weektargets" style={{ marginTop: 12 }}>
          <div className="hb-cardhead">
            <h3>{t('time.weekTargetTitle')}</h3>
            <IconButton icon="settings" label={t('settings.wochensollEdit')} onClick={onOpenSettings} />
          </div>
          <div className="hb-stack" style={{ gap: 16 }}>
            {weekUsers.map((u) => {
              // a running timer ticks the snapshot numbers live: add the seconds
              // elapsed since the forecast was fetched (#59), never from startedAt (#531)
              const live = entries.find((e) => !e.stoppedAt && e.userId === u.userId)
              const extra = liveSecondsSinceSnapshot(!!live, nowMs, forecastAtMs)
              return (
                <WeekBalance
                  key={u.userId}
                  forecast={u}
                  projectsById={projectsById}
                  liveExtraSeconds={extra}
                  liveProjectId={live?.projectId}
                />
              )
            })}
          </div>
        </Card>
      )}

      {loading ? (
        <p className="hb-muted" style={{ textAlign: 'center', padding: 24 }}>{t('common.loading')}</p>
      ) : (
        <div className="hb-zeit-grid">
          {/* Projects — the tracker lists active projects to start timers on; project
              management (edit/colour/archive) lives in Einstellungen → Zeiterfassung (#99). */}
          <div>
            <div className="hb-sectionlabel">{t('time.projectsLabel')}</div>
            {activeProjects.length === 0 ? (
              <Card className="hb-card--pad"><EmptyState icon="clock" title={t('time.noProjects')} hint={t('time.noProjectsHint')} action={<Button size="sm" icon="plus" onClick={() => setProjectDraft({ name: '', color: COLOR_CHOICES[0] })}>{t('time.newProject')}</Button>} /></Card>
            ) : (
              <div className="hb-proj-grid">
                {activeProjects.map((p) => {
                  const isRunning = running?.projectId === p.id
                  return (
                    <Card key={p.id} className={`hb-projcard${isRunning ? ' is-running' : ''}`}>
                      <div className="hb-projcard__head">
                        <span className="hb-pdot" style={{ background: p.color }} />
                        <button className="hb-projcard__name hb-projcard__namebtn" title={t('time.viewDetails')} onClick={() => setDetailProject(p)}>{p.name}</button>
                      </div>
                      {/* Dauer + Label sind eine nicht-umbrechende Baseline-Gruppe; das Öffnen
                          ist ein beschrifteter Button statt eines freistehenden Pfeils (#220). */}
                      <div className="hb-projcard__stats">
                        <span className="hb-projcard__stat hb-mono">
                          {hm(projectStats[p.id]?.daySeconds ?? 0)}<span>{projectStats[p.id]?.dayLabel ?? t('time.today')}</span>
                        </span>
                        <span className="hb-projcard__stat2 hb-mono">
                          {hm(projectStats[p.id]?.weekSeconds ?? 0)}<span>{projectStats[p.id]?.weekLabel ?? t('time.thisWeek')}</span>
                        </span>
                      </div>
                      <div className="hb-projcard__foot">
                        <button className="hb-projcard__open" title={t('time.viewDetails')} onClick={() => setDetailProject(p)}>
                          {t('time.open')}<Icon name="chevronRight" size={15} stroke={2.2} />
                        </button>
                        {isRunning ? (
                          <Button variant="secondary" size="sm" icon="stop" onClick={() => stopTimer()}>{t('time.stop')}</Button>
                        ) : (
                          <Button variant="soft" size="sm" icon="play" onClick={() => startTimer(p.id)}>{t('time.start')}</Button>
                        )}
                      </div>
                    </Card>
                  )
                })}
              </div>
            )}
          </div>

          {/* Recent entries */}
          <div>
            <div className="hb-sectionlabel">{t('time.recentEntries')}</div>
            <Card className="hb-card--pad">
              {recent.length === 0 ? (
                <EmptyState icon="clock" title={t('time.noEntries')} hint={t('time.emptyHint')} />
              ) : (
                <DayGroupedList entries={recent} projectsById={projectsById} onDelete={requestDelete} onEdit={requestEdit} onSplit={requestSplit} showProject />
              )}
            </Card>
          </div>
        </div>
      )}

      {/* Lightweight project create — you need a project to start a timer; full
          project management lives in Einstellungen → Zeiterfassung (#99). */}
      {projectDraft && (
        <ProjectModal draft={projectDraft} onChange={setProjectDraft} onSave={saveProject} onClose={() => setProjectDraft(null)} />
      )}

      {showManual && (
        <ManualEntryModal projects={activeProjects} users={users} me={me} onCreate={createManual} onClose={() => setShowManual(false)} />
      )}

      {sharedModals}
    </div>
  )
}

// Project create/edit dialog. Shared by the tracker (lightweight create) and the
// Einstellungen → Zeiterfassung subpage (full management). Purely presentational —
// the caller owns the draft state and the save (#99).
export function ProjectModal({ draft, onChange, onSave, onClose }: {
  draft: ProjectDraft
  onChange: (d: ProjectDraft) => void
  onSave: (d: ProjectDraft) => void
  onClose: () => void
}) {
  const { t } = useTranslation()
  return (
    <Modal
      open
      onClose={onClose}
      title={draft.id ? t('time.editProject') : t('time.newProject')}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>{t('common.cancel')}</Button>
          <Button onClick={() => onSave(draft)} disabled={!draft.name.trim()}>
            {draft.id ? t('common.save') : t('time.create')}
          </Button>
        </>
      }
    >
      <Field label={t('time.project')}>
        <TextInput autoFocus value={draft.name} onChange={(v) => onChange({ ...draft, name: v })} placeholder={t('time.projectNamePlaceholder')} />
      </Field>
      <Field label={t('time.color')}>
        <div className="hb-swatches">
          {COLOR_CHOICES.map((c) => (
            <button
              key={c}
              className={`hb-swatch${draft.color === c ? ' is-active' : ''}`}
              style={{ background: c }}
              onClick={() => onChange({ ...draft, color: c })}
              aria-label={`${t('time.colorLabel')} ${c}`}
            />
          ))}
        </div>
      </Field>
    </Modal>
  )
}

// "Voraussichtlich fertig um 16:32" under the live clock; flips to "Tagessoll
// erreicht" once the projected end has passed (#31). Hidden without a forecast.
function EtaLine({ eta, nowMs }: { eta?: string; nowMs: number }) {
  const { t } = useTranslation()
  if (!eta) return null
  const reached = new Date(eta).getTime() <= nowMs
  return (
    <div className="hb-timerhero__eta hb-muted">
      {reached ? t('time.targetReached') : t('time.expectedEnd', { time: clockTime(eta) })}
    </div>
  )
}

// One person's week balance (#31): soll/ist row with progress bar, today's
// redistributed target and the per-project saldo for projects with a target.
// `liveExtraSeconds` are the seconds a running timer has accumulated since the
// forecast snapshot — they tick all displayed figures live (#59); the running
// entry's project (`liveProjectId`) accrues them in its saldo row too.
function WeekBalance({ forecast, projectsById, liveExtraSeconds = 0, liveProjectId }: {
  forecast: UserForecast
  projectsById: Record<string, Project>
  liveExtraSeconds?: number
  liveProjectId?: string
}) {
  const { t } = useTranslation()
  const u = forecast
  const done = u.weekRecordedSeconds + u.weekCreditedSeconds + liveExtraSeconds
  const weekRemaining = u.weekRemainingSeconds - liveExtraSeconds
  const todayRemaining = u.todayRemainingSeconds - liveExtraSeconds
  const pct = u.weekTargetSeconds > 0 ? Math.min(100, (done / u.weekTargetSeconds) * 100) : 0
  const hue = userMeta(u.userId)?.hue ?? 150
  const todayLine = todayRemaining >= 60
    ? t('time.todayLeft', { time: hm(todayRemaining) })
    : todayRemaining <= -60
      ? t('time.todayOver', { time: hm(-todayRemaining) })
      : t('time.targetReached')
  // deliberately a soll view: projects with recorded time but no target stay out
  const projects = (u.projects ?? []).filter((p) => p.weeklyHours > 0)
  return (
    <div className="hb-weektarget">
      <div className="hb-weektarget__head">
        <Avatar user={u.userId} size={24} />
        <span className="hb-weektarget__name">{userMeta(u.userId)?.name ?? u.userId}</span>
        <span className="hb-mono hb-weektarget__nums">{hm(done)} / {hm(u.weekTargetSeconds)}</span>
        <span className={`hb-weektarget__delta${weekRemaining < 0 ? ' is-over' : ''}`}>
          {weekRemaining < 0
            ? t('time.weekOver', { time: hm(-weekRemaining) })
            : t('time.weekLeft', { time: hm(weekRemaining) })}
        </span>
      </div>
      <div className="hb-weekbar">
        <span className="hb-weekbar__seg" style={{ width: `${pct}%`, background: `oklch(0.62 0.1 ${hue})` }} />
      </div>
      <div className="hb-muted" style={{ fontSize: 13 }}>
        {todayLine}
        {u.weekCreditedSeconds > 0 && <> · {hm(u.weekCreditedSeconds)} {t('time.credited')}</>}
      </div>
      {projects.length > 0 && (
        <div className="hb-weektarget__projects">
          {projects.map((p) => {
            const proj = projectsById[p.projectId]
            const rec = p.recordedSeconds + p.creditedSeconds + (p.projectId === liveProjectId ? liveExtraSeconds : 0)
            const delta = p.deltaSeconds + (p.projectId === liveProjectId ? liveExtraSeconds : 0)
            return (
              <div key={p.projectId} className="hb-weektarget__proj">
                <span className="hb-pdot" style={{ background: proj?.color ?? 'var(--ink-3)' }} />
                <span className="hb-weektarget__projname">{proj?.name ?? t('time.project')}</span>
                <span className="hb-mono hb-muted">{hm(rec)} / {hm(p.weeklyHours * 3600)}</span>
                <span className={`hb-weektarget__delta${delta < 0 ? '' : ' is-over'}`} style={{ minWidth: 58, textAlign: 'right' }}>
                  {delta < 0 ? `-${hm(-delta)}` : `+${hm(delta)}`}
                </span>
              </div>
            )
          })}
        </div>
      )}
    </div>
  )
}

// The other household member's timer: when they're running, show project +
// live clock + Stop; when idle, offer a project picker to start one on their behalf.
function PartnerTimer({ user, running, projectsById, nowMs, projects, eta, onStop, onStart }: {
  user: string
  running: TimeEntry | null
  projectsById: Record<string, Project>
  nowMs: number
  projects: Project[]
  eta?: string
  onStop: () => void
  onStart: (projectId: string) => void
}) {
  const { t } = useTranslation()
  const [picking, setPicking] = useState(false)
  const name = userMeta(user)?.name ?? user
  const project = running ? projectsById[running.projectId] : undefined
  const etaSuffix = running && eta
    ? ` · ${new Date(eta).getTime() <= nowMs ? t('dashboard.targetReachedShort') : t('dashboard.expectedEndShort', { time: clockTime(eta) })}`
    : ''
  return (
    <Card className="hb-card--pad">
      <div style={{ display: 'flex', alignItems: 'center', gap: 11 }}>
        <Avatar user={user} size={26} />
        {running ? (
          <>
            <span className="hb-pdot" style={{ background: project?.color ?? 'var(--ink-3)' }} />
            <div style={{ flex: 1, minWidth: 0 }}>
              <div className="hb-row__title">{project?.name ?? t('time.project')}</div>
              <div className="hb-muted" style={{ fontSize: 13 }}>
                {name}{running.description ? ` · ${running.description}` : ''}{etaSuffix}
              </div>
            </div>
            <span className="hb-mono" style={{ fontWeight: 600 }}>{fmtClock(elapsedSeconds(running.startedAt, nowMs))}</span>
            <Button variant="secondary" size="sm" icon="stop" onClick={onStop}>{t('time.stop')}</Button>
          </>
        ) : (
          <>
            <div style={{ flex: 1, minWidth: 0 }}>
              <div className="hb-row__title">{name}</div>
              <div className="hb-muted" style={{ fontSize: 13 }}>{t('time.partnerIdle')}</div>
            </div>
            {projects.length > 0 && (
              <Button variant="soft" size="sm" icon="play" onClick={() => setPicking((v) => !v)}>
                {t('time.startForPartner', { name: name })}
              </Button>
            )}
          </>
        )}
      </div>
      {picking && !running && projects.length > 0 && (
        <div className="hb-pickrow" style={{ marginTop: 12 }}>
          {projects.map((p) => (
            <button key={p.id} className="hb-pick" onClick={() => { onStart(p.id); setPicking(false) }}>
              <span className="hb-pdot" style={{ background: p.color }} />
              {p.name}
            </button>
          ))}
        </div>
      )}
    </Card>
  )
}

// Actions are offered on every entry — also the partner's (the household manages
// entries together); cross-person clicks are confirmed upstream (requestEdit/
// requestSplit/requestDelete in TimeView, #129).
function EntryRow({ entry, project, onDelete, onEdit, onSplit, showProject }: {
  entry: TimeEntry
  project?: Project
  onDelete: (entry: TimeEntry) => void
  onEdit: (entry: TimeEntry) => void
  onSplit: (entry: TimeEntry) => void
  showProject: boolean
}) {
  const { t } = useTranslation()
  const noDesc = <span className="hb-muted">{t('time.noDescription')}</span>
  return (
    <div className="hb-row">
      {showProject && <span className="hb-pdot" style={{ background: project?.color ?? 'var(--ink-3)' }} />}
      <div className="hb-row__main">
        <div className="hb-row__title">{showProject ? (project?.name ?? t('time.project')) : (entry.description || noDesc)}</div>
        <div className="hb-row__meta">
          {showProject && (entry.description ? <span>{entry.description}</span> : noDesc)}
          {showProject && <span className="dot-sep" />}
          <span className="hb-mono">{clockTime(entry.startedAt)}–{entry.stoppedAt ? clockTime(entry.stoppedAt) : ''}</span>
        </div>
      </div>
      <div className="hb-row__right">
        <Avatar user={entry.userId} size={24} />
        <span className="hb-mono" style={{ fontWeight: 600, minWidth: 64, textAlign: 'right' }}>{fmtDurationShort(entry.durationSeconds ?? 0)}</span>
        <IconButton icon="edit" label={t('common.edit')} onClick={() => onEdit(entry)} />
        <IconButton icon="scissors" label={t('time.split')} onClick={() => onSplit(entry)} />
        <IconButton icon="trash" label={t('common.delete')} danger onClick={() => onDelete(entry)} />
      </div>
    </div>
  )
}

// Reusable day-grouped entry list. `showProject` toggles whether the project
// name (recent list) or the description (project detail) is the row title.
function DayGroupedList({ entries, projectsById, onDelete, onEdit, onSplit, showProject }: {
  entries: TimeEntry[]
  projectsById: Record<string, Project>
  onDelete: (entry: TimeEntry) => void
  onEdit: (entry: TimeEntry) => void
  onSplit: (entry: TimeEntry) => void
  showProject: boolean
}) {
  const groups = groupByDay(entries)
  return (
    <div className="hb-list">
      {groups.map((g) => (
        <Fragment key={g.key}>
          <div className="hb-daysep">
            <span className="hb-daysep__label">{g.label}</span>
            <span className="hb-daysep__line" />
            <span className="hb-daysep__sum hb-mono">{fmtDurationShort(g.seconds)}</span>
          </div>
          {g.entries.map((e) => (
            <EntryRow key={e.id} entry={e} project={projectsById[e.projectId]} onDelete={onDelete} onEdit={onEdit} onSplit={onSplit} showProject={showProject} />
          ))}
        </Fragment>
      ))}
    </div>
  )
}

interface WeekBucket {
  key: string
  label: string | null
  range: string
  // recorded + credited (bars and the week total reflect both)
  seconds: number
  count: number
  // absence/holiday hours credited to this project that week (subset of `seconds`)
  credited: number
  byUser: Record<string, number>
}

function ProjectDetail({ project, entries, credits, projectsById, onDelete, onEdit, onSplit, onBack }: {
  project: Project
  entries: TimeEntry[]
  credits: TimeCredit[]
  projectsById: Record<string, Project>
  onDelete: (entry: TimeEntry) => void
  onEdit: (entry: TimeEntry) => void
  onSplit: (entry: TimeEntry) => void
  onBack: () => void
}) {
  const { t } = useTranslation()
  const projEntries = useMemo(
    () =>
      entries
        .filter((e) => e.projectId === project.id && e.stoppedAt)
        // Sort by startedAt (like the main "recent" list) so the day separators stay in
        // chronological order under groupByDay's startedAt bucketing (#544) — a cross-midnight
        // entry sorted by stoppedAt could otherwise push its (earlier) start-day group out of order.
        .sort((a, b) => b.startedAt.localeCompare(a.startedAt)),
    [entries, project.id],
  )

  // Absence/holiday credits (#31) that landed on THIS project — only ever the default
  // project of whoever was absent. Folded into the week/user/total figures so sick,
  // vacation and holiday hours count in the historical timesheet, not just the live
  // Wochenbilanz. `count` stays entry-only (a credit is not a tracked entry).
  const projCredits = useMemo(() => credits.filter((c) => c.projectId === project.id), [credits, project.id])

  const recordedTotal = projEntries.reduce((s, e) => s + (e.durationSeconds ?? 0), 0)
  const creditedTotal = projCredits.reduce((s, c) => s + c.seconds, 0)
  const totalSeconds = recordedTotal + creditedTotal
  // Ø per entry stays recorded-only — credits have no entry to average over.
  const avgSeconds = projEntries.length ? recordedTotal / projEntries.length : 0

  // per-user totals (recorded + credited)
  const byUser: Record<string, number> = {}
  for (const e of projEntries) byUser[e.userId] = (byUser[e.userId] ?? 0) + (e.durationSeconds ?? 0)
  for (const c of projCredits) byUser[c.userId] = (byUser[c.userId] ?? 0) + c.seconds
  const userIds = Object.keys(byUser)

  // per-week summary (entries are newest-first → weeks newest-first; credit-only weeks
  // are appended, so the list is re-sorted by week key below)
  const weekMap = new Map<string, WeekBucket>()
  for (const e of projEntries) {
    // Bucket by START date — matches Android (buildWeekStats), backend forecast and
    // CSV export, all of which attribute by started_at (#541). A shift that starts
    // Sun 23:00 and stops Mon 01:00 counts in the Sunday-start week on every client.
    const k = weekKey(e.startedAt)
    let w = weekMap.get(k)
    if (!w) {
      const { label, range } = weekLabel(e.startedAt)
      w = { key: k, label, range, seconds: 0, count: 0, credited: 0, byUser: {} }
      weekMap.set(k, w)
    }
    w.seconds += e.durationSeconds ?? 0
    w.count += 1
    w.byUser[e.userId] = (w.byUser[e.userId] ?? 0) + (e.durationSeconds ?? 0)
  }
  // Fold each credit into its week (noon-local so the date lands in the right week
  // regardless of zone), creating a bucket for weeks that were entirely absent.
  for (const c of projCredits) {
    const iso = `${c.date}T12:00:00`
    const k = weekKey(iso)
    let w = weekMap.get(k)
    if (!w) {
      const { label, range } = weekLabel(iso)
      w = { key: k, label, range, seconds: 0, count: 0, credited: 0, byUser: {} }
      weekMap.set(k, w)
    }
    w.seconds += c.seconds
    w.credited += c.seconds
    w.byUser[c.userId] = (w.byUser[c.userId] ?? 0) + c.seconds
  }
  // Newest week first — credit-only weeks were appended out of order above.
  const weeks = [...weekMap.values()].sort((a, b) => b.key.localeCompare(a.key))
  const maxWeekSeconds = Math.max(...weeks.map((w) => w.seconds), 1)
  const thisWeekSeconds = weekMap.get(weekKey(new Date().toISOString()))?.seconds ?? 0

  return (
    <>
      <div className="hb-detailnav">
        <Button variant="ghost" size="sm" icon="chevronLeft" onClick={onBack}>{t('time.backToOverview')}</Button>
      </div>
      <PageHead
        eyebrow={t('time.projectsLabel')}
        title={project.name}
      />
      <div className="hb-projhead">
        <span className="hb-pdot" style={{ background: project.color, width: 16, height: 16 }} />
        {project.archived && <span className="hb-muted">{t('time.archivedSection')}</span>}
      </div>

      <Card className="hb-card--pad hb-detailpage">
        <div className="hb-detail-stats">
          <div className="hb-fact"><span className="hb-fact__v hb-mono">{fmtDurationShort(totalSeconds)}</span><span className="hb-fact__l">{t('time.detailTotal')}</span></div>
          <div className="hb-fact"><span className="hb-fact__v hb-mono">{fmtDurationShort(thisWeekSeconds)}</span><span className="hb-fact__l">{t('time.thisWeek')}</span></div>
          <div className="hb-fact"><span className="hb-fact__v hb-mono">{projEntries.length}</span><span className="hb-fact__l">{t('time.detailEntries')}</span></div>
          <div className="hb-fact"><span className="hb-fact__v hb-mono">{fmtDurationShort(avgSeconds)}</span><span className="hb-fact__l">{t('time.detailAvg')}</span></div>
        </div>

        {userIds.length > 1 && (
          <div className="hb-detail-users">
            {userIds.map((uid) => (
              <div key={uid} className="hb-detail-user">
                <Avatar user={uid} size={26} />
                <span className="hb-detail-user__name">{userMeta(uid)?.name ?? uid}</span>
                <span className="hb-mono hb-detail-user__ms">{fmtDurationShort(byUser[uid])}</span>
              </div>
            ))}
          </div>
        )}

        {projEntries.length === 0 && projCredits.length === 0 ? (
          <EmptyState icon="clock" title={t('time.noEntries')} hint={t('time.detailEmptyHint')} />
        ) : (
          <>
            <div className="hb-sectionlabel hb-detail-h">{t('time.perWeek')}</div>
            <div className="hb-weeklist">
              {weeks.map((w) => (
                <div key={w.key} className="hb-weekrow">
                  <div className="hb-weekrow__head">
                    <span className="hb-weekrow__label">{w.label ?? w.range}</span>
                    {w.label && <span className="hb-weekrow__range">{w.range}</span>}
                    <span className="hb-weekrow__ms hb-mono">{fmtDurationShort(w.seconds)}</span>
                  </div>
                  <div className="hb-weekbar">
                    {userIds.map((uid) =>
                      w.byUser[uid] ? (
                        <span
                          key={uid}
                          className="hb-weekbar__seg"
                          style={{ width: `${(w.byUser[uid] / maxWeekSeconds) * 100}%`, background: `oklch(0.62 0.1 ${userMeta(uid)?.hue ?? 150})` }}
                          title={`${userMeta(uid)?.name ?? uid}: ${fmtDurationShort(w.byUser[uid])}`}
                        />
                      ) : null,
                    )}
                  </div>
                  <div className="hb-weekrow__sub">
                    {w.count > 0 && `${w.count} ${w.count === 1 ? t('time.entryOne') : t('time.entryMany')}`}
                    {w.count > 0 && w.credited > 0 && ' · '}
                    {w.credited > 0 && `${fmtDurationShort(w.credited)} ${t('time.creditedShort')}`}
                  </div>
                </div>
              ))}
            </div>

            <div className="hb-sectionlabel hb-detail-h">{t('time.allEntries')}</div>
            <DayGroupedList entries={projEntries} projectsById={projectsById} onDelete={onDelete} onEdit={onEdit} onSplit={onSplit} showProject={false} />
          </>
        )}
      </Card>
    </>
  )
}

// What the manual-entry sheet submits; userId only when recording for the partner.
interface ManualEntryBody {
  projectId: string
  startedAt: string
  stoppedAt: string
  description?: string
  userId?: string
}

function ManualEntryModal({ projects, users, me, onCreate, onClose }: {
  projects: Project[]
  users: string[]
  me: string | null
  onCreate: (body: ManualEntryBody) => Promise<string | null>
  onClose: () => void
}) {
  const { t } = useTranslation()
  const today = dayKey(new Date())
  const [projectId, setProjectId] = useState(projects[0]?.id ?? '')
  const [date, setDate] = useState(today)
  const [start, setStart] = useState('09:00')
  const [end, setEnd] = useState('10:00')
  const [description, setDescription] = useState('')
  // Who the entry is for — self by default, any household member selectable (a
  // partner target is confirmed by the caller before it posts, #129). Without a
  // decoded own username the selector stays hidden and entries are recorded as self.
  const [forUser, setForUser] = useState(me ?? '')
  const partners = me ? users.filter((u) => u !== me) : []
  const [error, setError] = useState<string | null>(null)
  const submitRef = useRef(false)

  const submit = async () => {
    if (!projectId || submitRef.current) return
    const startedAt = new Date(`${date}T${start}`)
    const stoppedAt = new Date(`${date}T${end}`)
    if (!(stoppedAt.getTime() > startedAt.getTime())) {
      setError(t('time.endAfterStart'))
      return
    }
    submitRef.current = true
    setError(null)
    // On failure the modal stays open; re-enable submit and show the reason.
    // The catch covers transport errors (offline) so the button can't get stuck.
    try {
      const err = await onCreate({
        projectId,
        startedAt: startedAt.toISOString(),
        stoppedAt: stoppedAt.toISOString(),
        description: description.trim() || undefined,
        userId: me && forUser && forUser !== me ? forUser : undefined,
      })
      if (err) {
        submitRef.current = false
        setError(err)
      } else {
        // a partner-targeted create defers behind the confirm dialog — allow a
        // re-submit in case it is cancelled and the sheet is still open
        submitRef.current = false
      }
    } catch {
      submitRef.current = false
      setError(t('time.saveFailed'))
    }
  }

  return (
    <Sheet
      open
      onClose={onClose}
      title={t('time.recordEntry')}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>{t('common.cancel')}</Button>
          <Button onClick={submit} disabled={!projectId}>{t('common.save')}</Button>
        </>
      }
    >
      <Field label={t('time.project')}>
        <Select value={projectId} onChange={setProjectId}>
          {projects.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
        </Select>
      </Field>
      {me && partners.length > 0 && (
        <Field label={t('time.personLabel')}>
          <Select value={forUser} onChange={setForUser}>
            <option value={me}>{userMeta(me)?.name ?? me}</option>
            {partners.map((u) => <option key={u} value={u}>{userMeta(u)?.name ?? u}</option>)}
          </Select>
        </Field>
      )}
      <Field label={t('time.date')}>
        <TextInput type="date" value={date} onChange={setDate} />
      </Field>
      <div className="hb-formgrid">
        <Field label={t('time.from')}><TextInput type="time" value={start} onChange={setStart} /></Field>
        <Field label={t('time.to')}><TextInput type="time" value={end} onChange={setEnd} /></Field>
      </div>
      <Field label={t('common.descriptionOptional')}>
        <TextInput value={description} onChange={setDescription} placeholder={t('common.descriptionOptional')} />
      </Field>
      {error && <p style={{ color: 'oklch(0.55 0.16 32)', fontSize: 13.5, margin: 0 }}>{error}</p>}
    </Sheet>
  )
}

// Edit an existing entry's project / start / stop / description, or — for a still
// running timer — just its start time (stop stays open, so only `startedAt` is sent).
// Mirrors ManualEntryModal's inline-error handling. The project select offers the
// active projects plus the entry's current one (so an archived project stays
// selectable as the no-op default, but you can't switch *to* an archived one).
function EditEntryModal({ entry, projects, onSave, onClose }: {
  entry: TimeEntry
  projects: Project[]
  onSave: (id: string, body: object) => Promise<string | null>
  onClose: () => void
}) {
  const { t } = useTranslation()
  const running = !entry.stoppedAt
  const [projectId, setProjectId] = useState(entry.projectId)
  const [start, setStart] = useState(toLocalInput(entry.startedAt))
  const [stop, setStop] = useState(entry.stoppedAt ? toLocalInput(entry.stoppedAt) : '')
  const [description, setDescription] = useState(entry.description ?? '')
  const [error, setError] = useState<string | null>(null)
  const submitRef = useRef(false)

  const projectOptions = projects.filter((p) => !p.archived || p.id === entry.projectId)

  const submit = async () => {
    if (submitRef.current) return
    if (!start) {
      setError(t('errors.INVALID_DATE'))
      return
    }
    if (!projectId) return
    const startedAt = new Date(start)
    const body: Record<string, unknown> = { startedAt: startedAt.toISOString() }
    // Only send projectId when it actually changed: re-sending an unchanged but
    // archived project would trip the backend's PROJECT_ARCHIVED guard and block
    // a pure time/description edit on an archived project's entry.
    if (projectId !== entry.projectId) body.projectId = projectId
    if (running) {
      // The backend skips its range check while stoppedAt is null, so guard here
      // against a future start that would freeze the live clock at 00:00:00.
      if (startedAt.getTime() > Date.now()) {
        setError(t('time.startInFuture'))
        return
      }
    } else {
      const stoppedAt = new Date(stop)
      if (!(stoppedAt.getTime() > startedAt.getTime())) {
        setError(t('time.endAfterStart'))
        return
      }
      body.stoppedAt = stoppedAt.toISOString()
      body.description = description.trim() // sent raw so an emptied field clears it
    }
    submitRef.current = true
    setError(null)
    // On failure the modal stays open; re-enable submit and show the reason.
    // The catch covers transport errors (offline) so the button can't get stuck.
    try {
      const err = await onSave(entry.id, body)
      if (err) {
        submitRef.current = false
        setError(err)
      }
    } catch {
      submitRef.current = false
      setError(t('time.saveFailed'))
    }
  }

  return (
    <Sheet
      open
      onClose={onClose}
      title={running ? t('time.editRunning') : t('time.editEntry')}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>{t('common.cancel')}</Button>
          <Button onClick={submit} disabled={!start || !projectId || (!running && !stop)}>{t('common.save')}</Button>
        </>
      }
    >
      {running && <p className="hb-muted" style={{ marginTop: 0 }}>{t('time.editRunningHint')}</p>}
      <Field label={t('time.project')}>
        <Select value={projectId} onChange={setProjectId}>
          {projectOptions.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
        </Select>
      </Field>
      <Field label={t('time.startLabel')}>
        <TextInput type="datetime-local" value={start} onChange={setStart} />
      </Field>
      {!running && (
        <Field label={t('time.endLabel')}>
          <TextInput type="datetime-local" value={stop} onChange={setStop} />
        </Field>
      )}
      {!running && (
        <Field label={t('common.descriptionOptional')}>
          <TextInput value={description} onChange={setDescription} placeholder={t('common.descriptionOptional')} />
        </Field>
      )}
      {error && <p style={{ color: 'oklch(0.55 0.16 32)', fontSize: 13.5, margin: 0 }}>{error}</p>}
    </Sheet>
  )
}

// Split an entry at a cut time into two parts, optionally with an untracked
// break between them (#62) — the break is just a gap, no row of its own.
// Typical uses: a forgotten lunch break, or a missed project switch (split,
// then edit part two). Works on a *running* timer too (#634): part one closes
// at the cut, part two keeps running — so the cut and the break must lie in the
// past (`nowMs` takes the place of the missing stoppedAt). Shows a live preview
// of both resulting parts; same inline-error convention as the other modals.
function SplitEntryModal({ entry, nowMs, onSave, onClose }: {
  entry: TimeEntry
  nowMs: number
  onSave: (id: string, body: object) => Promise<string | null>
  onClose: () => void
}) {
  const { t } = useTranslation()
  const startMs = new Date(entry.startedAt).getTime()
  const isRunning = !entry.stoppedAt
  const stopMs = entry.stoppedAt ? new Date(entry.stoppedAt).getTime() : nowMs
  // default cut: the entry's midpoint, snapped to the full minute
  const [cut, setCut] = useState(() => toLocalInput(new Date(Math.floor((startMs + stopMs) / 2 / 60000) * 60000).toISOString()))
  const [breakMin, setBreakMin] = useState('')
  const [error, setError] = useState<string | null>(null)
  const submitRef = useRef(false)

  const cutMs = cut ? new Date(cut).getTime() : NaN
  // comma or dot input is fine ("7,5" / "7.5", #299); the backend takes whole minutes, so round
  const breakRaw = breakMin.trim() === '' ? 0 : (parseLocaleNumber(breakMin) ?? NaN)
  const breakNum = Math.round(breakRaw)
  const breakParses = Number.isFinite(breakRaw) && breakNum >= 0
  const secondStartMs = cutMs + breakNum * 60000
  const cutValid = Number.isFinite(cutMs) && cutMs > startMs && cutMs < stopMs
  const breakValid = breakParses && secondStartMs < stopMs

  const submit = async () => {
    if (submitRef.current) return
    if (!cutValid) return setError(t(isRunning ? 'time.splitInvalidCutRunning' : 'time.splitInvalidCut'))
    if (!breakParses) return setError(t('time.splitInvalidBreak'))
    if (!breakValid) return setError(t(isRunning ? 'time.splitBreakTooLongRunning' : 'time.splitBreakTooLong'))
    submitRef.current = true
    setError(null)
    // On failure the modal stays open; re-enable submit and show the reason.
    // The catch covers transport errors (offline) so the button can't get stuck.
    try {
      const err = await onSave(entry.id, {
        splitAt: new Date(cutMs).toISOString(),
        breakMinutes: breakNum > 0 ? breakNum : undefined,
      })
      if (err) {
        submitRef.current = false
        setError(err)
      }
    } catch {
      submitRef.current = false
      setError(t('time.splitFailed'))
    }
  }

  return (
    <Modal
      open
      onClose={onClose}
      title={t('time.splitTitle')}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>{t('common.cancel')}</Button>
          <Button icon="scissors" onClick={submit} disabled={!cut}>{t('common.save')}</Button>
        </>
      }
    >
      <p className="hb-muted" style={{ marginTop: 0 }}>{t(isRunning ? 'time.splitHintRunning' : 'time.splitHint')}</p>
      <Field label={t('time.splitAtLabel')}>
        <TextInput type="datetime-local" value={cut} onChange={setCut} />
      </Field>
      <Field label={t('time.breakLabel')}>
        <TextInput value={breakMin} onChange={setBreakMin} placeholder="0" />
      </Field>
      {cutValid && breakValid && (
        <p className="hb-mono hb-muted" style={{ fontSize: 13.5, margin: 0 }}>
          {t('time.splitPart1')} {clockTime(entry.startedAt)}–{clockTime(new Date(cutMs).toISOString())}
          {' · '}
          {t('time.splitPart2')} {clockTime(new Date(secondStartMs).toISOString())}–{entry.stoppedAt ? clockTime(entry.stoppedAt) : t('time.running')}
        </p>
      )}
      {error && <p style={{ color: 'oklch(0.55 0.16 32)', fontSize: 13.5, margin: 0 }}>{error}</p>}
    </Modal>
  )
}
