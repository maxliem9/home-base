// Abwesenheit grids — Jahresraster (year grid) + Monatskalender (month view).
// Ported from the design handoff (abw_grid.jsx).
import type { MouseEvent, ReactNode } from 'react'
import { Icon } from '../../ui/Icon'
import { userMeta } from '../../ui/format'
import { t } from '../../i18n'
import * as C from './holidays'
import { type Ctx, type DayState, type Palette, TYPES, cellBg, colorFor, personDay, statusLabel } from './core'

const nameOf = (uid: string): string => userMeta(uid)?.name ?? uid
const initialsOf = (uid: string): string => userMeta(uid)?.initials ?? uid.slice(0, 1).toUpperCase()
const ctxHue = (uid: string): number => userMeta(uid)?.hue ?? 150

type Pick = (ds: string, e: MouseEvent) => void

interface GridProps {
  ctx: Ctx
  pal: Palette
  userIds: string[]
  today: string
  onPick: Pick
}

/* ---------- Legend ---------- */
export function AbwLegend({ userIds, pal }: { userIds: string[]; pal: Palette }) {
  const items: { sw: string; label: string }[] = [
    { sw: 'split', label: t.abwesenheit.legendUrlaub },
    { sw: pal.KRANK, label: t.abwesenheit.legendKrank },
    { sw: pal.KIND_KRANK, label: t.abwesenheit.legendKind },
    { sw: pal.FEIERTAG, label: t.abwesenheit.legendFeiertag },
    { sw: pal.teilzeit(220), label: t.abwesenheit.legendTeilzeit },
    { sw: pal.WEEKEND, label: t.abwesenheit.legendWeekend },
    { sw: 'kita', label: t.abwesenheit.legendKita },
  ]
  return (
    <div className="abw-legend">
      {items.map((it, i) => (
        <span key={i} className="abw-legend__item">
          {it.sw === 'split' ? (
            <span className="abw-legend__sw" style={{ background: cellBg(pal.urlaub(ctxHue(userIds[0])), pal.urlaub(ctxHue(userIds[1] ?? userIds[0]))) }} />
          ) : it.sw === 'kita' ? (
            <span className="abw-legend__sw abw-legend__sw--kita" />
          ) : (
            <span className="abw-legend__sw" style={{ background: it.sw }} />
          )}
          {it.label}
        </span>
      ))}
    </div>
  )
}

/* =================== JAHRESRASTER =================== */
export function JahresRaster({ ctx, pal, userIds, today, onPick }: GridProps) {
  const year = ctx.year
  const [uA, uB] = userIds
  const months = C.MON_ABBR
  const cells: ReactNode[] = []

  // header row
  cells.push(<div key="corner" className="abw-rcell abw-rcell--corner" />)
  months.forEach((m, mi) => cells.push(<div key={'h' + mi} className="abw-rcell abw-rcell--mhead">{m}</div>))

  for (let d = 1; d <= 31; d++) {
    cells.push(<div key={'d' + d} className="abw-rcell abw-rcell--dhead hb-mono">{d}</div>)
    for (let mi = 0; mi < 12; mi++) {
      if (d > C.daysInMonth(year, mi)) {
        cells.push(<div key={mi + '_' + d} className="abw-rcell abw-rcell--void" />)
        continue
      }
      const ds = `${year}-${C.pad(mi + 1)}-${C.pad(d)}`
      const a = personDay(ctx, uA, ds)
      const b = personDay(ctx, uB, ds)
      const bg = cellBg(colorFor(pal, a), colorFor(pal, b))
      const kita = ctx.kita[ds]
      const isToday = ds === today
      // Custom holidays are household-wide and only apply when no statutory holiday masks
      // the day (statutory wins in personDay). Surface the custom name + ½ in the tooltip.
      const custom = ctx.customHol[ds.slice(5)]
      const showCustom = custom && !ctx.holidays[uA][ds]
      const halfHol = a.holidayHalf
      const title = `${ds} · ${nameOf(uA)}: ${statusLabel(a)} · ${nameOf(uB)}: ${statusLabel(b)}`
        + (showCustom ? ` · ${custom.label}${custom.half ? ' (½)' : ''}` : '')
        + (kita ? ` · ${t.abwesenheit.kitaShort}: ${kita.label}` : '')
      cells.push(
        <button
          key={mi + '_' + d}
          className={`abw-rcell abw-rcell--day${isToday ? ' is-today' : ''}${kita ? ' is-kita' : ''}`}
          style={{ background: bg }}
          title={title}
          onClick={(e) => onPick(ds, e)}
        >
          {a.half ? <span className="abw-rcell__h abw-rcell__h--a">{a.half === 'vm' ? 'AM' : 'PM'}</span> : null}
          {b.half ? <span className="abw-rcell__h abw-rcell__h--b">{b.half === 'vm' ? 'AM' : 'PM'}</span> : null}
          {halfHol ? <span className="abw-rcell__half" aria-hidden="true">½</span> : null}
        </button>,
      )
    }
  }
  return (
    <div className="abw-raster" role="grid" aria-label={'Jahresübersicht ' + year}>
      {cells}
    </div>
  )
}

