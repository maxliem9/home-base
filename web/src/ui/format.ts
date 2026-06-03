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

// --- avatars --------------------------------------------------------------

interface UserMeta {
  name: string
  initials: string
  hue: number
}

// Known users from the seed; everyone else gets a stable hashed hue.
const KNOWN: Record<string, UserMeta> = {
  max: { name: 'Max', initials: 'M', hue: 150 },
  lea: { name: 'Lea', initials: 'L', hue: 250 },
}

function hashHue(s: string): number {
  let h = 0
  for (let i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) % 360
  return h
}

export function userMeta(username?: string | null): UserMeta | null {
  if (!username) return null
  const key = username.toLowerCase()
  if (KNOWN[key]) return KNOWN[key]
  return {
    name: username,
    initials: username.slice(0, 2).toUpperCase(),
    hue: hashHue(key),
  }
}

/** Decode the `username` claim from a JWT without verifying it. */
export function usernameFromToken(token: string): string | null {
  try {
    return JSON.parse(atob(token.split('.')[1])).username ?? null
  } catch {
    return null
  }
}
