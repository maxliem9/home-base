# Handoff: HomeBase — Familien-Hub (household management app)

## Overview
HomeBase is a German-language **household / family hub** web app shared by two
users (Max & Lea). It bundles six tools behind one sidebar:

1. **Dashboard** (Heute) — greeting, day overview, quick stats, today's agenda
2. **Aufgaben** — shared to-do / task management (Inbox → Planned → Done)
3. **Einkauf** — shared shopping list grouped by category
4. **Notizen** — shared markdown notes (private / shared visibility)
5. **Zeiterfassung** — time tracking with projects + a single running timer
6. **Rezepte** — recipe collection with categories, detail view, "add ingredients to list"

It is a two-person, real-time-feeling collaborative app: data is shared, but
**each user can only edit/delete their own entries** where ownership applies
(see Permissions below).

## About the Design Files
The files in this bundle are **design references created in HTML/React (via
in-browser Babel)** — they are prototypes that demonstrate the intended look,
layout, and behavior. They are **not production code to copy directly**.

Your task is to **recreate these designs in the target codebase's environment**
using its established patterns, component library, state management, and routing.
If no codebase exists yet, pick the most appropriate stack (the prototype is
React, so React/Next is a natural fit) and implement the designs there. Replace
the in-browser Babel + global-script approach with a real build, real routing,
and a real data layer.

## Fidelity
**High-fidelity.** Final colors, typography, spacing, density, and interactions
are all represented. Recreate the UI faithfully, but map the ad-hoc CSS tokens
onto the target codebase's design-system equivalents where they exist.

## Locked-in visual settings (the only "theme" you need to ship)
The prototype has a Tweaks panel that lets you switch visual directions. The
**chosen, final configuration** to implement is:

| Setting   | Value      | Meaning |
|-----------|-----------|---------|
| Stil (look)   | **Klar**   | Clean, flat, hairline borders, minimal shadows |
| Akzentfarbe   | **Lehm (clay)** — `--accent-hue: 35` | Warm terracotta accent |
| Theme         | **Hell (light)** | Light, warm-neutral "paper" base |
| Dichte (density) | **Normal (regular)** | Standard spacing scale |

(These four are already baked in as the prototype's `TWEAK_DEFAULTS` in
`src/app.jsx`.)

You do **not** need to build the other looks (Kontur, Erde), other accents,
dark theme, or the Tweaks panel itself — those were exploration scaffolding.
Ship the single configuration above. (If a dark theme is wanted later, the
token structure in `styles.css` supports it via `[data-theme="dark"]`.)

## Design Tokens

All colors are authored in **OKLCH**. Accent hue is **35** (clay). These are the
resolved tokens for **light theme · klar · clay accent**:

### Color — surfaces & ink (light)
- `--paper`        `oklch(0.96 0.014 128)`  — app background
- `--surface`      `oklch(0.988 0.008 128)` — cards
- `--surface-2`    `oklch(0.935 0.018 128)`
- `--surface-3`    `oklch(0.9 0.024 128)`
- `--ink`          `oklch(0.26 0.022 152)`  — primary text
- `--ink-2`        `oklch(0.44 0.02 152)`   — secondary text
- `--ink-3`        `oklch(0.57 0.018 150)`  — muted / eyebrow text
- `--line`         `oklch(0.87 0.018 130)`  — borders
- `--line-soft`    `oklch(0.915 0.014 130)` — hairline dividers

### Color — accent (clay, hue 35)
- `--accent`        `oklch(0.52 0.078 35)`
- `--accent-strong` `oklch(0.44 0.085 35)`
- `--accent-soft`   `oklch(0.925 0.04 35)`
- `--accent-soft-2` `oklch(0.87 0.058 35)`
- `--accent-ink`    `oklch(0.4 0.08 35)`
- `--on-accent`     `oklch(0.985 0.018 35)` — text on accent fills
- Secondary "clay" decorative accent `--clay` uses hue 42.

### Radius (klar)
- `--radius` 11px · `--radius-sm` 8px · `--radius-lg` 16px

### Spacing / density (regular)
- `--gap` 22px · `--row-pad` 14px
- (compact: gap 15 / pad 9 · comfy: gap 28 / pad 18 — not needed for ship)

