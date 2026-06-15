// HB-03 — global search index for the command palette (⌘K). A flat, in-memory index across
// the household's resources (todos / notes / recipes / projects / shopping), loaded on demand
// and matched with a tiny subsequence-fuzzy scorer. Pure data + matching logic, kept out of the
// React component so the scorer can be unit-tested in a plain Node env.
import { API_BASE, safeFetch } from './api'
import type { Note, Project, Recipe, ShoppingItem, Todo } from './types'

export type SearchKind = 'todo' | 'note' | 'recipe' | 'project' | 'shopping'

// A navigable nav tab (subset of App's Tab) that a search hit jumps to.
export type ResultTab = 'todos' | 'notes' | 'recipes' | 'time' | 'shopping'

export const KIND_TAB: Record<SearchKind, ResultTab> = {
  todo: 'todos',
  note: 'notes',
  recipe: 'recipes',
  project: 'time',
  shopping: 'shopping',
}

export const KIND_ICON: Record<SearchKind, string> = {
  todo: 'checkCircle',
  note: 'note',
  recipe: 'chef',
  project: 'clock',
  shopping: 'cart',
}

export interface SearchItem {
  id: string
  kind: SearchKind
  title: string
  subtitle?: string
  /** lower-cased haystack for matching (title + secondary text) */
  haystack: string
}

const norm = (s: string): string => s.toLowerCase()

/**
 * Load a flat searchable index across the household's resources. Each read is best-effort:
 * a failed or empty resource simply contributes nothing, so the palette still searches the
 * rest (transport failures stay silent here — the views themselves surface connectivity).
 */
export async function loadSearchIndex(token: string): Promise<SearchItem[]> {
  const get = async (path: string): Promise<unknown[]> => {
    const r = await safeFetch(token, `${API_BASE}${path}`)
    if (!r.ok || !r.res.ok) return []
    const data = await r.res.json().catch(() => [])
    return Array.isArray(data) ? data : []
  }
  const [todos, notes, recipes, projects, shopping] = await Promise.all([
    get('/todos'),
    get('/notes'),
    get('/recipes'),
    get('/time/projects'),
    get('/shopping'),
  ])
  const items: SearchItem[] = []
  for (const t of todos as Todo[]) {
    items.push({ id: t.id, kind: 'todo', title: t.title, subtitle: t.description, haystack: norm(`${t.title} ${t.description ?? ''}`) })
  }
  for (const n of notes as Note[]) {
    const tags = (n.tags ?? []).join(' ')
    items.push({ id: n.id, kind: 'note', title: n.title, subtitle: tags || undefined, haystack: norm(`${n.title} ${n.content ?? ''} ${tags}`) })
  }
  for (const r of recipes as Recipe[]) {
    items.push({ id: r.id, kind: 'recipe', title: r.title, subtitle: r.description, haystack: norm(`${r.title} ${r.description ?? ''}`) })
  }
  for (const p of projects as Project[]) {
    items.push({ id: p.id, kind: 'project', title: p.name, haystack: norm(p.name) })
  }
  for (const s of shopping as ShoppingItem[]) {
    items.push({ id: s.id, kind: 'shopping', title: s.name, haystack: norm(s.name) })
  }
  return items
}

/**
 * Subsequence fuzzy score: every query char must appear in `haystack` in order. Returns a
 * score (higher = better) or -1 for no match. Contiguous runs and word-start matches are
 * rewarded so "spag" ranks "Spaghetti" above an incidental scatter of those letters. `query`
 * must already be lower-cased; `haystack` is the lower-cased index field.
 */
export function fuzzyScore(haystack: string, query: string): number {
  if (!query) return 0
  let qi = 0
  let score = 0
  let streak = 0
  let prev = -2
  for (let i = 0; i < haystack.length && qi < query.length; i++) {
    if (haystack[i] === query[qi]) {
      score += 1
      if (prev === i - 1) {
        streak += 1
        score += streak * 2 // reward contiguous runs
      } else {
        streak = 0
      }
      if (i === 0 || haystack[i - 1] === ' ') score += 4 // reward word-start hits
      prev = i
      qi++
    }
  }
  return qi === query.length ? score : -1
}

/** Ranked matches for `query` (empty query → no results; the palette shows quick actions then). */
export function searchItems(index: SearchItem[], query: string): SearchItem[] {
  const q = norm(query.trim())
  if (!q) return []
  const scored: { item: SearchItem; score: number }[] = []
  for (const item of index) {
    const s = fuzzyScore(item.haystack, q)
    if (s >= 0) scored.push({ item, score: s })
  }
  // Best score first; tie-break by the shorter title (a closer/cleaner match).
  scored.sort((a, b) => b.score - a.score || a.item.title.length - b.item.title.length)
  return scored.map((x) => x.item)
}
