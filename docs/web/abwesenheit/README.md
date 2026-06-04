# Handoff: Abwesenheit / Familienkalender (Absence & Vacation Planner)

## Overview
A shared household absence planner for the **HomeBase** family app — it replaces the per-person Excel sheets a couple uses to track who is off when. Two people (here **Max** and **Chen**) mark each day by type (vacation / own sickness / child-sick / public holiday), with recurring part-time "fixed free day" rules, per-person public holidays by German Bundesland, half-days, bulk period entry, and a yearly budget summary (entitlement, carry-over with expiry, sickness tallies). Kita (daycare) closures are tracked household-wide as a background marker.

The feature lives at the in-app route `abwesenheit` ("Abwesenheit" in the sidebar) and offers **two switchable layouts of the same data**:
- **Jahresraster** (default) — a months×days year grid, the Excel replacement.
- **Monatskalender** — a single-month calendar, both people charted per day.

## About the Design Files
The files in this bundle are **design references created in HTML/React-via-Babel** — a working prototype showing intended look and behavior, **not production code to ship directly**. The data lives in an in-memory seed (no backend, no persistence across reload). The task is to **recreate this feature in the target codebase's environment** (its framework, state layer, and persistence) using established patterns. The logic files (`holidays.jsx`, `abw_core.jsx`) are framework-agnostic plain JS and can be ported almost verbatim; the `.jsx` view files illustrate component structure and should be rebuilt in the host's component system.

## Fidelity
**High-fidelity.** Final colors, typography, spacing, interactions, and German domain logic are all defined. Recreate pixel-faithfully using the codebase's libraries. Colors are expressed in **oklch** and are theme-adaptive (light/dark) and hue-configurable — preserve that approach rather than flattening to static hex.

---

## Domain model & business logic (the important part)

### Day-types (explicit, user-set — stored)
| id | label (DE) | meaning |
|---|---|---|
| `URLAUB` | Urlaub | vacation |
| `KRANK` | Krank | own sickness |
| `KIND_KRANK` | Kind-krank | child-sick — **separate statutory counter** (DE Kinderkrankentage, default cap 15/parent/yr) |

### Derived day-states (computed, never stored)
- **FEIERTAG** — public holiday, computed per person from their Bundesland + year (see Holidays).
- **TEILZEIT** — a recurring "fixed free day" from a part-time rule (e.g. "every Monday off, Jan–Apr").
- **WEEKEND** — Sat/Sun.
- **(none)** — a normal working day.

### Half-days
An absence may cover half a day: `half ∈ { null (full), "vm" (Vormittag = AM), "nm" (Nachmittag = PM) }`. A half-day counts as **0.5**. In the year grid the marker ("AM"/"PM") is rendered **on that person's side of the split cell** (left = Max, right = Chen). In the month grid the chip reads e.g. "AM Urlaub".

### Counting rules (per person, per year) — `summarize()`
Iterate every date of the year; for each stored absence:
- It only counts if the day **would otherwise be worked** — i.e. **not** weekend, **not** that person's public holiday, **not** a part-time free day. (Marking vacation on an already-free day is a no-op for counts.)
- `URLAUB` on a workday: **taken** if date ≤ today, else **planned**. Amount = 0.5 if half, else 1.
- `KRANK` / `KIND_KRANK` on a workday: added to their respective tallies.

Budget math:
- `allowance` = yearly entitlement (Max 30, Chen 24 in seed).
- `carry` = Resturlaub carried from previous year, with an expiry date `carryoverExpires` (default `YYYY-03-31`).
- `total = allowance + carry`; `used = taken + planned`; `remaining = total − used`.
- Carry-over expiry: `carryExpired = today > carryoverExpires`. `carryUsed = min(carry, taken)`; if expired, `carryLost = max(0, carry − carryUsed)` is shown as "X verfallen" (warning style). Not a payroll-grade accrual engine — it's a clear household view.

