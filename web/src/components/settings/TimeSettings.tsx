// Einstellungen → Zeiterfassung (#99). Self-contained settings subpage that owns
// project management (create/rename/colour/archive) and the Wochensoll editor —
// both moved out of the TimeView tracker, which keeps only a lightweight project
// create for the start-a-timer flow. Reads/writes the same endpoints as TimeView
// and follows the same per-view "fetch its own data + subscribe to its own WS
// channel" convention; the small write handlers are duplicated deliberately
// (idiomatic here) rather than lifted into a shared hook.
import { Fragment, useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { API_BASE, errorCode, notifyTransportError, safeFetch } from '../../api'
import { errorText } from '../../i18n'
import { BASE_TARGET_PERIOD, Project, User, WorkTarget } from '../../types'
import { useWebSocket } from '../../hooks/useWebSocket'
import { Icon } from '../../ui/Icon'
import { Avatar, Button, Card, ConfirmDialog, EmptyState, Field, IconButton, Modal, PageHead, Select, TextInput } from '../../ui/primitives'
import { formatDecimal, parseLocaleNumber, userMeta } from '../../ui/format'
import { COLOR_CHOICES, ProjectDraft, ProjectModal } from '../TimeView'

const WS_SCHEME = window.location.protocol === 'https:' ? 'wss' : 'ws'
const WS_URL = import.meta.env.VITE_WS_URL_TIME ?? `${WS_SCHEME}://${window.location.host}/api/v1/ws/time`

export function TimeSettings({ token, onLogout }: { token: string; onLogout: () => void }) {
  const { t } = useTranslation()
  const [projects, setProjects] = useState<Project[]>([])
  const [targets, setTargets] = useState<WorkTarget[]>([])
  const [users, setUsers] = useState<string[]>([])
  const [loading, setLoading] = useState(true)
  const [projectDraft, setProjectDraft] = useState<ProjectDraft | null>(null)
  // The Wochensoll editor is its own full page (a per-person×project table that grows
  // with the project count), not a modal — like the project detail (#32) and the
  // Abwesenheits-Einstellungen (#128/#29). 'overview' = the settings cards.
  const [view, setView] = useState<'overview' | 'targets'>('overview')
  const [showArchived, setShowArchived] = useState(false)
  const [showExport, setShowExport] = useState(false)
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
    if (!result.ok) return flashError(errorText(null, t('time.saveFailed')))
    if (result.res.status === 401) return onLogout()
    if (!result.res.ok) return flashError(errorText(await errorCode(result.res), t('time.saveFailed')))
    upsertProject(await result.res.json())
    setProjectDraft(null)
  }

  const setArchived = async (p: Project, archived: boolean) => {
    const result = await safeFetch(token, `${API_BASE}/time/projects/${p.id}/archive`, {
      method: 'PATCH', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ archived }),
    })
    if (!result.ok) return flashError(errorText(null, t('time.archiveFailed')))
    if (result.res.status === 401) return onLogout()
    if (!result.res.ok) return flashError(errorText(await errorCode(result.res), t('time.archiveFailed')))
    upsertProject(await result.res.json())
  }

  // One PUT per changed person×project cell; inline-error convention as in TimeView.
  // Each body may carry a `validFrom` so the edit lands in the right Wochensoll period.
  const saveTargets = async (puts: { userId: string; projectId: string; body: object }[]): Promise<string | null> => {
    for (const { userId, projectId, body } of puts) {
      const result = await safeFetch(token, `${API_BASE}/time/targets/${encodeURIComponent(userId)}/${projectId}`, {
        method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
      })
      if (!result.ok) return errorText(null, t('time.targetsFailed'))
      if (result.res.status === 401) { onLogout(); return null }
      if (!result.res.ok) return errorText(await errorCode(result.res), t('time.targetsFailed'))
    }
    await fetchTargets()
    return null
  }

  // A Wochensoll period is household-wide in the UI: creating/deleting one loops over
  // every person so their targets stay aligned (the backend stores per-person rows).
  // Each new period is seeded server-side from the values in force on `validFrom`.
  const createPeriod = async (validFrom: string): Promise<string | null> => {
    for (const userId of users) {
      const result = await safeFetch(token, `${API_BASE}/time/targets/${encodeURIComponent(userId)}/periods`, {
        method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ validFrom }),
      })
      if (!result.ok) return errorText(null, t('time.targetsFailed'))
      if (result.res.status === 401) { onLogout(); return null }
      // 409 = this person already has the period, or has no base target to seed it from
      // (NO_SEED_SOURCE). Either way there is nothing to create for them — ignore, keep going.
      if (!result.res.ok && result.res.status !== 409) return errorText(await errorCode(result.res), t('time.targetsFailed'))
    }
    await fetchTargets()
    return null
  }

  const deletePeriod = async (validFrom: string): Promise<string | null> => {
    for (const userId of users) {
      const result = await safeFetch(token, `${API_BASE}/time/targets/${encodeURIComponent(userId)}/periods/${validFrom}`, {
        method: 'DELETE',
      })
      if (!result.ok) return errorText(null, t('time.targetsFailed'))
      if (result.res.status === 401) { onLogout(); return null }
      // 404 = this person had no such period — fine when only one person configured it.
      if (!result.res.ok && result.res.status !== 404) return errorText(await errorCode(result.res), t('time.targetsFailed'))
    }
    await fetchTargets()
    return null
  }

  // CSV-Export: server-rendered CSV with the JWT in the header (token out of
  // the URL), downloaded from the returned blob. Moved here from the tracker (#99).
  const exportCsv = async ({ from, to, projectId }: { from?: string; to?: string; projectId?: string }) => {
    const params = new URLSearchParams()
    if (from) params.set('from', new Date(`${from}T00:00:00`).toISOString())
    if (to) params.set('to', new Date(`${to}T23:59:59.999`).toISOString())
    if (projectId) params.set('project_id', projectId)
    const qs = params.toString()
    const result = await safeFetch(token, `${API_BASE}/time/export.csv${qs ? `?${qs}` : ''}`)
    if (!result.ok) {
      notifyTransportError()
      return
    }
    if (result.res.status === 401) return onLogout()
    if (!result.res.ok) return
    const blob = await result.res.blob()
    const filename = result.res.headers.get('Content-Disposition')?.match(/filename="?([^"]+)"?/)?.[1] ?? 'zeiterfassung.csv'
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

  const activeProjects = projects.filter((p) => !p.archived)
  const archivedProjects = projects.filter((p) => p.archived)
  const shownProjects = showArchived ? projects : activeProjects

  // Projects offered in the targets editor: the active ones plus archived projects
  // that still carry a target (so an archived project's Wochensoll stays clearable).
  const targetProjects = useMemo(
    () => projects.filter((p) => !p.archived || targets.some((x) => x.projectId === p.id && (x.weeklyHours > 0 || x.isDefault))),
    [projects, targets],
  )

  // Wochensoll editor as its own full page (back nav + PageHead), not a modal (#128).
  if (view === 'targets') {
    return (
      <TargetsPage
        users={users}
        projects={targetProjects}
        targets={targets}
        onSave={saveTargets}
        onCreatePeriod={createPeriod}
        onDeletePeriod={deletePeriod}
        onBack={() => setView('overview')}
      />
    )
  }

  return (
    <div className="hb-stack" style={{ gap: 'var(--gap)' }}>
      {/* Projekt-Verwaltung */}
      <Card className="hb-card--pad">
        <div className="hb-cardhead">
          <div>
            <h3>{t('settings.projectsTitle')}</h3>
            <p className="hb-muted" style={{ margin: '2px 0 0' }}>{t('settings.projectsHint')}</p>
          </div>
          <Button size="sm" icon="plus" onClick={() => setProjectDraft({ name: '', color: COLOR_CHOICES[0] })}>{t('time.newProject')}</Button>
        </div>
        {loading ? (
          <p className="hb-muted" style={{ marginBottom: 0 }}>{t('common.loading')}</p>
        ) : shownProjects.length === 0 ? (
          <EmptyState icon="clock" title={t('time.noProjects')} hint={t('time.noProjectsHint')} />
        ) : (
          <div className="hb-list" style={{ marginTop: 8 }}>
            {shownProjects.map((p) => (
              <div key={p.id} className={`hb-row${p.archived ? ' is-archived' : ''}`}>
                <span className="hb-pdot" style={{ background: p.color }} />
                <div className="hb-row__main">
                  <div className="hb-row__title">
                    {p.name}
                    {p.archived && <span className="hb-muted"> · {t('time.archivedSection')}</span>}
                  </div>
                </div>
                <div className="hb-row__right">
                  <IconButton icon="edit" label={t('common.edit')} onClick={() => setProjectDraft({ id: p.id, name: p.name, color: p.color })} />
                  <IconButton
                    icon="archive"
                    label={p.archived ? t('time.reactivate') : t('time.archive')}
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
            {showArchived ? t('time.hideArchived') : t('time.showArchived')}
          </button>
        )}
      </Card>

      {/* Wochensoll */}
      <Card className="hb-card--pad">
        <div className="hb-cardhead">
          <div>
            <h3>{t('time.weekTargetTitle')}</h3>
            <p className="hb-muted" style={{ margin: '2px 0 0' }}>{t('time.targetsModalHint')}</p>
          </div>
          <Button
            size="sm"
            variant="secondary"
            icon="settings"
            onClick={() => setView('targets')}
            disabled={loading || projects.length === 0}
          >
            {t('settings.wochensollEdit')}
          </Button>
        </div>
        <TargetsSummary users={users} projects={projects} targets={targets} />
      </Card>

      {/* CSV-Export — completed entries, optionally filtered by date range/project. */}
      <Card className="hb-card--pad">
        <div className="hb-cardhead">
          <div>
            <h3>{t('time.exportCsv')}</h3>
            <p className="hb-muted" style={{ margin: '2px 0 0' }}>{t('time.exportHint')}</p>
          </div>
          <Button size="sm" variant="secondary" icon="download" onClick={() => setShowExport(true)} disabled={loading}>{t('settings.exportOpen')}</Button>
        </div>
      </Card>

      {projectDraft && (
        <ProjectModal draft={projectDraft} onChange={setProjectDraft} onSave={saveProject} onClose={() => setProjectDraft(null)} />
      )}
      {showExport && (
        <ExportModal projects={projects} onExport={exportCsv} onClose={() => setShowExport(false)} />
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

// --- Wochensoll periods (#31 follow-up) ------------------------------------
// A target's period start; the API omits validFrom for the base period.
const periodOf = (x: WorkTarget) => x.validFrom ?? BASE_TARGET_PERIOD

// Distinct period start dates across the given targets, always incl. the base, sorted
// ascending. ISO YYYY-MM-DD strings sort chronologically, so plain string order works.
function periodsOf(targets: WorkTarget[]): string[] {
  const set = new Set<string>([BASE_TARGET_PERIOD])
  targets.forEach((x) => set.add(periodOf(x)))
  return [...set].sort()
}

// The period in force on `onDate` (ISO): the latest start on/before it, else the base.
function effectivePeriod(periods: string[], onDate: string): string {
  const applicable = periods.filter((p) => p <= onDate)
  return applicable.length ? applicable[applicable.length - 1] : BASE_TARGET_PERIOD
}

// Local today as YYYY-MM-DD (matches the server's start-date attribution well enough
// for the "which period is current" hint — the backend stays the source of truth).
function todayIso(): string {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

// ISO date → locale date, parsed as a local (not UTC) day to avoid an off-by-one.
function formatPeriodDate(iso: string): string {
  const [y, m, d] = iso.split('-').map(Number)
  return new Date(y, m - 1, d).toLocaleDateString()
}

// Read-only summary of the configured Wochensoll, so the settings card shows the
// current state at a glance (the editor opens via the card's button). Shows each
// person's currently-effective period and notes any changes scheduled for later.
function TargetsSummary({ users, projects, targets }: { users: string[]; projects: Project[]; targets: WorkTarget[] }) {
  const { t } = useTranslation()
  const today = todayIso()
  const groups = users
    .map((u) => {
      const mine = targets.filter((x) => x.userId === u)
      const eff = effectivePeriod(periodsOf(mine), today)
      const rows = mine.filter((x) => periodOf(x) === eff && (x.weeklyHours > 0 || x.isDefault))
      const future = periodsOf(mine)
        .filter((p) => p > today)
        .map((p) => ({ p, hours: mine.filter((x) => periodOf(x) === p).reduce((s, x) => s + x.weeklyHours, 0) }))
      return { u, rows, future }
    })
    .filter((g) => g.rows.length > 0 || g.future.length > 0)
  if (groups.length === 0) return <p className="hb-muted" style={{ margin: '12px 0 0' }}>{t('settings.wochensollEmpty')}</p>
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
                    {proj(r.projectId)?.name ?? t('time.project')}
                    {r.isDefault && <span className="hb-muted"> · {t('settings.defaultBadge')}</span>}
                  </div>
                </div>
                <div className="hb-row__right">
                  <span className="hb-mono">{r.weeklyHours > 0 ? `${formatDecimal(r.weeklyHours)} ${t('settings.perWeek')}` : '—'}</span>
                </div>
              </div>
            ))}
          </div>
          {g.future.map((f) => (
            <p key={f.p} className="hb-muted" style={{ margin: '4px 0 0', fontSize: 13 }}>
              {t('time.periodScheduled', { date: formatPeriodDate(f.p), hours: formatDecimal(f.hours) })}
            </p>
          ))}
        </div>
      ))}
    </div>
  )
}

