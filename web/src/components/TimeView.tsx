import { useState, useEffect, useCallback, useMemo, useRef } from 'react'
import { API_BASE, authFetch, withWsToken } from '../api'
import { Project, TimeEntry } from '../types'
import { useWebSocket } from '../hooks/useWebSocket'

const WS_SCHEME = window.location.protocol === 'https:' ? 'wss' : 'ws'
const WS_URL = import.meta.env.VITE_WS_URL_TIME ?? `${WS_SCHEME}://${window.location.host}/api/v1/ws/time`

type SubView = 'day' | 'week' | 'projects'

interface TimeViewProps {
  token: string
  onLogout: () => void
}

// --- helpers ---------------------------------------------------------------

function currentUsername(token: string): string | null {
  try {
    return JSON.parse(atob(token.split('.')[1])).username ?? null
  } catch {
    return null
  }
}

function elapsedSeconds(startedAt: string, nowMs: number): number {
  return Math.max(0, Math.floor((nowMs - new Date(startedAt).getTime()) / 1000))
}

function formatDuration(seconds: number): string {
  const h = Math.floor(seconds / 3600)
  const m = Math.floor((seconds % 3600) / 60)
  if (h > 0) return `${h}h ${m}m`
  if (m > 0) return `${m}m`
  return `${seconds}s`
}

