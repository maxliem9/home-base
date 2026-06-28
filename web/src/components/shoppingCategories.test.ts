import { describe, expect, it } from 'vitest'
import { ITEM_ICON_CHOICES, iconMatchesQuery, slugifyIconKey } from './shoppingCategories'
import { ITEM_ICON_KEY } from './shoppingIconMap'

// Basenames the bundler actually sees in each icon folder (same glob mechanism the component uses).
const basenames = (urls: Record<string, unknown>) =>
  new Set(Object.keys(urls).map((p) => p.split('/').pop()!.replace(/\.svg$/, '')))
const itemFiles = basenames(import.meta.glob('./shopping-icons/items/*.svg'))
const categoryFiles = basenames(import.meta.glob('./shopping-icons/categories/*.svg'))

// Locks the SVG-filename contract that the icon mapping leans on (mirror of the backend
// GroceryCatalog.normalize + umlaut→ASCII transliteration). If these drift, dropped-in SVGs stop
// matching their items.
describe('slugifyIconKey', () => {
  it('lowercases and transliterates umlauts/ß to ASCII', () => {
    expect(slugifyIconKey('Leberkäse')).toBe('leberkaese')
    expect(slugifyIconKey('Möhren')).toBe('moehren')
    expect(slugifyIconKey('Olivenöl')).toBe('olivenoel')
    expect(slugifyIconKey('Müsli')).toBe('muesli')
    expect(slugifyIconKey('Weißwein')).toBe('weisswein')
  })

  it('strips a leading "<qty> <unit>" prefix', () => {
    expect(slugifyIconKey('500 g Möhren')).toBe('moehren')
    expect(slugifyIconKey('2 Paprika')).toBe('paprika')
    expect(slugifyIconKey('1,5 l Milch')).toBe('milch')
    expect(slugifyIconKey('3 Stück Brötchen')).toBe('broetchen')
  })

  it('joins multiple words with a single hyphen and trims edges', () => {
    expect(slugifyIconKey('Passierte Tomaten')).toBe('passierte-tomaten')
    expect(slugifyIconKey('  Toilettenpapier  ')).toBe('toilettenpapier')
    expect(slugifyIconKey('Apfel & Birne')).toBe('apfel-birne')
  })

  it('returns an empty string for blank / qty-only input (caller falls back)', () => {
    expect(slugifyIconKey('')).toBe('')
    expect(slugifyIconKey('   ')).toBe('')
  })
})

// The whole point of the migration: the famous wrong cases now point at the right icon.
describe('ITEM_ICON_KEY', () => {
  it('maps key product names (incl. the Leberkäse→Käse fix) to the right English file', () => {
    expect(ITEM_ICON_KEY[slugifyIconKey('Leberkäse')]).toBe('meatloaf')
    expect(ITEM_ICON_KEY[slugifyIconKey('Möhren')]).toBe('carrots')
    expect(ITEM_ICON_KEY[slugifyIconKey('Gouda')]).toBe('cheese')
    expect(ITEM_ICON_KEY[slugifyIconKey('Apfelsaft')]).toBe('juice')
  })

  it('every mapped icon file actually exists', () => {
    const missing = [...new Set(Object.values(ITEM_ICON_KEY))].filter((en) => !itemFiles.has(en))
    expect(missing).toEqual([])
  })

  it('all 10 category icons are present', () => {
    expect(categoryFiles.size).toBe(10)
  })
})

// The icon-override picker (#442): choices + German-aware search.
describe('icon picker', () => {
  it('ITEM_ICON_CHOICES lists every item icon except the neutral misc fallback', () => {
    const keys = ITEM_ICON_CHOICES.map((c) => c.key)
    expect(keys).not.toContain('misc')
    expect(keys).toContain('carrots')
    expect(keys.length).toBe(itemFiles.size - 1) // all items minus misc
    expect(ITEM_ICON_CHOICES.every((c) => typeof c.url === 'string' && c.url.length > 0)).toBe(true)
  })

  it('iconMatchesQuery matches by English key and by German name', () => {
    expect(iconMatchesQuery('carrots', 'möhren')).toBe(true) // German name → carrots
    expect(iconMatchesQuery('carrots', 'karotten')).toBe(true) // synonym
    expect(iconMatchesQuery('carrots', 'carr')).toBe(true) // English substring
    expect(iconMatchesQuery('meatloaf', 'leberkäse')).toBe(true) // the famous one
    expect(iconMatchesQuery('cheese', 'möhren')).toBe(false)
    expect(iconMatchesQuery('carrots', '')).toBe(true) // empty query matches all
  })
})
