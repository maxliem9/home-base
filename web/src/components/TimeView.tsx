import { Fragment, useState, useEffect, useCallback, useMemo, useRef } from 'react'
import { API_BASE, authFetch, errorCode, notifyTransportError, safeFetch } from '../api'
import { t, errorText } from '../i18n'
import { Project, TimeEntry, User } from '../types'
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

// Format an ISO timestamp as the local `YYYY-MM-DDTHH:mm` a <input type="datetime-local">
// expects. `new Date(value)` parses that back as local time, so a round-trip preserves
// the wall-clock the user sees.
function toLocalInput(iso: string): string {
  const d = new Date(iso)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`
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
  const [users, setUsers] = useState<string[]>([])
  const [loading, setLoading] = useState(true)
  const [nowMs, setNowMs] = useState(() => Date.now())
  const [showArchived, setShowArchived] = useState(false)
  const [projectDraft, setProjectDraft] = useState<ProjectDraft | null>(null)
  const [showManual, setShowManual] = useState(false)
  const [showExport, setShowExport] = useState(false)
  const [editEntry, setEditEntry] = useState<TimeEntry | null>(null)
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
  // Live timers of the *other* household member(s) — shown in the partner strip (#142).
  // Guard on `me` so a momentarily-unknown user doesn't mislabel own timer as a partner's.
  const othersRunning = useMemo(() => (me ? entries.filter((e) => !e.stoppedAt && e.userId !== me) : []), [entries, me])
  // Other household members, so we can offer "start a timer for them" even while idle.
  const others = useMemo(() => users.filter((u) => u !== me), [users, me])

  const fetchAll = useCallback(async () => {
    try {
      const [pResult, eResult, uResult] = await Promise.all([
        safeFetch(token, `${API_BASE}/time/projects`),
        safeFetch(token, `${API_BASE}/time/entries`),
        safeFetch(token, `${API_BASE}/users`),
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
      }
    } catch {
      // ignore malformed frames
    }
  })

  // The three click-driven write paths use safeFetch so a rejected fetch
  // (offline/DNS/aborted — issue #93) shows the per-action fallback toast
  // instead of an unhandled rejection. On a transport failure no backend code
  // exists, so errorText(null, fallback) resolves to the German fallback.
  // `userId` starts the timer on behalf of the partner (#142); omitted → self.
  const startTimer = async (projectId: string, description = '', userId?: string) => {
    // Acting on the partner's timer is a cross-person action — confirm first.
    if (userId) {
      const name = userMeta(userId)?.name ?? userId
      if (!confirm(t.time.confirmStartForPartner.replace('{name}', name))) return
    }
    const result = await safeFetch(token, `${API_BASE}/time/entries/start`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ projectId, description: description.trim() || undefined, userId }),
    })
    if (!result.ok) return flashError(errorText(null, t.time.startFailed))
    const { res } = result
    if (res.status === 401) return onLogout()
    if (!res.ok) return flashError(errorText(await errorCode(res), t.time.startFailed))
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
  }

  // `userId` stops the partner's timer (#142); omitted → own timer (no body).
  const stopTimer = async (userId?: string) => {
    if (userId) {
      const name = userMeta(userId)?.name ?? userId
      if (!confirm(t.time.confirmStopPartner.replace('{name}', name))) return
    }
    const init: RequestInit = userId
      ? { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ userId }) }
      : { method: 'POST' }
    const result = await safeFetch(token, `${API_BASE}/time/entries/stop`, init)
    if (!result.ok) return flashError(errorText(null, t.time.stopFailed))
    const { res } = result
    if (res.status === 401) return onLogout()
    if (!res.ok) return flashError(errorText(await errorCode(res), t.time.stopFailed))
    upsertEntry(await res.json())
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
    if (!res.ok) return flashError(errorText(await errorCode(res), t.time.saveFailed))
    upsertEntry(await res.json())
  }

  const deleteEntry = async (id: string) => {
    setEntries((prev) => prev.filter((e) => e.id !== id))
    const result = await safeFetch(token, `${API_BASE}/time/entries/${id}`, { method: 'DELETE' })
    // On failure refetch to resync (the optimistic removal may be wrong) and toast.
    if (!result.ok) {
      await fetchAll()
      return flashError(errorText(null, t.time.deleteFailed))
    }
    const { res } = result
    if (res.status === 401) return onLogout()
    if (!res.ok) {
      await fetchAll()
      flashError(errorText(await errorCode(res), t.time.deleteFailed))
    }
  }

  const setArchived = async (p: Project, archived: boolean) => {
    const result = await safeFetch(token, `${API_BASE}/time/projects/${p.id}/archive`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ archived }),
    })
    if (!result.ok) return flashError(errorText(null, t.time.archiveFailed))
    const { res } = result
    if (res.status === 401) return onLogout()
    if (!res.ok) return flashError(errorText(await errorCode(res), t.time.archiveFailed))
    upsertProject(await res.json())
  }

  const saveProject = async (d: ProjectDraft) => {
    if (!d.name.trim()) return
    const body = JSON.stringify({ name: d.name.trim(), color: d.color })
    const result = d.id
      ? await safeFetch(token, `${API_BASE}/time/projects/${d.id}`, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body })
      : await safeFetch(token, `${API_BASE}/time/projects`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body })
    if (!result.ok) return flashError(errorText(null, t.time.saveFailed))
    const { res } = result
    if (res.status === 401) return onLogout()
    if (!res.ok) return flashError(errorText(await errorCode(res), t.time.saveFailed))
    upsertProject(await res.json())
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
    upsertEntry(await res.json())
    setShowManual(false)
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
    if (!res.ok) return errorText(await errorCode(res), t.time.saveFailed)
    upsertEntry(await res.json())
    setEditEntry(null)
    return null
  }

  // Fetch the server-rendered CSV with the JWT in the Authorization header (keeping
  // the token out of the URL), then trigger a download from the returned blob.
  const exportCsv = async ({ from, to, projectId }: { from?: string; to?: string; projectId?: string }) => {
    const params = new URLSearchParams()
    if (from) params.set('from', new Date(`${from}T00:00:00`).toISOString())
    if (to) params.set('to', new Date(`${to}T23:59:59.999`).toISOString())
    if (projectId) params.set('project_id', projectId)
    const qs = params.toString()
    const result = await safeFetch(token, `${API_BASE}/time/export.csv${qs ? `?${qs}` : ''}`)
    // transport reject → fire the global toast once and abort the download
    if (!result.ok) {
      notifyTransportError()
      return
    }
    const { res } = result
    if (res.status === 401) {
      onLogout()
      return
    }
    if (!res.ok) return
    const blob = await res.blob()
    const filename = res.headers.get('Content-Disposition')?.match(/filename="?([^"]+)"?/)?.[1] ?? 'zeiterfassung.csv'
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = filename
    document.body.appendChild(a)
    a.click()
    a.remove()
    URL.revokeObjectURL(url)
    setShowExport(false)
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
          projectsById={projectsById}
          me={me}
          onDelete={deleteEntry}
          onEdit={setEditEntry}
          onBack={() => setDetailProject(null)}
        />
        {sharedModals}
      </div>
    )
  }

  return (
    <div className="hb-page">
      <PageHead
        eyebrow={running ? t.time.running : t.time.projectsLabel}
        title={t.time.title}
        actions={
          <>
            <Button variant="ghost" size="sm" icon="download" onClick={() => setShowExport(true)}>{t.time.exportCsv}</Button>
            <Button icon="calendar" onClick={() => setShowManual(true)}>{t.time.recordEntry}</Button>
            <Button variant="secondary" size="sm" icon="plus" onClick={() => setProjectDraft({ name: '', color: COLOR_CHOICES[0] })}>{t.time.newProject}</Button>
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
              <IconButton icon="edit" label={t.time.editRunning} size={16} onClick={() => setEditEntry(running)} />
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
            <Button variant="secondary" icon="stop" onClick={() => stopTimer()}>{t.time.stop}</Button>
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

      {/* Partner strip — the other household member's timer (#142): see & stop their
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
              onStop={() => stopTimer(u)}
              onStart={(pid) => startTimer(pid, '', u)}
            />
          ))}
        </div>
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
                          <Button variant="secondary" size="sm" icon="stop" onClick={() => stopTimer()}>{t.time.stop}</Button>
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
                <DayGroupedList entries={recent} projectsById={projectsById} me={me} onDelete={deleteEntry} onEdit={setEditEntry} showProject />
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

      {showExport && (
        <ExportModal projects={projects} onExport={exportCsv} onClose={() => setShowExport(false)} />
      )}

      {sharedModals}
    </div>
  )
}

// The other household member's timer (#142): when they're running, show project +
// live clock + Stop; when idle, offer a project picker to start one on their behalf.
function PartnerTimer({ user, running, projectsById, nowMs, projects, onStop, onStart }: {
  user: string
  running: TimeEntry | null
  projectsById: Record<string, Project>
  nowMs: number
  projects: Project[]
  onStop: () => void
  onStart: (projectId: string) => void
}) {
  const [picking, setPicking] = useState(false)
  const name = userMeta(user)?.name ?? user
  const project = running ? projectsById[running.projectId] : undefined
  return (
    <Card className="hb-card--pad">
      <div style={{ display: 'flex', alignItems: 'center', gap: 11 }}>
        <Avatar user={user} size={26} />
        {running ? (
          <>
            <span className="hb-pdot" style={{ background: project?.color ?? 'var(--ink-3)' }} />
            <div style={{ flex: 1, minWidth: 0 }}>
              <div className="hb-row__title">{project?.name ?? t.time.project}</div>
              <div className="hb-muted" style={{ fontSize: 13 }}>
                {name}{running.description ? ` · ${running.description}` : ''}
              </div>
            </div>
            <span className="hb-mono" style={{ fontWeight: 600 }}>{fmtClock(elapsedSeconds(running.startedAt, nowMs))}</span>
            <Button variant="secondary" size="sm" icon="stop" onClick={onStop}>{t.time.stop}</Button>
          </>
        ) : (
          <>
            <div style={{ flex: 1, minWidth: 0 }}>
              <div className="hb-row__title">{name}</div>
              <div className="hb-muted" style={{ fontSize: 13 }}>{t.time.partnerIdle}</div>
            </div>
            {projects.length > 0 && (
              <Button variant="soft" size="sm" icon="play" onClick={() => setPicking((v) => !v)}>
                {t.time.startForPartner.replace('{name}', name)}
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

function EntryRow({ entry, project, me, onDelete, onEdit, showProject }: {
  entry: TimeEntry
  project?: Project
  me: string | null
  onDelete: (id: string) => void
  onEdit: (entry: TimeEntry) => void
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
          <>
            <IconButton icon="edit" label={t.common.edit} onClick={() => onEdit(entry)} />
            <IconButton icon="trash" label={t.common.delete} danger onClick={() => onDelete(entry.id)} />
          </>
        ) : (
          <span className="hb-iconbtn" title={t.time.ownEntriesOnly} style={{ cursor: 'default' }}><Icon name="lock" size={16} stroke={2} /></span>
        )}
      </div>
    </div>
  )
}

// Reusable day-grouped entry list. `showProject` toggles whether the project
// name (recent list) or the description (project detail) is the row title.
function DayGroupedList({ entries, projectsById, me, onDelete, onEdit, showProject }: {
  entries: TimeEntry[]
  projectsById: Record<string, Project>
  me: string | null
  onDelete: (id: string) => void
  onEdit: (entry: TimeEntry) => void
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
            <EntryRow key={e.id} entry={e} project={projectsById[e.projectId]} me={me} onDelete={onDelete} onEdit={onEdit} showProject={showProject} />
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

function ProjectDetail({ project, entries, projectsById, me, onDelete, onEdit, onBack }: {
  project: Project
  entries: TimeEntry[]
  projectsById: Record<string, Project>
  me: string | null
  onDelete: (id: string) => void
  onEdit: (entry: TimeEntry) => void
  onBack: () => void
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
    <>
      <div className="hb-detailnav">
        <Button variant="ghost" size="sm" icon="chevronLeft" onClick={onBack}>{t.time.backToOverview}</Button>
      </div>
      <PageHead
        eyebrow={t.time.projectsLabel}
        title={project.name}
      />
      <div className="hb-projhead">
        <span className="hb-pdot" style={{ background: project.color, width: 16, height: 16 }} />
        {project.archived && <span className="hb-muted">{t.time.archivedSection}</span>}
      </div>

      <Card className="hb-card--pad hb-detailpage">
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
            <DayGroupedList entries={projEntries} projectsById={projectsById} me={me} onDelete={onDelete} onEdit={onEdit} showProject={false} />
          </>
        )}
      </Card>
    </>
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
      setError(t.errors.INVALID_DATE)
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
        setError(t.time.startInFuture)
        return
      }
    } else {
      const stoppedAt = new Date(stop)
      if (!(stoppedAt.getTime() > startedAt.getTime())) {
        setError(t.time.endAfterStart)
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
      setError(t.time.saveFailed)
    }
  }

  return (
    <Modal
      open
      onClose={onClose}
      title={running ? t.time.editRunning : t.time.editEntry}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>{t.common.cancel}</Button>
          <Button onClick={submit} disabled={!start || !projectId || (!running && !stop)}>{t.common.save}</Button>
        </>
      }
    >
      {running && <p className="hb-muted" style={{ marginTop: 0 }}>{t.time.editRunningHint}</p>}
      <Field label={t.time.project}>
        <Select value={projectId} onChange={setProjectId}>
          {projectOptions.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
        </Select>
      </Field>
      <Field label={t.time.startLabel}>
        <TextInput type="datetime-local" value={start} onChange={setStart} />
      </Field>
      {!running && (
        <Field label={t.time.endLabel}>
          <TextInput type="datetime-local" value={stop} onChange={setStop} />
        </Field>
      )}
      {!running && (
        <Field label={t.common.descriptionOptional}>
          <TextInput value={description} onChange={setDescription} placeholder={t.common.descriptionOptional} />
        </Field>
      )}
      {error && <p style={{ color: 'oklch(0.55 0.16 32)', fontSize: 13.5, margin: 0 }}>{error}</p>}
    </Modal>
  )
}

// CSV export with optional date-range and project filters. All fields are optional;
// an empty form exports every completed entry. Includes archived projects so their
// history can still be exported.
function ExportModal({ projects, onExport, onClose }: {
  projects: Project[]
  onExport: (opts: { from?: string; to?: string; projectId?: string }) => void
  onClose: () => void
}) {
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')
  const [projectId, setProjectId] = useState('')

  return (
    <Modal
      open
      onClose={onClose}
      title={t.time.exportTitle}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>{t.common.cancel}</Button>
          <Button icon="download" onClick={() => onExport({ from: from || undefined, to: to || undefined, projectId: projectId || undefined })}>
            {t.time.exportSubmit}
          </Button>
        </>
      }
    >
      <p className="hb-muted" style={{ marginTop: 0 }}>{t.time.exportHint}</p>
      <div className="hb-formgrid">
        <Field label={t.time.from}><TextInput type="date" value={from} onChange={setFrom} /></Field>
        <Field label={t.time.to}><TextInput type="date" value={to} onChange={setTo} /></Field>
      </div>
      <Field label={t.time.project}>
        <Select value={projectId} onChange={setProjectId}>
          <option value="">{t.time.exportAllProjects}</option>
          {projects.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
        </Select>
      </Field>
    </Modal>
  )
}
