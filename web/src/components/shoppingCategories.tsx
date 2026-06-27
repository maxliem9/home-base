import { ShoppingCategory, ShoppingItem } from '../types'
import { ITEM_ICON_KEY } from './shoppingIconMap'

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

// ---- Custom SVG icon set (Bring-style, #443) -------------------------------------------------
//
// Designed SVGs replace the emoji. Two auto-registered folders (no wiring — drop a file in and it's
// picked up): `./shopping-icons/items/<en>.svg` (per product) and `./shopping-icons/categories/<key>.svg`
// (per category header). Files are named in English; [ITEM_ICON_KEY] maps a normalized German item
// name → its English item file, and [CATEGORY_ICON_KEY] maps a category key → its English file.
// Resolution per item: item-name icon → category icon → emoji fallback. Unknown items (no name match)
// still get their category icon, so coverage is high; only categoryless/unknown rows fall to emoji.
const ITEM_ICON_URLS = import.meta.glob('./shopping-icons/items/*.svg', {
  eager: true,
  query: '?url',
  import: 'default',
}) as Record<string, string>
const CATEGORY_ICON_URLS = import.meta.glob('./shopping-icons/categories/*.svg', {
  eager: true,
  query: '?url',
  import: 'default',
}) as Record<string, string>

const urlByBasename = (urls: Record<string, string>): Record<string, string> => {
  const out: Record<string, string> = {}
  for (const [path, url] of Object.entries(urls)) {
    const base = path.split('/').pop()?.replace(/\.svg$/, '')
    if (base) out[base] = url
  }
  return out
}
const itemIconUrl = urlByBasename(ITEM_ICON_URLS)
const categoryIconUrl = urlByBasename(CATEGORY_ICON_URLS)

// Category key → English category-icon basename. MEAT_FISH's file uses a hyphen; the rest lowercase.
const CATEGORY_ICON_KEY: Record<string, string> = {
  PRODUCE: 'produce',
  BAKERY: 'bakery',
  DAIRY: 'dairy',
  MEAT_FISH: 'meat-fish',
  FROZEN: 'frozen',
  PANTRY: 'pantry',
  SNACKS: 'snacks',
  DRINKS: 'drinks',
  HOUSEHOLD: 'household',
  OTHER: 'other',
}

/**
 * Slug for an item name → [ITEM_ICON_KEY] lookup key. Mirrors GroceryCatalog.normalize (lowercase,
 * strip a leading "<qty> <unit>", drop punctuation) and additionally transliterates umlauts to ASCII:
 * "500 g Möhren" → "moehren", "Leberkäse" → "leberkaese".
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

/** The designed SVG URL for an item (exact item-name match first, then its category), or undefined. */
function iconSvgFor(item: ShoppingItem): string | undefined {
  const fileKey = ITEM_ICON_KEY[slugifyIconKey(item.name)]
  if (fileKey && itemIconUrl[fileKey]) return itemIconUrl[fileKey]
  if (item.category) {
    const catUrl = categoryIconUrl[CATEGORY_ICON_KEY[item.category] ?? '']
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

/** The designed SVG URL for a category header/picker icon, or undefined. */
export function categoryIconSvg(key: string | undefined): string | undefined {
  return key ? categoryIconUrl[CATEGORY_ICON_KEY[key] ?? ''] : undefined
}

/**
 * Category icon for headers and the move-to-category picker: prefers the designed SVG, falls back to
 * the emoji. `className` carries the call site's sizing (`hb-cathead__emoji` or `em`).
 */
export function CategoryIcon({
  catKey,
  emoji,
  className,
}: {
  catKey: string
  emoji: string
  className: string
}) {
  const svg = categoryIconSvg(catKey)
  return (
    <span className={className} aria-hidden="true">
      {svg ? <img src={svg} alt="" /> : emoji}
    </span>
  )
}