### Shadows (light)
- `--shadow-sm` `0 1px 2px oklch(0.35 0.03 150 / 0.07)`
- `--shadow`    `0 5px 18px oklch(0.32 0.04 150 / 0.09), 0 2px 5px oklch(0.32 0.04 150 / 0.05)`
- `--shadow-lg` `0 24px 56px oklch(0.28 0.04 150 / 0.18), 0 6px 16px oklch(0.28 0.04 150 / 0.08)`

### Typography
- UI + display font: `"Helvetica Neue", Helvetica, Arial, "Segoe UI", Roboto, sans-serif`
- Mono (numbers, timers, amounts): `ui-monospace, "SF Mono", "Roboto Mono", Menlo, monospace`
- Base body: 15px / line-height 1.5
- Page `<h1>`: ~38px, weight 700, letter-spacing −0.028em
- Eyebrow labels: 12.5px, uppercase, letter-spacing 0.06em, color `--ink-3`
- Headings weight 600, letter-spacing −0.01em

## App Shell / Layout
- Flex row, full viewport height.
- **Sidebar**: 252px fixed, sticky, full height, `border-right: 1px solid --line-soft`.
  - Brand block (mark + "HomeBase" + "Max & Lea" sub) at top.
  - Nav: vertical list of buttons, each = icon (20px) + label. Active item gets
    `.is-active` (accent treatment). Nav items can show a count **badge**
    (Inbox count, shopping count) and a small live **sync dot** on Zeit when a
    timer is running.
  - Footer: user chip (avatar + "Max" + "Echtzeit-Sync aktiv").
- **Main**: `.hb-page`, max-width 1120px, centered, padding `34px 40px 80px`.
  - Every page opens with `.hb-pagehead` — a space-between flex: left = eyebrow +
    `<h1>`; right = primary action button (optional).

## Screens / Views

### 1. Dashboard (Heute)
- Time-of-day greeting (morning/afternoon/evening logic), date.
- Quick stats row (cards with icon + value + label).
- Today's agenda: due tasks, running-timer status, etc.
- File: `src/views_heute.jsx`.

### 2. Aufgaben (Tasks)
Tasks are organized into **named lists** (tabs at the top), and within the
active list shown as **one scrolling page grouped by due date** — there is NO
Inbox/Planned/Done mode switcher anymore.
- **List tabs** across the top, one per todo list, each with an open-item count
  badge; private lists show a lock icon. A "+ Neue Liste" tab opens a modal with
  a **Name** field + a **Geteilt/Privat** visibility picker.
  - Lists are shared by default; a **private** list is only visible to its
    creator (`visibility: "private"`, filtered by `created_by === currentUser`).
  - With >1 visible list, a "Liste löschen" link deletes the active list and all
    its todos.
- **Quick-add** bar adds an undated task to the active list (appears under
  "Ohne Datum").
- **Open todos** are auto-grouped into due-date sections, rendered in this order
  when non-empty: **Überfällig → Heute → Demnächst → Später → Ohne Datum**, each
  with a count. "Planen" = set a due date (via the edit modal); there is no
  separate Inbox step.
- **Erledigt** (done) is a **collapsible section** at the bottom with a count,
  collapsed by default.
- Each task: checkbox (toggle done), title, optional meta (description, priority
  flag, done-time), due-date badge, assignee avatar (or a "Planen" button when
  undated), delete.
- **Subtasks**: every task has a progress pill on the right (e.g. `1/3`) + a
  chevron; clicking it **expands an inline checklist** with an
  "Unteraufgabe hinzufügen …" input. Subtasks are **title + done only**.
  Toggling a subtask updates the count and keeps the panel open; the parent is
  **not** auto-completed. Tasks without subtasks still show a dashed toggle to
  add the first one.
- Data: each todo has `list_id` and a `subtasks: [{ id, title, done }]` array.
- Assignable to Max or Lea.
- Files: `src/views_aufgaben.jsx` (lists, grouping, subtasks); todo lists live in
  the `todoLists` collection.
- Nav badge (sidebar) = count of open todos that are **overdue or due today**.

### 3. Einkauf (Shopping)
Multiple **named lists** switched via **tabs** at the top — there are **no item
categories** anymore; each list is a single flat list.
- **List tabs**, one per shopping list, each with an open-item count; a
  "+ Neue Liste" tab opens a modal with just a **Name** field. All shopping
  lists are shared.
