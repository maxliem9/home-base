// Einstellungen → Zeiterfassung (#99). Self-contained settings subpage that owns
// project management (create/rename/colour/archive) and the Wochensoll editor —
// both moved out of the TimeView tracker, which keeps only a lightweight project
// create for the start-a-timer flow. Reads/writes the same endpoints as TimeView
// and follows the same per-view "fetch its own data + subscribe to its own WS
// channel" convention; the small write handlers are duplicated deliberately
// (idiomatic here) rather than lifted into a shared hook.
import { Fragment, useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { API_BASE, errorCode, safeFetch } from '../../api'
import { t, errorText } from '../../i18n'
import { Project, User, WorkTarget } from '../../types'
import { useWebSocket } from '../../hooks/useWebSocket'
import { Icon } from '../../ui/Icon'
import { Avatar, Button, Card, EmptyState, IconButton, Modal } from '../../ui/primitives'
import { userMeta } from '../../ui/format'
import { COLOR_CHOICES, ProjectDraft, ProjectModal } from '../TimeView'

const WS_SCHEME = window.location.protocol === 'https:' ? 'wss' : 'ws'
const WS_URL = import.meta.env.VITE_WS_URL_TIME ?? `${WS_SCHEME}://${window.location.host}/api/v1/ws/time`

export function TimeSettings({ token, onLogout }: { token: string; onLogout: () => void }) {
  const [projects, setProjects] = useState<Project[]>([])
  const [targets, setTargets] = useState<WorkTarget[]>([])
  const [users, setUsers] = useState<string[]>([])
  const [loading, setLoading] = useState(true)
  const [projectDraft, setProjectDraft] = useState<ProjectDraft | null>(null)
  const [showTargets, setShowTargets] = useState(false)
  const [showArchived, setShowArchived] = useState(false)
  const [toast, setToast] = useState<string | null>(null)

  const flashError = useCallback((msg: string) => {
    setToast(msg)
    setTimeout(() => setToast(null), 3500)
  }, [])

  const upsertProject = useCallback((p: Project) => {
    setProjects((prev) => (prev.some((x) => x.id === p.id) ? prev.map((x) => (x.id === p.id ? p : x)) : [...prev, p]))
  }, [])

  const fetchTargets = useCallback(async () => {
    const result = await safeFetch(token, `${API_BASE}/time/targets`)
    if (!result.ok) return
    if (result.res.status === 401) return onLogout()
    if (result.res.ok) setTargets(await result.res.json())
  }, [onLogout, token])

  const fetchAll = useCallback(async () => {
    try {
      const [pResult, uResult] = await Promise.all([
        safeFetch(token, `${API_BASE}/time/projects`),
        safeFetch(token, `${API_BASE}/users`),
        fetchTargets(),
      ])
      if (!pResult.ok) return
      if (pResult.res.status === 401) return onLogout()
      if (pResult.res.ok) setProjects(await pResult.res.json())
      // users is non-critical (only labels the Wochensoll rows); ignore its failure quietly
      if (uResult.ok && uResult.res.ok) setUsers((await uResult.res.json()).map((u: User) => u.username))
    } finally {
      setLoading(false)
    }
  }, [fetchTargets, onLogout, token])

  useEffect(() => { fetchAll() }, [fetchAll])

  useWebSocket({ url: WS_URL, token }, (raw) => {
    try {
      const msg = JSON.parse(raw)
      if (msg.project) {
        const p: Project = msg.project
        if (msg.type === 'PROJECT_CREATED') setProjects((prev) => (prev.some((x) => x.id === p.id) ? prev : [...prev, p]))
        else if (msg.type === 'PROJECT_UPDATED') setProjects((prev) => prev.map((x) => (x.id === p.id ? p : x)))
      } else if (msg.type === 'TARGET_UPDATED') {
        fetchTargets()
      }
    } catch {
      // ignore malformed frames
    }
  })

  // Create/rename/recolour — same endpoints/convention as TimeView.saveProject.
  const saveProject = async (d: ProjectDraft) => {
    if (!d.name.trim()) return
    const body = JSON.stringify({ name: d.name.trim(), color: d.color })
    const result = d.id
      ? await safeFetch(token, `${API_BASE}/time/projects/${d.id}`, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body })
      : await safeFetch(token, `${API_BASE}/time/projects`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body })
    if (!result.ok) return flashError(errorText(null, t.time.saveFailed))
    if (result.res.status === 401) return onLogout()
    if (!result.res.ok) return flashError(errorText(await errorCode(result.res), t.time.saveFailed))
    upsertProject(await result.res.json())
    setProjectDraft(null)
  }

  const setArchived = async (p: Project, archived: boolean) => {
    const result = await safeFetch(token, `${API_BASE}/time/projects/${p.id}/archive`, {
      method: 'PATCH', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ archived }),
    })
    if (!result.ok) return flashError(errorText(null, t.time.archiveFailed))
    if (result.res.status === 401) return onLogout()
    if (!result.res.ok) return flashError(errorText(await errorCode(result.res), t.time.archiveFailed))
    upsertProject(await result.res.json())
  }

  // One PUT per changed person×project cell; inline-error convention as in TimeView.
  const saveTargets = async (puts: { userId: string; projectId: string; body: object }[]): Promise<string | null> => {
    for (const { userId, projectId, body } of puts) {
      const result = await safeFetch(token, `${API_BASE}/time/targets/${encodeURIComponent(userId)}/${projectId}`, {
        method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
      })
      if (!result.ok) return errorText(null, t.time.targetsFailed)
      if (result.res.status === 401) { onLogout(); return null }
      if (!result.res.ok) return errorText(await errorCode(result.res), t.time.targetsFailed)
    }
    await fetchTargets()
    setShowTargets(false)
    return null
  }

  const activeProjects = projects.filter((p) => !p.archived)
  const archivedProjects = projects.filter((p) => p.archived)
  const shownProjects = showArchived ? projects : activeProjects

  // Projects offered in the targets editor: the active ones plus archived projects
  // that still carry a target (so an archived project's Wochensoll stays clearable).
  const targetProjects = useMemo(
    () => projects.filter((p) => !p.archived || targets.some((x) => x.projectId === p.id && (x.weeklyHours > 0 || x.isDefault))),
    [projects, targets],
  )

  return (
    <div className="hb-stack" style={{ gap: 'var(--gap)' }}>
      {/* Projekt-Verwaltung */}
      <Card className="hb-card--pad">
        <div className="hb-cardhead">
          <div>
            <h3>{t.settings.projectsTitle}</h3>
            <p className="hb-muted" style={{ margin: '2px 0 0' }}>{t.settings.projectsHint}</p>
          </div>
          <Button size="sm" icon="plus" onClick={() => setProjectDraft({ name: '', color: COLOR_CHOICES[0] })}>{t.time.newProject}</Button>
        </div>
        {loading ? (
          <p className="hb-muted" style={{ marginBottom: 0 }}>{t.common.loading}</p>
        ) : shownProjects.length === 0 ? (
          <EmptyState icon="clock" title={t.time.noProjects} hint={t.time.noProjectsHint} />
        ) : (
          <div className="hb-list" style={{ marginTop: 8 }}>
            {shownProjects.map((p) => (
              <div key={p.id} className={`hb-row${p.archived ? ' is-archived' : ''}`}>
                <span className="hb-pdot" style={{ background: p.color }} />
                <div className="hb-row__main">
                  <div className="hb-row__title">
                    {p.name}
                    {p.archived && <span className="hb-muted"> · {t.time.archivedSection}</span>}
                  </div>
                </div>
                <div className="hb-row__right">
                  <IconButton icon="edit" label={t.common.edit} onClick={() => setProjectDraft({ id: p.id, name: p.name, color: p.color })} />
                  <IconButton
                    icon="archive"
                    label={p.archived ? t.time.reactivate : t.time.archive}
                    active={p.archived}
                    onClick={() => setArchived(p, !p.archived)}
                  />
                </div>
              </div>
            ))}
          </div>
        )}
        {archivedProjects.length > 0 && (
          <button className="hb-link" style={{ marginTop: 12 }} onClick={() => setShowArchived((v) => !v)}>
            {showArchived ? t.time.hideArchived : t.time.showArchived}
          </button>
        )}
      </Card>

      {/* Wochensoll */}
      <Card className="hb-card--pad">
        <div className="hb-cardhead">
          <div>
            <h3>{t.time.weekTargetTitle}</h3>
            <p className="hb-muted" style={{ margin: '2px 0 0' }}>{t.time.targetsModalHint}</p>
          </div>
          <Button
            size="sm"
            variant="secondary"
            icon="settings"
            onClick={() => setShowTargets(true)}
            disabled={loading || projects.length === 0}
          >
            {t.settings.wochensollEdit}
          </Button>
        </div>
        <TargetsSummary users={users} projects={projects} targets={targets} />
      </Card>

      {projectDraft && (
        <ProjectModal draft={projectDraft} onChange={setProjectDraft} onSave={saveProject} onClose={() => setProjectDraft(null)} />
      )}
      {showTargets && (
        <TargetsModal users={users} projects={targetProjects} targets={targets} onSave={saveTargets} onClose={() => setShowTargets(false)} />
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

// Read-only summary of the configured Wochensoll, so the settings card shows the
// current state at a glance (the editor opens via the card's button).
function TargetsSummary({ users, projects, targets }: { users: string[]; projects: Project[]; targets: WorkTarget[] }) {
  const groups = users
    .map((u) => ({ u, rows: targets.filter((x) => x.userId === u && (x.weeklyHours > 0 || x.isDefault)) }))
    .filter((g) => g.rows.length > 0)
  if (groups.length === 0) return <p className="hb-muted" style={{ margin: '12px 0 0' }}>{t.settings.wochensollEmpty}</p>
  const proj = (id: string) => projects.find((p) => p.id === id)
  return (
    <div className="hb-stack" style={{ gap: 14, marginTop: 14 }}>
      {groups.map((g) => (
        <div key={g.u}>
          <div className="hb-sectionlabel" style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <Avatar user={g.u} size={20} /> {userMeta(g.u)?.name ?? g.u}
          </div>
          <div className="hb-list" style={{ marginTop: 4 }}>
            {g.rows.map((r) => (
              <div key={r.projectId} className="hb-row">
                <span className="hb-pdot" style={{ background: proj(r.projectId)?.color ?? 'var(--ink-3)' }} />
                <div className="hb-row__main">
                  <div className="hb-row__title">
                    {proj(r.projectId)?.name ?? t.time.project}
                    {r.isDefault && <span className="hb-muted"> · {t.settings.defaultBadge}</span>}
                  </div>
                </div>
                <div className="hb-row__right">
                  <span className="hb-mono">{r.weeklyHours > 0 ? `${String(r.weeklyHours).replace('.', ',')} ${t.settings.perWeek}` : '—'}</span>
                </div>
              </div>
            ))}
          </div>
        </div>
      ))}
    </div>
  )
}

// Wochensoll configuration (#31): weekly hours per person × project plus the
// person's default project (absence/holiday credits are booked there). Saving
// PUTs only the changed cells; the household may edit either person (like the
// absence planner, #127). Inline-error convention as in the other modals.
// Moved here from TimeView with #99 — configuration now lives in the settings hub.
function TargetsModal({ users, projects, targets, onSave, onClose }: {
  users: string[]
  projects: Project[]
  targets: WorkTarget[]
  onSave: (puts: { userId: string; projectId: string; body: object }[]) => Promise<string | null>
  onClose: () => void
}) {
  const targetFor = (u: string, p: string) => targets.find((x) => x.userId === u && x.projectId === p)
  const defaultFor = (u: string) => targets.find((x) => x.userId === u && x.isDefault)?.projectId ?? ''
  const [draft, setDraft] = useState<Record<string, { hours: Record<string, string>; def: string }>>(() =>
    Object.fromEntries(users.map((u) => [u, {
      hours: Object.fromEntries(projects.map((p) => {
        const h = targetFor(u, p.id)?.weeklyHours ?? 0
        return [p.id, h > 0 ? String(h).replace('.', ',') : '']
      })),
      def: defaultFor(u),
    }])),
  )
  const [error, setError] = useState<string | null>(null)
  const submitRef = useRef(false)

  // Hours > 0 require a default project (#59) — entering the first hours for a
  // person without one auto-selects that project (mirrors the backend's behavior).
  const setHours = (u: string, p: string, v: string) =>
    setDraft((d) => ({
      ...d,
      [u]: {
        def: d[u].def === '' && Number(v.trim().replace(',', '.')) > 0 ? p : d[u].def,
        hours: { ...d[u].hours, [p]: v },
      },
    }))
  const setDef = (u: string, p: string) =>
    setDraft((d) => ({ ...d, [u]: { ...d[u], def: p } }))

  const submit = async () => {
    if (submitRef.current) return
    const puts: { userId: string; projectId: string; body: object }[] = []
    for (const u of users) {
      let sumHours = 0
      for (const p of projects) {
        const raw = (draft[u].hours[p.id] ?? '').trim()
        const hours = raw === '' ? 0 : Number(raw.replace(',', '.'))
        if (!Number.isFinite(hours) || hours < 0 || hours > 168) {
          setError(t.time.invalidHours)
          return
        }
        sumHours += hours
        const body: { weeklyHours?: number; isDefault?: boolean } = {}
        if (hours !== (targetFor(u, p.id)?.weeklyHours ?? 0)) body.weeklyHours = hours
        const defBefore = defaultFor(u)
        // setting the new default clears the old one server-side
        if (draft[u].def !== defBefore && draft[u].def === p.id) body.isDefault = true
        if (Object.keys(body).length > 0) puts.push({ userId: u, projectId: p.id, body })
      }
      // hours > 0 ⇒ a default project must be chosen (#59); auto-select normally
      // covers this — backstop for legacy data without a default
      if (sumHours > 0 && draft[u].def === '') {
        setError(t.time.defaultRequired)
        return
      }
    }
    if (puts.length === 0) return onClose()
    submitRef.current = true
    setError(null)
    try {
      const err = await onSave(puts)
      if (err) {
        submitRef.current = false
        setError(err)
      }
    } catch {
      submitRef.current = false
      setError(t.time.targetsFailed)
    }
  }

  return (
    <Modal
      open
      onClose={onClose}
      title={t.time.targetsModalTitle}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>{t.common.cancel}</Button>
          <Button onClick={submit}>{t.common.save}</Button>
        </>
      }
    >
      <p className="hb-muted" style={{ marginTop: 0 }}>{t.time.targetsModalHint}</p>
      {projects.length === 0 ? (
        <p className="hb-muted">{t.time.noProjectsHint}</p>
      ) : (
        users.map((u) => (
          <div key={u} style={{ marginBottom: 18 }}>
            <div className="hb-sectionlabel" style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
              <Avatar user={u} size={20} /> {userMeta(u)?.name ?? u}
            </div>
            <div className="hb-targetgrid">
              <span className="hb-muted hb-targetgrid__h">{t.time.project}</span>
              <span className="hb-muted hb-targetgrid__h">{t.time.hoursPerWeek}</span>
              <span className="hb-muted hb-targetgrid__h">{t.time.defaultColumn}</span>
              {projects.map((p) => (
                <Fragment key={p.id}>
                  <span style={{ display: 'flex', alignItems: 'center', gap: 7, minWidth: 0 }}>
                    <span className="hb-pdot" style={{ background: p.color }} />
                    <span style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                      {p.name}{p.archived && <span className="hb-muted"> ({t.time.archivedSection})</span>}
                    </span>
                  </span>
                  <input
                    className="hb-input"
                    inputMode="decimal"
                    value={draft[u].hours[p.id] ?? ''}
                    onChange={(e) => setHours(u, p.id, e.target.value)}
                    placeholder="0"
                    aria-label={`${t.time.hoursPerWeek} ${p.name} ${userMeta(u)?.name ?? u}`}
                  />
                  <input
                    type="radio"
                    name={`hb-default-${u}`}
                    checked={draft[u].def === p.id}
                    onChange={() => setDef(u, p.id)}
                    aria-label={`${t.time.defaultColumn} ${p.name} ${userMeta(u)?.name ?? u}`}
                  />
                </Fragment>
              ))}
            </div>
          </div>
        ))
      )}
      {error && <p style={{ color: 'oklch(0.55 0.16 32)', fontSize: 13.5, margin: 0 }}>{error}</p>}
    </Modal>
  )
}
