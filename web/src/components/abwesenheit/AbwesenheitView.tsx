// Abwesenheit / Familienkalender — shared household absence planner.
// Ported from the design handoff (views_abwesenheit.jsx) to the HomeBase web stack.
import { useCallback, useEffect, useMemo, useState } from 'react'
import { API_BASE, errorCode, notifyTransportError, safeFetch } from '../../api'
import type { FetchResult } from '../../api'
import { t, errorText } from '../../i18n'
import type { AbsenceState, AbsenceType, HalfDay } from '../../types'
import { useWebSocket } from '../../hooks/useWebSocket'
import { Icon } from '../../ui/Icon'
import { useErrorToast } from '../../ui/ErrorToast'
import { Avatar, Button, Card, Field, IconButton, Modal, Select, SegmentedControl, Sheet, TextInput } from '../../ui/primitives'
import { userMeta } from '../../ui/format'
import * as C from './holidays'
import {
  type Ctx,
  type Summary,
  type Theme,
  buildContext,
  eachDate,
  fmtDays,
  isWorkdayFor,
  normalizeAbsenceState,
  palette,
  personDay,
  summarize,
} from './core'
import { AbwLegend, JahresRaster, MonatsKalender } from './Grids'
import './abw.css'

const WS_SCHEME = window.location.protocol === 'https:' ? 'wss' : 'ws'
const WS_URL = `${WS_SCHEME}://${window.location.host}/api/v1/ws/absence`

const EMPTY: AbsenceState = { users: [], absences: [], partTime: [], kitaClosures: [], customHolidays: [], settings: [] }

const nameOf = (uid: string): string => userMeta(uid)?.name ?? uid
const hueOf = (uid: string): number => userMeta(uid)?.hue ?? 150
const ddmm = (ds?: string | null): string => {
  if (!ds) return ''
  const d = C.parse(ds)
  return `${d.getDate()}.${d.getMonth() + 1}.`
}
const currentTheme = (): Theme => (document.documentElement.getAttribute('data-theme') === 'dark' ? 'dark' : 'light')

// Keep the visible year inside the same window the backend accepts for settings (#144),
// so paging the year nav can never produce a year the settings PUT would reject.
const YEAR_MIN = 2000
const YEAR_MAX = 2200
const clampYear = (y: number): number => Math.min(YEAR_MAX, Math.max(YEAR_MIN, y))

interface ViewProps {
  token: string
  onLogout: () => void
}

/** Mutators against the backend; each refetches the snapshot after the change. */
interface Api {
  setAbsence: (userId: string, date: string, type: AbsenceType, half: HalfDay | null) => Promise<void>
  clearAbsence: (userId: string, date: string) => Promise<void>
  setAbsenceRange: (userId: string, type: AbsenceType | null, from: string, to: string, half: HalfDay | null) => Promise<void>
  toggleKita: (date: string, label: string | null, keep?: boolean) => Promise<void>
  addKita: (date: string, label: string) => Promise<void>
  addKitaRange: (from: string, to: string, label: string) => Promise<void>
  updateKita: (id: string, patch: { date?: string; label?: string }) => Promise<void>
  removeKita: (id: string) => Promise<void>
  addCustomHoliday: (holiday: { month: number; day: number; half: boolean; label: string }) => Promise<void>
  updateCustomHoliday: (id: string, patch: { month?: number; day?: number; half?: boolean; label?: string }) => Promise<void>
  removeCustomHoliday: (id: string) => Promise<void>
  updateAbsSettings: (userId: string, year: number, patch: Record<string, unknown>) => Promise<void>
  addPartTime: (rule: { userId: string; weekday: number; start: string; end: string | null }) => Promise<void>
  updatePartTime: (id: string, patch: { weekday?: number; start?: string; end?: string | null }) => Promise<void>
  removePartTime: (id: string) => Promise<void>
}

