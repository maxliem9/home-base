# Handoff: HomeBase — Android app (mobile)

## Overview
This is the **Android / phone** design for **HomeBase**, the German-language
household & family hub shared by two users (Max & Lea). It is the mobile
counterpart to the existing desktop web app and covers the **same six tools**:

1. **Heute** (Dashboard) — greeting, day overview, quick stats, agenda
2. **Aufgaben** — shared to-do lists grouped by due date, with subtasks
3. **Einkauf** — shared shopping lists with an "Im Wagen" (in-cart) section
4. **Notizen** — shared/private markdown notes
5. **Zeiterfassung** — time tracking with projects + a single running timer
6. **Rezepte** — recipe collection with detail + "add ingredients to list"

The **domain model, data, business rules, permissions, and German copy are
identical to the desktop app.** This document covers only the **mobile
presentation layer** — the app shell, navigation, per-screen layouts, and the
mobile component specs. For the data model, API surface, seed data, ownership
rules, and the single-running-timer invariant, read the sibling handoff:
**`design_handoff_homebase/README.md`** (the desktop bundle). Everything there
applies here too; don't duplicate that logic — reuse it.

## About the Design Files
The files in this bundle are **design references created in HTML/React (via
in-browser Babel)** — prototypes that demonstrate the intended look, layout, and
behavior on a phone. They are **not production code to copy directly.**

Your task is to **recreate these designs in the target app's environment** using
its established patterns. The natural target for Android is **Jetpack Compose**
(or React Native / Flutter if that's the existing stack). Map the ad-hoc CSS
tokens below onto the platform's theme system (e.g. a Compose `MaterialTheme`
with custom color/typography), and replace the in-browser Babel + global-script
prototype approach with real navigation and a real data layer.

The screens are presented as static phone frames on a **design canvas**
(`android/design-canvas.jsx`) — that canvas and the device bezel
(`android/android-frame.jsx`, of which only the status bar + gesture-nav pill are
used) are **presentation scaffolding, not part of the product.** Ignore them
when implementing; build real screens inside the OS chrome.

## Fidelity
**High-fidelity.** Final colors, typography, spacing, density, component
treatments, and screen states are all represented. Recreate the UI faithfully.
This is a brand-faithful adaptation: it keeps HomeBase's warm-clay identity
rather than adopting stock Material You — but it uses **Android interaction
patterns** (navigation drawer, top app bar, FAB, bottom sheets, gesture nav).

---

## Design Tokens

Same "ship" configuration as desktop: **light theme · "klar" look · clay accent
(hue 35) · regular density.** All colors authored in **OKLCH**. If your platform
needs hex/sRGB, convert from these — do not eyeball new values.

### Color — surfaces & ink (light)
| Token | OKLCH | Use |
|---|---|---|
| `--paper` | `oklch(0.96 0.014 128)` | app background (warm off-white) |
| `--surface` | `oklch(0.988 0.008 128)` | cards, sheets, app bar |
| `--surface-2` | `oklch(0.935 0.018 128)` | inset fills, chips, segmented bg |
| `--surface-3` | `oklch(0.9 0.024 128)` | count-badge bg |
| `--ink` | `oklch(0.26 0.022 152)` | primary text |
| `--ink-2` | `oklch(0.44 0.02 152)` | secondary text |
| `--ink-3` | `oklch(0.57 0.018 150)` | muted / eyebrow / placeholder |
| `--line` | `oklch(0.87 0.018 130)` | borders, input outlines |
| `--line-soft` | `oklch(0.915 0.014 130)` | hairline dividers |

### Color — accent (clay / "Lehm", hue 35)
| Token | OKLCH | Use |
|---|---|---|
| `--accent` | `oklch(0.52 0.078 35)` | FAB, primary buttons, active nav, checks |
| `--accent-strong` | `oklch(0.44 0.085 35)` | pressed primary |
| `--accent-soft` | `oklch(0.925 0.04 35)` | active-nav bg, soft button bg, today badge |
| `--accent-soft-2` | `oklch(0.87 0.058 35)` | hover/secondary soft, toast link |
| `--accent-ink` | `oklch(0.4 0.08 35)` | text/icons on soft accent fills |
| `--on-accent` | `oklch(0.985 0.018 35)` | text/icons on solid accent |
| `--clay` (hue 42) | `oklch(0.56 0.092 42)` | live-timer dot, secondary decorative |
| `--clay-soft` (hue 42) | `oklch(0.91 0.045 42)` | clay badge bg |