/* =================== MONATSKALENDER =================== */
function MonatsChip({ st, uid, pal }: { st: DayState; uid: string; pal: Palette }) {
  let bg: string
  let fg: string
  let label: string
  if (st.type) {
    bg = st.type === 'URLAUB' ? pal.urlaub(st.hue) : pal[st.type]
    fg = pal.dark ? pal.onLight : 'oklch(0.99 0.01 150)'
    label = (st.half ? (st.half === 'vm' ? 'AM ' : 'PM ') : '') + TYPES[st.type].label
  } else if (st.holiday) {
    bg = pal.FEIERTAG
    fg = pal.onLight
    // ½ prefix marks a half-day custom holiday (#51); statutory ones are full days.
    label = (st.holidayHalf ? '½ ' : '') + st.holiday
  } else if (st.ptOff) {
    bg = pal.teilzeit(st.hue)
    fg = pal.onLight
    label = t.abwesenheit.frei
  } else {
    return null
  }
  return (
    <span className="abw-mchip" style={{ background: bg, color: fg }}>
      <span
        className="abw-mchip__who"
        style={{ background: `oklch(${pal.dark ? '0.3' : '0.99'} 0.02 ${st.hue} / ${pal.dark ? 0.55 : 0.65})` }}
      >
        {initialsOf(uid)}
      </span>
      <span className="abw-mchip__txt">{label}</span>
    </span>
  )
}

interface MonthProps extends GridProps {
  month: number
  setMonth: (m: number) => void
}

export function MonatsKalender({ ctx, pal, userIds, today, onPick, month, setMonth }: MonthProps) {
  const year = ctx.year
  const first = new Date(year, month, 1, 12)
  const lead = (first.getDay() + 6) % 7 // Mon = 0
  const gridStart = C.addDays(first, -lead)

  const weeks: ReactNode[] = []
  for (let w = 0; w < 6; w++) {
    const row: ReactNode[] = []
    for (let dow = 0; dow < 7; dow++) {
      const date = C.addDays(gridStart, w * 7 + dow)
      const ds = C.ymd(date)
      const inMonth = date.getMonth() === month
      const isToday = ds === today
      const weekend = C.isWeekend(date)
      const kita = ctx.kita[ds]
      row.push(
        <button
          key={ds}
          className={`abw-mcell${inMonth ? '' : ' is-out'}${weekend ? ' is-weekend' : ''}${isToday ? ' is-today' : ''}`}
          onClick={(e) => onPick(ds, e)}
        >
          <div className="abw-mcell__top">
            <span className={`abw-mcell__num hb-mono${isToday ? ' is-today' : ''}`}>{date.getDate()}</span>
            {kita ? <span className="abw-mcell__kita" title={`${t.abwesenheit.kitaShort}: ${kita.label}`}>{t.abwesenheit.kitaShort}</span> : null}
          </div>
          <div className="abw-mcell__chips">
            {userIds.map((uid) => <MonatsChip key={uid} st={personDay(ctx, uid, ds)} uid={uid} pal={pal} />)}
          </div>
        </button>,
      )
    }
    weeks.push(<div key={w} className="abw-mrow">{row}</div>)
    const lastInRow = C.addDays(gridStart, w * 7 + 6)
    if (lastInRow.getMonth() !== month && lastInRow > first && w >= 4) break
  }

  return (
    <div className="abw-month">
      <div className="abw-mnav">
        <button className="hb-iconbtn" onClick={() => setMonth(month - 1 < 0 ? 11 : month - 1)} aria-label={t.abwesenheit.prevMonth}>
          <Icon name="chevronLeft" size={18} stroke={2} />
        </button>
        <div className="abw-mnav__title">{C.MON_FULL[month]} <span>{year}</span></div>
        <button className="hb-iconbtn" onClick={() => setMonth(month + 1 > 11 ? 0 : month + 1)} aria-label={t.abwesenheit.nextMonth}>
          <Icon name="chevronRight" size={18} stroke={2} />
        </button>
      </div>
      <div className="abw-mhead">
        {C.WD_MIN.map((wd, i) => <div key={wd} className={`abw-mhead__c${i >= 5 ? ' is-we' : ''}`}>{wd}</div>)}
      </div>
      <div className="abw-mgrid">{weeks}</div>
    </div>
  )
}
