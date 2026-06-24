import { useState, useMemo, useCallback } from 'react'
import { useTranslation } from 'react-i18next'
import { API_BASE } from '../../api'
import type { Absence, KitaClosure, MealPlanEntry, Todo } from '../../types'
import { Avatar, Button, IconButton, Modal, PageHead, Sheet } from '../../ui/primitives'
import { Icon } from '../../ui/Icon'
import { CATEGORY_ICON } from '../../lib/cover'
import { useCalendarData } from './useCalendarData'
import { TYPES } from '../abwesenheit/core'
import './familienkalender.css'

const pad = (n: number) => String(n).padStart(2, '0')
/** Local YYYY-MM-DD (matches the app's date keying everywhere else). */
const ymd = (d: Date) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
const addDays = (d: Date, n: number) => new Date(d.getFullYear(), d.getMonth(), d.getDate() + n)
/** Monday-based weekday index (Mon = 0 … Sun = 6). */
const mondayIdx = (d: Date) => (d.getDay() + 6) % 7

const MEAL_SLOT_ORDER: Record<string, number> = { BREAKFAST: 0, LUNCH: 1, DINNER: 2 }

// Markers per day cell before the rest collapse into a "+N" chip — keeps a packed day readable.
const MAX_MARKERS = 4

interface FamilienkalenderViewProps {
  token: string
  onLogout: () => void
}

/** Everything happening on one day, already grouped by domain. */
interface DayBucket {
  todos: Todo[]
  absences: Absence[]
  kita?: KitaClosure
  meals: MealPlanEntry[]
}

export function FamilienkalenderView({ token, onLogout }: FamilienkalenderViewProps) {
  const { t } = useTranslation()
  // The visible month, anchored to its first day. Kept as a Date; nav steps whole months.
  const [monthAnchor, setMonthAnchor] = useState(() => {
    const now = new Date()
    return new Date(now.getFullYear(), now.getMonth(), 1)
  })
  const [openDay, setOpenDay] = useState<string | null>(null)
  const [subscribing, setSubscribing] = useState(false)

  // The grid spans whole weeks (Mon..Sun) around the month, so it always renders a clean
  // rectangle. Fetch exactly that visible range.
  const gridDays = useMemo(() => {
    const first = new Date(monthAnchor.getFullYear(), monthAnchor.getMonth(), 1)
    const start = addDays(first, -mondayIdx(first))
    // 6 weeks always covers any month layout; trailing empty weeks are trimmed below.
    const raw = Array.from({ length: 42 }, (_, i) => addDays(start, i))
    // Trim a fully-trailing 6th week that belongs entirely to the next month (keeps the grid tight).
    const weeks: Date[][] = []
    for (let i = 0; i < raw.length; i += 7) weeks.push(raw.slice(i, i + 7))
    const trimmed = weeks.filter((w) => w.some((d) => d.getMonth() === monthAnchor.getMonth()))
    return trimmed.flat()
  }, [monthAnchor])

  const from = ymd(gridDays[0])
  const to = ymd(gridDays[gridDays.length - 1])
  const { todos, absence, meals } = useCalendarData(token, onLogout, from, to)

  const todayIso = ymd(new Date())

  // Index everything by day for O(1) cell lookups.
  const buckets = useMemo(() => {
    const map = new Map<string, DayBucket>()
    const ensure = (date: string): DayBucket => {
      let b = map.get(date)
      if (!b) { b = { todos: [], absences: [], meals: [] }; map.set(date, b) }
      return b
    }
    for (const todo of todos) {
      if (todo.status === 'DONE' || !todo.dueDate) continue
      ensure(todo.dueDate).todos.push(todo)
    }
    for (const a of absence.absences) ensure(a.date).absences.push(a)
    for (const k of absence.kitaClosures) ensure(k.date).kita = k
    for (const m of meals) ensure(m.date).meals.push(m)
    // stable meal order within a day
    for (const b of map.values()) {
      b.meals.sort((x, y) => (MEAL_SLOT_ORDER[x.slot] ?? 9) - (MEAL_SLOT_ORDER[y.slot] ?? 9))
    }
    return map
  }, [todos, absence, meals])

  const shiftMonth = useCallback((delta: number) => {
    setMonthAnchor((m) => new Date(m.getFullYear(), m.getMonth() + delta, 1))
  }, [])
  const goToday = useCallback(() => {
    const now = new Date()
    setMonthAnchor(new Date(now.getFullYear(), now.getMonth(), 1))
  }, [])

  const monthTitle = useMemo(
    () => new Intl.DateTimeFormat(undefined, { month: 'long', year: 'numeric' }).format(monthAnchor),
    [monthAnchor],
  )
  const weekdays = t('familienkalender.weekdays', { returnObjects: true }) as string[]

  const openBucket = openDay ? buckets.get(openDay) : undefined

  return (
    <div className="hb-page">
      <PageHead
        eyebrow={t('familienkalender.eyebrow')}
        title={t('familienkalender.title')}
        actions={
          <Button variant="soft" icon="calendar" onClick={() => setSubscribing(true)}>
            {t('familienkalender.subscribe')}
          </Button>
        }
      />

      <div className="hb-weeknav" role="group" aria-label={t('familienkalender.monthNav')}>
        <IconButton icon="chevronLeft" label={t('familienkalender.prevMonth')} onClick={() => shiftMonth(-1)} />
        <div className="hb-weeknav__label">
          <span className="hb-weeknav__range" style={{ textTransform: 'capitalize' }}>{monthTitle}</span>
        </div>
        <IconButton icon="chevronRight" label={t('familienkalender.nextMonth')} onClick={() => shiftMonth(1)} />
        <Button variant="ghost" size="sm" onClick={goToday}>{t('familienkalender.today')}</Button>
      </div>

      <div className="hb-cal">
        <div className="hb-cal__head" aria-hidden="true">
          {weekdays.map((wd) => (
            <div key={wd} className="hb-cal__wd">{wd}</div>
          ))}
        </div>
        <div className="hb-cal__grid">
          {gridDays.map((d) => {
            const date = ymd(d)
            const inMonth = d.getMonth() === monthAnchor.getMonth()
            const bucket = buckets.get(date)
            const markers = bucket ? collectMarkers(bucket) : []
            const shown = markers.slice(0, MAX_MARKERS)
            const overflow = markers.length - shown.length
            const isToday = date === todayIso
            return (
              <button
                key={date}
                type="button"
                className={`hb-cal__day${inMonth ? '' : ' is-outside'}${isToday ? ' is-today' : ''}${bucket?.kita ? ' has-kita' : ''}`}
                onClick={() => setOpenDay(date)}
                aria-label={new Intl.DateTimeFormat(undefined, { dateStyle: 'full' }).format(d)}
                data-date={date}
              >
                <span className="hb-cal__dnum">{d.getDate()}</span>
                {bucket?.kita && <span className="hb-cal__kita" title={bucket.kita.label}>{t('familienkalender.catKita')}</span>}
                {markers.length > 0 && (
                  <span className="hb-cal__markers">
                    {shown.map((m, i) => (
                      <span
                        key={i}
                        className={`hb-cal__chip hb-cal__chip--${m.kind}`}
                        style={m.hue != null ? { ['--chip-hue' as string]: m.hue } : undefined}
                        title={m.label}
                      >
                        <span className="hb-cal__chiptext">{m.label}</span>
                      </span>
                    ))}
                    {overflow > 0 && <span className="hb-cal__more">{t('familienkalender.moreCount', { count: overflow })}</span>}
                  </span>
                )}
              </button>
            )
          })}
        </div>
      </div>

      <CalendarLegend />

      {openDay && (
        <DayDetailSheet
          dateIso={openDay}
          bucket={openBucket}
          onClose={() => setOpenDay(null)}
        />
      )}

      {subscribing && <SubscribeModal token={token} onClose={() => setSubscribing(false)} />}
    </div>
  )
}

