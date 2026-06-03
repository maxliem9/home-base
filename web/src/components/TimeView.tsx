import { useState, useEffect, useCallback, useMemo, useRef } from 'react'
import { API_BASE, authFetch, withWsToken } from '../api'
import { t } from '../i18n'
import { Project, TimeEntry } from '../types'
import { useWebSocket } from '../hooks/useWebSocket'
import { Icon } from '../ui/Icon'
import { Avatar, Button, Card, EmptyState, Field, IconButton, Modal, PageHead, Select, TextInput } from '../ui/primitives'
import { clockTime, fmtClock, fmtDurationShort, usernameFromToken } from '../ui/format'

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
  const [desc, setDesc] = useState('')

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

  const startTimer = async (projectId: string, description = '') => {
    await authFetch(token, `${API_BASE}/time/entries/start`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ projectId, description: description.trim() || undefined }),
    })
  }

  const stopTimer = async () => {
    await authFetch(token, `${API_BASE}/time/entries/stop`, { method: 'POST' })
  }

  const saveDescription = async () => {
    if (!running || desc === (running.description ?? '')) return
    await authFetch(token, `${API_BASE}/time/entries/${running.id}`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ description: desc }),
    })
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

  const createManual = async (body: object) => {
    await authFetch(token, `${API_BASE}/time/entries`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    })
    setShowManual(false)
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
                        <span className="hb-projcard__name">{p.name}</span>
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
                      <div className="hb-projcard__stat hb-mono">{fmtDurationShort(totalsByProject[p.id] ?? 0)}</div>
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
                <div className="hb-list">
                  {recent.map((e) => {
                    const p = projectsById[e.projectId]
                    const own = !me || e.userId === me
                    return (
                      <div key={e.id} className="hb-entry">
                        <span className="hb-pdot" style={{ background: p?.color ?? 'var(--ink-3)' }} />
                        <div className="hb-row__main">
                          <div className="hb-row__title">{p?.name ?? t.time.project}</div>
                          <div className="hb-row__meta">
                            <span className="hb-mono">{clockTime(e.startedAt)}–{e.stoppedAt ? clockTime(e.stoppedAt) : ''}</span>
                            {e.description && <><span className="dot-sep" />{e.description}</>}
                          </div>
                        </div>
                        <div className="hb-row__right">
                          <Avatar user={e.userId} size={22} />
                          <span className="hb-mono hb-muted">{fmtDurationShort(e.durationSeconds ?? 0)}</span>
                          {own ? (
                            <IconButton icon="trash" label={t.common.delete} danger onClick={() => deleteEntry(e.id)} />
                          ) : (
                            <span className="hb-iconbtn" title={t.time.ownEntriesOnly} style={{ cursor: 'default' }}><Icon name="lock" size={16} stroke={2} /></span>
                          )}
                        </div>
                      </div>
                    )
                  })}
                </div>
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
    </div>
  )
}

function ManualEntryModal({ projects, onCreate, onClose }: {
  projects: Project[]
  onCreate: (body: object) => void
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

  const submit = () => {
    if (!projectId || submitRef.current) return
    const startedAt = new Date(`${date}T${start}`)
    const stoppedAt = new Date(`${date}T${end}`)
    if (!(stoppedAt.getTime() > startedAt.getTime())) {
      setError(t.time.endAfterStart)
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
