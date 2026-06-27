import { describe, expect, it } from 'vitest'
import { slugifyIconKey } from './shoppingCategories'

// Locks the SVG-filename contract that the icon migration leans on (mirror of the backend
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