// --- markers ----------------------------------------------------------------

interface Marker {
  kind: 'todo' | 'absence' | 'meal'
  label: string
  hue?: number
}

/** Flattens a day bucket into ordered cell markers (absence first, then todos, then meals). */
function collectMarkers(b: DayBucket): Marker[] {
  const out: Marker[] = []
  for (const a of b.absences) {
    out.push({ kind: 'absence', label: absenceLabel(a), hue: a.type === 'URLAUB' ? 150 : undefined })
  }
  for (const todo of b.todos) out.push({ kind: 'todo', label: todo.title })
  for (const m of b.meals) out.push({ kind: 'meal', label: m.recipeTitle ?? m.dishTitle ?? '' })
  return out
}

function absenceLabel(a: Absence): string {
  const base = TYPES[a.type]?.short ?? a.type
  return a.half ? `${base} ${a.userId} ½` : `${base} ${a.userId}`
}

// --- legend -----------------------------------------------------------------

function CalendarLegend() {
  const { t } = useTranslation()
  return (
    <div className="hb-cal__legend" aria-label={t('familienkalender.legend')}>
      <span className="hb-cal__legitem"><span className="hb-cal__dot hb-cal__chip--absence" />{t('familienkalender.catAbsence')}</span>
      <span className="hb-cal__legitem"><span className="hb-cal__dot hb-cal__chip--todo" />{t('familienkalender.catTodos')}</span>
      <span className="hb-cal__legitem"><span className="hb-cal__dot hb-cal__chip--meal" />{t('familienkalender.catMeals')}</span>
      <span className="hb-cal__legitem"><span className="hb-cal__dot hb-cal__dot--kita" />{t('familienkalender.catKita')}</span>
    </div>
  )
}

// --- day detail -------------------------------------------------------------