function formatClock(seconds: number): string {
  const h = Math.floor(seconds / 3600)
  const m = Math.floor((seconds % 3600) / 60)
  const s = seconds % 60
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${pad(h)}:${pad(m)}:${pad(s)}`
}

function dayKey(iso: string): string {
  const d = new Date(iso)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

function formatDayLabel(key: string): string {
  const [y, m, d] = key.split('-').map(Number)
  const date = new Date(y, m - 1, d)
  const today = new Date()
  const isToday = dayKey(today.toISOString()) === key
  const yesterday = new Date(today)
  yesterday.setDate(today.getDate() - 1)
  const isYesterday = dayKey(yesterday.toISOString()) === key
  if (isToday) return 'Heute'
  if (isYesterday) return 'Gestern'
  return date.toLocaleDateString('de-DE', { weekday: 'long', day: '2-digit', month: 'long' })
}

function formatTime(iso: string): string {
  return new Date(iso).toLocaleTimeString('de-DE', { hour: '2-digit', minute: '2-digit' })
}

function startOfWeek(d: Date): Date {
  const date = new Date(d.getFullYear(), d.getMonth(), d.getDate())
  const day = (date.getDay() + 6) % 7 // Monday = 0
  date.setDate(date.getDate() - day)
  return date
}

// --- component -------------------------------------------------------------

export function TimeView({ token, onLogout }: TimeViewProps) {
  const me = useMemo(() => currentUsername(token), [token])
  const [projects, setProjects] = useState<Project[]>([])
  const [entries, setEntries] = useState<TimeEntry[]>([])
  const [loading, setLoading] = useState(true)
  const [view, setView] = useState<SubView>('day')
  const [nowMs, setNowMs] = useState(() => Date.now())

  const [showStart, setShowStart] = useState(false)
  const [showManual, setShowManual] = useState(false)

  const projectsById = useMemo(
    () => Object.fromEntries(projects.map((p) => [p.id, p])),
    [projects],
  )

  const running = useMemo(
    () => entries.find((e) => !e.stoppedAt && (!me || e.userId === me)) ?? null,
    [entries, me],
  )

  const fetchAll = useCallback(async () => {
    try {
      const [pRes, eRes] = await Promise.all([
        authFetch(token, `${API_BASE}/time/projects`),
        authFetch(token, `${API_BASE}/time/entries`),
      ])
      if (pRes.status === 401 || eRes.status === 401) {
        onLogout()
        return
      }
      if (pRes.ok) setProjects(await pRes.json())
      if (eRes.ok) setEntries(await eRes.json())
    } finally {
      setLoading(false)
    }
  }, [onLogout, token])

  useEffect(() => { fetchAll() }, [fetchAll])

  // tick once a second while a timer is running so the live clock updates
  useEffect(() => {
    if (!running) return
    const id = setInterval(() => setNowMs(Date.now()), 1000)
    return () => clearInterval(id)
  }, [running])

  useWebSocket(withWsToken(WS_URL, token), (raw) => {
    try {
      const msg = JSON.parse(raw)
      if (msg.project) {
        const p: Project = msg.project
        if (msg.type === 'PROJECT_CREATED') {
          setProjects((prev) => prev.some((x) => x.id === p.id) ? prev : [...prev, p])
        } else if (msg.type === 'PROJECT_UPDATED') {
          setProjects((prev) => prev.map((x) => x.id === p.id ? p : x))
        }
      } else if (msg.entry) {
        const e: TimeEntry = msg.entry
        if (msg.type === 'ENTRY_CREATED') {
          setEntries((prev) => prev.some((x) => x.id === e.id) ? prev.map((x) => x.id === e.id ? e : x) : [e, ...prev])
        } else if (msg.type === 'ENTRY_UPDATED') {
          setEntries((prev) => prev.map((x) => x.id === e.id ? e : x))
        } else if (msg.type === 'ENTRY_DELETED') {
          setEntries((prev) => prev.filter((x) => x.id !== e.id))
        }
      }
    } catch {
      // ignore malformed frames
    }
  })

  const startTimer = async (projectId: string, description: string) => {
    await authFetch(token, `${API_BASE}/time/entries/start`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ projectId, description: description.trim() || undefined }),
    })
    setShowStart(false)
  }

  const stopTimer = async () => {
    await authFetch(token, `${API_BASE}/time/entries/stop`, { method: 'POST' })
  }

  const createManual = async (body: object) => {
    await authFetch(token, `${API_BASE}/time/entries`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    })
    setShowManual(false)
  }

  const deleteEntry = async (id: string) => {
    setEntries((prev) => prev.filter((e) => e.id !== id))
    await authFetch(token, `${API_BASE}/time/entries/${id}`, { method: 'DELETE' })
  }

  const activeProjects = projects.filter((p) => !p.archived)

  return (
    <div className="min-h-screen bg-gray-50 flex flex-col">
      <header className="bg-white shadow-sm px-4 py-3">
        <div className="flex items-center justify-between gap-3">
          <h1 className="text-xl font-semibold text-gray-800 truncate">HomeBase — Zeit</h1>
          <button onClick={onLogout} className="text-sm text-gray-500 hover:text-gray-800">
            Abmelden
          </button>
        </div>
        <div className="mt-3 flex gap-1 text-sm">
          {([['day', 'Tag'], ['week', 'Woche'], ['projects', 'Projekte']] as [SubView, string][]).map(([id, label]) => (
            <button
              key={id}
              onClick={() => setView(id)}
              className={`px-3 py-1.5 rounded-lg font-medium transition ${
                view === id ? 'bg-indigo-600 text-white' : 'text-gray-500 hover:bg-gray-100'
              }`}
            >
              {label}
            </button>
          ))}
        </div>
      </header>

      {running && (
        <RunningBanner
          entry={running}
          project={projectsById[running.projectId]}
          elapsed={elapsedSeconds(running.startedAt, nowMs)}
          onStop={stopTimer}
        />
      )}

      <main className="flex-1 px-4 py-4 max-w-xl mx-auto w-full">
        {loading ? (
          <p className="text-gray-400 text-center mt-10">Lädt…</p>
        ) : view === 'day' ? (
          <DayView entries={entries} projectsById={projectsById} runningId={running?.id} onDelete={deleteEntry} />
        ) : view === 'week' ? (
          <WeekView entries={entries} projects={projects} />
        ) : (
          <ProjectsManager token={token} projects={projects} onChanged={fetchAll} />
        )}
      </main>

      {view !== 'projects' && (
        <div className="fixed bottom-20 right-6 flex flex-col gap-3 items-end">
          <button
            onClick={() => setShowManual(true)}
            className="px-4 h-11 rounded-full bg-white border border-gray-300 text-gray-700 shadow-md hover:bg-gray-50 text-sm font-medium"
          >
            Eintrag erfassen
          </button>
          <button
            onClick={() => setShowStart(true)}
            disabled={activeProjects.length === 0}
            className="w-14 h-14 rounded-full bg-indigo-600 text-white text-2xl shadow-lg hover:bg-indigo-700 active:scale-95 transition flex items-center justify-center disabled:opacity-50"
            aria-label="Timer starten"
          >
            ▶
          </button>
        </div>
      )}

      {showStart && (
        <StartTimerModal
          projects={activeProjects}
          onStart={startTimer}
          onClose={() => setShowStart(false)}
        />
      )}
      {showManual && (
        <ManualEntryModal
          projects={activeProjects}
          onCreate={createManual}
          onClose={() => setShowManual(false)}
        />
      )}
    </div>
  )
}

// --- running banner --------------------------------------------------------

function RunningBanner({ entry, project, elapsed, onStop }: {
  entry: TimeEntry
  project?: Project
  elapsed: number
  onStop: () => void
}) {
  return (
    <div className="bg-indigo-600 text-white px-4 py-3">
      <div className="max-w-xl mx-auto flex items-center gap-3">
        <span className="w-3 h-3 rounded-full bg-white animate-pulse" style={project ? { background: project.color } : undefined} />
        <div className="flex-1 min-w-0">
          <p className="font-medium truncate">{project?.name ?? 'Projekt'}</p>
          {entry.description && <p className="text-indigo-100 text-sm truncate">{entry.description}</p>}
        </div>
        <span className="font-mono text-lg tabular-nums">{formatClock(elapsed)}</span>
        <button
          onClick={onStop}
          className="ml-1 w-10 h-10 rounded-full bg-white text-indigo-600 flex items-center justify-center hover:bg-indigo-50"
          aria-label="Stoppen"
        >
          ■
        </button>
      </div>
    </div>
  )
}

// --- day view --------------------------------------------------------------

function DayView({ entries, projectsById, runningId, onDelete }: {
  entries: TimeEntry[]
  projectsById: Record<string, Project>
  runningId?: string
  onDelete: (id: string) => void
}) {
  // completed entries only, grouped by day, newest day first
  const byDay = useMemo(() => {
    const groups: Record<string, TimeEntry[]> = {}
    for (const e of entries) {
      if (!e.stoppedAt) continue
      ;(groups[dayKey(e.startedAt)] ??= []).push(e)
    }
    for (const k of Object.keys(groups)) {
      groups[k].sort((a, b) => new Date(b.startedAt).getTime() - new Date(a.startedAt).getTime())
    }
    return groups
  }, [entries])

  const days = Object.keys(byDay).sort((a, b) => b.localeCompare(a))

  if (days.length === 0 && !runningId) {
    return (
      <div className="text-center mt-20">
        <p className="text-gray-400 text-lg">Noch keine Zeiteinträge</p>
        <p className="text-gray-300 text-sm mt-1">Starte einen Timer oder erfasse einen Eintrag</p>
      </div>
    )
  }

  return (
    <div className="space-y-6">
      {days.map((day) => {
        const dayEntries = byDay[day]
        const total = dayEntries.reduce((s, e) => s + (e.durationSeconds ?? 0), 0)
        return (
          <section key={day}>
            <div className="flex items-baseline justify-between mb-2 px-1">
              <h2 className="text-sm font-semibold text-gray-700">{formatDayLabel(day)}</h2>
              <span className="text-sm font-mono text-gray-500 tabular-nums">{formatDuration(total)}</span>
            </div>
            <ul className="space-y-2">
              {dayEntries.map((e) => {
                const p = projectsById[e.projectId]
                return (
                  <li key={e.id} className="bg-white rounded-lg shadow-sm px-4 py-3 flex items-center gap-3">
                    <span className="w-3 h-3 rounded-full shrink-0" style={{ background: p?.color ?? '#9CA3AF' }} />
                    <div className="flex-1 min-w-0">
                      <p className="text-gray-800 truncate">{p?.name ?? 'Projekt'}</p>
                      <p className="text-gray-400 text-xs truncate">
                        {formatTime(e.startedAt)}–{e.stoppedAt ? formatTime(e.stoppedAt) : ''}
                        {e.description ? ` · ${e.description}` : ''}
                      </p>
                    </div>
                    <span className="font-mono text-sm text-gray-600 tabular-nums">{formatDuration(e.durationSeconds ?? 0)}</span>
                    <button
                      onClick={() => onDelete(e.id)}
                      className="text-gray-300 hover:text-red-500 transition px-1"
                      aria-label="Löschen"
                    >
                      ✕
                    </button>
                  </li>
                )
              })}
            </ul>
          </section>
        )
      })}
    </div>
  )
}

// --- week view -------------------------------------------------------------

function WeekView({ entries, projects }: { entries: TimeEntry[]; projects: Project[] }) {
  const weekStart = useMemo(() => startOfWeek(new Date()), [])
  const projectsById = useMemo(
    () => Object.fromEntries(projects.map((p) => [p.id, p])),
    [projects],
  )

  // [day index 0..6][projectId] = seconds
  const data = useMemo(() => {
    const days: Record<string, number>[] = Array.from({ length: 7 }, () => ({}))
    const startMs = weekStart.getTime()
    const endMs = startMs + 7 * 86400_000
    for (const e of entries) {
      if (!e.stoppedAt || !e.durationSeconds) continue
      const t = new Date(e.startedAt).getTime()
      if (t < startMs || t >= endMs) continue
      const idx = Math.floor((t - startMs) / 86400_000)
      const bucket = days[idx]
      bucket[e.projectId] = (bucket[e.projectId] ?? 0) + e.durationSeconds
    }
    return days
  }, [entries, weekStart])

  const dayTotals = data.map((d) => Object.values(d).reduce((a, b) => a + b, 0))
  const maxTotal = Math.max(1, ...dayTotals)
  const weekTotal = dayTotals.reduce((a, b) => a + b, 0)
  const labels = ['Mo', 'Di', 'Mi', 'Do', 'Fr', 'Sa', 'So']

  const usedProjectIds = useMemo(() => {
    const ids = new Set<string>()
    for (const d of data) for (const id of Object.keys(d)) ids.add(id)
    return [...ids]
  }, [data])

  return (
    <div className="space-y-5">
      <div className="bg-white rounded-lg shadow-sm p-4">
        <div className="flex items-baseline justify-between mb-4">
          <h2 className="text-sm font-semibold text-gray-700">Diese Woche</h2>
          <span className="text-sm font-mono text-gray-500 tabular-nums">{formatDuration(weekTotal)}</span>
        </div>
        <div className="flex items-end justify-between gap-2 h-44">
          {data.map((bucket, i) => {
            const total = dayTotals[i]
            return (
              <div key={i} className="flex-1 flex flex-col items-center gap-1 h-full justify-end">
                <div className="text-[10px] text-gray-400 font-mono tabular-nums h-3">
                  {total > 0 ? `${(total / 3600).toFixed(1)}h` : ''}
                </div>
                <div
                  className="w-full max-w-[28px] rounded-t overflow-hidden flex flex-col-reverse bg-gray-100"
                  style={{ height: `${(total / maxTotal) * 100}%`, minHeight: total > 0 ? 4 : 0 }}
                >
                  {Object.entries(bucket).map(([pid, secs]) => (
                    <div
                      key={pid}
                      style={{ height: `${(secs / total) * 100}%`, background: projectsById[pid]?.color ?? '#9CA3AF' }}
                    />
                  ))}
                </div>
                <span className="text-xs text-gray-500">{labels[i]}</span>
              </div>
            )
          })}
        </div>
      </div>

      {usedProjectIds.length > 0 && (
        <div className="bg-white rounded-lg shadow-sm p-4">
          <h3 className="text-xs font-semibold text-gray-400 uppercase tracking-wide mb-2">Legende</h3>
          <ul className="space-y-1">
            {usedProjectIds.map((pid) => {
              const secs = data.reduce((s, d) => s + (d[pid] ?? 0), 0)
              return (
                <li key={pid} className="flex items-center gap-2 text-sm">
                  <span className="w-3 h-3 rounded-full" style={{ background: projectsById[pid]?.color ?? '#9CA3AF' }} />
                  <span className="flex-1 text-gray-700">{projectsById[pid]?.name ?? 'Projekt'}</span>
                  <span className="font-mono text-gray-500 tabular-nums">{formatDuration(secs)}</span>
                </li>
              )
            })}
          </ul>
        </div>
      )}
    </div>
  )
}

// --- projects manager ------------------------------------------------------

const COLOR_CHOICES = ['#4F46E5', '#10B981', '#F59E0B', '#EF4444', '#EC4899', '#06B6D4', '#8B5CF6', '#64748B']

function ProjectsManager({ token, projects, onChanged }: {
  token: string
  projects: Project[]
  onChanged: () => void
}) {
  const [name, setName] = useState('')
  const [color, setColor] = useState(COLOR_CHOICES[0])
  const [submitting, setSubmitting] = useState(false)

  const create = async () => {
    if (!name.trim()) return
    setSubmitting(true)
    try {
      await authFetch(token, `${API_BASE}/time/projects`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name: name.trim(), color }),
      })
      setName('')
      onChanged()
    } finally {
      setSubmitting(false)
    }
  }

  const setArchived = async (p: Project, archived: boolean) => {
    await authFetch(token, `${API_BASE}/time/projects/${p.id}/archive`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ archived }),
    })
    onChanged()
  }

  const active = projects.filter((p) => !p.archived)
  const archived = projects.filter((p) => p.archived)

  return (
    <div className="space-y-5">
      <div className="bg-white rounded-lg shadow-sm p-4">
        <h2 className="text-sm font-semibold text-gray-700 mb-3">Neues Projekt</h2>
        <input
          type="text"
          placeholder="Projektname…"
          value={name}
          onChange={(e) => setName(e.target.value)}
          onKeyDown={(e) => e.key === 'Enter' && create()}
          className="w-full border border-gray-300 rounded-lg px-3 py-2 text-gray-800 focus:outline-none focus:ring-2 focus:ring-indigo-500"
        />
        <div className="flex gap-2 mt-3 flex-wrap">
          {COLOR_CHOICES.map((c) => (
            <button
              key={c}
              onClick={() => setColor(c)}
              className={`w-7 h-7 rounded-full transition ${color === c ? 'ring-2 ring-offset-2 ring-gray-400' : ''}`}
              style={{ background: c }}
              aria-label={`Farbe ${c}`}
            />
          ))}
        </div>
        <button
          onClick={create}
          disabled={submitting || !name.trim()}
          className="mt-3 w-full rounded-lg bg-indigo-600 text-white py-2 font-medium hover:bg-indigo-700 disabled:opacity-50"
        >
          Anlegen
        </button>
      </div>

      <section>
        <h2 className="text-xs font-semibold text-gray-400 uppercase tracking-wide mb-2 px-1">Aktiv</h2>
        {active.length === 0 ? (
          <p className="text-gray-400 text-sm px-1">Keine aktiven Projekte</p>
        ) : (
          <ul className="space-y-2">
            {active.map((p) => (
              <li key={p.id} className="bg-white rounded-lg shadow-sm px-4 py-3 flex items-center gap-3">
                <span className="w-4 h-4 rounded-full shrink-0" style={{ background: p.color }} />
                <span className="flex-1 text-gray-800 truncate">{p.name}</span>
                <button onClick={() => setArchived(p, true)} className="text-sm text-gray-400 hover:text-gray-700">
                  Archivieren
                </button>
              </li>
            ))}
          </ul>
        )}
      </section>

      {archived.length > 0 && (
        <section>
          <h2 className="text-xs font-semibold text-gray-400 uppercase tracking-wide mb-2 px-1">Archiviert</h2>
          <ul className="space-y-2">
            {archived.map((p) => (
              <li key={p.id} className="bg-white rounded-lg shadow-sm px-4 py-3 flex items-center gap-3 opacity-60">
                <span className="w-4 h-4 rounded-full shrink-0" style={{ background: p.color }} />
                <span className="flex-1 text-gray-800 truncate line-through">{p.name}</span>
                <button onClick={() => setArchived(p, false)} className="text-sm text-indigo-500 hover:text-indigo-700">
                  Reaktivieren
                </button>
              </li>
            ))}
          </ul>
        </section>
      )}
    </div>
  )
}

// --- modals ----------------------------------------------------------------

function ModalShell({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div className="fixed inset-0 bg-black/40 flex items-end sm:items-center justify-center p-4 z-50">
      <div className="bg-white rounded-2xl w-full max-w-md p-5 shadow-xl">
        <h2 className="text-lg font-semibold text-gray-800 mb-3">{title}</h2>
        {children}
      </div>
    </div>
  )
}

const inputClass = 'w-full border border-gray-300 rounded-lg px-3 py-2 text-gray-800 focus:outline-none focus:ring-2 focus:ring-indigo-500'

function StartTimerModal({ projects, onStart, onClose }: {
  projects: Project[]
  onStart: (projectId: string, description: string) => void
  onClose: () => void
}) {
  const [projectId, setProjectId] = useState(projects[0]?.id ?? '')
  const [description, setDescription] = useState('')
  const submitRef = useRef(false)

  const submit = () => {
    if (!projectId || submitRef.current) return
    submitRef.current = true
    onStart(projectId, description)
  }

  return (
    <ModalShell title="Timer starten">
      <label className="block text-sm text-gray-500 mb-1">Projekt</label>
      <select value={projectId} onChange={(e) => setProjectId(e.target.value)} className={inputClass}>
        {projects.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
      </select>
      <input
        type="text"
        placeholder="Beschreibung (optional)…"
        value={description}
        onChange={(e) => setDescription(e.target.value)}
        onKeyDown={(e) => e.key === 'Enter' && submit()}
        className={`${inputClass} mt-2`}
      />
      <div className="flex justify-end gap-2 mt-4">
        <button onClick={onClose} className="px-4 py-2 rounded-lg text-gray-600 hover:bg-gray-100">Abbrechen</button>
        <button onClick={submit} disabled={!projectId} className="px-4 py-2 rounded-lg bg-indigo-600 text-white hover:bg-indigo-700 disabled:opacity-50">
          Start
        </button>
      </div>
    </ModalShell>
  )
}

function ManualEntryModal({ projects, onCreate, onClose }: {
  projects: Project[]
  onCreate: (body: object) => void
  onClose: () => void
}) {
  const today = dayKey(new Date().toISOString())
  const [projectId, setProjectId] = useState(projects[0]?.id ?? '')
  const [date, setDate] = useState(today)
  const [start, setStart] = useState('09:00')
  const [end, setEnd] = useState('10:00')
  const [description, setDescription] = useState('')
  const [error, setError] = useState<string | null>(null)
  const submitRef = useRef(false)

  const submit = () => {
    if (!projectId || submitRef.current) return
    const startedAt = new Date(`${date}T${start}`)
    const stoppedAt = new Date(`${date}T${end}`)
    if (!(stoppedAt.getTime() > startedAt.getTime())) {
      setError('Ende muss nach dem Start liegen')
      return
    }
    submitRef.current = true
    onCreate({
      projectId,
      startedAt: startedAt.toISOString(),
      stoppedAt: stoppedAt.toISOString(),
      description: description.trim() || undefined,
    })
  }

  return (
    <ModalShell title="Eintrag erfassen">
      <label className="block text-sm text-gray-500 mb-1">Projekt</label>
      <select value={projectId} onChange={(e) => setProjectId(e.target.value)} className={inputClass}>
        {projects.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
      </select>
      <input type="date" value={date} onChange={(e) => setDate(e.target.value)} className={`${inputClass} mt-2`} />
      <div className="flex gap-2 mt-2">
        <input type="time" value={start} onChange={(e) => setStart(e.target.value)} className={inputClass} />
        <input type="time" value={end} onChange={(e) => setEnd(e.target.value)} className={inputClass} />
      </div>
      <input
        type="text"
        placeholder="Beschreibung (optional)…"
        value={description}
        onChange={(e) => setDescription(e.target.value)}
        className={`${inputClass} mt-2`}
      />
      {error && <p className="text-sm text-red-600 mt-2">{error}</p>}
      <div className="flex justify-end gap-2 mt-4">
        <button onClick={onClose} className="px-4 py-2 rounded-lg text-gray-600 hover:bg-gray-100">Abbrechen</button>
        <button onClick={submit} disabled={!projectId} className="px-4 py-2 rounded-lg bg-indigo-600 text-white hover:bg-indigo-700 disabled:opacity-50">
          Speichern
        </button>
      </div>
    </ModalShell>
  )
}
