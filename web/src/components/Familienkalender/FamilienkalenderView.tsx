import { useState, useMemo, useCallback, useEffect } from 'react'
import { useTranslation } from 'react-i18next'
import { API_BASE, safeFetch } from '../../api'
import type { Absence, CalendarEvent, CalendarEventType, KitaClosure, MealPlanEntry, Todo } from '../../types'
import { Avatar, Button, Checkbox, IconButton, Modal, PageHead, Sheet } from '../../ui/primitives'
import { Icon } from '../../ui/Icon'
import { CATEGORY_ICON } from '../../lib/cover'
import { dueTimeLabel } from '../../ui/format'
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

// Icon per event kind for the day-detail rows (markers use a coloured chip). Limited to the
// app's existing Icon registry (no dedicated pet/gift glyph) — cake for birthdays, calendar/tag
// otherwise.
const EVENT_ICON: Record<CalendarEventType, string> = {
  APPOINTMENT: 'calendar',
  BIRTHDAY: 'cake',
  VET: 'flag',
  OTHER: 'tag',
}

/** "HH:mm" from an "HH:mm[:ss]" time string (drops seconds); null/empty -> ''. */
const shortTime = (t?: string) => (t ? t.slice(0, 5) : '')

/** Sort key for events within a day: all-day first (no time), then by start time. */
const eventSortKey = (e: CalendarEvent) => (e.allDay || !e.startTime ? '' : e.startTime)

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
  events: CalendarEvent[]
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
  const { todos, absence, meals, events, loading } = useCalendarData(token, onLogout, from, to)

  const todayIso = ymd(new Date())

  // Index everything by day for O(1) cell lookups.
  const buckets = useMemo(() => {
    const map = new Map<string, DayBucket>()
    const ensure = (date: string): DayBucket => {
      let b = map.get(date)
      if (!b) { b = { todos: [], absences: [], meals: [], events: [] }; map.set(date, b) }
      return b
    }
    for (const todo of todos) {
      if (todo.status === 'DONE' || !todo.dueDate) continue
      ensure(todo.dueDate).todos.push(todo)
    }
    for (const a of (absence.absences ?? [])) ensure(a.date).absences.push(a)
    for (const k of (absence.kitaClosures ?? [])) ensure(k.date).kita = k
    for (const m of meals) ensure(m.date).meals.push(m)
    for (const e of events) ensure(e.date).events.push(e)
    // stable order within a day
    for (const b of map.values()) {
      b.meals.sort((x, y) => (MEAL_SLOT_ORDER[x.slot] ?? 9) - (MEAL_SLOT_ORDER[y.slot] ?? 9))
      b.events.sort((x, y) => eventSortKey(x).localeCompare(eventSortKey(y)))
    }
    return map
  }, [todos, absence, meals, events])

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
  // Only show the spinner on the very first load (nothing fetched yet). A month switch keeps the
  // grid on screen and just refills it, so it doesn't flash a spinner.
  const firstLoad = loading && buckets.size === 0

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

      {firstLoad && <p className="hb-muted" style={{ textAlign: 'center', padding: 24 }}>{t('common.loading')}</p>}

      <div className="hb-cal" aria-busy={loading}>
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
  kind: 'todo' | 'absence' | 'meal' | 'event'
  label: string
  hue?: number
}

