// Abwesenheit: data model, palette + summary math.
// Ported from the design handoff (abw_core.jsx → ABW). Pure, framework-free.
import { userMeta } from '../../ui/format'
import type { AbsSettings, Absence, AbsenceState, AbsenceType, HalfDay, KitaClosure, PartTimeRule } from '../../types'
import * as C from './holidays'

export type Theme = 'light' | 'dark'

// Markable, explicit day-types (Feiertag + Teilzeit are *derived*, not stored).
export const TYPES: Record<AbsenceType, { id: AbsenceType; label: string; short: string }> = {
  URLAUB: { id: 'URLAUB', label: 'Urlaub', short: 'U' },
  KRANK: { id: 'KRANK', label: 'Krank', short: 'K' },
  KIND_KRANK: { id: 'KIND_KRANK', label: 'Kind-krank', short: 'KK' },
}

export interface Palette {
  dark: boolean
  urlaub: (hue: number) => string
  KRANK: string
  KIND_KRANK: string
  FEIERTAG: string
  teilzeit: (hue: number) => string
  WEEKEND: string
  WORKDAY: string
  ink: string
  onLight: string
}

export interface PaletteOpts {
  krank?: number
  kind?: number
  feier?: number
}

/** Theme-aware fill palette. urlaub takes the person hue; the rest from configurable hues. */
export function palette(theme: Theme, opts: PaletteOpts = {}): Palette {
  const dark = theme === 'dark'
  const hK = opts.krank != null ? opts.krank : 27
  const hKK = opts.kind != null ? opts.kind : 62
  const hF = opts.feier != null ? opts.feier : 288
  return {
    dark,
    urlaub: (hue: number) => (dark ? `oklch(0.56 0.105 ${hue})` : `oklch(0.7 0.108 ${hue})`),
    KRANK: dark ? `oklch(0.56 0.13 ${hK})` : `oklch(0.71 0.13 ${hK})`,
    KIND_KRANK: dark ? `oklch(0.62 0.115 ${hKK})` : `oklch(0.78 0.125 ${hKK})`,
    FEIERTAG: dark ? `oklch(0.5 0.045 ${hF})` : `oklch(0.82 0.05 ${hF})`,
    teilzeit: (hue: number) => (dark ? `oklch(0.39 0.035 ${hue})` : `oklch(0.91 0.034 ${hue})`),
    WEEKEND: dark ? 'oklch(0.29 0.008 150)' : 'oklch(0.925 0.006 130)',
    WORKDAY: 'var(--surface)',
    ink: dark ? 'oklch(0.16 0.02 150)' : 'oklch(0.99 0.01 150)',
    onLight: dark ? 'oklch(0.95 0.01 150)' : 'oklch(0.3 0.03 150)',
  }
}

export interface DayState {
  hue: number
  type: AbsenceType | null
  half: HalfDay | null
  holiday: string | null
  weekend: boolean
  ptOff: boolean
}

/** colour for a person's resolved day-state. */
export function colorFor(pal: Palette, st: DayState | null): string {
  if (!st) return pal.WORKDAY
  if (st.type) return st.type === 'URLAUB' ? pal.urlaub(st.hue) : pal[st.type]
  if (st.holiday) return pal.FEIERTAG
  if (st.ptOff) return pal.teilzeit(st.hue)
  if (st.weekend) return pal.WEEKEND
  return pal.WORKDAY
}

/** is this user off this weekday under a part-time rule active on `date`? */
export function partTimeOff(rules: PartTimeRule[], userId: string, date: Date, dateStr: string): boolean {
  const wd = C.isoDow(date) // 1..7
  return rules.some(
    (r) => r.userId === userId && r.weekday === wd && dateStr >= r.start && (!r.end || dateStr <= r.end),
  )
}

const hueOf = (userId: string): number => userMeta(userId)?.hue ?? 150

function defaultSettings(userId: string, year: number): AbsSettings {
  return { userId, state: 'BE', allowance: 30, carryover: 0, carryoverExpires: `${year}-03-31`, kindKrankCap: 15 }
}

export interface Ctx {
  year: number
  settings: Record<string, AbsSettings>
  holidays: Record<string, Record<string, string>>
  absByUser: Record<string, Record<string, Absence>>
  kita: Record<string, KitaClosure>
  parttime: PartTimeRule[]
  hue: Record<string, number>
}

/** Build a lookup context once per render: holidays per user, absence map, etc. */
export function buildContext(state: AbsenceState, year: number, users: string[]): Ctx {
  const settings: Record<string, AbsSettings> = {}
  const holidays: Record<string, Record<string, string>> = {}
  const absByUser: Record<string, Record<string, Absence>> = {}
  const hue: Record<string, number> = {}
  users.forEach((uid) => {
    const s = state.settings.find((x) => x.userId === uid) ?? defaultSettings(uid, year)
    settings[uid] = s
    holidays[uid] = C.holidays(year, s.state)
    absByUser[uid] = {}
    hue[uid] = hueOf(uid)
  })
  state.absences.forEach((a) => {
    if (a.date.slice(0, 4) !== String(year)) return
    if (!absByUser[a.userId]) absByUser[a.userId] = {}
    absByUser[a.userId][a.date] = a
  })
  const kita: Record<string, KitaClosure> = {}
  state.kitaClosures.forEach((k) => {
    kita[k.date] = k
  })
  return { year, settings, holidays, absByUser, kita, parttime: state.partTime, hue }
}

