// Split a written shopping-item name into a leading "<qty> <unit>" prefix and the rest, for the
// tile/row detail line (#440). Display-only; an independent copy of GroceryCatalog.normalize's
// quantity unit list (keep the units roughly in sync). "2 L Milch" → {detail:"2 L", title:"Milch"};
// a name without a quantity prefix → {title:name}. Never throws, never yields an empty title.
const QTY_PREFIX =
  /^\s*(\d+(?:[.,]\d+)?\s*(?:g|kg|mg|ml|l|el|tl|stk|stück|st|x|prise|prisen|bund|dose|dosen|pkg|pck|pack|packung|tasse|cup|msp|glas|gläser|becher|flasche|flaschen)?\.?)\s+(.+)$/i

export function splitQuantity(name: string): { detail?: string; title: string } {
  const m = name.match(QTY_PREFIX)
  if (m && m[2].trim()) return { detail: m[1].trim(), title: m[2].trim() }
  return { title: name }
}