### Status / semantic
- Overdue badge: bg `oklch(0.93 0.05 32)`, text `oklch(0.5 0.13 32)`
- Priority dots: **Hoch** `oklch(0.58 0.16 25)` · **Mittel** `oklch(0.72 0.13 70)` · **Niedrig** `oklch(0.64 0.08 195)`
- User hues (avatars): **Max** `oklch(0.62 0.09 150)` (green, initial "M") · **Lea** `oklch(0.62 0.09 250)` (blue, initial "L")
- Project swatch palette (hex, from data): `#5b9e7a` `#c9805a` `#6a8fc0` `#c2a14d` `#a86ab0` `#9a9a9a`
- Week-bar user segments: Max `oklch(0.62 0.09 150)`, Lea `oklch(0.62 0.09 250)`

### Typography
- **UI + display font:** `"Helvetica Neue", Helvetica, Arial, Roboto, sans-serif`. On Android, use **Roboto** or a close Helvetica-like grotesque; the desktop intent is Helvetica Neue.
- **Mono** (numbers, timers, amounts, counts): `ui-monospace, "Roboto Mono", monospace`, `font-variant-numeric: tabular-nums`.
- Base body **15px / 1.5**.
- **Display / page title** (app-bar `h`): 23px, weight 700, letter-spacing −0.03em.
- **Dashboard greeting** ("Hallo, Max."): 38px, weight 700, −0.03em, line-height 1.0.
- **Note / recipe detail title:** 27px, weight 700, −0.025em.
- **Eyebrow** (above titles, section labels): 11–12.5px, uppercase, letter-spacing 0.04–0.07em, weight 600, color `--ink-3`.
- Headings weight 600, letter-spacing −0.01em.

### Radius (klar)
`--radius` **11px** · `--radius-sm` **8px** · `--radius-lg` **16px** · sheets top corners **26px** · FAB **19px** · phone frame **44px**.

### Spacing / density (regular)
`--gap` **18px** (card/section rhythm) · `--row-pad` **13px** (list-row vertical) · screen content padding **18px** horizontal · scroll bottom padding **110px** (clears the FAB) · min touch target **44px**.

### Shadows (light)
- `--shadow-sm` `0 1px 2px oklch(0.35 0.03 150 / 0.07)` — cards, buttons
- `--shadow` `0 5px 18px oklch(0.32 0.04 150 / 0.09), 0 2px 5px oklch(0.32 0.04 150 / 0.05)` — running timer card
- `--shadow-lg` `0 24px 56px …, 0 6px 16px …` — drawer, bottom sheets, toast
- **FAB shadow:** `0 8px 22px oklch(0.5 0.09 35 / 0.34), 0 2px 6px oklch(0.4 0.08 35 / 0.22)`

> All tokens are defined in `android/hb-mobile.css` under the `.hbphone` scope —
> that file is the source of truth for exact values.

---

## App Shell & Navigation

### Design dimensions
Screens are designed for a **412 × 892 dp** viewport (content width ~376 dp after
18 dp side padding). Status bar 40 dp at top, gesture-nav pill 24 dp at bottom
are OS-owned.

### Navigation model — **navigation drawer** (mirrors desktop sidebar)
There is **no bottom tab bar.** A left **navigation drawer** is the primary nav,
opened by the hamburger (`menu`) icon in each screen's top app bar. The drawer:
- **Width 300 dp**, surface bg, right hairline border, large shadow, scrim behind.
- **Brand block** (top): 40 dp clay rounded-square mark (radius 12) with `home`
  glyph + "HomeBase" (22px/700/−0.03em) + "MAX & LEA" eyebrow.
- **Nav list:** one row per tool — `icon` (21px) + label (15.5px/500), 12 dp
  padding, radius 8. **Active** row: `--accent-soft` bg, `--accent-ink` text
  (weight 600), accent-colored icon. Rows can show a right-aligned **count badge**
  (Aufgaben "4", Einkauf "10") or a small **clay dot** (Zeit, when a timer runs).
- **Footer** (bottom, hairline top border): Max avatar (36) + "Max" /
  "Echtzeit-Sync aktiv" + a small accent **sync dot**.
