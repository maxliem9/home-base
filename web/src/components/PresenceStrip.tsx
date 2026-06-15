// HB-01 — "Wer ist da?" presence strip on the dashboard. A read-only weekly
// (Mon–Fri) presence card per household member, colour-coded with the existing
// absence palette. Reuses the absence data layer (useAbsenceData) so it stays in
// sync over the same WS channel as the calendar; it never mutates.
import { Fragment, useMemo } from 'react'
import { useTranslation } from 'react-i18next'
import { Avatar, Card } from '../ui/primitives'
import { Icon } from '../ui/Icon'
import { userMeta } from '../ui/format'
import { useAbsenceData } from './abwesenheit/useAbsenceData'
import * as C from './abwesenheit/holidays'
import { TYPES, buildContext, colorFor, palette, personDay, statusLabel, type Ctx, type Theme } from './abwesenheit/core'

const nameOf = (uid: string): string => userMeta(uid)?.name ?? uid
const currentTheme = (): Theme => (document.documentElement.getAttribute('data-theme') === 'dark' ? 'dark' : 'light')

/** Monday (noon, DST-safe) of the week containing `d`. */
function mondayOf(d: Date): Date {
  const dow = (d.getDay() + 6) % 7 // Mon = 0
  return new Date(d.getFullYear(), d.getMonth(), d.getDate() - dow, 12)
}

interface Props {
  token: string
  onLogout: () => void
  /** open the full absence calendar (this week) */
  onOpen: () => void
}

export function PresenceStrip({ token, onLogout, onOpen }: Props) {
  const { t } = useTranslation()
  const { data, loading } = useAbsenceData(token, onLogout)
  const theme = currentTheme()
  const pal = useMemo(() => palette(theme), [theme])
  const userIds = data.users

  const today = C.ymd(new Date())
  // Mon–Fri of the current week.
  const weekDays = useMemo(() => {
    const mon = mondayOf(new Date())
    return Array.from({ length: 5 }, (_, i) => C.addDays(mon, i))
  }, [])

  // A Mon–Fri stretch can straddle a year boundary (e.g. late December). buildContext is
  // keyed to a single year, so build one context per year present in the week and resolve
  // each day against its own — otherwise a January day would lose its holidays/absences.
  const ctxByYear = useMemo(() => {
    const m = new Map<number, Ctx>()
    Array.from(new Set(weekDays.map((d) => d.getFullYear()))).forEach((y) => m.set(y, buildContext(data, y, userIds)))
    return m
  }, [data, userIds, weekDays])
  const dayFor = (uid: string, ds: string) => personDay(ctxByYear.get(Number(ds.slice(0, 4)))!, uid, ds)

  const kitaByDate = useMemo(() => {
    const m = new Map<string, string>()
    data.kitaClosures.forEach((k) => m.set(k.date, k.label))
    return m
  }, [data.kitaClosures])

  // Until the first snapshot resolves — or for a household with no members — render
  // nothing rather than an empty frame (matches the rest of the dashboard's quiet loads).
  if (loading || userIds.length === 0) return null

  const cols = `minmax(76px, 0.8fr) repeat(5, 1fr)`

  return (
    <Card className="hb-card--pad hb-presence">
      <div className="hb-cardhead">
        <h3>
          <Icon name="users" size={17} stroke={2} style={{ verticalAlign: '-3px', marginRight: 7, color: 'var(--accent)' }} />
          {t('dashboard.presenceTitle')}
        </h3>
        <button className="hb-link" onClick={onOpen}>
          {t('dashboard.presenceOpen')} <Icon name="chevronRight" size={15} stroke={2.2} />
        </button>
      </div>

      <div className="hb-presence__grid" style={{ gridTemplateColumns: cols }}>
        {/* header row: corner + weekday columns */}
        <div className="hb-presence__corner">{t('dashboard.presenceWeek')}</div>
        {weekDays.map((d) => {
          const ds = C.ymd(d)
          const isToday = ds === today
          const kita = kitaByDate.get(ds)
          return (
            <div key={ds} className={`hb-presence__dh${isToday ? ' is-today' : ''}`}>
              <span className="hb-presence__wd">{C.WD_MIN[(d.getDay() + 6) % 7]}</span>
              <span className="hb-presence__date">{d.getDate()}</span>
              {kita && (
                <span className="hb-presence__kita" title={`${t('dashboard.presenceKita')} · ${kita}`}>
                  {t('dashboard.presenceKita')}
                </span>
              )}
            </div>
          )
        })}

        {/* one row per member */}
        {userIds.map((uid) => (
          <Fragment key={uid}>
            <div className="hb-presence__name">
              <Avatar user={uid} size={26} />
              <span>{nameOf(uid)}</span>
            </div>
            {weekDays.map((d) => {
              const ds = C.ymd(d)
              const st = dayFor(uid, ds)
              const isToday = ds === today
              const short = st.type ? TYPES[st.type].short : null
              const label = `${nameOf(uid)} · ${C.WD_MIN[(d.getDay() + 6) % 7]} ${d.getDate()}.: ${st.half ? '½ ' : ''}${statusLabel(st)}`
              return (
                <div
                  key={ds}
                  className={`hb-presence__cell${isToday ? ' is-today' : ''}${st.type ? ' is-absent' : ''}`}
                  style={{ background: colorFor(pal, st), color: pal.onLight }}
                  title={label}
                >
                  {short && <span className="hb-presence__short">{short}{st.half ? '½' : ''}</span>}
                </div>
              )
            })}
          </Fragment>
        ))}
      </div>

      {/* compact legend — category colours + Kita marker */}
      <div className="hb-presence__legend">
        <span className="hb-presence__leg"><i style={{ background: pal.urlaub(40) }} />{t('abwesenheit.legendUrlaub')}</span>
        <span className="hb-presence__leg"><i style={{ background: pal.KRANK }} />{t('abwesenheit.legendKrank')}</span>
        <span className="hb-presence__leg"><i style={{ background: pal.KIND_KRANK }} />{t('abwesenheit.legendKind')}</span>
        <span className="hb-presence__leg"><i style={{ background: pal.FEIERTAG }} />{t('abwesenheit.legendFeiertag')}</span>
        <span className="hb-presence__leg hb-presence__leg--kita"><i />{t('dashboard.presenceKita')}</span>
      </div>
    </Card>
  )
}
