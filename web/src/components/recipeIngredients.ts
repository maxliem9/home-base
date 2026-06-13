// Free-text ("paste") ingredient parsing + serialization. Pure helpers, kept out of the React
// component so they can be unit-tested in a plain Node env (RecipesView.tsx touches `window` at
// import time). The rules MUST stay identical to the Android parser in
// android/.../ui/recipes/RecipesScreen.kt (same fraction/range handling + same 3-decimal rounding).

export interface IngredientDraft { name: string; amount: string; unit: string }
export interface SectionDraft { name: string; ingredients: IngredientDraft[] }

export const emptyIngredient = (): IngredientDraft => ({ name: '', amount: '', unit: '' })
export const emptySection = (): SectionDraft => ({ name: '', ingredients: [emptyIngredient()] })

// One ingredient per line ("200 g Mehl"); a line starting with "#" opens a named section.
// This is the bulk-entry counterpart to the structured rows — it matches how recipes are
// copied off the web and mirrors the one-per-line steps field. Parsing is best-effort: it
// only treats the first token after a leading amount as a unit when it's a known unit, so
// "3 Eier" keeps "Eier" as the name (not the unit).
const KNOWN_UNITS = new Set([
  'g', 'kg', 'mg', 'ml', 'cl', 'dl', 'l', 'el', 'tl', 'msp', 'prise', 'prisen', 'stück', 'stk', 'st',
  'dose', 'dosen', 'pkg', 'packung', 'päckchen', 'bund', 'zehe', 'zehen', 'scheibe', 'scheiben',
  'tasse', 'tassen', 'becher', 'glas', 'cm', 'mm', 'kugel', 'kugeln', 'blatt', 'blätter',
])
const isUnitToken = (tok: string) => KNOWN_UNITS.has(tok.toLowerCase().replace(/\.$/, ''))

// Format a decimal to at most 3 places, trailing zeros stripped ("0.5", "1.5", "1", "0.333").
// 3 places keeps 1/3, 2/3 etc. honest and identical to the Android parser (same rounding).
const formatAmount = (n: number): string => {
  const s = n.toFixed(3).replace(/0+$/, '').replace(/\.$/, '')
  return s === '-0' ? '0' : s
}

// Parse a leading amount TOKEN into a normalized decimal string, or null if it isn't a clean
// number / fraction / range. Supported: plain number (1, 1.5, 1,5), fraction a/b (1/2 → 0.5),
// and range a-b → LOWER bound (1-2 → 1; predictable, never over-shops). The mixed-number form
// "a b/c" spans two tokens and is handled by parseIngredientLine. Decimal comma is accepted.
const parseAmountToken = (tok: string): string | null => {
  const num = (s: string) => Number(s.replace(',', '.'))
  // range a-b (each side a plain number) → lower bound. Hyphen only, e.g. "1-2", "1,5-2".
  const range = tok.match(/^([0-9]+(?:[.,][0-9]+)?)-([0-9]+(?:[.,][0-9]+)?)$/)
  if (range) {
    const lo = num(range[1])
    return Number.isFinite(lo) ? formatAmount(lo) : null
  }
  // fraction a/b → decimal. b must be non-zero.
  const frac = tok.match(/^([0-9]+(?:[.,][0-9]+)?)\/([0-9]+(?:[.,][0-9]+)?)$/)
  if (frac) {
    const a = num(frac[1])
    const b = num(frac[2])
    return Number.isFinite(a) && Number.isFinite(b) && b !== 0 ? formatAmount(a / b) : null
  }
  // plain number (keep as typed, only normalize the decimal comma)
  if (/^[0-9]+(?:[.,][0-9]+)?$/.test(tok)) return tok.replace(',', '.')
  return null
}

export const parseIngredientLine = (line: string): IngredientDraft => {
  const trimmed = line.trim()
  // Split off the leading whitespace-delimited token as a candidate amount. We treat it as an
  // amount only when it cleanly matches a number / fraction / range (incl. a mixed number like
  // "1 1/2"); otherwise the whole line stays the name (honest, identical to the Android parser,
  // never silently stores a wrong number).
  const m = trimmed.match(/^(\S+)\s+(.*)$/)
  if (!m) return { name: trimmed, amount: '', unit: '' }
  const first = m[1]
  let rest = m[2].trim()
  let amount = parseAmountToken(first)
  if (amount == null) return { name: trimmed, amount: '', unit: '' }

  // mixed number: a leading integer followed by a "b/c" fraction ("1 1/2" → 1.5). Only when the
  // first token was a bare integer (not itself a fraction/range) and the next token is a fraction.
  const fracNext = rest.match(/^([0-9]+)\/([0-9]+)(?:\s+(.*))?$/)
  if (fracNext && /^[0-9]+$/.test(first)) {
    const b = Number(fracNext[2])
    if (b !== 0) {
      amount = formatAmount(Number(first) + Number(fracNext[1]) / b)
      rest = (fracNext[3] ?? '').trim()
    }
  }
  if (!rest) return { name: '', amount, unit: '' }

  const parts = rest.split(/\s+/)
  if (parts.length > 1 && isUnitToken(parts[0])) {
    return { name: parts.slice(1).join(' '), amount, unit: parts[0] }
  }
  return { name: rest, amount, unit: '' }
}

export const parseIngredientsText = (text: string): SectionDraft[] => {
  const sections: SectionDraft[] = []
  let current: SectionDraft | null = null
  for (const raw of text.split('\n')) {
    const line = raw.trim()
    if (!line) continue
    if (line.startsWith('#')) {
      current = { name: line.replace(/^#+/, '').trim(), ingredients: [] }
      sections.push(current)
    } else {
      if (!current) { current = { name: '', ingredients: [] }; sections.push(current) }
      current.ingredients.push(parseIngredientLine(line))
    }
  }
  return sections.length ? sections : [emptySection()]
}

// Structured sections → the editable text block (named sections become "# name" headers).
export const serializeSections = (sections: SectionDraft[]): string => {
  const out: string[] = []
  for (const sec of sections) {
    if (sec.name.trim()) out.push(`# ${sec.name.trim()}`)
    for (const ing of sec.ingredients) {
      const line = [ing.amount.trim(), ing.unit.trim(), ing.name.trim()].filter(Boolean).join(' ')
      if (line) out.push(line)
    }
  }
  return out.join('\n')
}