- Nav items, in order: **Heute, Aufgaben, Einkaufsliste, Notizen,
  Zeiterfassung, Rezepte.**

### Top app bar (per screen)
Flex row, paper bg, ~56 dp.
- **Left:** 44 dp icon button — `menu` (hamburger) on top-level screens, or
  `chevronLeft` (back) on detail screens (Notiz detail, Rezept detail).
- **Title block:** optional **eyebrow** (uppercase, --ink-3) above the **title**
  (23px/700/−0.03em). On detail screens the title is smaller (20px) and the bar
  gets a hairline bottom border + surface bg (`m-appbar--bordered`).
- **Right:** 0–2 icon actions (`search`, `bell`, `more`, `edit`). Icon buttons
  are 44 dp, `--ink-2`, circular pressed state (`--surface-2`). A small accent
  count badge can sit on an action.

### FAB (floating action button)
Bottom-right, **right/bottom 18–22 dp**, above the gesture nav. Clay bg,
`--on-accent` icon (`plus`, 24px). **Extended** style: pill (radius 19), height
58, with a short label ("Aufgabe", "Artikel", "Notiz", "Projekt", "Rezept").
One FAB per top-level screen = that screen's primary create action.

### Bottom sheets (replace desktop modals)
Rise from the bottom; **top corners radius 26**, surface bg, large shadow, scrim.
- **Grip** handle (38×4, `--line`) centered at top.
- **Header:** title (display, 21px/700/−0.02em) + `x` close button (omitted on
  some). 
- **Body:** 20 dp horizontal padding, fields stacked with 16 dp gap.
- **Footer:** action buttons, full-width split (e.g. "Abbrechen" secondary +
  primary), honoring bottom safe-area inset.
- **Two heights:** standard (content height, max 92%) for small forms; **full**
  (`m-sheet--full`, ~96% height) for long forms — Project detail, New recipe.

### List tabs (Aufgaben, Einkauf)
Horizontal **scrollable** tab strip under the app bar, hairline bottom border.
Each tab: label (14.5px/600) + a mono **count pill**; **active** tab = `--ink`
text + 2 dp accent underline. Private lists show a `lock` glyph. A trailing
**"+ Neue Liste"** tab (accent text) opens the new-list sheet.

### Toast
Bottom-anchored (above FAB), dark pill (`oklch(0.26 0.022 152)` bg, light text),
icon + message + trailing accent action link. Auto-dismiss ~2.6 s. Used after
"Zutaten zur Liste".

### Reused leaf components (specs in `hb-mobile.css`)
`hb-btn` (primary / secondary / ghost / soft, md/sm, pill radius 999),
`hb-badge` (neutral / accent / clay / today / soon / over / far),
`hb-check` (24 dp, radius 7; checked = accent fill + white `check`),
`hb-row` (list row: 13 dp pad, hairline divider), `hb-avatar` (hue circle),
`hb-card` (surface, hairline border, radius 11, shadow-sm), `hb-input`
(radius 8, accent focus ring), `hb-seg` (segmented control), `hb-quickadd`
(pill add-bar), `hb-pick` (pill choice chip), `hb-empty` (centered empty state).

---

## Screens / Views

> 22 frames total, grouped by tool. Each top-level screen = scrollable content +
> top app bar + FAB. "Sheet" / "keyboard" / "empty" entries are **states** of
> their parent screen, not separate destinations.

### 1 · Heute (Dashboard) — `m-screens-heute.jsx`
Scroll content:
- **Eyebrow** date ("Mittwoch, 3. Juni") + **greeting** "Hallo, Max." (38px).
  Greeting is time-of-day aware (morning/afternoon/evening) per desktop logic.
- **Quick-add** pill bar (sparkle + "Schnell erfassen …" + accent round `plus`).
- **Quick stats** — 2×2 grid of stat cards: icon chip (accent-soft) + big value
  (30px/600) + label. Values: **2** Heute fällig (`calendar`), **4** In der Inbox
  (`inbox`), **2** Morgen fällig (`clock`), **1** Heute erledigt (`checkCircle`).
- **"Heute dran"** card: header + "Alle Aufgaben ›" link; 2 mini task rows
  (check + title + priority + assignee avatar).
- **"Zeiterfassung"** card: header + "Öffnen ›"; running widget (project dot +
  name + description + mono clock `01:35:08`) + soft "Stoppen" button.