export function AbwesenheitView({ token, onLogout }: ViewProps) {
  const nowY = new Date().getFullYear()
  const [data, setData] = useState<AbsenceState>(EMPTY)
  const [loading, setLoading] = useState(true)
  const [year, setYear] = useState(nowY)
  const [layout, setLayout] = useState<'raster' | 'monat'>('raster')
  const [month, setMonth] = useState(new Date().getMonth())
  const [editDs, setEditDs] = useState<string | null>(null)
  const [showSettings, setShowSettings] = useState(false)
  const [anchor, setAnchor] = useState<string | null>(null)
  const [rangeOpen, setRangeOpen] = useState(false)
  const [rangePrefill, setRangePrefill] = useState<{ von: string; bis: string } | null>(null)
  const { flashError, errorToast } = useErrorToast()

  const fetchState = useCallback(async () => {
    const result = await safeFetch(token, `${API_BASE}/absence`)
    // transport reject → fire the global toast once, keep existing data, clear spinner
    if (!result.ok) {
      notifyTransportError()
      setLoading(false)
      return
    }
    const { res } = result
    if (res.status === 401) {
      onLogout()
      return
    }
    // Any snapshot list may be missing when empty (encodeDefaults=false, CLAUDE.md /
    // issue #46) — normalise once at the read so the whole view can rely on arrays (#54).
    if (res.ok) setData(normalizeAbsenceState(await res.json()))
    setLoading(false)
  }, [token, onLogout])

  useEffect(() => {
    fetchState()
  }, [fetchState])

  useWebSocket({ url: WS_URL, token }, () => {
    fetchState()
  })

  // --- API mutators ---------------------------------------------------------
  const json = (body: object): RequestInit => ({
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
  // Run a mutation via safeFetch, then refetch on success. A transport reject
  // (offline/DNS — issue #93) surfaces the German fallback; on 401 → logout; on
  // any other HTTP failure show the reason as an error toast and skip the refetch
  // (the backend cleanly refused the change, so the snapshot is unchanged) — #96.
  const mutate = async (req: () => Promise<FetchResult>, fallback: string): Promise<boolean> => {
    const result = await req()
    if (!result.ok) {
      flashError(errorText(null, fallback))
      return false
    }
    const { res } = result
    if (res.status === 401) {
      onLogout()
      return false
    }
    if (!res.ok) {
      flashError(errorText(await errorCode(res), fallback))
      return false
    }
    await fetchState()
    return true
  }
  const api: Api = {
    setAbsence: async (userId, date, type, half) => {
      await mutate(() => safeFetch(token, `${API_BASE}/absence/entries`, { method: 'POST', ...json({ userId, date, type, half }) }), t.abwesenheit.saveFailed)
    },
    clearAbsence: async (userId, date) => {
      await mutate(() => safeFetch(token, `${API_BASE}/absence/entries?userId=${encodeURIComponent(userId)}&date=${date}`, { method: 'DELETE' }), t.abwesenheit.deleteFailed)
    },
    setAbsenceRange: async (userId, type, from, to, half) => {
      const dates = type
        ? eachDate(from, to).filter((ds) => isWorkdayFor(data, userId, ds))
        : eachDate(from, to)
      await mutate(() => safeFetch(token, `${API_BASE}/absence/entries/batch`, { method: 'POST', ...json({ userId, type, half, dates }) }), t.abwesenheit.saveFailed)
    },
    toggleKita: async (date, label, keep = false) => {
      const existing = data.kitaClosures.find((k) => k.date === date)
      if (label == null) {
        if (existing) await mutate(() => safeFetch(token, `${API_BASE}/absence/kita/${existing.id}`, { method: 'DELETE' }), t.abwesenheit.deleteFailed)
      } else if (keep) {
        if (existing) await mutate(() => safeFetch(token, `${API_BASE}/absence/kita/${existing.id}`, { method: 'PUT', ...json({ label }) }), t.abwesenheit.kitaFailed)
        else await mutate(() => safeFetch(token, `${API_BASE}/absence/kita`, { method: 'POST', ...json({ date, label }) }), t.abwesenheit.kitaFailed)
      } else if (!existing) {
        await mutate(() => safeFetch(token, `${API_BASE}/absence/kita`, { method: 'POST', ...json({ date, label }) }), t.abwesenheit.kitaFailed)
      }
    },
    addKita: async (date, label) => {
      await mutate(() => safeFetch(token, `${API_BASE}/absence/kita`, { method: 'POST', ...json({ date, label }) }), t.abwesenheit.kitaFailed)
    },
    addKitaRange: async (from, to, label) => {
      await mutate(() => safeFetch(token, `${API_BASE}/absence/kita/range`, { method: 'POST', ...json({ from, to, label }) }), t.abwesenheit.kitaFailed)
    },
    updateKita: async (id, patch) => {
      await mutate(() => safeFetch(token, `${API_BASE}/absence/kita/${id}`, { method: 'PUT', ...json(patch) }), t.abwesenheit.kitaFailed)
    },
    removeKita: async (id) => {
      await mutate(() => safeFetch(token, `${API_BASE}/absence/kita/${id}`, { method: 'DELETE' }), t.abwesenheit.deleteFailed)
    },
    addCustomHoliday: async (holiday) => {
      await mutate(() => safeFetch(token, `${API_BASE}/absence/holidays`, { method: 'POST', ...json(holiday) }), t.abwesenheit.holidayFailed)
    },
    updateCustomHoliday: async (id, patch) => {
      await mutate(() => safeFetch(token, `${API_BASE}/absence/holidays/${id}`, { method: 'PUT', ...json(patch) }), t.abwesenheit.holidayFailed)
    },
    removeCustomHoliday: async (id) => {
      await mutate(() => safeFetch(token, `${API_BASE}/absence/holidays/${id}`, { method: 'DELETE' }), t.abwesenheit.deleteFailed)
    },
    updateAbsSettings: async (userId, year, patch) => {
      await mutate(() => safeFetch(token, `${API_BASE}/absence/settings/${encodeURIComponent(userId)}/${year}`, { method: 'PUT', ...json(patch) }), t.abwesenheit.settingsFailed)
    },
    addPartTime: async (rule) => {
      await mutate(() => safeFetch(token, `${API_BASE}/absence/parttime`, { method: 'POST', ...json(rule) }), t.abwesenheit.partTimeFailed)
    },
    updatePartTime: async (id, patch) => {
      const rule = data.partTime.find((r) => r.id === id)
      if (!rule) return
      const body = {
        weekday: patch.weekday ?? rule.weekday,
        start: patch.start ?? rule.start,
        end: 'end' in patch ? patch.end ?? null : rule.end ?? null,
      }
      await mutate(() => safeFetch(token, `${API_BASE}/absence/parttime/${id}`, { method: 'PUT', ...json(body) }), t.abwesenheit.partTimeFailed)
    },
    removePartTime: async (id) => {
      await mutate(() => safeFetch(token, `${API_BASE}/absence/parttime/${id}`, { method: 'DELETE' }), t.abwesenheit.deleteFailed)
    },
  }

  // --- derived --------------------------------------------------------------
  const theme = currentTheme()
  const pal = useMemo(() => palette(theme), [theme])
  const userIds = data.users
  const ctx = useMemo(() => buildContext(data, year, userIds), [data, year, userIds])
  const today = C.ymd(new Date())

  // single click → day editor; shift-click after a first click → period editor
  const onPick = (ds: string, e?: { shiftKey?: boolean }) => {
    if (e && e.shiftKey && anchor) {
      setRangePrefill({ von: anchor < ds ? anchor : ds, bis: anchor < ds ? ds : anchor })
      setRangeOpen(true)
      return
    }
    setAnchor(ds)
    setEditDs(ds)
  }
  const openRange = () => {
    setRangePrefill({ von: today, bis: today })
    setRangeOpen(true)
  }

  // Settings as its own full-width page (not a modal) — like the time-tracking
  // project detail (#32, #29). Early-return the settings page instead of the
  // calendar overview; "‹ Zurück" returns to the calendar.
  if (showSettings && !loading && userIds.length > 0) {
    return (
      <div className="hb-page hb-page--wide">
        <AbwSettings ctx={ctx} data={data} api={api} userIds={userIds} year={year} onBack={() => setShowSettings(false)} />
        {errorToast}
      </div>
    )
  }

  return (
    <div className="hb-page hb-page--wide">
      <div className="hb-pagehead">
        <div>
          <div className="hb-pagehead__eyebrow">{t.abwesenheit.eyebrow}</div>
          <h1>{t.abwesenheit.title}</h1>
        </div>
        <div className="hb-pagehead__actions abw-actions">
          <div className="abw-yearnav">
            <button className="hb-iconbtn" onClick={() => setYear((y) => clampYear(y - 1))} aria-label={t.abwesenheit.prevYear}>
              <Icon name="chevronLeft" size={17} stroke={2.2} />
            </button>
            <span className="abw-yearnav__y hb-mono">{year}</span>
            <button className="hb-iconbtn" onClick={() => setYear((y) => clampYear(y + 1))} aria-label={t.abwesenheit.nextYear}>
              <Icon name="chevronRight" size={17} stroke={2.2} />
            </button>
          </div>
          <SegmentedControl
            value={layout}
            onChange={setLayout}
            options={[
              { value: 'raster', label: t.abwesenheit.layoutYear },
              { value: 'monat', label: t.abwesenheit.layoutMonth },
            ]}
          />
          <Button variant="secondary" icon="plus" onClick={openRange}>{t.abwesenheit.period}</Button>
          <Button variant="secondary" icon="edit" onClick={() => setShowSettings(true)}>{t.abwesenheit.settings}</Button>
        </div>
      </div>

      {loading ? (
        <p className="hb-muted" style={{ textAlign: 'center', padding: 24 }}>{t.common.loading}</p>
      ) : userIds.length === 0 ? (
        <Card className="hb-card--pad"><p className="hb-muted">{t.abwesenheit.loadError}</p></Card>
      ) : (
        <>
          <div className="abw-sumgrid">
            {userIds.map((uid) => (
              <AbwSummaryCard key={uid} uid={uid} sum={summarize(ctx, uid, today)} hue={ctx.hue[uid]} pal={pal} />
            ))}
          </div>

          <div className="abw-legendrow">
            <AbwLegend userIds={userIds} pal={pal} />
            <div className="abw-legendrow__right">
              <span className="abw-hint">{t.abwesenheit.clickHint}</span>
              {layout === 'monat' ? (
                <button className="hb-link" onClick={() => { setYear(nowY); setMonth(new Date().getMonth()) }}>{t.abwesenheit.today}</button>
              ) : null}
            </div>
          </div>

          <Card className="abw-gridcard">
            {layout === 'raster' ? (
              <JahresRaster ctx={ctx} pal={pal} userIds={userIds} today={today} onPick={onPick} />
            ) : (
              <MonatsKalender ctx={ctx} pal={pal} userIds={userIds} today={today} onPick={onPick} month={month} setMonth={setMonth} />
            )}
          </Card>
        </>
      )}

      {editDs ? <AbwDayEditor ctx={ctx} ds={editDs} api={api} userIds={userIds} onClose={() => setEditDs(null)} /> : null}
      {rangeOpen ? <AbwRangeModal data={data} api={api} userIds={userIds} prefill={rangePrefill} onClose={() => setRangeOpen(false)} /> : null}

      {errorToast}
    </div>
  )
}

/* ---------- Summary card ---------- */
function AbwSummaryCard({ uid, sum, hue, pal }: { uid: string; sum: Summary; hue: number; pal: ReturnType<typeof palette> }) {
  const f = fmtDays
  const H = hue != null ? hue : hueOf(uid)
  const total = Math.max(sum.total, 1)
  const takenPct = Math.min(100, (sum.taken / total) * 100)
  const plannedPct = Math.min(100 - takenPct, (sum.planned / total) * 100)
  return (
    <Card className="abw-sumcard">
      <div className="abw-sumcard__head">
        <Avatar user={uid} size={34} />
        <div className="abw-sumcard__id">
          <div className="abw-sumcard__name">{nameOf(uid)}</div>
          <div className="abw-sumcard__state">{C.stateName(sum.state)}</div>
        </div>
        <div className="abw-sumcard__big">
          <span className="abw-sumcard__bigv hb-mono" style={{ color: `oklch(0.55 0.1 ${H})` }}>{f(sum.remaining)}</span>
          <span className="abw-sumcard__bigl">{t.abwesenheit.leaveRemaining}</span>
        </div>
      </div>

      <div className="abw-bar">
        <span className="abw-bar__seg" style={{ width: takenPct + '%', background: `oklch(0.6 0.1 ${H})` }} />
        <span className="abw-bar__seg" style={{ width: plannedPct + '%', background: `oklch(0.6 0.1 ${H})`, opacity: 0.45 }} />
      </div>
      <div className="abw-sumcard__legend">
        <span><i className="abw-dot" style={{ background: `oklch(0.6 0.1 ${H})` }} />{t.abwesenheit.taken} {f(sum.taken)}</span>
        <span><i className="abw-dot" style={{ background: `oklch(0.6 0.1 ${H})`, opacity: 0.45 }} />{t.abwesenheit.planned} {f(sum.planned)}</span>
        <span className="hb-muted">{t.abwesenheit.allowance} {f(sum.allowance)}</span>
      </div>

      <div className="abw-sumcard__foot">
        {sum.carry > 0 ? (
          <span className={`abw-chip${sum.carryExpired ? ' abw-chip--warn' : ' abw-chip--soft'}`}>
            +{f(sum.carry)} {t.abwesenheit.carryover} · {sum.carryExpired ? `${f(sum.carryLost)} ${t.abwesenheit.carryLost}` : `${t.abwesenheit.carryUntil} ${ddmm(sum.carryExpires)}`}
          </span>
        ) : null}
        <span className="abw-chip abw-chip--neutral"><i className="abw-dot" style={{ background: pal.KRANK }} />{t.abwesenheit.sick} {f(sum.krank)}</span>
        <span className="abw-chip abw-chip--neutral"><i className="abw-dot" style={{ background: pal.KIND_KRANK }} />{t.abwesenheit.childSick} {f(sum.kind)}{sum.kindCap ? ` / ${sum.kindCap}` : ''}</span>
      </div>
    </Card>
  )
}

/* ---------- Day editor ---------- */
function HalfToggle({ value, onChange }: { value: HalfDay | null; onChange: (v: HalfDay | null) => void }) {
  const opts: { v: HalfDay | null; l: string }[] = [
    { v: null, l: t.abwesenheit.fullDay },
    { v: 'vm', l: t.abwesenheit.forenoon },
    { v: 'nm', l: t.abwesenheit.afternoon },
  ]
  return (
    <div className="abw-half">
      {opts.map((o) => (
        <button key={o.l} className={`abw-half__b${value === o.v ? ' is-active' : ''}`} onClick={() => onChange(o.v)}>{o.l}</button>
      ))}
    </div>
  )
}

// Day editor as a slide-over panel (right edge on desktop, bottom sheet on
// mobile) instead of a centered modal — comfortable on a 360px phone (#44, #29).
// Dimmed backdrop closes on click; Escape closes too (preserving the Modal's
// affordance).
function AbwDayEditor({ ctx, ds, api, userIds, onClose }: { ctx: Ctx; ds: string; api: Api; userIds: string[]; onClose: () => void }) {
  const d = C.parse(ds)
  const title = `${C.WD_LONG[d.getDay()]}, ${d.getDate()}. ${C.MON_FULL[d.getMonth()]} ${d.getFullYear()}`
  const kita = ctx.kita[ds]
  const typeOpts: { id: AbsenceType | null; label: string }[] = [
    { id: null, label: t.abwesenheit.work },
    { id: 'URLAUB', label: t.abwesenheit.urlaub },
    { id: 'KRANK', label: t.abwesenheit.krank },
    { id: 'KIND_KRANK', label: t.abwesenheit.kindKrank },
  ]
  return (
    <Sheet open onClose={onClose} title={title} footer={<Button onClick={onClose}>{t.abwesenheit.done}</Button>}>
      {userIds.map((uid) => {
        const st = personDay(ctx, uid, ds)
        const note = st.holiday
          ? `${t.abwesenheit.noteHoliday} · ${st.holiday}`
          : st.ptOff
            ? t.abwesenheit.noteTeilzeit
            : st.weekend
              ? t.abwesenheit.noteWeekend
              : null
        return (
          <div key={uid} className="abw-ed-person">
            <div className="abw-ed-person__head">
              <Avatar user={uid} size={26} />
              <span className="abw-ed-person__name">{nameOf(uid)}</span>
              {note ? <span className="abw-ed-person__note">{note}</span> : null}
            </div>
            <div className="abw-pickrow">
              {typeOpts.map((opt) => (
                <button
                  key={String(opt.id)}
                  className={`abw-pick${(st.type || null) === opt.id ? ' is-active' : ''}`}
                  onClick={() => (opt.id ? api.setAbsence(uid, ds, opt.id, st.type === opt.id ? st.half : null) : api.clearAbsence(uid, ds))}
                >
                  {opt.label}
                </button>
              ))}
            </div>
            {st.type ? <HalfToggle value={st.half} onChange={(h) => api.setAbsence(uid, ds, st.type!, h)} /> : null}
          </div>
        )
      })}

      <div className="abw-ed-kita">
        <div>
          <div className="abw-ed-kita__t">{t.abwesenheit.kitaClosure}</div>
          <div className="abw-ed-kita__s hb-muted">{t.abwesenheit.kitaForFamily}</div>
        </div>
        <button
          className={`abw-switch${kita ? ' is-on' : ''}`}
          role="switch"
          aria-checked={!!kita}
          onClick={() => api.toggleKita(ds, kita ? null : t.abwesenheit.kitaDefaultLabel)}
        >
          <span className="abw-switch__knob" />
        </button>
      </div>
      {kita ? (
        <Field label={t.abwesenheit.occasionOptional}>
          <TextInput value={kita.label} onChange={(v) => api.toggleKita(ds, v || t.abwesenheit.kitaDefaultLabel, true)} placeholder={t.abwesenheit.occasionPlaceholder} />
        </Field>
      ) : null}
    </Sheet>
  )
}

/* ---------- Zeitraum (period) editor ---------- */
function AbwRangeModal({ data, api, userIds, prefill, onClose }: {
  data: AbsenceState
  api: Api
  userIds: string[]
  prefill: { von: string; bis: string } | null
  onClose: () => void
}) {
  const [targets, setTargets] = useState<string[]>(userIds.slice())
  const [type, setType] = useState<AbsenceType | null>('URLAUB')
  const [von, setVon] = useState(prefill?.von ?? C.ymd(new Date()))
  const [bis, setBis] = useState(prefill?.bis ?? C.ymd(new Date()))
  const toggleT = (uid: string) => setTargets((tg) => (tg.includes(uid) ? tg.filter((x) => x !== uid) : [...tg, uid]))
  const typeOpts: { id: AbsenceType | null; label: string }[] = [
    { id: 'URLAUB', label: t.abwesenheit.urlaub },
    { id: 'KRANK', label: t.abwesenheit.krank },
    { id: 'KIND_KRANK', label: t.abwesenheit.kindKrank },
    { id: null, label: t.abwesenheit.deleteEntry },
  ]
  const preview = targets[0] ? eachDate(von, bis).filter((ds) => isWorkdayFor(data, targets[0], ds)).length : 0
  const dis = targets.length === 0 || von > bis
  const apply = async () => {
    await Promise.all(targets.map((uid) => api.setAbsenceRange(uid, type, von, bis, null)))
    onClose()
  }
  return (
    <Modal
      open
      onClose={onClose}
      width={480}
      title={t.abwesenheit.periodTitle}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>{t.common.cancel}</Button>
          <Button icon="check" onClick={apply} disabled={dis}>{t.abwesenheit.apply}</Button>
        </>
      }
    >
      <Field label={t.abwesenheit.forWhom}>
        <div className="abw-pickrow">
          {userIds.map((uid) => (
            <button key={uid} className={`abw-pick${targets.includes(uid) ? ' is-active' : ''}`} onClick={() => toggleT(uid)}>{nameOf(uid)}</button>
          ))}
        </div>
      </Field>
      <Field label={t.abwesenheit.kind}>
        <div className="abw-pickrow">
          {typeOpts.map((opt) => (
            <button key={String(opt.id)} className={`abw-pick${type === opt.id ? ' is-active' : ''}`} onClick={() => setType(opt.id)}>{opt.label}</button>
          ))}
        </div>
      </Field>
      <div className="abw-range-dates">
        <Field label={t.abwesenheit.from}><TextInput type="date" value={von} onChange={setVon} /></Field>
        <Field label={t.abwesenheit.to}><TextInput type="date" value={bis} onChange={setBis} /></Field>
      </div>
      <div className="hb-muted" style={{ fontSize: 12.5, lineHeight: 1.5 }}>
        {type
          ? `${t.abwesenheit.rangeHint}${targets[0] ? ` (${t.abwesenheit.rangePreview.replace('{n}', String(preview)).replace('{name}', nameOf(targets[0]))})` : ''}. ${t.abwesenheit.rangeHalfHint}`
          : t.abwesenheit.rangeClearHint}
      </div>
    </Modal>
  )
}

/* ---------- Settings (own page, not a modal — #43) ---------- */
function AbwSettings({ ctx, data, api, userIds, year, onBack }: {
  ctx: Ctx
  data: AbsenceState
  api: Api
  userIds: string[]
  year: number
  onBack: () => void
}) {
  const num = (v: string, fallback: number): number => {
    const n = parseFloat(String(v).replace(',', '.'))
    return Number.isFinite(n) ? n : fallback
  }
  const [kDate, setKDate] = useState(`${year}-01-01`)
  const [rVon, setRVon] = useState(`${year}-07-27`)
  const [rBis, setRBis] = useState(`${year}-08-07`)
  const [rLabel, setRLabel] = useState(t.abwesenheit.kitaDefaultLabel)
  const kita = [...data.kitaClosures].sort((a, b) => a.date.localeCompare(b.date))
  const wd = t.abwesenheit.weekdaysShort

  // Eigene Feiertage (#51): recurring by month+day. The date input's year is purely a
  // carrier and is ignored on read — only month+day are stored. Add-form state below.
  const [hDate, setHDate] = useState(`${year}-12-24`)
  const [hHalf, setHHalf] = useState(true)
  const [hLabel, setHLabel] = useState('')
  // `data` is normalised in fetchState (#54): every snapshot list is a real array here.
  const holidays = [...data.customHolidays].sort((a, b) => a.month - b.month || a.day - b.day)
  // MM-DD of a custom holiday → a YYYY-MM-DD value the date input understands (year = the
  // currently viewed year, just a carrier).
  const holDateValue = (h: { month: number; day: number }): string => `${year}-${C.pad(h.month)}-${C.pad(h.day)}`
  const monthDayOf = (ds: string): { month: number; day: number } => {
    const [, m, d] = ds.split('-').map(Number)
    return { month: m, day: d }
  }

  return (
    <>
      <div className="hb-detailnav">
        <Button variant="ghost" size="sm" icon="chevronLeft" onClick={onBack}>{t.abwesenheit.backToCalendar}</Button>
      </div>
      <div className="hb-pagehead">
        <div>
          <div className="hb-pagehead__eyebrow">{t.abwesenheit.eyebrow}</div>
          <h1>{t.abwesenheit.settingsTitle}</h1>
        </div>
      </div>

      <Card className="hb-card--pad abw-set-page">
      {userIds.map((uid) => {
        const s = ctx.settings[uid]
        const rules = data.partTime.filter((r) => r.userId === uid)
        return (
          <div key={uid} className="abw-set-person">
            <div className="abw-set-person__head"><Avatar user={uid} size={28} /><span>{nameOf(uid)}</span></div>
            <div className="abw-set-grid">
              <Field label={t.abwesenheit.bundesland}>
                <Select value={s.state} onChange={(v) => api.updateAbsSettings(uid, year, { state: v })}>
                  {C.STATES.map((st) => <option key={st.code} value={st.code}>{st.name}</option>)}
                </Select>
              </Field>
              <Field label={t.abwesenheit.yearAllowance}>
                <TextInput type="number" value={String(s.allowance ?? '')} onChange={(v) => api.updateAbsSettings(uid, year, { allowance: num(v, 0) })} />
              </Field>
              <Field label={t.abwesenheit.restLeave}>
                <TextInput type="number" value={String(s.carryover ?? '')} onChange={(v) => api.updateAbsSettings(uid, year, { carryover: num(v, 0) })} />
              </Field>
              <Field label={t.abwesenheit.expiresOn}>
                <TextInput type="date" value={s.carryoverExpires || `${year}-03-31`} onChange={(v) => api.updateAbsSettings(uid, year, { carryoverExpires: v })} />
              </Field>
              <Field label={t.abwesenheit.kindKrankCap}>
                <TextInput type="number" value={String(s.kindKrankCap ?? '')} onChange={(v) => api.updateAbsSettings(uid, year, { kindKrankCap: Math.round(num(v, 15)) })} />
              </Field>
            </div>

            <div className="abw-set-pt">
              <div className="abw-set-pt__label">{t.abwesenheit.teilzeitTitle}</div>
              {rules.length === 0 ? <div className="hb-muted abw-set-pt__empty">{t.abwesenheit.teilzeitEmpty}</div> : null}
              {rules.map((r) => (
                <div key={r.id} className="abw-set-ptrow">
                  <Select value={String(r.weekday)} onChange={(v) => api.updatePartTime(r.id, { weekday: Number(v) })} style={{ width: 130 }}>
                    {[1, 2, 3, 4, 5].map((w) => <option key={w} value={w}>{wd[w - 1]}{t.abwesenheit.weekdayFree}</option>)}
                  </Select>
                  <span className="abw-set-ptrow__lab">{t.abwesenheit.teilzeitFromLabel}</span>
                  <TextInput type="date" value={r.start} onChange={(v) => api.updatePartTime(r.id, { start: v })} />
                  <span className="abw-set-ptrow__lab">{t.abwesenheit.teilzeitToLabel}</span>
                  <TextInput type="date" value={r.end || ''} onChange={(v) => api.updatePartTime(r.id, { end: v || null })} />
                  <IconButton icon="trash" label={t.abwesenheit.deleteRule} danger size={16} onClick={() => api.removePartTime(r.id)} />
                </div>
              ))}
              <button className="hb-link" style={{ marginTop: 8 }} onClick={() => api.addPartTime({ userId: uid, weekday: 1, start: `${year}-01-01`, end: null })}>
                <Icon name="plus" size={14} stroke={2.2} /> {t.abwesenheit.addFreeDay}
              </button>
            </div>
          </div>
        )
      })}

      <div className="abw-set-kita">
        <div className="abw-set-pt__label">{t.abwesenheit.kitaSection}</div>
        <div className="hb-muted abw-set-kita__hint">{t.abwesenheit.kitaSectionHint}</div>
        {kita.length === 0 ? <div className="hb-muted abw-set-pt__empty">{t.abwesenheit.kitaEmpty}</div> : null}
        <div className="abw-kita-list">
          {kita.map((k) => (
            <div key={k.id} className="abw-kita-row">
              <TextInput type="date" value={k.date} onChange={(v) => api.updateKita(k.id, { date: v })} />
              <TextInput value={k.label} onChange={(v) => api.updateKita(k.id, { label: v })} placeholder={t.abwesenheit.occasion} />
              <IconButton icon="trash" label={t.abwesenheit.delete} danger size={16} onClick={() => api.removeKita(k.id)} />
            </div>
          ))}
        </div>
        <div className="abw-kita-add">
          <div className="abw-kita-add__row">
            <span className="abw-kita-add__lab">{t.abwesenheit.singleDay}</span>
            <TextInput type="date" value={kDate} onChange={setKDate} />
            <Button size="sm" variant="soft" icon="plus" onClick={() => api.addKita(kDate, t.abwesenheit.kitaDefaultLabel)}>{t.abwesenheit.add}</Button>
          </div>
          <div className="abw-kita-add__row">
            <span className="abw-kita-add__lab">{t.abwesenheit.period}</span>
            <TextInput type="date" value={rVon} onChange={setRVon} />
            <span className="abw-set-ptrow__lab">{t.abwesenheit.teilzeitToLabel}</span>
            <TextInput type="date" value={rBis} onChange={setRBis} />
            <TextInput value={rLabel} onChange={setRLabel} placeholder={t.abwesenheit.occasion} />
            <Button size="sm" variant="soft" icon="plus" onClick={() => api.addKitaRange(rVon, rBis, rLabel)}>{t.abwesenheit.add}</Button>
          </div>
          <div className="hb-muted abw-set-kita__hint">{t.abwesenheit.kitaRangeHint}</div>
        </div>
      </div>

      <div className="abw-set-kita">
        <div className="abw-set-pt__label">{t.abwesenheit.holidaySection}</div>
        <div className="hb-muted abw-set-kita__hint">{t.abwesenheit.holidaySectionHint}</div>
        {holidays.length === 0 ? <div className="hb-muted abw-set-pt__empty">{t.abwesenheit.holidayEmpty}</div> : null}
        <div className="abw-kita-list">
          {holidays.map((h) => (
            <div key={h.id} className="abw-kita-row">
              <TextInput type="date" value={holDateValue(h)} onChange={(v) => api.updateCustomHoliday(h.id, monthDayOf(v))} />
              <div className="abw-half">
                <button className={`abw-half__b${h.half ? '' : ' is-active'}`} onClick={() => api.updateCustomHoliday(h.id, { half: false })}>{t.abwesenheit.fullDay}</button>
                <button className={`abw-half__b${h.half ? ' is-active' : ''}`} onClick={() => api.updateCustomHoliday(h.id, { half: true })}>{t.abwesenheit.halfDay}</button>
              </div>
              <TextInput value={h.label} onChange={(v) => api.updateCustomHoliday(h.id, { label: v })} placeholder={t.abwesenheit.occasion} />
              <IconButton icon="trash" label={t.abwesenheit.delete} danger size={16} onClick={() => api.removeCustomHoliday(h.id)} />
            </div>
          ))}
        </div>
        <div className="abw-kita-add">
          <div className="abw-kita-add__row">
            <span className="abw-kita-add__lab">{t.abwesenheit.holidayDate}</span>
            <TextInput type="date" value={hDate} onChange={setHDate} />
            <div className="abw-half">
              <button className={`abw-half__b${hHalf ? '' : ' is-active'}`} onClick={() => setHHalf(false)}>{t.abwesenheit.fullDay}</button>
              <button className={`abw-half__b${hHalf ? ' is-active' : ''}`} onClick={() => setHHalf(true)}>{t.abwesenheit.halfDay}</button>
            </div>
            <TextInput value={hLabel} onChange={setHLabel} placeholder={t.abwesenheit.occasion} />
            <Button
              size="sm"
              variant="soft"
              icon="plus"
              onClick={() => api.addCustomHoliday({ ...monthDayOf(hDate), half: hHalf, label: hLabel.trim() || t.abwesenheit.holidayDefaultLabel })}
            >{t.abwesenheit.add}</Button>
          </div>
          <div className="hb-muted abw-set-kita__hint">{t.abwesenheit.holidayRecurHint}</div>
        </div>
      </div>
      </Card>
    </>
  )
}