// Wochensoll configuration (#31): weekly hours per person × project plus the
// person's default project (absence/holiday credits are booked there). Saving
// PUTs only the changed cells; the household may edit either person (like the
// absence planner). Inline-error convention as in the other editors.
// Moved here from TimeView with #99 — configuration now lives in the settings hub.
// Its own full page rather than a modal (#128/#29): the per-person×project table
// grows with the project count and scrolls badly when boxed into a dialog.
//
// The hours are effective-dated (#31 follow-up): the page edits one period at a time,
// picked from the selector; a new period is seeded server-side from the values in force
// then, so past weeks keep the value that was valid then (e.g. 40h until August, 32h from
// September). Periods are household-wide in the UI (see createPeriod/deletePeriod above).
function TargetsPage({ users, projects, targets, onSave, onCreatePeriod, onDeletePeriod, onBack }: {
  users: string[]
  projects: Project[]
  targets: WorkTarget[]
  onSave: (puts: { userId: string; projectId: string; body: object }[]) => Promise<string | null>
  onCreatePeriod: (validFrom: string) => Promise<string | null>
  onDeletePeriod: (validFrom: string) => Promise<string | null>
  onBack: () => void
}) {
  const { t } = useTranslation()
  const periods = useMemo(() => periodsOf(targets), [targets])
  const [selected, setSelected] = useState(() => effectivePeriod(periods, todayIso()))
  const [showAdd, setShowAdd] = useState(false)
  const [confirmDelete, setConfirmDelete] = useState(false)
  const [error, setError] = useState<string | null>(null)

  // After a create/delete refetch changes the period set, keep the selection valid.
  useEffect(() => {
    if (!periods.includes(selected)) setSelected(effectivePeriod(periods, todayIso()))
  }, [periods, selected])

  const periodLabel = (p: string) =>
    p === BASE_TARGET_PERIOD ? t('time.periodBase') : t('time.periodFrom', { date: formatPeriodDate(p) })

  const addPeriod = async (validFrom: string) => {
    setError(null)
    const err = await onCreatePeriod(validFrom)
    if (err) return setError(err)
    setShowAdd(false)
    setSelected(validFrom)
  }
  const removePeriod = async () => {
    if (selected === BASE_TARGET_PERIOD) return
    setError(null)
    const gone = selected
    const err = await onDeletePeriod(gone)
    if (err) return setError(err)
    setSelected(effectivePeriod(periods.filter((p) => p !== gone), todayIso()))
  }

  return (
    <div className="hb-stack" style={{ gap: 'var(--gap)' }}>
      <div className="hb-detailnav">
        <Button variant="ghost" size="sm" icon="chevronLeft" onClick={onBack}>{t('time.backToOverview')}</Button>
      </div>
      <PageHead eyebrow={t('settings.time')} title={t('time.targetsModalTitle')} />
      <Card className="hb-card--pad">
        <p className="hb-muted" style={{ marginTop: 0 }}>{t('time.targetsModalHint')}</p>

        {/* Period selector — which validity period the grid below edits. */}
        <Field label={t('time.periodLabel')}>
          <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', alignItems: 'center' }}>
            <div style={{ minWidth: 200, flex: '1 1 200px' }}>
              <Select value={selected} onChange={setSelected}>
                {periods.map((p) => <option key={p} value={p}>{periodLabel(p)}</option>)}
              </Select>
            </div>
            <Button size="sm" variant="secondary" icon="plus" onClick={() => setShowAdd(true)}>{t('time.periodAdd')}</Button>
            {selected !== BASE_TARGET_PERIOD && (
              <Button size="sm" variant="ghost" icon="trash" onClick={() => setConfirmDelete(true)}>{t('time.periodDelete')}</Button>
            )}
          </div>
        </Field>
        <p className="hb-muted" style={{ margin: '2px 0 14px', fontSize: 13 }}>{t('time.periodHint')}</p>
        {error && <p style={{ color: 'oklch(0.55 0.16 32)', fontSize: 13.5, margin: '0 0 14px' }}>{error}</p>}

        {projects.length === 0 ? (
          <p className="hb-muted">{t('time.noProjectsHint')}</p>
        ) : (
          <PeriodEditor
            key={selected}
            period={selected}
            users={users}
            projects={projects}
            targets={targets}
            onSave={onSave}
            onDone={onBack}
          />
        )}
      </Card>

      {showAdd && <AddPeriodModal existing={periods} onAdd={addPeriod} onClose={() => setShowAdd(false)} />}
      {confirmDelete && (
        <ConfirmDialog
          title={t('time.periodDeleteTitle')}
          message={t('time.periodDeleteConfirm', { date: formatPeriodDate(selected) })}
          confirmLabel={t('time.periodDelete')}
          danger
          onConfirm={removePeriod}
          onClose={() => setConfirmDelete(false)}
        />
      )}
    </div>
  )
}

