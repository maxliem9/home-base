# Einkaufs-Icons (designte SVGs, Bring-Stil)

Designtes Icon-Set, das die Emoji in der Einkaufsliste ersetzt (#443). `shoppingCategories.tsx`
registriert die Dateien **automatisch** (`import.meta.glob`) — kein manuelles Verdrahten.

## Struktur

- `items/<en>.svg` — ein Icon pro Produkt, **englischer** Dateiname (`tomatoes.svg`,
  `meatloaf.svg`, `carrots.svg`).
- `categories/<key>.svg` — ein Icon pro Kategorie-Header (`produce.svg`, `dairy.svg`,
  `meat-fish.svg`, … `other.svg`).

## Wie ein Item zu seinem Icon kommt

1. Der deutsche Item-Name wird normalisiert (`slugifyIconKey`: klein, führende Menge/Einheit
   weg, Umlaute→ASCII): `"500 g Möhren"` → `moehren`, `"Leberkäse"` → `leberkaese`.
2. `ITEM_ICON_KEY` (in `../shoppingIconMap.ts`) mappt diesen Slug auf den **englischen**
   Dateinamen: `moehren` → `carrots`, `leberkaese` → `meatloaf`.
3. Kein Treffer → Kategorie-Icon (`CATEGORY_ICON_KEY`), sonst → Emoji-Fallback.

## Ein neues Item-Icon ergänzen

1. SVG nach `items/<en>.svg` legen (96×96 viewBox, transparent, Inline-Pfade, keine Fonts).
2. In `../shoppingIconMap.ts` für jeden passenden deutschen Namen einen Eintrag
   `'<slug>': '<en>'` ergänzen (Synonyme teilen sich ein Icon).

Drift-Hinweis: `ITEM_ICON_KEY` spiegelt die Namensmenge des Backend-`GroceryCatalog.seed`.
Wächst der Katalog, hier nachziehen — sonst greift „nur" das Kategorie-Icon. Saubere Zukunft:
Icon-Key serverseitig am Item (siehe #443).
