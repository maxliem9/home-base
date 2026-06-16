// Abwesenheit: data model, palette + summary math.
// Ported from the original design handoff (ABW). Pure, framework-free.
import { formatDecimal, userMeta } from '../../ui/format'
import type { AbsSettings, Absence, AbsenceState, AbsenceType, CustomHoliday, HalfDay, KitaClosure, PartTimeRule } from '../../types'
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
  // Holiday label (statutory or a custom one, #51) — null = not a holiday.
  holiday: string | null
  // true only for a *half-day* custom holiday: the day shows the holiday marker but is
  // still half a regular work/tracking day. Statutory + full custom holidays are full days
  // (holidayHalf=false). Used for the ½ display now and the fractional work-credit in #31.
  holidayHalf: boolean
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
  return { userId, year, state: 'BE', allowance: 30, carryover: 0, carryoverExpires: `${year}-03-31`, kindKrankCap: 15 }
}

/**
 * Effective settings for a user in a given year. Settings are stored per year;
 * for a year without its own row we inherit the *stable* fields (Bundesland, allowance,
 * kind-krank cap) from the nearest year — preferring the closest earlier year, else the
 * closest later one — while resetting the per-year carryover ("Resturlaub") to 0. This
 * mirrors the backend's lazy-create inheritance so the displayed defaults match what a
 * first edit would persist.
 */
export function settingsFor(all: AbsSettings[], userId: string, year: number): AbsSettings {
  const mine = all.filter((s) => s.userId === userId)
  const exact = mine.find((s) => s.year === year)
  if (exact) return exact
  if (mine.length === 0) return defaultSettings(userId, year)
  const sorted = [...mine].sort((a, b) => a.year - b.year)
  const base = sorted.filter((s) => s.year <= year).pop() ?? sorted[0]
  return { ...base, year, carryover: 0, carryoverExpires: `${year}-03-31` }
}

/** MM-DD key for a custom holiday (recurring by month+day, year-agnostic). */
const mdKey = (month: number, day: number): string => `${C.pad(month)}-${C.pad(day)}`
/** MM-DD slice of a YYYY-MM-DD date string. */
const mdOf = (dateStr: string): string => dateStr.slice(5)

/**
 * The GET /absence snapshot as it may really arrive: the backend omits fields holding
 * their default (encodeDefaults=false, see CLAUDE.md / issue #46), so *every* list can
 * be missing — e.g. a household without absences/part-time rules/kita closures (#54).
 */
export type AbsenceSnapshot = Partial<AbsenceState>

/** Fill every missing snapshot list with [] so downstream code can rely on real arrays. */
export function normalizeAbsenceState(raw: AbsenceSnapshot): AbsenceState {
  return {
    users: raw.users ?? [],
    absences: raw.absences ?? [],
    partTime: raw.partTime ?? [],
    kitaClosures: raw.kitaClosures ?? [],
    customHolidays: raw.customHolidays ?? [],
    settings: raw.settings ?? [],
  }
}

export interface Ctx {
  year: number
  settings: Record<string, AbsSettings>
  holidays: Record<string, Record<string, string>>
  absByUser: Record<string, Record<string, Absence>>
  kita: Record<string, KitaClosure>
  // Household-wide custom holidays (#51), keyed by MM-DD so a single fixed date matches
  // every year. Applies to every user regardless of Bundesland.
  customHol: Record<string, CustomHoliday>
  parttime: PartTimeRule[]
  hue: Record<string, number>
}

/** Build a lookup context once per render: holidays per user, absence map, etc. */
export function buildContext(snapshot: AbsenceSnapshot, year: number, users: string[]): Ctx {
  const state = normalizeAbsenceState(snapshot) // tolerate raw snapshots with missing lists (#54)
  const settings: Record<string, AbsSettings> = {}
  const holidays: Record<string, Record<string, string>> = {}
  const absByUser: Record<string, Record<string, Absence>> = {}
  const hue: Record<string, number> = {}
  users.forEach((uid) => {
    const s = settingsFor(state.settings, uid, year)
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
  const customHol: Record<string, CustomHoliday> = {}
  state.customHolidays.forEach((h) => {
    customHol[mdKey(h.month, h.day)] = h
  })
  return { year, settings, holidays, absByUser, kita, customHol, parttime: state.partTime, hue }
}

/** Resolve a single person's day. */
export function personDay(ctx: Ctx, userId: string, dateStr: string): DayState {
  const date = C.parse(dateStr)
  const hue = ctx.hue[userId] != null ? ctx.hue[userId] : hueOf(userId)
  const abs = ctx.absByUser[userId] && ctx.absByUser[userId][dateStr]
  // Statutory holiday (per Bundesland, always full-day) wins; otherwise fall back to a
  // household-wide custom holiday matched by month+day (#51), which may be a half day.
  const statutory = ctx.holidays[userId][dateStr] || null
  const custom = statutory ? null : ctx.customHol[mdOf(dateStr)] || null
  const weekend = C.isWeekend(date)
  const ptOff = partTimeOff(ctx.parttime, userId, date, dateStr)
  return {
    hue,
    type: abs ? abs.type : null,
    half: abs ? abs.half || null : null,
    holiday: statutory ?? (custom ? custom.label : null),
    holidayHalf: custom ? custom.half : false,
    weekend,
    ptOff,
  }
}

/** would this be a working day absent any leave? (used for counting). A *half* custom
 *  holiday (#51) still leaves the other half a regular work day, so it does not disqualify
 *  the day here — only full holidays (statutory + whole-day custom) do. */
export const wouldWork = (st: DayState): boolean =>
  !st.weekend && !(st.holiday && !st.holidayHalf) && !st.ptOff

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

/** pretty day count, locale-aware: "3" / "2,5" (de) · "3" / "2.5" (en). Half-days are the
 *  only fractional case, so one decimal place is plenty (#238). */
export const fmtDays = (n: number): string => formatDecimal(n, 1)

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

/** would this date be a working day for this user (not weekend / holiday / part-time-off)?
 *  A whole-day custom holiday (#51) counts as non-working; a half-day one stays workable
 *  (the other half is bookable), so range-booking still applies on it. */
export function isWorkdayFor(snapshot: AbsenceSnapshot, userId: string, ds: string): boolean {
  const state = normalizeAbsenceState(snapshot) // tolerate raw snapshots with missing lists (#54)
  const s = settingsFor(state.settings, userId, Number(ds.slice(0, 4)))
  const date = C.parse(ds)
  if (C.isWeekend(date)) return false
  if (C.holidays(date.getFullYear(), s.state)[ds]) return false
  const custom = state.customHolidays.find((h) => h.month === date.getMonth() + 1 && h.day === date.getDate())
  if (custom && !custom.half) return false
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