// The person×project hours grid for ONE Wochensoll period. Re-mounted (keyed on the
// period) whenever the selection changes so its draft reflects that period's values.
// Every PUT carries the period's `validFrom` so the edit lands in the right period.
function PeriodEditor({ period, users, projects, targets, onSave, onDone }: {
  period: string
  users: string[]
  projects: Project[]
  targets: WorkTarget[]
  onSave: (puts: { userId: string; projectId: string; body: object }[]) => Promise<string | null>
  onDone: () => void
}) {
  const { t } = useTranslation()
  const targetFor = (u: string, p: string) => targets.find((x) => x.userId === u && x.projectId === p && periodOf(x) === period)
  const defaultFor = (u: string) => targets.find((x) => x.userId === u && periodOf(x) === period && x.isDefault)?.projectId ?? ''
  const [draft, setDraft] = useState<Record<string, { hours: Record<string, string>; def: string }>>(() =>
    Object.fromEntries(users.map((u) => [u, {
      hours: Object.fromEntries(projects.map((p) => {
        const h = targetFor(u, p.id)?.weeklyHours ?? 0
        // Pre-fill the editable field with the locale decimal mark (#299) so it matches the
        // read-only summary and round-trips: en "1.5" / de "1,5". Hours stay ≤168, so
        // formatDecimal never adds a grouping separator here.
        return [p.id, h > 0 ? formatDecimal(h) : '']
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
        def: d[u].def === '' && (parseLocaleNumber(v) ?? 0) > 0 ? p : d[u].def,
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
        // empty field → 0 hours; an unparseable entry (null) stays NaN and fails validation below
        const hours = raw === '' ? 0 : (parseLocaleNumber(raw) ?? NaN)
        if (!Number.isFinite(hours) || hours < 0 || hours > 168) {
          setError(t('time.invalidHours'))
          return
        }
        sumHours += hours
        const body: { weeklyHours?: number; isDefault?: boolean; validFrom?: string } = {}
        if (hours !== (targetFor(u, p.id)?.weeklyHours ?? 0)) body.weeklyHours = hours
        const defBefore = defaultFor(u)
        // setting the new default clears the old one (within this period) server-side
        if (draft[u].def !== defBefore && draft[u].def === p.id) body.isDefault = true
        if (body.weeklyHours === undefined && body.isDefault === undefined) continue
        // The base period omits validFrom (backend default) so its payloads stay identical
        // to the pre-periods behaviour; scheduled periods carry their start date.
        if (period !== BASE_TARGET_PERIOD) body.validFrom = period
        puts.push({ userId: u, projectId: p.id, body })
      }
      // hours > 0 ⇒ a default project must be chosen (#59); auto-select normally
      // covers this — backstop for legacy data without a default
      if (sumHours > 0 && draft[u].def === '') {
        setError(t('time.defaultRequired'))
        return
      }
    }
    if (puts.length === 0) return onDone()
    submitRef.current = true
    setError(null)
    try {
      const err = await onSave(puts)
      if (err) {
        submitRef.current = false
        setError(err)
      } else {
        onDone()
      }
    } catch {
      submitRef.current = false
      setError(t('time.targetsFailed'))
    }
  }

  return (
    <>
      {users.map((u) => (
        <div key={u} style={{ marginBottom: 18 }}>
          <div className="hb-sectionlabel" style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <Avatar user={u} size={20} /> {userMeta(u)?.name ?? u}
          </div>
          <div className="hb-targetgrid">
            <span className="hb-muted hb-targetgrid__h">{t('time.project')}</span>
            <span className="hb-muted hb-targetgrid__h">{t('time.hoursPerWeek')}</span>
            <span className="hb-muted hb-targetgrid__h">{t('time.defaultColumn')}</span>
            {projects.map((p) => (
              <Fragment key={p.id}>
                <span style={{ display: 'flex', alignItems: 'center', gap: 7, minWidth: 0 }}>
                  <span className="hb-pdot" style={{ background: p.color }} />
                  <span style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                    {p.name}{p.archived && <span className="hb-muted"> ({t('time.archivedSection')})</span>}
                  </span>
                </span>
                <input
                  className="hb-input"
                  inputMode="decimal"
                  value={draft[u].hours[p.id] ?? ''}
                  onChange={(e) => setHours(u, p.id, e.target.value)}
                  placeholder="0"
                  aria-label={`${t('time.hoursPerWeek')} ${p.name} ${userMeta(u)?.name ?? u}`}
                />
                <input
                  type="radio"
                  name={`hb-default-${u}`}
                  checked={draft[u].def === p.id}
                  onChange={() => setDef(u, p.id)}
                  aria-label={`${t('time.defaultColumn')} ${p.name} ${userMeta(u)?.name ?? u}`}
                />
              </Fragment>
            ))}
          </div>
        </div>
      ))}
      {error && <p style={{ color: 'oklch(0.55 0.16 32)', fontSize: 13.5, margin: '0 0 14px' }}>{error}</p>}
      <div className="hb-formactions">
        <Button variant="ghost" onClick={onDone}>{t('common.cancel')}</Button>
        <Button onClick={submit}>{t('common.save')}</Button>
      </div>
    </>
  )
}

// Small modal to schedule a new Wochensoll period from a chosen date. The date must be
// unique and not the base period; the backend seeds the new period from the values in
// force then, so the grid opens pre-filled with the current hours to adjust.
function AddPeriodModal({ existing, onAdd, onClose }: {
  existing: string[]
  onAdd: (validFrom: string) => void
  onClose: () => void
}) {
  const { t } = useTranslation()
  const [date, setDate] = useState('')
  const [err, setErr] = useState<string | null>(null)
  const submit = () => {
    if (!date) return setErr(t('time.periodDatePick'))
    if (date === BASE_TARGET_PERIOD || existing.includes(date)) return setErr(t('time.periodExists'))
    onAdd(date)
  }
  return (
    <Modal
      open
      onClose={onClose}
      title={t('time.periodAddTitle')}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>{t('common.cancel')}</Button>
          <Button icon="plus" onClick={submit}>{t('time.periodAdd')}</Button>
        </>
      }
    >
      <p className="hb-muted" style={{ marginTop: 0 }}>{t('time.periodAddHint')}</p>
      <Field label={t('time.periodValidFrom')}>
        <TextInput type="date" value={date} onChange={(v) => { setDate(v); setErr(null) }} />
      </Field>
      {err && <p style={{ color: 'oklch(0.55 0.16 32)', fontSize: 13.5, margin: '8px 0 0' }}>{err}</p>}
    </Modal>
  )
}

const pad2 = (n: number) => String(n).padStart(2, '0')

// From/To bounds (YYYY-MM-DD) of the calendar month containing `ref`, offset by
// `monthDelta` months (0 = same month, -1 = previous month). new Date(y, m+1, 0)
// yields the last day of month m, which also handles year rollover for the offset.
function monthBounds(ref: Date, monthDelta: number): { from: string; to: string } {
  const y = ref.getFullYear()
  const m = ref.getMonth() + monthDelta
  const first = new Date(y, m, 1)
  const last = new Date(y, m + 1, 0)
  return {
    from: `${first.getFullYear()}-${pad2(first.getMonth() + 1)}-01`,
    to: `${last.getFullYear()}-${pad2(last.getMonth() + 1)}-${pad2(last.getDate())}`,
  }
}

// CSV export with optional date-range and project filters. All fields are
// optional; an empty form exports every completed entry. Includes archived
// projects so their history can still be exported. Moved here from TimeView (#99).
// Quick-select buttons and a month picker fill the from/to range for whole months
// (#491); the free from/to date pickers stay for arbitrary ranges.
function ExportModal({ projects, onExport, onClose }: {
  projects: Project[]
  onExport: (opts: { from?: string; to?: string; projectId?: string }) => void
  onClose: () => void
}) {
  const { t } = useTranslation()
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')
  const [projectId, setProjectId] = useState('')
  // Month picker value (YYYY-MM); empty until a month is chosen or a quick button used.
  const [month, setMonth] = useState('')

  // Apply a whole month to the from/to range and reflect it in the month picker.
  const applyMonth = (ref: Date, monthDelta: number) => {
    const { from: f, to: tt } = monthBounds(ref, monthDelta)
    setFrom(f)
    setTo(tt)
    setMonth(f.slice(0, 7))
  }
  const onMonthChange = (value: string) => {
    setMonth(value)
    if (!value) return
    const [y, m] = value.split('-').map(Number)
    applyMonth(new Date(y, m - 1, 1), 0)
  }

  return (
    <Modal
      open
      onClose={onClose}
      title={t('time.exportTitle')}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>{t('common.cancel')}</Button>
          <Button icon="download" onClick={() => onExport({ from: from || undefined, to: to || undefined, projectId: projectId || undefined })}>
            {t('time.exportSubmit')}
          </Button>
        </>
      }
    >
      <p className="hb-muted" style={{ marginTop: 0 }}>{t('time.exportHint')}</p>
      <Field label={t('time.exportQuickMonth')} group>
        <div className="hb-stack" style={{ gap: 8 }}>
          <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
            <Button size="sm" variant="secondary" onClick={() => applyMonth(new Date(), -1)}>{t('time.exportLastMonth')}</Button>
            <Button size="sm" variant="secondary" onClick={() => applyMonth(new Date(), 0)}>{t('time.exportThisMonth')}</Button>
          </div>
          <TextInput type="month" value={month} onChange={onMonthChange} />
        </div>
      </Field>
      <div className="hb-formgrid">
        <Field label={t('time.from')}><TextInput type="date" value={from} onChange={(v) => { setFrom(v); setMonth('') }} /></Field>
        <Field label={t('time.to')}><TextInput type="date" value={to} onChange={(v) => { setTo(v); setMonth('') }} /></Field>
      </div>
      <Field label={t('time.project')}>
        <Select value={projectId} onChange={setProjectId}>
          <option value="">{t('time.exportAllProjects')}</option>
          {projects.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
        </Select>
      </Field>
    </Modal>
  )
}
