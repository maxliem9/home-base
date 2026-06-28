// Split a written shopping-item name into a leading "<qty> <unit>" prefix and the rest, for the
// tile/row detail line (#440). Display-only; an independent copy of GroceryCatalog.normalize's
// quantity unit list (keep the units roughly in sync). "2 L Milch" → {detail:"2 L", title:"Milch"};
// a name without a quantity prefix → {title:name}. Never throws, never yields an empty title.
const UNITS = 'g|kg|mg|ml|l|el|tl|stk|stück|st|x|prise|prisen|bund|dose|dosen|pkg|pck|pack|packung|tasse|cup|msp|glas|gläser|becher|flasche|flaschen'
const QTY_PREFIX = new RegExp(`^\\s*(\\d+(?:[.,]\\d+)?\\s*(?:${UNITS})?\\.?)\\s+(.+)$`, 'i')
// Same, but the unit is mandatory — used to gate the destructive add-time split so a bare leading
// number ("3 Musketiere", "2 Äpfel") is NOT torn apart when persisting.
const QTY_PREFIX_WITH_UNIT = new RegExp(`^\\s*\\d+(?:[.,]\\d+)?\\s*(?:${UNITS})\\.?\\s+.+$`, 'i')

/**
 * Split a leading "<qty> <unit>" prefix off a name. `requireUnit` (used at add-time, where the split
 * is persisted) only splits when a real unit is present; the lenient default (display) also splits a
 * bare leading count. "2 L Milch" → {detail:"2 L", title:"Milch"}; no prefix → {title:name}. Never
 * throws, never yields an empty title.
 */
export function splitQuantity(name: string, requireUnit = false): { detail?: string; title: string } {
  const m = name.match(QTY_PREFIX)
  if (m && m[2].trim() && (!requireUnit || QTY_PREFIX_WITH_UNIT.test(name))) {
    return { detail: m[1].trim(), title: m[2].trim() }
  }
  return { title: name }
}

/**
 * Title + quantity to display for an item (#445): an explicit `quantity` field wins (name stays
 * whole); otherwise the quantity is parsed out of the name. Keeps tiles and rows consistent whether
 * the amount was entered structurally or typed into the name (e.g. the batch/Wochenplan flow).
 */
export function itemDisplayParts(item: { name: string; quantity?: string }): {
  title: string
  detail?: string
} {
  if (item.quantity && item.quantity.trim()) return { title: item.name, detail: item.quantity.trim() }
  return splitQuantity(item.name)
}
