# Einkaufs-Icons (designte SVGs)

Hier liegen die designten SVG-Icons, die die Emoji in der Einkaufsliste ersetzen.
Dateien in diesem Ordner werden von `shoppingCategories.tsx` **automatisch registriert**
(`import.meta.glob`) — kein manuelles Verdrahten nötig. Fehlt für ein Item ein SVG,
rendert weiterhin das Emoji als Fallback.

## Namenskonvention

- **Item-Icon:** `<slug-des-namens>.svg` — Schlüssel = `slugifyIconKey(name)`:
  Kleinbuchstaben, führende Menge/Einheit entfernt, Umlaute transliteriert
  (ä→ae, ö→oe, ü→ue, ß→ss), Rest zu `a–z0–9`, Wörter mit `-` verbunden.
  Beispiele: `Leberkäse` → `leberkaese.svg`, `Möhren` → `moehren.svg`,
  `Olivenöl` → `olivenoel.svg`.
- **Kategorie-Icon:** `cat-<kategorieschlüssel-klein>.svg` — der Schlüssel wird nur
  kleingeschrieben (kein weiterer Umbau), Unterstriche bleiben. Beispiele:
  `cat-produce.svg`, `cat-meat_fish.svg` (Schlüssel `MEAT_FISH`), `cat-other.svg`.

Reihenfolge der Auflösung: Item-Icon (per Name) → Kategorie-Icon → Emoji-Fallback.

## Format

- viewBox `0 0 96 96`, Motiv zentriert, transparenter Hintergrund.
- Optimierte SVGs (Pfade, keine eingebetteten Rasterbilder, keine `<text>`/Fonts).
- Bei 24px (Liste) klar erkennbar; die App rendert sie in einer 24×22px-Box.

Der Generier-Prompt für das vollständige Set liegt im zugehörigen GitHub-Issue.
