// HomeBase — German formatting helpers + per-user avatar metadata.
// Ported/adapted from the design handoff (icons.jsx HBfmt + seed.jsx users).

const DAY = 86_400_000
const MON = ['Jan.', 'Feb.', 'März', 'Apr.', 'Mai', 'Juni', 'Juli', 'Aug.', 'Sep.', 'Okt.', 'Nov.', 'Dez.']

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
  if (diff === 0) return { text: 'Heute', tone: 'today' }
  if (diff === 1) return { text: 'Morgen', tone: 'soon' }
  if (diff === -1) return { text: 'Gestern', tone: 'over' }
  if (diff < 0) return { text: `${Math.abs(diff)} Tage überfällig`, tone: 'over' }
  if (diff < 7) return { text: `In ${diff} Tagen`, tone: 'soon' }
  return { text: `${d.getDate()}. ${MON[d.getMonth()]}`, tone: 'far' }
}

export function relTime(isoStr: string): string {
  const mins = Math.round((Date.now() - new Date(isoStr).getTime()) / 60000)
  if (mins < 1) return 'gerade eben'
  if (mins < 60) return `vor ${mins} Min.`
  const hrs = Math.round(mins / 60)
  if (hrs < 24) return `vor ${hrs} Std.`
  const days = Math.round(hrs / 24)
  if (days === 1) return 'gestern'
  if (days < 7) return `vor ${days} Tagen`
  return `vor ${Math.round(days / 7)} Wo.`
}

const pad = (n: number) => String(n).padStart(2, '0')

/** HH:MM:SS from a number of seconds. */
export function fmtClock(seconds: number): string {
  const s = Math.max(0, Math.floor(seconds))
  return `${pad(Math.floor(s / 3600))}:${pad(Math.floor((s % 3600) / 60))}:${pad(s % 60)}`
}

/** "2 Std 5 Min" / "12 Min" from a number of seconds. */
export function fmtDurationShort(seconds: number): string {
  const totalMin = Math.round(seconds / 60)
  const hh = Math.floor(totalMin / 60)
  const mm = totalMin % 60
  if (hh === 0) return `${mm} Min`
  return `${hh} Std ${mm} Min`
}

export function clockTime(isoStr: string): string {
  const d = new Date(isoStr)
  return `${pad(d.getHours())}:${pad(d.getMinutes())}`
}

const WD_LONG = ['Sonntag', 'Montag', 'Dienstag', 'Mittwoch', 'Donnerstag', 'Freitag', 'Samstag']

/** Separator label for a day in a chronological list: Heute / Gestern /
 *  Vorgestern / weekday name (within the last 7 days) / "D. Mon". */
export function dayGroupLabel(isoStr: string): string {
  const d = new Date(isoStr)
  const day = new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime()
  const diff = Math.round((day - startOfToday()) / DAY)
  if (diff === 0) return 'Heute'
  if (diff === -1) return 'Gestern'
  if (diff === -2) return 'Vorgestern'
  if (diff < 0 && diff > -7) return WD_LONG[d.getDay()]
  return `${d.getDate()}. ${MON[d.getMonth()]}`
}

/** "Sonntag, 8. Juni" — long weekday + day + month, for the dashboard eyebrow. */
export function todayLabel(d: Date = new Date()): string {
  return `${WD_LONG[d.getDay()]}, ${d.getDate()}. ${MON[d.getMonth()]}`
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
 *  range is e.g. "12.–18. Mai". */
export function weekLabel(isoStr: string): { label: string | null; range: string } {
  const s = weekStart(new Date(isoStr))
  const e = new Date(s.getFullYear(), s.getMonth(), s.getDate() + 6)
  const diffWeeks = Math.round((s.getTime() - weekStart(new Date()).getTime()) / (7 * DAY))
  let label: string | null = null
  if (diffWeeks === 0) label = 'Diese Woche'
  else if (diffWeeks === -1) label = 'Letzte Woche'
  const range =
    s.getMonth() === e.getMonth()
      ? `${s.getDate()}.–${e.getDate()}. ${MON[e.getMonth()]}`
      : `${s.getDate()}. ${MON[s.getMonth()]} – ${e.getDate()}. ${MON[e.getMonth()]}`
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

export function userMeta(username?: string | null): UserMeta | null {
  if (!username) return null
  // Guard against an all-whitespace / empty-after-trim username: fall back to "?"
  // rather than indexing undefined.
  const first = username.trim()[0]
  return {
    name: capitalize(username),
    initials: first ? first.toUpperCase() : '?',
    hue: hashHue(username.toLowerCase()),
  }
}

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