function DayDetailSheet({ dateIso, bucket, onClose }: { dateIso: string; bucket?: DayBucket; onClose: () => void }) {
  const { t } = useTranslation()
  const title = new Intl.DateTimeFormat(undefined, { weekday: 'long', day: 'numeric', month: 'long' }).format(
    new Date(dateIso + 'T00:00:00'),
  )
  const empty = !bucket || (bucket.todos.length === 0 && bucket.absences.length === 0 && !bucket.kita && bucket.meals.length === 0)

  return (
    <Sheet open onClose={onClose} title={title} width={440}>
      {empty ? (
        <p className="hb-muted" style={{ margin: 0 }}>{t('familienkalender.detailEmpty')}</p>
      ) : (
        <div className="hb-caldetail">
          {bucket!.absences.length > 0 && (
            <section className="hb-caldetail__sec">
              <h3 className="hb-caldetail__head"><Icon name="sun" size={15} stroke={2} />{t('familienkalender.sectionAbsence')}</h3>
              {bucket!.absences.map((a) => (
                <div key={a.id} className="hb-caldetail__row">
                  <Avatar user={a.userId} size={22} />
                  <span className="hb-caldetail__label">
                    {a.userId} · {TYPES[a.type]?.label ?? a.type}
                    {a.half && <span className="hb-muted"> ({t(`familienkalender.half.${a.half}`)})</span>}
                  </span>
                </div>
              ))}
            </section>
          )}

          {bucket!.kita && (
            <section className="hb-caldetail__sec">
              <h3 className="hb-caldetail__head"><Icon name="home" size={15} stroke={2} />{t('familienkalender.sectionKita')}</h3>
              <div className="hb-caldetail__row"><span className="hb-caldetail__label">{bucket!.kita.label}</span></div>
            </section>
          )}

          {bucket!.todos.length > 0 && (
            <section className="hb-caldetail__sec">
              <h3 className="hb-caldetail__head"><Icon name="checkCircle" size={15} stroke={2} />{t('familienkalender.sectionTodos')}</h3>
              {bucket!.todos.map((todo) => (
                <div key={todo.id} className="hb-caldetail__row">
                  <Icon name="circle" size={14} stroke={2} />
                  <span className="hb-caldetail__label">
                    {todo.title}
                    {todo.assignee && <span className="hb-muted"> · {todo.assignee}</span>}
                  </span>
                </div>
              ))}
            </section>
          )}

          {bucket!.meals.length > 0 && (
            <section className="hb-caldetail__sec">
              <h3 className="hb-caldetail__head"><Icon name="utensils" size={15} stroke={2} />{t('familienkalender.sectionMeals')}</h3>
              {bucket!.meals.map((m) => (
                <div key={m.id} className="hb-caldetail__row">
                  <Icon name={m.recipeId ? (m.recipeCategory ? CATEGORY_ICON[m.recipeCategory] ?? 'utensils' : 'utensils') : 'edit'} size={14} stroke={2} />
                  <span className="hb-caldetail__label">
                    <span className="hb-muted">{t(`wochenplan.slots.${m.slot}`)}: </span>
                    {m.recipeTitle ?? m.dishTitle}
                  </span>
                </div>
              ))}
            </section>
          )}
        </div>
      )}
    </Sheet>
  )
}

// --- subscribe modal --------------------------------------------------------

function SubscribeModal({ token, onClose }: { token: string; onClose: () => void }) {
  const { t } = useTranslation()
  const [copied, setCopied] = useState(false)
  // The token rides in the query string (calendar apps can't set headers) — same path as
  // note-image loads. Absolute URL so a subscriber can paste it straight into Apple/Google.
  const feedUrl = `${window.location.origin}${API_BASE}/calendar.ics?token=${encodeURIComponent(token)}`

  const copy = useCallback(async () => {
    try {
      await navigator.clipboard.writeText(feedUrl)
      setCopied(true)
      setTimeout(() => setCopied(false), 2000)
    } catch {
      // clipboard blocked (insecure context / permissions) — the field is selectable as a fallback
    }
  }, [feedUrl])

  return (
    <Modal
      open
      onClose={onClose}
      title={t('familienkalender.subscribeTitle')}
      width={520}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>{t('common.close')}</Button>
          <Button variant="primary" icon="check" onClick={copy}>
            {copied ? t('familienkalender.subscribeCopied') : t('familienkalender.subscribeCopy')}
          </Button>
        </>
      }
    >
      <p style={{ marginTop: 0 }}>{t('familienkalender.subscribeIntro')}</p>
      <input
        className="hb-input"
        type="text"
        value={feedUrl}
        readOnly
        onFocus={(e) => e.currentTarget.select()}
        aria-label={t('familienkalender.subscribeTitle')}
      />
      <p className="hb-muted" style={{ marginBottom: 0, fontSize: 13 }}>{t('familienkalender.subscribeNote')}</p>
    </Modal>
  )
}
