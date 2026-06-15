import { describe, it, expect } from 'vitest'
import { fuzzyScore, searchItems, type SearchItem } from './search'

const item = (id: string, kind: SearchItem['kind'], title: string, extra = ''): SearchItem => ({
  id,
  kind,
  title,
  haystack: `${title} ${extra}`.toLowerCase().trim(),
})

describe('fuzzyScore', () => {
  it('matches an exact substring', () => {
    expect(fuzzyScore('spaghetti bolognese', 'spag')).toBeGreaterThan(0)
  })

  it('matches an in-order subsequence (non-contiguous)', () => {
    expect(fuzzyScore('milchreis', 'mlrs')).toBeGreaterThanOrEqual(0)
  })

  it('returns -1 when a query char is missing or out of order', () => {
    expect(fuzzyScore('apfel', 'xyz')).toBe(-1)
    expect(fuzzyScore('abc', 'cba')).toBe(-1)
  })

  it('empty query scores 0 (neutral)', () => {
    expect(fuzzyScore('anything', '')).toBe(0)
  })

  it('rewards a word-start match over a mid-word one', () => {
    // "ei" at the start of a word ("Eier") should outscore "ei" buried mid-word ("Pürei").
    expect(fuzzyScore('eier', 'ei')).toBeGreaterThan(fuzzyScore('pürei', 'ei'))
  })

  it('rewards contiguous runs over scattered hits', () => {
    // contiguous "butt" in "butter" should outscore the same letters scattered mid-word
    // (no spaces → no word-start bonus to compensate).
    expect(fuzzyScore('butter', 'butt')).toBeGreaterThan(fuzzyScore('bxuxtxter', 'butt'))
  })
})

describe('searchItems', () => {
  const index: SearchItem[] = [
    item('r1', 'recipe', 'Spaghetti Bolognese'),
    item('t1', 'todo', 'Spinat kaufen'),
    item('n1', 'note', 'Spielideen', 'kinder draußen'),
    item('s1', 'shopping', 'Milch'),
  ]

  it('returns no results for an empty query', () => {
    expect(searchItems(index, '')).toEqual([])
    expect(searchItems(index, '   ')).toEqual([])
  })

  it('finds matches across kinds and is case-insensitive', () => {
    const r = searchItems(index, 'SP')
    expect(r.map((x) => x.id).sort()).toEqual(['n1', 'r1', 't1'])
  })

  it('ranks the closest match first', () => {
    // "spag" only fully matches the recipe.
    expect(searchItems(index, 'spag')[0]?.id).toBe('r1')
  })

  it('matches secondary text (note tags/content) too', () => {
    expect(searchItems(index, 'kinder').map((x) => x.id)).toContain('n1')
  })
})
