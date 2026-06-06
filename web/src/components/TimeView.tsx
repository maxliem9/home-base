import { Fragment, useState, useEffect, useCallback, useMemo, useRef } from 'react'
import { API_BASE, authFetch, errorCode, safeFetch, withWsToken } from '../api'
import { t, errorText } from '../i18n'
import { Project, TimeEntry } from '../types'
import { useWebSocket } from '../hooks/useWebSocket'
import { Icon } from '../ui/Icon'
import { Avatar, Button, Card, EmptyState, Field, IconButton, Modal, PageHead, Select, TextInput } from '../ui/primitives'
import { clockTime, dayGroupLabel, fmtClock, fmtDurationShort, userMeta, usernameFromToken, weekKey, weekLabel } from '../ui/format'

const WS_SCHEME = window.location.protocol === 'https:' ? 'wss' : 'ws'
const WS_URL = import.meta.env.VITE_WS_URL_TIME ?? `${WS_SCHEME}://${window.location.host}/api/v1/ws/time`

const COLOR_CHOICES = ['#B4654A', '#C98A3B', '#4F7A52', '#3F7C8C', '#6E5AA6', '#A6537A', '#7A8B57', '#64748B']

interface TimeViewProps {
  token: string
  onLogout: () => void
}

function elapsedSeconds(startedAt: string, nowMs: number): number {
  return Math.max(0, Math.floor((nowMs - new Date(startedAt).getTime()) / 1000))
}