/** Flattens a day bucket into ordered cell markers (events + absence first, then todos, meals). */
function collectMarkers(b: DayBucket): Marker[] {
  const out: Marker[] = []
  for (const e of b.events) {
    const time = e.allDay || !e.startTime ? '' : `${shortTime(e.startTime)} `
    out.push({ kind: 'event', label: `${time}${e.title}` })
  }
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
      <span className="hb-cal__legitem"><span className="hb-cal__dot hb-cal__chip--event" />{t('familienkalender.catEvents')}</span>
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
  const empty = !bucket || (bucket.todos.length === 0 && bucket.absences.length === 0 && !bucket.kita && bucket.meals.length === 0 && bucket.events.length === 0)

  return (
    <Sheet open onClose={onClose} title={title} width={440}>
      {empty ? (
        <p className="hb-muted" style={{ margin: 0 }}>{t('familienkalender.detailEmpty')}</p>
      ) : (
        <div className="hb-caldetail">
          {bucket!.events.length > 0 && (
            <section className="hb-caldetail__sec">
              <h3 className="hb-caldetail__head"><Icon name="calendar" size={15} stroke={2} />{t('familienkalender.sectionEvents')}</h3>
              {bucket!.events.map((e) => (
                <div key={e.id} className="hb-caldetail__row">
                  <Icon name={EVENT_ICON[e.type] ?? 'calendar'} size={14} stroke={2} />
                  <span className="hb-caldetail__label">
                    {!e.allDay && e.startTime && (
                      <span className="hb-muted">
                        {shortTime(e.startTime)}{e.endTime ? `–${shortTime(e.endTime)}` : ''}{' · '}
                      </span>
                    )}
                    {e.title}
                    {e.location && <span className="hb-muted"> · {e.location}</span>}
                  </span>
                </div>
              ))}
            </section>
          )}

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
                    {dueTimeLabel(todo.dueTime) && <span className="hb-muted">{dueTimeLabel(todo.dueTime)} · </span>}
                    {todo.title}
                    {(todo.assignees?.length ?? 0) > 0 && <span className="hb-muted"> · {todo.assignees!.join(', ')}</span>}
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

  // Which categories THIS user's feed includes (#427). Per-user config; loaded from
  // /config/calendar-feed, and toggling a box auto-saves the full selection (PUT). Boxes stay
  // disabled until the GET lands so a late read can't clobber a fresh toggle.
  const [available, setAvailable] = useState<string[]>([])
  const [selected, setSelected] = useState<Set<string>>(new Set())
  const [loaded, setLoaded] = useState(false)
  const [saveFailed, setSaveFailed] = useState(false)

  useEffect(() => {
    let alive = true
    safeFetch(token, `${API_BASE}/config/calendar-feed`).then(async (result) => {
      if (!alive) return
      if (result.ok && result.res.ok) {
        const data: { sections?: string[]; availableSections?: string[] } = await result.res.json()
        // encodeDefaults=false (CLAUDE.md): an empty selection arrives as a missing key.
        setAvailable(data.availableSections ?? [])
        setSelected(new Set(data.sections ?? []))
      }
      setLoaded(true)
    })
    return () => { alive = false }
  }, [token])

  const toggle = useCallback(async (id: string) => {
    const next = new Set(selected)
    if (next.has(id)) next.delete(id)
    else next.add(id)
    setSelected(next)
    setSaveFailed(false)
    // Persist in the backend's display order so the stored value is stable + readable.
    const sections = available.filter((s) => next.has(s))
    const result = await safeFetch(token, `${API_BASE}/config/calendar-feed`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ sections }),
    })
    if (!result.ok || !result.res.ok) setSaveFailed(true)
  }, [selected, available, token])

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

      {/* Per-category selection (#427): what this subscriber's feed carries. Auto-saves on toggle.
          Deliberately NOT wrapped in a single <label> (that would swallow every row into one name). */}
      {available.length > 0 && (
        <div style={{ marginTop: 18 }}>
          <div className="hb-field__label">{t('familienkalender.subscribeIncludeLabel')}</div>
          <p className="hb-muted" style={{ margin: '2px 0 8px', fontSize: 13 }}>{t('familienkalender.subscribeIncludeHint')}</p>
          <div style={{ display: 'grid', gap: 8 }}>
            {available.map((id) => (
              <label key={id} style={{ display: 'flex', alignItems: 'center', gap: 10, cursor: loaded ? 'pointer' : 'default' }}>
                <Checkbox checked={selected.has(id)} onChange={() => { if (loaded) toggle(id) }} />
                <span>{t(`familienkalender.feedSection.${id}`, { defaultValue: id })}</span>
              </label>
            ))}
          </div>
          {saveFailed && (
            <p style={{ color: 'oklch(0.55 0.16 32)', fontSize: 13.5, margin: '8px 0 0' }}>
              {t('familienkalender.subscribeSaveFailed')}
            </p>
          )}
        </div>
      )}
    </Modal>
  )
}
