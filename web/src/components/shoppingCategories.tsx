import { ShoppingItem } from '../types'

// Presentation metadata for the grocery categories (#389). The backend's GroceryCatalog is the
// source of truth for which item lands in which category; it stores the resolved category *key*
// (e.g. "PRODUCE") on each item. This small, fixed mirror only maps that key → German header label
// + emoji + shopping-route order, exactly like the recipe categories are known to the client. Keep
// it in sync with GroceryCatalog.kt's `categories` list.
export interface CategoryMeta {
  key: string
  label: string
  emoji: string
}

export const CATEGORIES: CategoryMeta[] = [
  { key: 'PRODUCE', label: 'Obst & Gemüse', emoji: '🥦' },
  { key: 'BAKERY', label: 'Backwaren', emoji: '🥐' },
  { key: 'DAIRY', label: 'Milchprodukte & Eier', emoji: '🧀' },
  { key: 'MEAT_FISH', label: 'Fleisch & Fisch', emoji: '🥩' },
  { key: 'FROZEN', label: 'Tiefkühl', emoji: '🧊' },
  { key: 'PANTRY', label: 'Vorrat', emoji: '🥫' },
  { key: 'SNACKS', label: 'Snacks & Süßes', emoji: '🍫' },
  { key: 'DRINKS', label: 'Getränke', emoji: '🥤' },
  { key: 'HOUSEHOLD', label: 'Haushalt & Hygiene', emoji: '🧽' },
  { key: 'OTHER', label: 'Sonstiges', emoji: '❓' },
]

const OTHER: CategoryMeta = CATEGORIES[CATEGORIES.length - 1]
const BY_KEY = new Map(CATEGORIES.map((c) => [c.key, c]))
const ORDER = new Map(CATEGORIES.map((c, i) => [c.key, i]))

/** Header label + emoji for a category key; unknown/missing → the OTHER bucket. */
export function categoryMeta(key?: string): CategoryMeta {
  return (key && BY_KEY.get(key)) || OTHER
}

/** Neutral fallback when an item carries no resolved emoji (legacy rows / unknown items). */
export const DEFAULT_ITEM_ICON = '🛒'

export interface CategoryGroup {
  category: CategoryMeta
  items: ShoppingItem[]
}

/**
 * Bucket items by their category key and return non-empty groups in fixed shopping-route order.
 * Items with no/unknown category fall into OTHER (rendered last). Within a group the input order is
 * preserved (callers pass newest-first).
 */
export function groupByCategory(items: ShoppingItem[]): CategoryGroup[] {
  const buckets = new Map<string, ShoppingItem[]>()
  for (const item of items) {
    const key = item.category && BY_KEY.has(item.category) ? item.category : OTHER.key
    const bucket = buckets.get(key)
    if (bucket) bucket.push(item)
    else buckets.set(key, [item])
  }
  return [...buckets.entries()]
    .sort((a, b) => (ORDER.get(a[0]) ?? 99) - (ORDER.get(b[0]) ?? 99))
    .map(([key, list]) => ({ category: categoryMeta(key), items: list }))
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