function dayKey(d: Date): string {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

interface ProjectDraft {
  id?: string
  name: string
  color: string
}

export function TimeView({ token, onLogout }: TimeViewProps) {
  const me = useMemo(() => usernameFromToken(token), [token])
  const [projects, setProjects] = useState<Project[]>([])
  const [entries, setEntries] = useState<TimeEntry[]>([])
  const [loading, setLoading] = useState(true)
  const [nowMs, setNowMs] = useState(() => Date.now())
  const [showArchived, setShowArchived] = useState(false)
  const [projectDraft, setProjectDraft] = useState<ProjectDraft | null>(null)
  const [showManual, setShowManual] = useState(false)
  const [detailProject, setDetailProject] = useState<Project | null>(null)
  const [desc, setDesc] = useState('')
  const [toast, setToast] = useState<string | null>(null)

  // Surface a write failure to the user. The backend cleanly rejects the
  // mutation (no data loss), but without this the action would just silently
  // not happen — see issue #84.
  const flashError = useCallback((msg: string) => {
    setToast(msg)
    setTimeout(() => setToast(null), 3500)
  }, [])

  const projectsById = useMemo(() => Object.fromEntries(projects.map((p) => [p.id, p])), [projects])
  const running = useMemo(() => entries.find((e) => !e.stoppedAt && (!me || e.userId === me)) ?? null, [entries, me])

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

  // keep the hero description input in sync with the running entry
  useEffect(() => { setDesc(running?.description ?? '') }, [running?.id])

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
        if (msg.type === 'PROJECT_CREATED') setProjects((prev) => (prev.some((x) => x.id === p.id) ? prev : [...prev, p]))
        else if (msg.type === 'PROJECT_UPDATED') setProjects((prev) => prev.map((x) => (x.id === p.id ? p : x)))
      } else if (msg.entry) {
        const e: TimeEntry = msg.entry
        if (msg.type === 'ENTRY_CREATED') setEntries((prev) => (prev.some((x) => x.id === e.id) ? prev.map((x) => (x.id === e.id ? e : x)) : [e, ...prev]))
        else if (msg.type === 'ENTRY_UPDATED') setEntries((prev) => prev.map((x) => (x.id === e.id ? e : x)))
        else if (msg.type === 'ENTRY_DELETED') setEntries((prev) => prev.filter((x) => x.id !== e.id))
      }
    } catch {
      // ignore malformed frames
    }
  })

  // The three click-driven write paths use safeFetch so a rejected fetch
  // (offline/DNS/aborted — issue #93) shows the per-action fallback toast
  // instead of an unhandled rejection. On a transport failure no backend code
  // exists, so errorText(null, fallback) resolves to the German fallback.
  const startTimer = async (projectId: string, description = '') => {
    const result = await safeFetch(token, `${API_BASE}/time/entries/start`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ projectId, description: description.trim() || undefined }),
    })
    if (!result.ok) return flashError(errorText(null, t.time.startFailed))
    const { res } = result
    if (res.status === 401) return onLogout()
    if (!res.ok) flashError(errorText(await errorCode(res), t.time.startFailed))
  }

  const stopTimer = async () => {
    const result = await safeFetch(token, `${API_BASE}/time/entries/stop`, { method: 'POST' })
    if (!result.ok) return flashError(errorText(null, t.time.stopFailed))
    const { res } = result
    if (res.status === 401) return onLogout()
    if (!res.ok) flashError(errorText(await errorCode(res), t.time.stopFailed))
  }

  const saveDescription = async () => {
    if (!running || desc === (running.description ?? '')) return
    const result = await safeFetch(token, `${API_BASE}/time/entries/${running.id}`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ description: desc }),
    })
    if (!result.ok) return flashError(errorText(null, t.time.saveFailed))
    const { res } = result
    if (res.status === 401) return onLogout()
    if (!res.ok) flashError(errorText(await errorCode(res), t.time.saveFailed))
  }

  const deleteEntry = async (id: string) => {
    setEntries((prev) => prev.filter((e) => e.id !== id))
    await authFetch(token, `${API_BASE}/time/entries/${id}`, { method: 'DELETE' })
  }

  const setArchived = async (p: Project, archived: boolean) => {
    await authFetch(token, `${API_BASE}/time/projects/${p.id}/archive`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ archived }),
    })
  }

  const saveProject = async (d: ProjectDraft) => {
    if (!d.name.trim()) return
    const body = JSON.stringify({ name: d.name.trim(), color: d.color })
    if (d.id) {
      await authFetch(token, `${API_BASE}/time/projects/${d.id}`, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body })
    } else {
      await authFetch(token, `${API_BASE}/time/projects`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body })
    }
    setProjectDraft(null)
  }

  // Returns null on success, or an error message the modal shows inline (so it
  // stays open for a retry) — e.g. 400 INVALID_RANGE or 409 PROJECT_ARCHIVED.
  const createManual = async (body: object): Promise<string | null> => {
    const res = await authFetch(token, `${API_BASE}/time/entries`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    })
    if (res.status === 401) {
      onLogout()
      return null
    }
    if (!res.ok) return errorText(await errorCode(res), t.time.saveFailed)
    setShowManual(false)
    return null
  }

  // total finished time per project
  const totalsByProject = useMemo(() => {
    const m: Record<string, number> = {}
    for (const e of entries) if (e.stoppedAt && e.durationSeconds) m[e.projectId] = (m[e.projectId] ?? 0) + e.durationSeconds
    return m
  }, [entries])

  const activeProjects = projects.filter((p) => !p.archived)
  const archivedProjects = projects.filter((p) => p.archived)
  const shownProjects = showArchived ? projects : activeProjects

  const recent = useMemo(
    () => entries.filter((e) => e.stoppedAt).sort((a, b) => new Date(b.startedAt).getTime() - new Date(a.startedAt).getTime()).slice(0, 40),
    [entries],
  )

  const runningProject = running ? projectsById[running.projectId] : undefined

  return (
    <div className="hb-page">
      <PageHead
        eyebrow={running ? t.time.running : t.time.projectsLabel}
        title={t.time.title}
        actions={
          <>
            <Button variant="secondary" size="sm" icon="calendar" onClick={() => setShowManual(true)}>{t.time.recordEntry}</Button>
            <Button icon="plus" onClick={() => setProjectDraft({ name: '', color: COLOR_CHOICES[0] })}>{t.time.newProject}</Button>
          </>
        }
      />

      {/* Timer hero */}
      {running ? (
        <Card className="hb-card--pad hb-timerhero is-running">
          <div className="hb-timerhero__left">
            <span className="hb-timerhero__live"><span className="hb-livedot" /> {t.time.running}</span>
            <div className="hb-timerhero__proj">
              <span className="hb-pdot" style={{ background: runningProject?.color ?? 'var(--ink-3)' }} />
              {runningProject?.name ?? t.time.project}
            </div>
            <input
              className="hb-timerhero__desc"
              value={desc}
              placeholder={t.time.descPlaceholder}
              onChange={(e) => setDesc(e.target.value)}
              onBlur={saveDescription}
              onKeyDown={(e) => e.key === 'Enter' && (e.target as HTMLInputElement).blur()}
            />
          </div>
          <div className="hb-timerhero__right">
            <div className="hb-timerhero__clock hb-mono">{fmtClock(elapsedSeconds(running.startedAt, nowMs))}</div>
            <Button variant="secondary" icon="stop" onClick={stopTimer}>{t.time.stop}</Button>
          </div>
        </Card>
      ) : (
        <Card className="hb-card--pad hb-timerhero">
          <div className="hb-timerhero__left">
            <span className="hb-timerhero__live" style={{ color: 'var(--ink-3)' }}>{t.time.noTimer}</span>
            {activeProjects.length === 0 ? (
              <div className="hb-muted">{t.time.noProjectsHint}</div>
            ) : (
              <>
                <div className="hb-muted">{t.time.startPrompt}</div>
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

      {loading ? (
        <p className="hb-muted" style={{ textAlign: 'center', padding: 24 }}>{t.common.loading}</p>
      ) : (
        <div className="hb-zeit-grid">
          {/* Projects */}
          <div>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
              <div className="hb-sectionlabel">{t.time.projectsLabel}</div>
              {archivedProjects.length > 0 && (
                <button className="hb-link" onClick={() => setShowArchived((v) => !v)}>
                  {showArchived ? t.time.hideArchived : t.time.showArchived}
                </button>
              )}
            </div>
            {shownProjects.length === 0 ? (
              <Card className="hb-card--pad"><EmptyState icon="clock" title={t.time.noProjects} hint={t.time.noProjectsHint} /></Card>
            ) : (
              <div className="hb-proj-grid">
                {shownProjects.map((p) => {
                  const isRunning = running?.projectId === p.id
                  return (
                    <Card key={p.id} className={`hb-projcard${isRunning ? ' is-running' : ''}${p.archived ? ' is-archived' : ''}`}>
                      <div className="hb-projcard__head">
                        <span className="hb-pdot" style={{ background: p.color }} />
                        <button className="hb-projcard__name hb-projcard__namebtn" title={t.time.viewDetails} onClick={() => setDetailProject(p)}>{p.name}</button>
                        <div style={{ display: 'flex', gap: 2 }}>
                          <IconButton icon="edit" label={t.common.edit} onClick={() => setProjectDraft({ id: p.id, name: p.name, color: p.color })} />
                          <IconButton
                            icon="archive"
                            label={p.archived ? t.time.reactivate : t.time.archive}
                            active={p.archived}
                            onClick={() => setArchived(p, !p.archived)}
                          />
                        </div>
                      </div>
                      <button className="hb-projcard__stat hb-projcard__statbtn hb-mono" onClick={() => setDetailProject(p)}>
                        {fmtDurationShort(totalsByProject[p.id] ?? 0)}<span> {t.time.total} →</span>
                      </button>
                      {!p.archived && (
                        isRunning ? (
                          <Button variant="secondary" size="sm" icon="stop" onClick={stopTimer}>{t.time.stop}</Button>
                        ) : (
                          <Button variant="soft" size="sm" icon="play" onClick={() => startTimer(p.id)}>{t.time.start}</Button>
                        )
                      )}
                    </Card>
                  )
                })}
              </div>
            )}
          </div>

          {/* Recent entries */}
          <div>
            <div className="hb-sectionlabel">{t.time.recentEntries}</div>
            <Card className="hb-card--pad">
              {recent.length === 0 ? (
                <EmptyState icon="clock" title={t.time.noEntries} hint={t.time.emptyHint} />
              ) : (
                <DayGroupedList entries={recent} projectsById={projectsById} me={me} onDelete={deleteEntry} showProject />
              )}
            </Card>
          </div>
        </div>
      )}

      {/* Project create/edit modal */}
      <Modal
        open={!!projectDraft}
        onClose={() => setProjectDraft(null)}
        title={projectDraft?.id ? t.time.editProject : t.time.newProject}
        footer={
          <>
            <Button variant="ghost" onClick={() => setProjectDraft(null)}>{t.common.cancel}</Button>
            <Button onClick={() => projectDraft && saveProject(projectDraft)} disabled={!projectDraft?.name.trim()}>
              {projectDraft?.id ? t.common.save : t.time.create}
            </Button>
          </>
        }
      >
        {projectDraft && (
          <>
            <Field label={t.time.project}>
              <TextInput autoFocus value={projectDraft.name} onChange={(v) => setProjectDraft({ ...projectDraft, name: v })} placeholder={t.time.projectNamePlaceholder} />
            </Field>
            <Field label={t.time.color}>
              <div className="hb-swatches">
                {COLOR_CHOICES.map((c) => (
                  <button
                    key={c}
                    className={`hb-swatch${projectDraft.color === c ? ' is-active' : ''}`}
                    style={{ background: c }}
                    onClick={() => setProjectDraft({ ...projectDraft, color: c })}
                    aria-label={`${t.time.colorLabel} ${c}`}
                  />
                ))}
              </div>
            </Field>
          </>
        )}
      </Modal>

      {showManual && (
        <ManualEntryModal projects={activeProjects} onCreate={createManual} onClose={() => setShowManual(false)} />
      )}

      {detailProject && (
        <ProjectDetail
          project={detailProject}
          entries={entries}
          projectsById={projectsById}
          me={me}
          onDelete={deleteEntry}
          onClose={() => setDetailProject(null)}
        />
      )}

      {toast && (
        <div className="hb-toast hb-toast--error" role="alert">
          <Icon name="x" size={18} stroke={2.4} />
          {toast}
        </div>
      )}
    </div>
  )
}

// Group stopped entries (already sorted newest-first) into day buckets with a
// separator label and per-day total.
function groupByDay(entries: TimeEntry[]) {
  const groups: { key: string; label: string; seconds: number; entries: TimeEntry[] }[] = []
  const map = new Map<string, (typeof groups)[number]>()
  for (const e of entries) {
    const iso = e.stoppedAt ?? e.startedAt
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

function EntryRow({ entry, project, me, onDelete, showProject }: {
  entry: TimeEntry
  project?: Project
  me: string | null
  onDelete: (id: string) => void
  showProject: boolean
}) {
  const own = !me || entry.userId === me
  const noDesc = <span className="hb-muted">{t.time.noDescription}</span>
  return (
    <div className="hb-row">
      {showProject && <span className="hb-pdot" style={{ background: project?.color ?? 'var(--ink-3)' }} />}
      <div className="hb-row__main">
        <div className="hb-row__title">{showProject ? (project?.name ?? t.time.project) : (entry.description || noDesc)}</div>
        <div className="hb-row__meta">
          {showProject && (entry.description ? <span>{entry.description}</span> : noDesc)}
          {showProject && <span className="dot-sep" />}
          <span className="hb-mono">{clockTime(entry.startedAt)}–{entry.stoppedAt ? clockTime(entry.stoppedAt) : ''}</span>
        </div>
      </div>
      <div className="hb-row__right">
        <Avatar user={entry.userId} size={24} />
        <span className="hb-mono" style={{ fontWeight: 600, minWidth: 64, textAlign: 'right' }}>{fmtDurationShort(entry.durationSeconds ?? 0)}</span>
        {own ? (
          <IconButton icon="trash" label={t.common.delete} danger onClick={() => onDelete(entry.id)} />
        ) : (
          <span className="hb-iconbtn" title={t.time.ownEntriesOnly} style={{ cursor: 'default' }}><Icon name="lock" size={16} stroke={2} /></span>
        )}
      </div>
    </div>
  )
}

// Reusable day-grouped entry list. `showProject` toggles whether the project
// name (recent list) or the description (project detail) is the row title.
function DayGroupedList({ entries, projectsById, me, onDelete, showProject }: {
  entries: TimeEntry[]
  projectsById: Record<string, Project>
  me: string | null
  onDelete: (id: string) => void
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
            <EntryRow key={e.id} entry={e} project={projectsById[e.projectId]} me={me} onDelete={onDelete} showProject={showProject} />
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
  seconds: number
  count: number
  byUser: Record<string, number>
}

function ProjectDetail({ project, entries, projectsById, me, onDelete, onClose }: {
  project: Project
  entries: TimeEntry[]
  projectsById: Record<string, Project>
  me: string | null
  onDelete: (id: string) => void
  onClose: () => void
}) {
  const projEntries = useMemo(
    () =>
      entries
        .filter((e) => e.projectId === project.id && e.stoppedAt)
        .sort((a, b) => (b.stoppedAt ?? '').localeCompare(a.stoppedAt ?? '')),
    [entries, project.id],
  )

  const totalSeconds = projEntries.reduce((s, e) => s + (e.durationSeconds ?? 0), 0)

  // per-user totals
  const byUser: Record<string, number> = {}
  for (const e of projEntries) byUser[e.userId] = (byUser[e.userId] ?? 0) + (e.durationSeconds ?? 0)
  const userIds = Object.keys(byUser)

  // per-week summary (entries are newest-first → weeks newest-first)
  const weekMap = new Map<string, WeekBucket>()
  for (const e of projEntries) {
    const k = weekKey(e.stoppedAt!)
    let w = weekMap.get(k)
    if (!w) {
      const { label, range } = weekLabel(e.stoppedAt!)
      w = { key: k, label, range, seconds: 0, count: 0, byUser: {} }
      weekMap.set(k, w)
    }
    w.seconds += e.durationSeconds ?? 0
    w.count += 1
    w.byUser[e.userId] = (w.byUser[e.userId] ?? 0) + (e.durationSeconds ?? 0)
  }
  const weeks = [...weekMap.values()]
  const maxWeekSeconds = Math.max(...weeks.map((w) => w.seconds), 1)
  const thisWeekSeconds = weekMap.get(weekKey(new Date().toISOString()))?.seconds ?? 0
  const avgSeconds = projEntries.length ? totalSeconds / projEntries.length : 0

  return (
    <Modal
      open
      onClose={onClose}
      width={660}
      title={
        <span style={{ display: 'inline-flex', alignItems: 'center', gap: 11 }}>
          <span className="hb-pdot" style={{ background: project.color, width: 14, height: 14 }} />
          {project.name}
        </span>
      }
    >
      <div className="hb-detail-stats">
        <div className="hb-fact"><span className="hb-fact__v hb-mono">{fmtDurationShort(totalSeconds)}</span><span className="hb-fact__l">{t.time.detailTotal}</span></div>
        <div className="hb-fact"><span className="hb-fact__v hb-mono">{fmtDurationShort(thisWeekSeconds)}</span><span className="hb-fact__l">{t.time.thisWeek}</span></div>
        <div className="hb-fact"><span className="hb-fact__v hb-mono">{projEntries.length}</span><span className="hb-fact__l">{t.time.detailEntries}</span></div>
        <div className="hb-fact"><span className="hb-fact__v hb-mono">{fmtDurationShort(avgSeconds)}</span><span className="hb-fact__l">{t.time.detailAvg}</span></div>
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

      {projEntries.length === 0 ? (
        <EmptyState icon="clock" title={t.time.noEntries} hint={t.time.detailEmptyHint} />
      ) : (
        <>
          <div className="hb-sectionlabel hb-detail-h">{t.time.perWeek}</div>
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
                <div className="hb-weekrow__sub">{w.count} {w.count === 1 ? t.time.entryOne : t.time.entryMany}</div>
              </div>
            ))}
          </div>

          <div className="hb-sectionlabel hb-detail-h">{t.time.allEntries}</div>
          <DayGroupedList entries={projEntries} projectsById={projectsById} me={me} onDelete={onDelete} showProject={false} />
        </>
      )}
    </Modal>
  )
}

function ManualEntryModal({ projects, onCreate, onClose }: {
  projects: Project[]
  onCreate: (body: object) => Promise<string | null>
  onClose: () => void
}) {
  const today = dayKey(new Date())
  const [projectId, setProjectId] = useState(projects[0]?.id ?? '')
  const [date, setDate] = useState(today)
  const [start, setStart] = useState('09:00')
  const [end, setEnd] = useState('10:00')
  const [description, setDescription] = useState('')
  const [error, setError] = useState<string | null>(null)
  const submitRef = useRef(false)

  const submit = async () => {
    if (!projectId || submitRef.current) return
    const startedAt = new Date(`${date}T${start}`)
    const stoppedAt = new Date(`${date}T${end}`)
    if (!(stoppedAt.getTime() > startedAt.getTime())) {
      setError(t.time.endAfterStart)
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
      })
      if (err) {
        submitRef.current = false
        setError(err)
      }
    } catch {
      submitRef.current = false
      setError(t.time.saveFailed)
    }
  }

  return (
    <Modal
      open
      onClose={onClose}
      title={t.time.recordEntry}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>{t.common.cancel}</Button>
          <Button onClick={submit} disabled={!projectId}>{t.common.save}</Button>
        </>
      }
    >
      <Field label={t.time.project}>
        <Select value={projectId} onChange={setProjectId}>
          {projects.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
        </Select>
      </Field>
      <Field label={t.time.date}>
        <TextInput type="date" value={date} onChange={setDate} />
      </Field>
      <div className="hb-formgrid">
        <Field label={t.time.from}><TextInput type="time" value={start} onChange={setStart} /></Field>
        <Field label={t.time.to}><TextInput type="time" value={end} onChange={setEnd} /></Field>
      </div>
      <Field label={t.common.descriptionOptional}>
        <TextInput value={description} onChange={setDescription} placeholder={t.common.descriptionOptional} />
      </Field>
      {error && <p style={{ color: 'oklch(0.55 0.16 32)', fontSize: 13.5, margin: 0 }}>{error}</p>}
    </Modal>
  )
}
