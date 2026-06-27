import { ShoppingCategory, ShoppingItem } from '../types'

// Presentation metadata for a grocery category: the header label + emoji + (route) order. The live,
// editable catalog (#411) is fetched from GET /shopping/categories and threaded into the helpers
// below as the `categories` argument; [BUILTIN_CATEGORIES] is the seed mirror used as the initial
// state / offline fallback until that fetch returns. The backend stores the resolved category *key*
// on each item; this only maps key → header presentation.
export interface CategoryMeta {
  key: string
  label: string
  emoji: string
}

// Seed mirror of GroceryCatalog.categories — initial state / offline fallback only. Keep in sync with
// GroceryCatalog.kt's `categories` (it seeds the same rows into shopping_categories on first startup).
export const BUILTIN_CATEGORIES: ShoppingCategory[] = [
  { key: 'PRODUCE', label: 'Obst & Gemüse', emoji: '🥦', sortOrder: 0, isBuiltin: true },
  { key: 'BAKERY', label: 'Backwaren', emoji: '🥐', sortOrder: 1, isBuiltin: true },
  { key: 'DAIRY', label: 'Milchprodukte & Eier', emoji: '🧀', sortOrder: 2, isBuiltin: true },
  { key: 'MEAT_FISH', label: 'Fleisch & Fisch', emoji: '🥩', sortOrder: 3, isBuiltin: true },
  { key: 'FROZEN', label: 'Tiefkühl', emoji: '🧊', sortOrder: 4, isBuiltin: true },
  { key: 'PANTRY', label: 'Vorrat', emoji: '🥫', sortOrder: 5, isBuiltin: true },
  { key: 'SNACKS', label: 'Snacks & Süßes', emoji: '🍫', sortOrder: 6, isBuiltin: true },
  { key: 'DRINKS', label: 'Getränke', emoji: '🥤', sortOrder: 7, isBuiltin: true },
  { key: 'HOUSEHOLD', label: 'Haushalt & Hygiene', emoji: '🧽', sortOrder: 8, isBuiltin: true },
  { key: 'OTHER', label: 'Sonstiges', emoji: '❓', sortOrder: 9, isBuiltin: true },
]

const FALLBACK_OTHER: CategoryMeta = { key: 'OTHER', label: 'Sonstiges', emoji: '❓' }

/** The OTHER/fallback bucket within a catalog (or a hardcoded default if the catalog lacks it). */
const otherOf = (categories: CategoryMeta[]): CategoryMeta =>
  categories.find((c) => c.key === 'OTHER') ?? FALLBACK_OTHER

/** Header label + emoji for a category key against [categories]; unknown/missing → the OTHER bucket. */
export function categoryMeta(key: string | undefined, categories: CategoryMeta[]): CategoryMeta {
  return (key ? categories.find((c) => c.key === key) : undefined) ?? otherOf(categories)
}

/** Neutral fallback when an item carries no resolved emoji (legacy rows / unknown items). */
export const DEFAULT_ITEM_ICON = '🛒'

// ---- Custom SVG icon set (migration prep) ---------------------------------------------------
//
// Designed SVGs (Bring-style) gradually replace the emoji. To add one: drop the file into
// `./shopping-icons/` and it's auto-registered here — no wiring. Naming convention:
//   - item icon:      <slug-of-name>.svg     e.g. leberkaese.svg, moehren.svg
//   - category icon:  cat-<categorykey>.svg  e.g. cat-produce.svg (lowercased key)
// The lookup slugifies the item name the same way the filenames are slugified, so a file named
// `leberkaese.svg` matches the item "Leberkäse". An item icon wins over its category icon; if
// neither exists the emoji fallback below renders. Until SVGs are added this map is empty and
// behaviour is unchanged.
const ICON_URLS = import.meta.glob('./shopping-icons/*.svg', {
  eager: true,
  query: '?url',
  import: 'default',
}) as Record<string, string>

const iconByKey: Record<string, string> = {}
for (const [path, url] of Object.entries(ICON_URLS)) {
  const base = path.split('/').pop()?.replace(/\.svg$/, '')
  if (base) iconByKey[base] = url
}

/**
 * Slug for an item name → SVG filename. Mirrors GroceryCatalog.normalize (lowercase, strip a leading
 * "<qty> <unit>", drop punctuation) and additionally transliterates umlauts to ASCII so the key is a
 * safe filename: "500 g Möhren" → "moehren", "Leberkäse" → "leberkaese".
 */
export function slugifyIconKey(raw: string): string {
  let s = raw.trim().toLowerCase()
  s = s.replace(
    /^\s*\d+([.,]\d+)?\s*(g|kg|mg|ml|l|el|tl|stk|stück|st|x|prise|prisen|bund|dose|dosen|pkg|pck|pack|packung|tasse|cup|msp|glas|gläser|becher|flasche|flaschen)?\.?\s+/,
    '',
  )
  s = s.replace(/ä/g, 'ae').replace(/ö/g, 'oe').replace(/ü/g, 'ue').replace(/ß/g, 'ss')
  s = s.replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '')
  return s
}

/** The designed SVG URL for an item (item-name match first, then its category), or undefined. */
function iconSvgFor(item: ShoppingItem): string | undefined {
  const nameKey = slugifyIconKey(item.name)
  if (nameKey && iconByKey[nameKey]) return iconByKey[nameKey]
  if (item.category) {
    const catUrl = iconByKey[`cat-${item.category.toLowerCase()}`]
    if (catUrl) return catUrl
  }
  return undefined
}

export interface CategoryGroup {
  category: CategoryMeta
  items: ShoppingItem[]
}

/**
 * Bucket items by their category key against [categories] and return non-empty groups in catalog
 * order. Items with no/unknown category fall into OTHER (rendered last). Within a group the input
 * order is preserved (callers pass newest-first).
 */
export function groupByCategory(items: ShoppingItem[], categories: CategoryMeta[]): CategoryGroup[] {
  const order = new Map(categories.map((c, i) => [c.key, i]))
  const known = new Set(categories.map((c) => c.key))
  const otherKey = otherOf(categories).key
  const buckets = new Map<string, ShoppingItem[]>()
  for (const item of items) {
    const key = item.category && known.has(item.category) ? item.category : otherKey
    const bucket = buckets.get(key)
    if (bucket) bucket.push(item)
    else buckets.set(key, [item])
  }
  return [...buckets.entries()]
    .sort((a, b) => (order.get(a[0]) ?? 99) - (order.get(b[0]) ?? 99))
    .map(([key, list]) => ({ category: categoryMeta(key, categories), items: list }))
}

/**
 * The single rendering seam for an item's icon. Prefers a designed SVG ([iconSvgFor]) and falls back
 * to the resolved emoji (#389 — "emojis first, keep it swappable"). `muted` desaturates it for checked
 * ("Im Wagen") rows — the `is-muted` filter on the span applies to both the emoji and the <img>.
 */
export function ItemIcon({ item, muted }: { item: ShoppingItem; muted?: boolean }) {
  const svg = iconSvgFor(item)
  return (
    <span className={`hb-row__emoji${muted ? ' is-muted' : ''}`} aria-hidden="true">
      {svg ? <img src={svg} alt="" /> : item.icon || DEFAULT_ITEM_ICON}
    </span>
  )
}