- **"Einkaufsliste"** card: header + "Öffnen ›"; 4 item rows + "+ 5 weitere".
- **"Abend-Digest"** card (accent-soft top gradient): `send` icon + title +
  "heute · 20:00" badge + subtitle; 3 key/value rows (Heute erledigt 1, Neu in
  der Inbox 4, Morgen fällig 2). This previews the Telegram digest both users get.
- App-bar actions: `search`, `bell`.
- **Drawer-open state** is shown as a separate frame.

### 2 · Aufgaben (Tasks) — `m-screens-aufgaben.jsx`
App bar: eyebrow "Aufgaben" + title = active list name ("Haushalt"); `more` action. FAB "Aufgabe".
- **List tabs:** Haushalt (4), Familie & Termine (4), Persönlich (lock, 2), + Neue Liste.
- **Quick-add** bar ("Aufgabe hinzufügen …") adds an undated task to the active list.
- **Due-date groups**, rendered in order when non-empty, each with a mono count:
  **Überfällig → Heute → Demnächst → Später → Ohne Datum.** Shown: Heute (2),
  Demnächst (1), Ohne Datum (1).
- **Task row:** `hb-check` + title; meta line = priority pill + due badge +
  optional description; right side = **subtask pill** (`done/total` + chevron, or
  dashed "Unteraufgaben" when none) and **assignee avatar** — or a **"Planen"**
  button instead of the avatar when the task is undated.
- **Erledigt** = collapsible footer (chevron + "Erledigt" + count "3"), collapsed by default.
- **States (separate frames):**
  - **Subtasks expanded:** tapping the subtask pill reveals an inline checklist
    (19 dp checks, 38 dp left inset) + an "Unteraufgabe hinzufügen …" input. Toggling a
    subtask updates the count; parent is **not** auto-completed.
  - **Aufgabe bearbeiten** (sheet): Titel, Beschreibung (textarea), **Zuständig**
    (pick chips: Max / Lea / Niemand), **Fällig** (date), **Priorität** (pick chips
    Niedrig/Mittel/Hoch with colored dots), footer = trash + Abbrechen + Speichern.
  - **Neue Liste** (sheet): Name + **Sichtbarkeit** segmented (Geteilt `users` /
    Privat `lock`) + helper text.
  - **Empty:** tabs + quick-add + centered empty ("Alles erledigt").

### 3 · Einkauf (Shopping) — `m-screens-einkauf.jsx`
App bar: eyebrow "Einkaufsliste" + title = list name ("Wocheneinkauf"); `more`. FAB "Artikel".
- **List tabs:** Wocheneinkauf (7), Drogerie (3), + Neue Liste. **No item
  categories** — each list is one flat list.
- **Add-item** pill bar ("Artikel hinzufügen …", name only).
- **Open items:** rows = check + name + "who added" avatar. (Äpfel, Bananen,
  Tomaten, Milch (1,5%), Naturjoghurt, Gouda am Stück, Filterkaffee.)
- **"Im Wagen · 2"** section header + "Abgehakte entfernen" link; checked rows
  (strikethrough, accent check): Babyspinat, Butter.
- **States (separate frames):**
  - **Artikel eingeben (keyboard):** the add-bar focused with a caret + green
    confirm button, and the **Gboard** keyboard docked at the bottom (use the OS
    keyboard; the prototype draws a stand-in). Demonstrates the typing flow.
  - **Neue Liste** (sheet): just a Name field + "Alle Einkaufslisten sind geteilt."
  - **Empty:** "Liste ist leer".

### 4 · Notizen (Notes) — `m-screens-notizen.jsx`
App bar: eyebrow "Notizen" + title; `search`. FAB "Notiz".
- **Tag filter row:** chips "Alle" (active, accent fill) + tags (urlaub, zuhause, …).
- **Note cards:** optional `lock` (private) + title; 2-line preview; meta row =
  author avatar (18) + relative time + static tag chips.
- **States:**
  - **Detail:** back app bar (title "Notiz", `edit` + `more` actions); big note
    title (27px); meta = "Geteilt" badge (`users`) + author avatar + "Lea · vor 4
    Std."; static tag chips; then **rendered markdown** (`hb-md`): h2/h3, paragraphs,
    bulleted + numbered lists, `**bold**`, and a left-accent-bar **blockquote**.
    Use the desktop `renderMarkdown` (in desktop `ui.jsx`) for the real renderer
    (headings, bold, italic, inline code, lists, blockquote).
  - **Empty:** "Noch keine Notizen".

