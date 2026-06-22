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
 * The single rendering seam for an item's icon. Emoji today; swapping to an SVG/icon-font scheme
 * later means changing only this component (#389 — "emojis first, keep it swappable"). `muted`
 * desaturates it for checked ("Im Wagen") rows.
 */
export function ItemIcon({ item, muted }: { item: ShoppingItem; muted?: boolean }) {
  return (
    <span className={`hb-row__emoji${muted ? ' is-muted' : ''}`} aria-hidden="true">
      {item.icon || DEFAULT_ITEM_ICON}
    </span>
  )
}