- With >1 list, a "Liste löschen" link removes the active list and its items.
- **Add-item** bar has only a name input (no category select); items go into the
  active list.
- Each item: checkbox, name, who added it (avatar), delete. Checked items move to
  an **"Im Wagen"** section per list with an "Abgehakte entfernen" action.
- Ingredients pushed from Rezepte land in the **first list** by default,
  de-duplicated by lowercased name within that list.
- Data: each item has `list_id` (no `category`); lists live in the
  `shoppingLists` collection.
- File: `src/views_einkauf.jsx`.

### 4. Notizen (Notes)
- List of notes (shared or private/visibility) with a small markdown renderer
  (headings, bold, italic, inline code, lists, blockquote).
- Add note, edit title/content, delete. Tags supported.
- File: `src/views_notizen.jsx` (+ `renderMarkdown` lives in `src/ui.jsx`).

### 5. Zeiterfassung (Time tracking)
- **Hero timer card**: shows the *current user's* running entry (project dot +
  name, editable description, live HH:MM:SS clock, Stop button). Idle state when
  nothing is running. **Invariant: only one timer runs per user at a time** —
  starting a timer stops the user's previous one.
- **Projects grid**: cards with color dot, name, total time, Start/Stop button,
  edit + archive actions. The project **name and total are clickable** and open
  a **project detail modal** (see below). "Neues Projekt" button in page head
  opens a modal (name + color swatch picker). Archived projects toggle.
- **Letzte Einträge** (recent entries): combined list of BOTH users' finished
  entries, **grouped by day** with separator rows ("Heute", "Gestern",
  "Vorgestern", weekday name within the last week, else a date) and a per-day
  total on the right. Each row: project dot, name, description, time range,
  owner avatar, duration.
  - **Permissions:** a user may only delete their **own** entries. Others' rows
    show a lock icon instead of the trash button.
- **Project detail modal** (`ProjectDetail`): opened from a project card. Shows
  - four stat tiles: total time, this-week time, entry count, ø per entry;
  - a per-user breakdown chip row (Max vs. Lea totals) when both have entries;
  - **"Pro Woche"** — a weekly summary: one row per ISO week (Monday-based),
    labelled "Diese Woche" / "Letzte Woche" / date range, with a horizontal bar
    **segmented by user** (segment width ∝ that user's time, scaled to the
    busiest week) and the week's total + entry count;
  - **"Alle Einträge"** — the project's full history, day-grouped, same
    ownership-based delete/lock rule.
- **Date/week helpers** live in `src/icons.jsx` (`HBfmt`): `dayGroupLabel`,
  `weekKey`, `weekLabel` (Monday-based week start). Reuse equivalents in prod.
- File: `src/views_zeit.jsx`.

### 6. Rezepte (Recipes)
- Page head: count eyebrow + "Rezepte" title + **"Neues Rezept"** primary button
  (opens a create-recipe form modal).
- Category filter chips: "Alle" + the categories below.
- Recipe grid of cards: image placeholder band (deterministic warm hue per
  recipe, "Foto folgt" label, category badge), title, description, meta
  (total time, servings).
- **Recipe detail modal**: description, facts (servings / prep / cook / total),
  ingredients list (amount+unit mono + name), numbered steps, footer actions
  ("Löschen", "Zutaten zur Liste" → pushes ingredients to shopping list + toast).
- **Create form modal** (`RecipeForm`): title, category select, servings,
  prep/cook minutes, description, ingredients (one per line — a `"200 g Mehl"`
  line is parsed into `{amount, unit, name}`), steps (one per line). Saving adds
  the recipe to the top and opens its detail view.
- File: `src/views_rezepte.jsx`.

#### Recipe categories (note the merge!)
```
BREAKFAST → "Frühstück"
MAIN      → "Hauptgerichte"   ← lunch + dinner were MERGED into one category
SNACK     → "Snack"
DESSERT   → "Dessert"
DRINK     → "Getränk"
```
Legacy data may still carry `LUNCH` / `DINNER`; the UI normalizes both to `MAIN`
(`catKey` helper). Implement the model with a single `MAIN` category and migrate
any old `LUNCH`/`DINNER` rows to it.

## Interactions & Behavior
- **Sidebar nav** swaps the active view (client-side routing). `route` also
  readable from `?route=` URL param in the prototype.