### Public holidays — `HBcal.holidays(year, stateCode)`
Computed, **not** stored. Returns `{ "YYYY-MM-DD": "Name" }` for one of the 16 German Bundesländer. Movable feasts derive from Easter (Anonymous Gregorian algorithm). State applicability matters and is the reason holidays differ per person:
- Example contrast in seed: **Berlin** has Internationaler Frauentag (Mar 8); **Bayern** has Heilige Drei Könige (Jan 6), Fronleichnam, Mariä Himmelfahrt (Aug 15), Allerheiligen (Nov 1) — none of which Berlin has.
- Special case: Buß- und Bettag = the Wednesday before Nov 23 (Sachsen only).
- Full state→holiday mapping is in `holidays.jsx`; port it as-is. (Note: Mariä Himmelfahrt is treated as a Bayern-wide holiday for simplicity; in reality it's municipality-dependent — make it configurable if needed.)

### Part-time / fixed free days
Rule shape: `{ id, user_id, weekday (ISO 1=Mon … 5=Fri), start: "YYYY-MM-DD", end: "YYYY-MM-DD" | null }`. A day is part-time-free if a rule matches its ISO weekday and `start ≤ date ≤ (end || ∞)`. Seed: Max → Mondays off Jan 1–Apr 30; Chen → Fridays off from Mar 1 (open-ended).

### Bulk period entry (range)
`setAbsenceRange(userId, type, from, to, half)`:
- Setting a type applies it **only to working days** in the range (skips weekends/holidays/part-time-free, per person) — so a "2-week vacation" yields 10 days for Max but 8 for Chen if her two Fridays fall in it.
- `type === null` **clears** every absence for that user in the range (all dates).
Triggered via a "Zeitraum" button, or by **shift-clicking** a second day in either grid after a first click (the span between the two opens the range modal pre-filled).

### Kita closures (household-level)
`{ id, date, label }`. Background marker only (not per person, not counted). Managed in Settings (list + single-day add + weekday-only range add) and togglable per-day in the day editor.

---

## Screens / Views

### 1. Page shell — `AbwesenheitView`
- **Layout**: standard app page (`.hb-page--wide`, max-width 1320px, centered, 34px top / 40px side padding). Page header row: left = eyebrow "FAMILIENKALENDER" + H1 "Abwesenheit"; right = actions cluster.
- **Actions cluster** (right, gap 10px): a year stepper pill `‹ 2026 ›` (mono, bordered 999px pill), a segmented control **Jahr / Monat** (defaults to **Jahr**/raster), a secondary **+ Zeitraum** button, a secondary **Einstellungen** (edit icon) button.
- Below: **two summary cards** (2-col grid, collapses to 1-col < 1000px), then a **legend row** (legend left; hint "Tag klicken zum Bearbeiten · mit ⇧ Shift einen Zeitraum markieren" + a "Heute" link in month view, right), then the **grid card** holding the active layout.

### 2. Summary card (per person) — `AbwSummaryCard`
- Padding 18×20, flex column gap 13.
- Head: 34px avatar, name (700/16px) + Bundesland name (12px, ink-3); right-aligned big number = `remaining` (mono, 30px/700, colored in that person's Urlaub hue) under label "URLAUB ÜBRIG" (11px uppercase).
- Progress bar (`.abw-bar`, height 9px, radius 999px, track = surface-2): segment 1 = taken (person hue), segment 2 = planned (person hue at opacity 0.45).
- Legend line (12.5px): "● Genommen {taken}", "● Geplant {planned}" (faded dot), "Anspruch {allowance}" (muted).
- Foot chips (pills, 12px/600): carry-over chip — `+{carry} Übertrag · bis {DD.MM.}` (soft/accent style) or `· {carryLost} verfallen` (warn style if expired); "● Krank {n}"; "● Kind-krank {n} / {cap}".
- Day counts format with a comma decimal ("2,5").

### 3. Jahresraster (default) — `JahresRaster`
- CSS grid: `grid-template-columns: 28px repeat(12, minmax(34px,1fr))`, **1px gap on a `--line-soft` background** so gaps read as hairline gridlines; rounded 8px, `overflow:hidden`, `min-width:720px` (card is `overflow-x:auto`).
- Row 0: empty corner + 12 month abbreviations (Jan…Dez, 9.5px/700 uppercase). Then 31 rows: a right-aligned day-number label (mono, 9.5px, ink-3) + 12 day cells. Non-existent days (e.g. Feb 30) render as a faint empty cell.
- **Day cell = a 2-person diagonal split**: `linear-gradient(135deg, <Max colour> 0 ~50%, <thin divider> ~50%, <Chen colour> ~50% 100%)`. Upper-left triangle = first user (Max), lower-right = second (Chen). If both sides are the same colour it renders solid (no divider); a normal workday side = `var(--surface)`.
- Cell height 18px. **Today** = 2px accent outline (inset). **Kita closure** = inset 1.5px clay ring. Hover = slight brightness shift. `title` tooltip summarizes both people + Kita.
- **Half-day marker**: tiny "AM"/"PM" (6px/800) absolutely positioned — Max's at top-left, Chen's at bottom-right.

### 4. Monatskalender — `MonatsKalender`
- Month nav (centered): `‹  {MonthName} {year}  ›` (display font, 21px/700). Weekday header Mo…So (7-col, 11px/700 uppercase; weekend cols faded). Grid of up to 6 week-rows, 7 cols, gap 6px.
- **Day cell** (`min-height 94px`, surface, 1px line-soft border, radius 9px, padding 6×7): top row = day number (mono; today shown as an accent filled 21px circle) + optional "KITA" pill (clay, 9px uppercase); below = a stack of **chips**, one per person *only when they have a non-working state*. Out-of-month cells faded (opacity .4); weekend cells use surface-2; today cell = accent border + accent-soft ring.
- **Chip** (`.abw-mchip`): rounded 6px bar, colored by state; a 15px rounded "who" badge (person initial) + label text (truncating). Label = `"AM "/"PM " + type` for half-days, the holiday name for FEIERTAG, "frei" for part-time, nothing for plain workday.

### 5. Day editor (modal) — `AbwDayEditor`
- Width 480, title = "Montag, 6. April 2026" (weekday, D. MonthName YYYY).
- Per person: head (26px avatar + name + a right-aligned note like "Feiertag · {name}" / "Teilzeit · ohnehin frei" / "Wochenende"); a pill row **Arbeit / Urlaub / Krank / Kind-krank** (active = accent-soft); when a type is active, a half toggle **Ganzer Tag / Vormittag (AM) / Nachmittag (PM)**.
- Footer of body: a **Kita-Schließtag** switch (household) + optional "Anlass" text field when on.
- Footer button: "Fertig" (auto-saves on each change).

### 6. Period editor (modal) — `AbwRangeModal`
- Width 480, title "Zeitraum eintragen". Fields: **Für wen** (multi-select person pills, default both), **Art** (Urlaub / Krank / Kind-krank / "Eintrag löschen"), **Von** / **Bis** date inputs (2-col). A muted hint shows the working-day rule + a live "≈ X Tage für {name}" preview. Footer: Abbrechen / Übernehmen (disabled if no person or von>bis).

### 7. Settings (modal) — `AbwSettings`
- Width 620, title "Kalender-Einstellungen". Per person section: head (avatar + name); a 2-col field grid: **Bundesland** (select of 16 states), **Jahresanspruch** (number), **Resturlaub Vorjahr** (number), **… verfällt am** (date), **Kind-krank Anspruch** (number). Then a **Teilzeit · feste freie Tage** subsection: rows of [weekday select Mo–Fr] "ab" [date] "bis" [date|leer] [delete]; a "+ Freien Tag hinzufügen" link.
- Final section **Kita-Schließtage**: a hint, the sorted list (each row = date input + label text + delete), then add controls — an "Einzeltag" [date][+ Hinzufügen] row and a "Zeitraum" [date]"bis"[date][label][+ Hinzufügen] row (range skips weekends).

---

## Interactions & Behavior
- **Click a day** (either grid) → opens Day editor for that date; also sets the shift-anchor.
- **Shift-click a day** (when an anchor exists) → opens Period editor pre-filled with the span (min→max).
- **+ Zeitraum** → opens Period editor pre-filled today→today.
- **Jahr/Monat** segmented → swaps layout in place (same data). A `?abw=raster|monat` URL param can force a layout (used by the side-by-side comparison page); an effect re-applies it.
- **Year stepper** changes the active year (re-computes holidays; absences are keyed by full date so other years simply show empty until populated).
- **Month nav** wraps Dec↔Jan; "Heute" link resets to current year+month.
- All edits mutate the in-app store immediately (optimistic). Modals auto-save; no explicit save button except range/settings "Übernehmen/Fertig".
- No animations beyond the app's existing modal pop/fade and cell hover brightness. Respect reduced-motion as the host app does.

## State Management
In the prototype, a single in-memory store (`db`) holds all collections; an `api` object exposes mutators (pure, immutable updates). For production, back these with real persistence. Store collections used by this feature:
- `absences: [{ id, user_id, date, type, half }]` — one entry per person per date max.
- `parttime: [{ id, user_id, weekday(1–5), start, end|null }]`
- `kitaClosures: [{ id, date, label }]`
- `absSettings: [{ user_id, state, allowance, carryover, carryoverExpires, kindKrankCap }]` — one per user.
- Users come from `HB.users` (`{ id, name, hue, initials }`); the per-person **hue** drives the Urlaub colour and avatar tint.

API surface to reimplement (names from `app.jsx`):
`setAbsence(userId,date,type,half)`, `clearAbsence(userId,date)`, `setAbsenceRange(userId,type,from,to,half)`, `toggleKita(date,label,keep)`, `addKita(date,label)`, `addKitaRange(from,to,label)`, `updateKita(id,patch)`, `removeKita(id)`, `updateAbsSettings(userId,patch)`, `addPartTime(rule)`, `updatePartTime(id,patch)`, `removePartTime(id)`.

## Design Tokens
The feature **reuses the HomeBase token system** (defined in `src/styles.css`): `--surface`, `--surface-2/3`, `--ink`, `--ink-2/3`, `--line`, `--line-soft`, `--accent`, `--accent-soft`, `--accent-ink`, `--clay`, `--clay-soft`, `--radius*`, `--gap`, `--shadow*`, fonts `--font-ui` (Helvetica Neue) / `--font-mono`. It is **look-aware** (`data-look=klar|kontur|erde`) and **theme-aware** (`data-theme=light|dark`) and **density-aware** (`data-density`).

Feature-specific category colours are computed in JS (`ABW.palette(theme, {krank,kind,feier})`) and are **hue-configurable** via Tweaks (persisted keys `abwUrlaubMax`, `abwUrlaubChen`, `abwKrank`, `abwKind`, `abwFeier`). Fill formulas (light / dark):
- Urlaub (per person hue H): `oklch(0.7 0.108 H)` / `oklch(0.56 0.105 H)`
- Krank (default hue 27): `oklch(0.71 0.13 27)` / `oklch(0.56 0.13 27)`
- Kind-krank (default 62): `oklch(0.78 0.125 62)` / `oklch(0.62 0.115 62)`
- Feiertag (default 288): `oklch(0.82 0.05 288)` / `oklch(0.5 0.045 288)`
- Teilzeit (per person hue H): `oklch(0.91 0.034 H)` / `oklch(0.39 0.035 H)`
- Weekend: `oklch(0.925 0.006 130)` / `oklch(0.29 0.008 150)`
Person hues in seed: Max = 150 (sage), Chen = 250 (blue).

## Assets
No images. Icons come from the app's inline stroke-icon set (`src/icons.jsx`) — uses `calendar`, `chevronLeft/Right`, `plus`, `edit`, `trash`, `check`, `x`. Avatars are CSS (initials on a hue-tinted disc). No external fonts beyond system Helvetica Neue.

## Files
Bundled here (the feature's own code):
- `holidays.jsx` — `HBcal`: German holiday computation per Bundesland + date utils (plain JS, port directly).
- `abw_core.jsx` — `ABW`: day-types, theme palette, day-model resolution, summary math, range helpers (plain JS, port directly).
- `abw_grid.jsx` — `JahresRaster`, `MonatsKalender`, `AbwLegend` (React via Babel).
- `views_abwesenheit.jsx` — `AbwesenheitView` + `AbwSummaryCard`, `AbwDayEditor`, `AbwRangeModal`, `AbwSettings` (React via Babel).
- `abw.css` — all feature styles.

Integration points in the parent app (for reference; not all bundled):
- `src/seed.jsx` — seeds `absences`, `parttime`, `kitaClosures`, `absSettings`, and the two users (`max`, `lea`→display name "Chen").
- `src/app.jsx` — nav item `abwesenheit`, route→view mapping, the `api` mutators above, the `?abw=` param, Tweaks `Kalender-Farben` swatch section, and `TWEAK_DEFAULTS` color keys.
- `HomeBase.html` — script/style includes (load order: `holidays.jsx` → `abw_core.jsx` → `abw_grid.jsx` → `views_abwesenheit.jsx`).
- `src/ui.jsx`, `src/styles.css`, `src/views.css` — shared primitives (`Modal`, `Field`, `TextInput`, `Select`, `Button`, `IconButton`, `Avatar`, `SegmentedControl`, `Card`) and tokens.
- `Abwesenheitsplaner.html` — the side-by-side layout comparison page (DesignCanvas).
