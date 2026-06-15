// HomeBase — locale-aware formatting helpers + per-user avatar metadata.
// Date/relative-time wording follows the active UI language (#223 / HB-07): German is the
// default; English applies when the language is switched. The relative words live in the i18n
// catalogs (`fmt.*`); month/weekday names and day↔month ordering are per-locale below. German
// output is intentionally byte-identical to the previous hard-coded strings.
import { currentLang, t } from '../i18n'

const DAY = 86_400_000
const MON_DE = ['Jan.', 'Feb.', 'März', 'Apr.', 'Mai', 'Juni', 'Juli', 'Aug.', 'Sep.', 'Okt.', 'Nov.', 'Dez.']
const MON_EN = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec']
const WD_DE = ['Sonntag', 'Montag', 'Dienstag', 'Mittwoch', 'Donnerstag', 'Freitag', 'Samstag']
const WD_EN = ['Sunday', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday']
const WD_SHORT_DE = ['So', 'Mo', 'Di', 'Mi', 'Do', 'Fr', 'Sa']
const WD_SHORT_EN = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat']

const isEn = (): boolean => currentLang() === 'en'
const mon = (m: number): string => (isEn() ? MON_EN : MON_DE)[m]
const wdLong = (w: number): string => (isEn() ? WD_EN : WD_DE)[w]

/** Short weekday name, locale-aware: "Mo" (de) / "Mon" (en) — for compact week-grid headers. */
export const weekdayShort = (d: Date): string => (isEn() ? WD_SHORT_EN : WD_SHORT_DE)[d.getDay()]
/** day + month, locale-ordered: "8. Juni" (de) / "Jun 8" (en). */
const dayMonth = (d: Date): string => (isEn() ? `${mon(d.getMonth())} ${d.getDate()}` : `${d.getDate()}. ${mon(d.getMonth())}`)

function startOfToday(): number {
  const d = new Date()
  d.setHours(0, 0, 0, 0)
  return d.getTime()
}

export type DueTone = 'today' | 'soon' | 'over' | 'far'

export function dueLabel(isoDate?: string): { text: string; tone: DueTone } | null {
  if (!isoDate) return null
  const d = new Date(isoDate + 'T00:00:00')
  const diff = Math.round((d.getTime() - startOfToday()) / DAY)
  if (diff === 0) return { text: t('fmt.today'), tone: 'today' }
  if (diff === 1) return { text: t('fmt.tomorrow'), tone: 'soon' }
  if (diff === -1) return { text: t('fmt.yesterday'), tone: 'over' }
  if (diff < 0) return { text: t('fmt.overdueDays', { n: Math.abs(diff) }), tone: 'over' }
  if (diff < 7) return { text: t('fmt.inDays', { n: diff }), tone: 'soon' }
  return { text: dayMonth(d), tone: 'far' }
}

export function relTime(isoStr: string): string {
  const mins = Math.round((Date.now() - new Date(isoStr).getTime()) / 60000)
  if (mins < 1) return t('fmt.justNow')
  if (mins < 60) return t('fmt.minAgo', { n: mins })
  const hrs = Math.round(mins / 60)
  if (hrs < 24) return t('fmt.hrsAgo', { n: hrs })
  const days = Math.round(hrs / 24)
  if (days === 1) return t('fmt.yesterdayRel')
  if (days < 7) return t('fmt.daysAgo', { n: days })
  return t('fmt.weeksAgo', { n: Math.round(days / 7) })
}

const pad = (n: number) => String(n).padStart(2, '0')

/** HH:MM:SS from a number of seconds. */
export function fmtClock(seconds: number): string {
  const s = Math.max(0, Math.floor(seconds))
  return `${pad(Math.floor(s / 3600))}:${pad(Math.floor((s % 3600) / 60))}:${pad(s % 60)}`
}

/** "2 Std 5 Min" / "12 Min" (de) · "2 h 5 min" / "12 min" (en) from a number of seconds. */
export function fmtDurationShort(seconds: number): string {
  const totalMin = Math.round(seconds / 60)
  const hh = Math.floor(totalMin / 60)
  const mm = totalMin % 60
  if (hh === 0) return t('fmt.durMin', { m: mm })
  return t('fmt.durHourMin', { h: hh, m: mm })
}

export function clockTime(isoStr: string): string {
  const d = new Date(isoStr)
  return `${pad(d.getHours())}:${pad(d.getMinutes())}`
}

/** Separator label for a day in a chronological list: today / yesterday /
 *  day-before-yesterday / weekday name (within the last 7 days) / "D. Mon". */
export function dayGroupLabel(isoStr: string): string {
  const d = new Date(isoStr)
  const day = new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime()
  const diff = Math.round((day - startOfToday()) / DAY)
  if (diff === 0) return t('fmt.today')
  if (diff === -1) return t('fmt.yesterday')
  if (diff === -2) return t('fmt.dayBeforeYesterday')
  if (diff < 0 && diff > -7) return wdLong(d.getDay())
  return dayMonth(d)
}

/** "Sonntag, 8. Juni" (de) / "Sunday, Jun 8" (en) — long weekday + day + month, dashboard eyebrow. */
export function todayLabel(d: Date = new Date()): string {
  return `${wdLong(d.getDay())}, ${dayMonth(d)}`
}

/** Monday-based start of the week containing `date`. */
function weekStart(date: Date): Date {
  const dow = (date.getDay() + 6) % 7 // Mon = 0
  return new Date(date.getFullYear(), date.getMonth(), date.getDate() - dow)
}

/** Stable key for the (Monday-based) week containing `isoStr`. */
export function weekKey(isoStr: string): string {
  const s = weekStart(new Date(isoStr))
  return `${s.getFullYear()}-${pad(s.getMonth() + 1)}-${pad(s.getDate())}`
}

/** { label, range } for a week — label is "Diese Woche"/"Letzte Woche"/null,
 *  range is e.g. "12.–18. Mai" (de) / "May 12–18" (en). */
export function weekLabel(isoStr: string): { label: string | null; range: string } {
  const s = weekStart(new Date(isoStr))
  const e = new Date(s.getFullYear(), s.getMonth(), s.getDate() + 6)
  const diffWeeks = Math.round((s.getTime() - weekStart(new Date()).getTime()) / (7 * DAY))
  let label: string | null = null
  if (diffWeeks === 0) label = t('fmt.thisWeek')
  else if (diffWeeks === -1) label = t('fmt.lastWeek')
  const sameMonth = s.getMonth() === e.getMonth()
  const range = isEn()
    ? sameMonth
      ? `${mon(e.getMonth())} ${s.getDate()}–${e.getDate()}`
      : `${mon(s.getMonth())} ${s.getDate()} – ${mon(e.getMonth())} ${e.getDate()}`
    : sameMonth
      ? `${s.getDate()}.–${e.getDate()}. ${mon(e.getMonth())}`
      : `${s.getDate()}. ${mon(s.getMonth())} – ${e.getDate()}. ${mon(e.getMonth())}`
  return { label, range }
}

// --- avatars --------------------------------------------------------------

interface UserMeta {
  name: string
  initials: string
  hue: number
}

// No hard-coded roster: a member's display name, avatar initial and colour are
// all derived from the username, so HomeBase works for any household — not just
// the seeded one (#88). Name = capitalised username, initial = its first letter,
// colour = a stable hash of the *full* lower-cased username.
// The full-username hash is what disambiguates two members who share a first
// letter (#89): Max & Martina both render the initial "M", but their hues are
// derived from the whole name and therefore differ. We deliberately keep the
// single-letter initial (deriving longer initials would need the whole roster);
// the distinct colour already resolves the visual ambiguity. Keep this hash
// algorithm in sync with Android's Hb.userHue (theme/Color.kt) for cross-platform
// parity — exact parity isn't required, but both must stay deterministic.
function hashHue(s: string): number {
  let h = 0
  // 32-bit FNV-ish accumulation, reduced to a hue only at the end so the whole
  // string contributes (a per-step `% 360` would collapse distribution).
  for (let i = 0; i < s.length; i++) h = (Math.imul(h, 31) + s.charCodeAt(i)) | 0
  return ((h % 360) + 360) % 360
}

const capitalize = (s: string): string => (s ? s[0].toUpperCase() + s.slice(1) : s)

/**
 * The avatar hue (0..359) for a user: a stored per-user override wins, otherwise the
 * hue derived from the username hash (#160). Single source of truth so the picker, the
 * Avatar component and any direct render site agree (Teil von #100). `hueOverride` comes
 * from the household-visible roster (GET /users avatarHue); null/undefined = automatic.
 */
export function avatarColor(username: string, hueOverride?: number | null): number {
  if (hueOverride != null) return hueOverride
  return hashHue(username.toLowerCase())
}

export function userMeta(username?: string | null, hueOverride?: number | null): UserMeta | null {
  if (!username) return null
  // Guard against an all-whitespace / empty-after-trim username: fall back to "?"
  // rather than indexing undefined.
  const first = username.trim()[0]
  return {
    name: capitalize(username),
    initials: first ? first.toUpperCase() : '?',
    hue: avatarColor(username, hueOverride),
  }
}

// Swatch hues offered in the avatar-colour picker (Teil von #100): an even sweep across
// the OKLCH hue wheel. Rendered as the actual avatar circle oklch(0.62,0.09,h); plus an
// "automatic" option (null → derived) handled by the picker itself.
export const AVATAR_HUE_SWATCHES: number[] = [0, 30, 60, 90, 120, 150, 180, 210, 240, 270, 300, 330]

// Fallback household usernames for offline dev only (npm run dev with no backend
// to hit GET /users); real deployments resolve the seeded usernames from the API.
// Keeps the assignee chips populated locally. Mirrors SEED_USERS in .env.example.
export const HOUSEHOLD_USERS: string[] = ['max', 'partner']

/** Decode the `username` claim from a JWT without verifying it. */
export function usernameFromToken(token: string): string | null {
  try {
    return JSON.parse(atob(token.split('.')[1])).username ?? null
  } catch {
    return null
  }
}