- **Live badges** on nav: open-inbox task count, unchecked shopping count.
- **Running-timer dot** on the Zeit nav item while a timer runs.
- **Modals** (`Modal` in `ui.jsx`): close on Esc / backdrop, title + footer
  actions, configurable width.
- **Toasts**: e.g. after adding recipe ingredients to the shopping list
  ("N Zutaten zur Einkaufsliste hinzugefügt" + "Ansehen" link), auto-dismiss ~2.6s.
- **Timer clock**: ticks every second from `started_at`.
- Card hover: subtle lift / accent border (`.hb-card--hover`).

## State Management
Single in-memory store in `App` (`src/app.jsx`), seeded from `src/seed.jsx`.
Collections: `todos`, `todoLists`, `shopping`, `shoppingLists`, `notes`,
`projects`, `timeEntries`, `recipes`. All mutations go through an `api` object:
- todos/lists: `addTodoList`, `renameTodoList`, `deleteTodoList` (also deletes
  its todos), `addTodo(title, listId)`, `updateTodo`, `toggleDone`, `deleteTodo`,
  and subtask ops `addSubtask`/`toggleSubtask`/`deleteSubtask`;
- shopping: `addList`, `renameList`, `deleteList` (also deletes its items),
  `addItem(name, listId)`, `toggleItem`, `deleteItem`, `clearChecked(listId)`,
  `addIngredientsToShopping(ings, listId?)` (defaults to the first list);
- plus `addNote`, `startTimer`/`stopTimer` (one-timer invariant), `addRecipe`,
  `deleteRecipe`, etc.

The "current user" is hard-coded as `max` (constant `ME` in `views_zeit.jsx`,
and a local `ME` in `views_aufgaben.jsx` used to filter private lists); in
production this becomes the authenticated user and drives per-user edit
permissions and private-list visibility.

In a real build, replace this with your data layer (API + server state) and
real auth. Keep the **ownership rule** and the **single-running-timer-per-user**
invariant.

## Permissions (important)
- All data is **shared / visible** to both users.
- A user may **only edit or delete entries they own** (e.g. time entries —
  others render a lock instead of delete). Apply the same principle wherever
  `created_by` / `user_id` ownership is meaningful.

## Users / seed data
Two users defined in `seed.jsx`:
```
max → { name: "Max", hue: 150, initials: "M" }
lea → { name: "Lea", hue: 250, initials: "L" }
```
Avatars are initial-on-tinted-circle using the user's hue. Seed data provides
realistic German content across all six tools — use it to validate your build.

## Assets
- **No external image assets.** Recipe photos are placeholders ("Foto folgt").
- **Icons**: inline SVG path set in `src/icons.jsx` (single `Icon` component,
  24×24 viewBox, currentColor stroke). Swap for your icon library; the names
  used: home, checkCircle, circle, plus, cart, note, clock, chef, play, stop,
  check, chevron*, calendar, inbox, flag, lock, users, archive, send, edit,
  trash, sparkle, dot, etc.
- **Fonts**: system Helvetica Neue stack + system mono — no web-font downloads.

## Files (design reference source)
Open these to see exact markup, styles, and behavior:

- `HomeBase.html` — entry point + script/style load order
- `src/styles.css` — design tokens (colors/spacing/shadow/type) + component CSS
- `src/looks.css` — visual-direction overrides (ship **klar** only)
- `src/views.css` — per-view component CSS
- `src/seed.jsx` — users, categories, seed data, `HB` globals + format helpers
- `src/icons.jsx` — `Icon` component + German date/duration formatters (`HBfmt`)
- `src/ui.jsx` — shared primitives: Button, IconButton, Card, Badge, Modal,
  Field, TextInput, Select, Avatar, EmptyState, markdown renderer
- `src/app.jsx` — store, api, routing, sidebar, tweak defaults
- `src/views_heute.jsx` — Dashboard
- `src/views_aufgaben.jsx` — Tasks
- `src/views_einkauf.jsx` — Shopping
- `src/views_notizen.jsx` — Notes
- `src/views_zeit.jsx` — Time tracking (ME constant + permissions)
- `src/views_rezepte.jsx` — Recipes (merged categories + create form)
- `src/tweaks-panel.jsx` — exploration-only Tweaks UI (do not ship)
