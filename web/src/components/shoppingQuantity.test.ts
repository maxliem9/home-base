import { describe, expect, it } from 'vitest'
import { itemDisplayParts, splitQuantity } from './shoppingQuantity'

// Display-only parse of a leading "<qty> <unit>" off the tile/row name (#440). Must never throw or
// yield an empty title.
describe('splitQuantity', () => {
  it('splits a leading quantity + unit', () => {
    expect(splitQuantity('2 L Milch')).toEqual({ detail: '2 L', title: 'Milch' })
    expect(splitQuantity('6 Stück Bananen')).toEqual({ detail: '6 Stück', title: 'Bananen' })
    expect(splitQuantity('500 g Möhren')).toEqual({ detail: '500 g', title: 'Möhren' })
    expect(splitQuantity('1,5 l Saft')).toEqual({ detail: '1,5 l', title: 'Saft' })
  })

  it('handles a bare leading count without a unit (lenient/display mode)', () => {
    expect(splitQuantity('2 Paprika')).toEqual({ detail: '2', title: 'Paprika' })
  })

  it('requireUnit only splits when a real unit is present (add-time, persisted)', () => {
    // a unit → split
    expect(splitQuantity('200 g Mehl', true)).toEqual({ detail: '200 g', title: 'Mehl' })
    // bare count, no unit → keep whole (don't tear "3 Musketiere" / "2 Äpfel" apart)
    expect(splitQuantity('2 Äpfel', true)).toEqual({ title: '2 Äpfel' })
    expect(splitQuantity('3 Musketiere', true)).toEqual({ title: '3 Musketiere' })
  })

  it('leaves a plain name untouched (no detail)', () => {
    expect(splitQuantity('Tomaten')).toEqual({ title: 'Tomaten' })
    expect(splitQuantity('Olivenöl')).toEqual({ title: 'Olivenöl' })
  })

  it('never yields an empty title', () => {
    // no quantity prefix at all → whole name kept as title
    expect(splitQuantity('42')).toEqual({ title: '42' })
    expect(splitQuantity('   ')).toEqual({ title: '   ' })
    // remainder is only whitespace → the trim() guard falls back to the full name (no empty title)
    expect(splitQuantity('5 kg   ')).toEqual({ title: '5 kg   ' })
  })
})

describe('itemDisplayParts', () => {
  it('prefers an explicit quantity field (name stays whole)', () => {
    expect(itemDisplayParts({ name: 'Mehl', quantity: '500 g' })).toEqual({ title: 'Mehl', detail: '500 g' })
    expect(itemDisplayParts({ name: 'Eier', quantity: '10er' })).toEqual({ title: 'Eier', detail: '10er' })
  })

  it('falls back to parsing the name when no quantity field is set', () => {
    expect(itemDisplayParts({ name: '200 g Mehl' })).toEqual({ title: 'Mehl', detail: '200 g' })
    expect(itemDisplayParts({ name: 'Tomaten' })).toEqual({ title: 'Tomaten' })
    // a blank quantity field is treated as unset → parse the name
    expect(itemDisplayParts({ name: 'Tomaten', quantity: '  ' })).toEqual({ title: 'Tomaten' })
  })
})
