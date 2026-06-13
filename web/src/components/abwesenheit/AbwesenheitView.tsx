// Abwesenheit / Familienkalender — shared household absence planner.
// Ported from the design handoff (views_abwesenheit.jsx) to the HomeBase web stack.
import { useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import type { AbsenceState, AbsenceType, HalfDay } from '../../types'
import { Avatar, Button, Card, Field, Modal, SegmentedControl, Sheet, TextInput } from '../../ui/primitives'
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
  palette,
  personDay,
  summarize,
} from './core'
import { AbwLegend, JahresRaster, MonatsKalender } from './Grids'
import { YearStepper } from './YearStepper'
import './abw.css'
import { useAbsenceData, type Api } from './useAbsenceData'

const nameOf = (uid: string): string => userMeta(uid)?.name ?? uid
const hueOf = (uid: string): number => userMeta(uid)?.hue ?? 150
const ddmm = (ds?: string | null): string => {
  if (!ds) return ''
  const d = C.parse(ds)
  return `${d.getDate()}.${d.getMonth() + 1}.`
}
const currentTheme = (): Theme => (document.documentElement.getAttribute('data-theme') === 'dark' ? 'dark' : 'light')

interface ViewProps {
  token: string
  onLogout: () => void
}

export function AbwesenheitView({ token, onLogout }: ViewProps) {
  const { t } = useTranslation()
  const nowY = new Date().getFullYear()
  const { data, loading, api, errorToast } = useAbsenceData(token, onLogout)
  const [year, setYear] = useState(nowY)
  const [layout, setLayout] = useState<'raster' | 'monat'>('raster')
  const [month, setMonth] = useState(new Date().getMonth())
  const [editDs, setEditDs] = useState<string | null>(null)
  const [anchor, setAnchor] = useState<string | null>(null)
  const [rangeOpen, setRangeOpen] = useState(false)
  const [rangePrefill, setRangePrefill] = useState<{ von: string; bis: string } | null>(null)
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

  return (
    <div className="hb-page hb-page--wide">
      <div className="hb-pagehead">
        <div>
          <div className="hb-pagehead__eyebrow">{t('abwesenheit.eyebrow')}</div>
          <h1>{t('abwesenheit.title')}</h1>
        </div>
        <div className="hb-pagehead__actions abw-actions">
          <YearStepper year={year} onChange={setYear} />
          <SegmentedControl
            value={layout}
            onChange={setLayout}
            options={[
              { value: 'raster', label: t('abwesenheit.layoutYear') },
              { value: 'monat', label: t('abwesenheit.layoutMonth') },
            ]}
          />
          <Button variant="secondary" icon="plus" onClick={openRange}>{t('abwesenheit.period')}</Button>
        </div>
      </div>

      {loading ? (
        <p className="hb-muted" style={{ textAlign: 'center', padding: 24 }}>{t('common.loading')}</p>
      ) : userIds.length === 0 ? (
        <Card className="hb-card--pad"><p className="hb-muted">{t('abwesenheit.loadError')}</p></Card>
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
              <span className="abw-hint">{t('abwesenheit.clickHint')}</span>
              {layout === 'monat' ? (
                <button className="hb-link" onClick={() => { setYear(nowY); setMonth(new Date().getMonth()) }}>{t('abwesenheit.today')}</button>
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
  const { t } = useTranslation()
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
          <span className="abw-sumcard__bigl">{t('abwesenheit.leaveRemaining')}</span>
        </div>
      </div>

      <div className="abw-bar">
        <span className="abw-bar__seg" style={{ width: takenPct + '%', background: `oklch(0.6 0.1 ${H})` }} />
        <span className="abw-bar__seg" style={{ width: plannedPct + '%', background: `oklch(0.6 0.1 ${H})`, opacity: 0.45 }} />
      </div>
      <div className="abw-sumcard__legend">
        <span><i className="abw-dot" style={{ background: `oklch(0.6 0.1 ${H})` }} />{t('abwesenheit.taken')} {f(sum.taken)}</span>
        <span><i className="abw-dot" style={{ background: `oklch(0.6 0.1 ${H})`, opacity: 0.45 }} />{t('abwesenheit.planned')} {f(sum.planned)}</span>
        <span className="hb-muted">{t('abwesenheit.allowance')} {f(sum.allowance)}</span>
      </div>

      <div className="abw-sumcard__foot">
        {sum.carry > 0 ? (
          <span className={`abw-chip${sum.carryExpired ? ' abw-chip--warn' : ' abw-chip--soft'}`}>
            +{f(sum.carry)} {t('abwesenheit.carryover')} · {sum.carryExpired ? `${f(sum.carryLost)} ${t('abwesenheit.carryLost')}` : `${t('abwesenheit.carryUntil')} ${ddmm(sum.carryExpires)}`}
          </span>
        ) : null}
        <span className="abw-chip abw-chip--neutral"><i className="abw-dot" style={{ background: pal.KRANK }} />{t('abwesenheit.sick')} {f(sum.krank)}</span>
        <span className="abw-chip abw-chip--neutral"><i className="abw-dot" style={{ background: pal.KIND_KRANK }} />{t('abwesenheit.childSick')} {f(sum.kind)}{sum.kindCap ? ` / ${sum.kindCap}` : ''}</span>
      </div>
    </Card>
  )
}

/* ---------- Day editor ---------- */
function HalfToggle({ value, onChange }: { value: HalfDay | null; onChange: (v: HalfDay | null) => void }) {
  const { t } = useTranslation()
  const opts: { v: HalfDay | null; l: string }[] = [
    { v: null, l: t('abwesenheit.fullDay') },
    { v: 'vm', l: t('abwesenheit.forenoon') },
    { v: 'nm', l: t('abwesenheit.afternoon') },
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
  const { t } = useTranslation()
  const d = C.parse(ds)
  const title = `${C.WD_LONG[d.getDay()]}, ${d.getDate()}. ${C.MON_FULL[d.getMonth()]} ${d.getFullYear()}`
  const kita = ctx.kita[ds]
  const typeOpts: { id: AbsenceType | null; label: string }[] = [
    { id: null, label: t('abwesenheit.work') },
    { id: 'URLAUB', label: t('abwesenheit.urlaub') },
    { id: 'KRANK', label: t('abwesenheit.krank') },
    { id: 'KIND_KRANK', label: t('abwesenheit.kindKrank') },
  ]
  return (
    <Sheet open onClose={onClose} title={title} footer={<Button onClick={onClose}>{t('abwesenheit.done')}</Button>}>
      {userIds.map((uid) => {
        const st = personDay(ctx, uid, ds)
        const note = st.holiday
          ? `${t('abwesenheit.noteHoliday')} · ${st.holiday}`
          : st.ptOff
            ? t('abwesenheit.noteTeilzeit')
            : st.weekend
              ? t('abwesenheit.noteWeekend')
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
          <div className="abw-ed-kita__t">{t('abwesenheit.kitaClosure')}</div>
          <div className="abw-ed-kita__s hb-muted">{t('abwesenheit.kitaForFamily')}</div>
        </div>
        <button
          className={`abw-switch${kita ? ' is-on' : ''}`}
          role="switch"
          aria-checked={!!kita}
          onClick={() => api.toggleKita(ds, kita ? null : t('abwesenheit.kitaDefaultLabel'))}
        >
          <span className="abw-switch__knob" />
        </button>
      </div>
      {kita ? (
        <Field label={t('abwesenheit.occasionOptional')}>
          <TextInput value={kita.label} onChange={(v) => api.toggleKita(ds, v || t('abwesenheit.kitaDefaultLabel'), true)} placeholder={t('abwesenheit.occasionPlaceholder')} />
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
  const { t } = useTranslation()
  const [targets, setTargets] = useState<string[]>(userIds.slice())
  const [type, setType] = useState<AbsenceType | null>('URLAUB')
  const [von, setVon] = useState(prefill?.von ?? C.ymd(new Date()))
  const [bis, setBis] = useState(prefill?.bis ?? C.ymd(new Date()))
  const toggleT = (uid: string) => setTargets((tg) => (tg.includes(uid) ? tg.filter((x) => x !== uid) : [...tg, uid]))
  const typeOpts: { id: AbsenceType | null; label: string }[] = [
    { id: 'URLAUB', label: t('abwesenheit.urlaub') },
    { id: 'KRANK', label: t('abwesenheit.krank') },
    { id: 'KIND_KRANK', label: t('abwesenheit.kindKrank') },
    { id: null, label: t('abwesenheit.deleteEntry') },
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
      title={t('abwesenheit.periodTitle')}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>{t('common.cancel')}</Button>
          <Button icon="check" onClick={apply} disabled={dis}>{t('abwesenheit.apply')}</Button>
        </>
      }
    >
      <Field label={t('abwesenheit.forWhom')}>
        <div className="abw-pickrow">
          {userIds.map((uid) => (
            <button key={uid} className={`abw-pick${targets.includes(uid) ? ' is-active' : ''}`} onClick={() => toggleT(uid)}>{nameOf(uid)}</button>
          ))}
        </div>
      </Field>
      <Field label={t('abwesenheit.kind')}>
        <div className="abw-pickrow">
          {typeOpts.map((opt) => (
            <button key={String(opt.id)} className={`abw-pick${type === opt.id ? ' is-active' : ''}`} onClick={() => setType(opt.id)}>{opt.label}</button>
          ))}
        </div>
      </Field>
      <div className="abw-range-dates">
        <Field label={t('abwesenheit.from')}><TextInput type="date" value={von} onChange={setVon} /></Field>
        <Field label={t('abwesenheit.to')}><TextInput type="date" value={bis} onChange={setBis} /></Field>
      </div>
      <div className="hb-muted" style={{ fontSize: 12.5, lineHeight: 1.5 }}>
        {type
          ? `${t('abwesenheit.rangeHint')}${targets[0] ? ` (${t('abwesenheit.rangePreview', { n: String(preview), name: nameOf(targets[0]) })})` : ''}. ${t('abwesenheit.rangeHalfHint')}`
          : t('abwesenheit.rangeClearHint')}
      </div>
    </Modal>
  )
}