### 5 · Zeiterfassung (Time tracking) — `m-screens-zeit.jsx`
App bar: eyebrow "Zeiterfassung" + title "Zeit"; `more`. FAB "Projekt".
- **Timer hero (running):** accent-soft diagonal gradient card, "LÄUFT" live row
  (clay pulsing dot), project dot + name, description, **46px mono clock**
  (`01:35:08`, ticks every second from `started_at`), full-width "Timer stoppen"
  primary button. **Invariant: one running timer per user** — starting one stops
  the user's previous.
- **Projekte** section ("Archiv ›" link): 2-col grid of project cards — color dot
  + name, big total (mono), and a **Start**/**Stopp** button. The running project
  card gets an accent border + 3 dp accent ring.
- **Letzte Einträge:** day-grouped (`Heute`, `Gestern`, …) with **per-day total**
  separators (`Σ 2 Std 45 Min`). Entry row = project dot, name · description,
  author avatar + time range, mono duration, and a **trash** icon for the
  current user's own entries or a **lock** icon for the other user's (permission rule).
- **States:**
  - **Kein Timer aktiv (idle):** hero shows "KEIN TIMER AKTIV" + muted `00:00:00`
    + prompt; all project cards show **Start**.
  - **Projekt-Detail** (full sheet): title + "Aktives Projekt" dot; **4 stat
    tiles** (Gesamt, Diese Woche, Einträge, ø/Eintrag); **per-user chips** (Max vs
    Lea totals); **"Pro Woche"** — week rows (Diese Woche / Letzte Woche / date
    range) each with a **horizontal bar segmented by user** (segment width ∝ that
    user's time, scaled to the busiest week) + week total + entry count; **"Alle
    Einträge"** — day-grouped history with the same delete/lock rule.
  - **Neues Projekt** (sheet): Name + **Farbe** swatch picker (6 swatches, first active).

### 6 · Rezepte (Recipes) — `m-screens-rezepte.jsx`
App bar: eyebrow "6 Rezepte" + title "Rezepte"; `search`. FAB "Rezept".
- **Category chips** (scrollable): Alle, Frühstück, **Hauptgerichte** (MAIN —
  lunch+dinner merged), Snack, Dessert, Getränk.
- **Recipe grid** (2-col cards): **image placeholder band** — a diagonal-stripe
  fill in a **deterministic warm hue per recipe** (`--rh`), with a `chef` glyph +
  mono "FOTO FOLGT" label and a category badge top-left; then title, 2-line
  description, meta (`clock` total time · `users` servings).
- **States:**
  - **Detail:** back app bar ("Rezept", `more`); full-width **hero** placeholder
    band (188 dp, same striped style) with category badge; title (27px) +
    description; **4 fact tiles** (Portionen, Vorb. Min, Koch Min, Gesamt);
    **Zutaten** list (mono amount + name); **Zubereitung** = numbered steps
    (accent-soft number circles); footer = "Löschen" (danger ghost) + "Zutaten zur
    Liste" (primary, `cart`). The "added" **toast** state is shown ("5 Zutaten zur
    Einkaufsliste hinzugefügt" + "Ansehen"). Ingredients push to the **first**
    shopping list, de-duplicated by lowercased name.
  - **Neues Rezept** (full sheet): Titel, **Kategorie** select, Portionen / Vorb. /
    Kochen (3-col), Beschreibung, **Zutaten** (mono textarea, one per line — a
    "200 g Mehl" line parses into `{amount, unit, name}`), **Schritte** (one per
    line). Saving adds to top and opens detail.
  - **Empty:** category with no recipes ("Keine Rezepte").

---

## Interactions & Behavior
- **Drawer** opens from the hamburger; scrim tap or back gesture closes it;
  selecting an item navigates and closes the drawer.
- **FAB** triggers the screen's create action (opens the relevant sheet).
- **Bottom sheets** dismiss on scrim tap, the close button, the footer
  "Abbrechen", or a downward swipe on the grip; submitting runs the create/save.
- **List tabs** switch the active list (Aufgaben/Einkauf); the title and content
  update; "+ Neue Liste" opens the new-list sheet.
- **Checkbox** toggles done/checked with the accent fill; shopping checks move the
  item into "Im Wagen"; subtask checks update the `done/total` pill without
  collapsing the panel.
- **Subtask pill** expands/collapses the inline checklist (chevron rotates).
- **Timer clock** ticks every second from `started_at`; **Stopp** ends the entry;
  starting a project's timer stops the user's previous one.
- **Toast** appears after pushing recipe ingredients to the shopping list and
  auto-dismisses (~2.6 s); "Ansehen" deep-links to Einkauf.
- **Permissions:** users may delete only their **own** time entries (others show a
  lock); private lists/notes are visible only to their creator.
- **Animations:** drawer/sheet slide-in (~180–220 ms ease-out), scrim fade,
  button press translateY(1px). Respect reduced-motion. Keep entrance animation
  end-states as the base style so content is visible without JS.

## State Management
Reuse the desktop store/api described in `design_handoff_homebase/README.md`
(collections: `todos`, `todoLists`, `shopping`, `shoppingLists`, `notes`,
`projects`, `timeEntries`, `recipes`; all mutations via the `api` object). The
mobile UI is a **new presentation layer over the same state.** Additional
**view-local UI state** the mobile shell needs:
- `drawerOpen: boolean`
- `activeRoute` (heute | aufgaben | einkauf | notizen | zeit | rezepte)
- `activeListId` per tab-based tool (Aufgaben, Einkauf)
- `openSheet` (which bottom sheet, + its target entity id)
- `expandedTaskId` (subtask panel), `doneCollapsed` (Aufgaben)
- `selectedNoteId`, `selectedRecipeId`, `openProjectId` (detail views)
- `recipeCategory` / `noteTag` filter
- `toast` (message + action), `tickingNow` (drives the live clock)
The "current user" is `max` in the prototype; in production it's the
authenticated user and drives delete permissions + private visibility.

## Assets
- **No external image assets.** Recipe photos are **placeholders** — a striped
  warm-hue band ("Foto folgt"); wire real photos later.
- **Icons:** a single inline stroke-icon set (24×24 viewBox, `currentColor`,
  1.8 stroke, round caps/joins) defined in `m-shell.jsx`. Names used: home,
  check, checkCircle, circle, plus, cart, note, clock, chef, play, stop, search,
  tag, trash, edit, x, chevron(Left/Right/Up/Down), calendar, inbox, flag, lock,
  users, archive, send, sparkle, dot, menu, more, bell, settings, list. Swap for
  your platform's icon library (Material Symbols equivalents are fine — match the
  thin, rounded style).
- **Avatars:** initial-on-tinted-circle using the user's hue (Max 150 / Lea 250).
- **Fonts:** Helvetica Neue stack (use Roboto / a Helvetica-like grotesque on
  Android) + a mono for numerals.

## Files (design reference source)
- `HomeBase Android.html` — entry point; open in a browser to view all frames on
  the canvas (script/style load order lives here).
- `android/hb-mobile.css` — **source of truth** for tokens + all mobile component
  styles (`.hbphone` scope).
- `android/m-shell.jsx` — icon set, Avatar, Phone frame, AppBar, FAB, Drawer,
  Bottom-Sheet, Scroll primitives.
- `android/m-screens-heute.jsx` — Dashboard + drawer-open state.
- `android/m-screens-aufgaben.jsx` — Tasks: list, subtasks, edit/new-list sheets, empty.
- `android/m-screens-einkauf.jsx` — Shopping: list, keyboard-entry, new-list sheet, empty.
- `android/m-screens-notizen.jsx` — Notes: list, markdown detail, empty.
- `android/m-screens-zeit.jsx` — Time: running, idle, project detail, new project.
- `android/m-screens-rezepte.jsx` — Recipes: grid, detail+toast, create form, empty.
- `android/m-app.jsx` — canvas assembly (presentation only — **not** product nav).
- `android/android-frame.jsx`, `android/design-canvas.jsx` — **scaffolding only**
  (device status bar / gesture pill + the review canvas). Not part of the app.

### See also
- `design_handoff_homebase/README.md` — the **desktop** handoff: full data model,
  api surface, seed data, permissions, recipe-category merge, date/week helpers,
  and German content. **Read it first** for everything non-visual.