/** Resolve a single person's day. */
export function personDay(ctx: Ctx, userId: string, dateStr: string): DayState {
  const date = C.parse(dateStr)
  const hue = ctx.hue[userId] != null ? ctx.hue[userId] : hueOf(userId)
  const abs = ctx.absByUser[userId] && ctx.absByUser[userId][dateStr]
  const holiday = ctx.holidays[userId][dateStr] || null
  const weekend = C.isWeekend(date)
  const ptOff = partTimeOff(ctx.parttime, userId, date, dateStr)
  return {
    hue,
    type: abs ? abs.type : null,
    half: abs ? abs.half || null : null,
    holiday,
    weekend,
    ptOff,
  }
}

/** would this be a working day absent any leave? (used for counting) */
export const wouldWork = (st: DayState): boolean => !st.weekend && !st.holiday && !st.ptOff

export interface Summary {
  allowance: number
  carry: number
  total: number
  taken: number
  planned: number
  used: number
  remaining: number
  krank: number
  kind: number
  kindCap: number
  state: string
  carryExpires?: string | null
  carryExpired: boolean
  carryLost: number
}

/** Per-person yearly summary. */
export function summarize(ctx: Ctx, userId: string, todayStr: string): Summary {
  const s = ctx.settings[userId]
  let taken = 0
  let planned = 0
  let krank = 0
  let kind = 0
  C.yearDates(ctx.year).forEach((ds) => {
    const st = personDay(ctx, userId, ds)
    if (!st.type) return
    const amt = st.half ? 0.5 : 1
    if (!wouldWork(st)) return // leave on an already-free day doesn't count
    if (st.type === 'URLAUB') {
      if (ds <= todayStr) taken += amt
      else planned += amt
    } else if (st.type === 'KRANK') krank += amt
    else if (st.type === 'KIND_KRANK') kind += amt
  })
  const allowance = s.allowance || 0
  const carry = s.carryover || 0
  const total = allowance + carry
  const used = taken + planned
  const remaining = total - used
  const carryExpired = todayStr > (s.carryoverExpires || `${ctx.year}-03-31`)
  const carryUsed = Math.min(carry, taken)
  const carryLost = carryExpired ? Math.max(0, carry - carryUsed) : 0
  return {
    allowance,
    carry,
    total,
    taken,
    planned,
    used,
    remaining,
    krank,
    kind,
    kindCap: s.kindKrankCap,
    state: s.state,
    carryExpires: s.carryoverExpires,
    carryExpired,
    carryLost,
  }
}

/** pretty day count: "3", "2,5" */
export const fmtDays = (n: number): string => (Number.isInteger(n) ? String(n) : n.toFixed(1).replace('.', ','))

/** inclusive list of date-strings from→to. */
export function eachDate(from: string, to: string): string[] {
  if (from > to) {
    const t = from
    from = to
    to = t
  }
  const out: string[] = []
  let d = C.parse(from)
  const end = C.parse(to)
  while (d <= end) {
    out.push(C.ymd(d))
    d = C.addDays(d, 1)
  }
  return out
}

/** would this date be a working day for this user (not weekend / holiday / part-time-off)? */
export function isWorkdayFor(state: AbsenceState, userId: string, ds: string): boolean {
  const s = state.settings.find((x) => x.userId === userId) ?? { state: 'BE' }
  const date = C.parse(ds)
  if (C.isWeekend(date)) return false
  if (C.holidays(date.getFullYear(), s.state)[ds]) return false
  if (partTimeOff(state.partTime, userId, date, ds)) return false
  return true
}

export function statusLabel(st: DayState): string {
  if (st.type) return TYPES[st.type].label
  if (st.holiday) return 'Feiertag'
  if (st.ptOff) return 'Teilzeit frei'
  if (st.weekend) return 'Wochenende'
  return 'Arbeitstag'
}

const TRANSP = 'transparent'
/** diagonal split bg for a 2-person cell (first = upper-left, second = lower-right). */
export function cellBg(cA: string, cB: string): string {
  if (cA === TRANSP && cB === TRANSP) return TRANSP
  if (cA === cB) return cA
  const div = 'oklch(0.5 0 0 / 0.14)'
  return `linear-gradient(135deg, ${cA} 0 calc(50% - 0.6px), ${div} calc(50% - 0.6px) calc(50% + 0.6px), ${cB} calc(50% + 0.6px) 100%)`
}
