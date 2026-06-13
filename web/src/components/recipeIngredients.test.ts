import { describe, expect, it } from 'vitest'
import { parseIngredientLine } from './recipeIngredients'

// The free-text/paste parser (#166): a leading number / fraction / range becomes the amount,
// otherwise the whole line stays the name (never silently stores a wrong value). Fractions →
// decimal, ranges → lower bound. Must stay identical to the Android parser (RecipesScreen.kt).
describe('parseIngredientLine', () => {
  it('keeps the existing plain "200 g Mehl" case', () => {
    expect(parseIngredientLine('200 g Mehl')).toEqual({ name: 'Mehl', amount: '200', unit: 'g' })
  })

  it('keeps a unit-less amount line ("3 Eier" → name Eier, no unit)', () => {
    expect(parseIngredientLine('3 Eier')).toEqual({ name: 'Eier', amount: '3', unit: '' })
  })

  it('leaves a plain-text line untouched ("Salz")', () => {
    expect(parseIngredientLine('Salz')).toEqual({ name: 'Salz', amount: '', unit: '' })
  })

  it('accepts a decimal amount with comma (1,5 → 1.5)', () => {
    expect(parseIngredientLine('1,5 l Milch')).toEqual({ name: 'Milch', amount: '1.5', unit: 'l' })
  })

  it('parses a simple fraction to a decimal (1/2 TL Zimt → 0.5)', () => {
    expect(parseIngredientLine('1/2 TL Zimt')).toEqual({ name: 'Zimt', amount: '0.5', unit: 'TL' })
  })

  it('parses a recurring fraction with stable 3-decimal rounding (1/3 → 0.333)', () => {
    expect(parseIngredientLine('1/3 Tasse Reis')).toEqual({ name: 'Reis', amount: '0.333', unit: 'Tasse' })
  })

  it('parses a mixed number (1 1/2 Tassen → 1.5)', () => {
    expect(parseIngredientLine('1 1/2 Tassen Mehl')).toEqual({ name: 'Mehl', amount: '1.5', unit: 'Tassen' })
  })

  it('uses the lower bound of a range (1-2 Eier → 1)', () => {
    expect(parseIngredientLine('1-2 Eier')).toEqual({ name: 'Eier', amount: '1', unit: '' })
  })

  it('uses the lower bound of a decimal range (0,5-1 TL Salz → 0.5)', () => {
    expect(parseIngredientLine('0,5-1 TL Salz')).toEqual({ name: 'Salz', amount: '0.5', unit: 'TL' })
  })

  it('does not split a fraction with a zero denominator (1/0 stays the name)', () => {
    expect(parseIngredientLine('1/0 weird')).toEqual({ name: '1/0 weird', amount: '', unit: '' })
  })

  it('does not treat a non-numeric leading token as an amount ("Saft einer Zitrone")', () => {
    expect(parseIngredientLine('Saft einer Zitrone')).toEqual({ name: 'Saft einer Zitrone', amount: '', unit: '' })
  })

  it('does not mis-parse a numeric-looking word ("200ml Wasser" — no space)', () => {
    // No space after the number → the leading token is "200ml", not a clean number → name stays whole.
    expect(parseIngredientLine('200ml Wasser')).toEqual({ name: '200ml Wasser', amount: '', unit: '' })
  })

  // 3/80 is a 4th-decimal tie where JS toFixed and Java "%.3f" historically diverged (0.037 vs
  // 0.038). Both parsers now round via Math.round(n*1000) → 0.038 identically; this locks the parity.
  it('rounds a fraction tie identically to Android (3/80 → 0.038)', () => {
    expect(parseIngredientLine('3/80 g Mehl')).toEqual({ name: 'Mehl', amount: '0.038', unit: 'g' })
  })

  it('strips only a single trailing dot from the unit token ("g.." is not a unit)', () => {
    expect(parseIngredientLine('2 g.. Mehl')).toEqual({ name: 'g.. Mehl', amount: '2', unit: '' })
  })
})
