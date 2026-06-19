/**
 * Realistic German seed data for the web screenshot renderer (issue #300).
 *
 * The data is built with the same fluent `MockApi` + factory helpers the e2e
 * suite uses (web/e2e/helpers/mockApi.ts), so it stays type-checked against the
 * app's real domain types and never drifts. Content mirrors the household the
 * old design mockups depicted (Max & Lea, deutsche Inhalte) so the regenerated
 * shots look like the originals.
 *
 * Dates are computed relative to a fixed "now" passed in by the renderer
 * (page.clock is pinned to the same instant) so "heute/morgen" buckets are
 * stable regardless of when the script runs.
 */
import {
  MockApi,
  todo, list, subtask,
  shoppingList, shoppingItem,
  note,
  project, timeEntry, workTarget,
  recipe, ingredient, recipeStep,
  absence, absSettings, partTimeRule, kitaClosure, customHoliday,
  type Todo,
} from '../../web/e2e/helpers/mockApi'

const DAY_MS = 86_400_000

/** Local YYYY-MM-DD, `offset` days from `now`. */
function isoDay(now: Date, offset: number): string {
  const d = new Date(now.getTime() + offset * DAY_MS)
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`
}

/** ISO timestamp `mins` minutes before `now`. */
function ago(now: Date, mins: number): string {
  return new Date(now.getTime() - mins * 60_000).toISOString()
}

// ---- Households roster (matches the e2e mock's /users → max, lea) ----
const ME = 'max'
const PARTNER = 'lea'

function buildTodos(now: Date): Todo[] {
  return [
    // --- Inbox (unplanned ideas) ---
    todo({
      id: 't-geschenk', title: 'Geschenk für Mama besorgen', listId: 'tl_familie',
      status: 'INBOX', createdBy: PARTNER, createdAt: ago(now, 40),
      subtasks: [
        subtask({ id: 'st1', title: 'Wunschliste checken', done: true, sortOrder: 0 }),
        subtask({ id: 'st2', title: 'Budget festlegen', done: false, sortOrder: 1 }),
        subtask({ id: 'st3', title: 'Bestellen', done: false, sortOrder: 2 }),
      ],
    }),
    todo({
      id: 't-zahnarzt', title: 'Zahnarzttermin vereinbaren', listId: 'tl_max',
      description: 'Kontrolle, ist überfällig.', status: 'INBOX', createdBy: ME, createdAt: ago(now, 180),
    }),
    todo({
      id: 't-amazon', title: 'Rückgabe Amazon-Paket', listId: 'tl_max',
      description: 'Bis Freitag in der Filiale abgeben.', status: 'INBOX', createdBy: ME, createdAt: ago(now, 600),
    }),
    todo({
      id: 't-steuer', title: 'Steuerunterlagen sortieren', listId: 'tl_haushalt',
      status: 'INBOX', createdBy: PARTNER, createdAt: ago(now, 1500),
      subtasks: [
        subtask({ id: 'st4', title: 'Belege sammeln', done: false, sortOrder: 0 }),
        subtask({ id: 'st5', title: 'Nach Kategorie sortieren', done: false, sortOrder: 1 }),
        subtask({ id: 'st6', title: 'Scannen', done: false, sortOrder: 2 }),
      ],
    }),
    // --- Planned ---
    todo({
      id: 't-muell', title: 'Müll rausbringen', listId: 'tl_haushalt',
      description: 'Gelber Sack + Restmüll.', status: 'PLANNED', assignee: ME,
      dueDate: isoDay(now, 0), priority: 'MEDIUM', createdBy: ME, createdAt: ago(now, 2000),
    }),
    todo({
      id: 't-blumen', title: 'Blumen auf dem Balkon gießen', listId: 'tl_haushalt',
      status: 'PLANNED', assignee: PARTNER, dueDate: isoDay(now, 0), priority: 'LOW',
      createdBy: PARTNER, createdAt: ago(now, 2500),
    }),
    todo({
      id: 't-auto', title: 'Auto zur Inspektion bringen', listId: 'tl_familie',
      description: 'Termin in der Werkstatt um 9:00.', status: 'PLANNED', assignee: PARTNER,
      dueDate: isoDay(now, 3), priority: 'HIGH', createdBy: ME, createdAt: ago(now, 3000),
      subtasks: [
        subtask({ id: 'st7', title: 'Termin bestätigen', done: true, sortOrder: 0 }),
        subtask({ id: 'st8', title: 'Scheckheft einpacken', done: false, sortOrder: 1 }),
      ],
    }),
    todo({
      id: 't-strom', title: 'Stromzähler ablesen', listId: 'tl_haushalt',
      description: 'Stand fotografieren und an Stadtwerke senden.', status: 'PLANNED', assignee: ME,
      dueDate: isoDay(now, 1), priority: 'MEDIUM', createdBy: ME, createdAt: ago(now, 4000),
    }),
    todo({
      id: 't-karte', title: 'Geburtstagskarte für Opa schreiben', listId: 'tl_familie',
      description: 'Wird nächste Woche 80.', status: 'PLANNED', assignee: PARTNER,
      dueDate: isoDay(now, 1), priority: 'HIGH', createdBy: PARTNER, createdAt: ago(now, 5000),
    }),
    todo({
      id: 't-kino', title: 'Kinokarten reservieren', listId: 'tl_familie',
      status: 'PLANNED', assignee: ME, dueDate: isoDay(now, 2), priority: 'LOW',
      createdBy: PARTNER, createdAt: ago(now, 6000),
    }),
    // --- Done (today / recent) ---
    todo({
      id: 't-einkauf-done', title: 'Wocheneinkauf erledigt', listId: 'tl_haushalt',
      status: 'DONE', assignee: ME, dueDate: isoDay(now, 0), priority: 'MEDIUM',
      createdBy: ME, createdAt: ago(now, 8000), doneAt: ago(now, 120),
    }),
    todo({
      id: 't-waesche-done', title: 'Wäsche gewaschen & aufgehängt', listId: 'tl_haushalt',
      status: 'DONE', assignee: PARTNER, dueDate: isoDay(now, 0), priority: 'LOW',
      createdBy: PARTNER, createdAt: ago(now, 9000), doneAt: ago(now, 300),
    }),
  ]
}

const TODO_LISTS = [
  list({ id: 'tl_haushalt', name: 'Haushalt', visibility: 'SHARED', createdBy: PARTNER }),
  list({ id: 'tl_familie', name: 'Familie & Termine', visibility: 'SHARED', createdBy: ME }),
  list({ id: 'tl_max', name: 'Persönlich', visibility: 'PRIVATE', createdBy: ME }),
]

const SHOPPING_LISTS = [
  shoppingList({ id: 'sl_woche', name: 'Wocheneinkauf', createdBy: PARTNER }),
  shoppingList({ id: 'sl_drog', name: 'Drogerie', createdBy: ME }),
]

function buildShopping(now: Date) {
  const open = (id: string, name: string, listId: string, by: string) =>
    shoppingItem({ id, name, listId, checked: false, createdBy: by })
  const done = (id: string, name: string, listId: string, by: string, mins: number) =>
    shoppingItem({ id, name, listId, checked: true, createdBy: by, checkedAt: ago(now, mins) })
  return [
    open('s1', 'Äpfel', 'sl_woche', PARTNER),
    open('s2', 'Bananen', 'sl_woche', ME),
    done('s3', 'Babyspinat', 'sl_woche', PARTNER, 30),
    open('s4', 'Tomaten', 'sl_woche', ME),
    open('s5', 'Milch (1,5%)', 'sl_woche', ME),
    open('s6', 'Naturjoghurt', 'sl_woche', PARTNER),
    done('s7', 'Butter', 'sl_woche', ME, 45),
    open('s8', 'Gouda am Stück', 'sl_woche', PARTNER),
    open('s9', 'Filterkaffee', 'sl_woche', PARTNER),
    open('s10', 'Spülmittel', 'sl_drog', ME),
    open('s11', 'Toilettenpapier', 'sl_drog', PARTNER),
    open('s12', 'AA-Batterien', 'sl_drog', ME),
  ]
}

function buildNotes(now: Date) {
  return [
    note({
      id: 'n_urlaub', title: 'Urlaubsplanung Sommer', visibility: 'SHARED',
      tags: ['urlaub', 'reise'], createdBy: PARTNER, updatedAt: ago(now, 220),
      content: `## Toskana, Ende Juli

Grobe Idee für 10 Tage:

- **Anreise** über Nacht, Stopp in Verona
- 4 Nächte Florenz, dann 4 Nächte am Meer
- Agriturismo statt Hotel — mehr Ruhe

> Budget grob: **1.800 €** ohne Sprit

### Noch klären
1. Hund bei Oma oder Tierhotel?
2. Mietwagen vor Ort vs. eigenes Auto
3. Reiseapotheke auffüllen`,
    }),
    note({
      id: 'n_wohnzimmer', title: 'Ideen fürs Wohnzimmer', visibility: 'SHARED',
      tags: ['zuhause', 'deko'], createdBy: PARTNER, updatedAt: ago(now, 15000),
      content: `### Umgestaltung

- Großer Teppich in warmem Sandton
- Stehlampe mit warmem Licht
- Mehr Pflanzen am Fenster
- Bilderleiste über dem Sofa`,
    }),
    note({
      id: 'n_kontakte', title: 'Hausmeister & Kontakte', visibility: 'SHARED',
      tags: ['wohnung'], createdBy: PARTNER, updatedAt: ago(now, 9000),
      content: `## Wichtige Nummern

- **Hausmeister Herr Klein** — erreichbar Mo–Fr vormittags
- **Notdienst Heizung** — Aushang im Treppenhaus
- **Vermietung** — per E-Mail bevorzugt`,
    }),
    note({
      id: 'n_geschenke', title: 'Geschenkideen Lea', visibility: 'PRIVATE',
      tags: ['geschenke'], createdBy: ME, updatedAt: ago(now, 1440),
      content: `### Ideen fürs nächste Mal

- Töpferkurs am Wochenende
- Die neue Kamera-Tasche (braun)
- Wochenendtrip nach Hamburg
- Lieblingstee nachbestellen`,
    }),
    note({
      id: 'n_wlan', title: 'WLAN & wichtige Codes', visibility: 'PRIVATE',
      tags: ['zuhause', 'passwörter'], createdBy: ME, updatedAt: ago(now, 4300),
      content: `## Zugänge

- **WLAN:** HomeBase-Netz
- **Gäste-WLAN:** Passwort liegt am Kühlschrank
- Heizung Servicecode: siehe Ordner *Wohnung*

Bitte nicht teilen.`,
    }),
  ]
}

const PROJECTS = [
  project({ id: 'p_app', name: 'Nebenprojekt: App', color: '#5b9e7a', createdBy: ME }),
  project({ id: 'p_steuer', name: 'Steuererklärung', color: '#c9805a', createdBy: PARTNER }),
  project({ id: 'p_garten', name: 'Garten & Balkon', color: '#6a8fc0', createdBy: PARTNER }),
  project({ id: 'p_lernen', name: 'Spanisch lernen', color: '#c2a14d', createdBy: ME }),
]

function buildEntries(now: Date) {
  // A running timer for "me" (started 95 min ago, still open) plus a spread of
  // finished entries across this week and earlier so the day-grouped list and
  // the project tiles look populated.
  const span = (id: string, projectId: string, user: string, startMin: number, lenMin: number, description: string) =>
    timeEntry({
      id, projectId, userId: user,
      startedAt: ago(now, startMin), stoppedAt: ago(now, startMin - lenMin),
      durationSeconds: lenMin * 60, description,
      createdAt: ago(now, startMin), updatedAt: ago(now, startMin - lenMin),
    })
  return [
    // running (no stoppedAt / durationSeconds)
    timeEntry({
      id: 'e_run', projectId: 'p_app', userId: ME,
      startedAt: ago(now, 95), stoppedAt: undefined, durationSeconds: undefined,
      description: 'Sync-Bug nachstellen', createdAt: ago(now, 95), updatedAt: ago(now, 95),
    }),
    span('e1', 'p_steuer', PARTNER, 1500, 90, 'Belege scannen'),
    span('e2', 'p_app', ME, 2400, 120, 'Notizen-Editor'),
    span('e3', 'p_lernen', ME, 2900, 45, 'Vokabeln Einheit 4'),
    span('e4', 'p_garten', PARTNER, 4400, 120, 'Hochbeet bepflanzt'),
    span('e5', 'p_steuer', PARTNER, 5900, 120, 'Umsatzsteuer prüfen'),
    span('e6', 'p_app', ME, 12120, 120, 'Kalender-Ansicht'),
    span('e7', 'p_app', PARTNER, 13090, 90, 'Code-Review'),
    span('e8', 'p_lernen', ME, 16560, 60, 'Vokabeln Einheit 5'),
  ]
}

const TARGETS = [
  workTarget({ userId: ME, projectId: 'p_app', weeklyHours: 8, isDefault: true }),
  workTarget({ userId: ME, projectId: 'p_lernen', weeklyHours: 2 }),
  workTarget({ userId: PARTNER, projectId: 'p_steuer', weeklyHours: 4, isDefault: true }),
  workTarget({ userId: PARTNER, projectId: 'p_garten', weeklyHours: 2 }),
]

const RECIPES = [
  recipe({
    id: 'r_pan', title: 'Fluffige Buttermilch-Pancakes',
    description: 'Sonntagsklassiker — innen weich, außen goldbraun.',
    servings: 4, prepTimeMinutes: 10, cookTimeMinutes: 15, category: 'BREAKFAST', createdBy: PARTNER,
    ingredients: [
      ingredient({ id: 'rp1', name: 'Mehl', amount: 250, unit: 'g', sortOrder: 0 }),
      ingredient({ id: 'rp2', name: 'Buttermilch', amount: 300, unit: 'ml', sortOrder: 1 }),
      ingredient({ id: 'rp3', name: 'Eier', amount: 2, unit: 'Stk', sortOrder: 2 }),
      ingredient({ id: 'rp4', name: 'Zucker', amount: 2, unit: 'EL', sortOrder: 3 }),
      ingredient({ id: 'rp5', name: 'Backpulver', amount: 1, unit: 'TL', sortOrder: 4 }),
    ],
    steps: [
      recipeStep({ id: 'rps1', stepNumber: 1, description: 'Trockene Zutaten in einer Schüssel vermengen.' }),
      recipeStep({ id: 'rps2', stepNumber: 2, description: 'Buttermilch und Eier verquirlen, zur Mehlmischung geben und nur kurz verrühren.' }),
      recipeStep({ id: 'rps3', stepNumber: 3, description: 'Teig 10 Minuten ruhen lassen.' }),
      recipeStep({ id: 'rps4', stepNumber: 4, description: 'Portionsweise goldbraun backen. Mit Ahornsirup servieren.' }),
    ],
  }),
  recipe({
    id: 'r_carb', title: 'Spaghetti Carbonara',
    description: 'Original ohne Sahne — nur Ei, Pecorino und Pfeffer.',
    servings: 2, prepTimeMinutes: 10, cookTimeMinutes: 15, category: 'DINNER', createdBy: ME,
    ingredients: [
      ingredient({ id: 'rc1', name: 'Spaghetti', amount: 250, unit: 'g', sortOrder: 0 }),
      ingredient({ id: 'rc2', name: 'Guanciale', amount: 120, unit: 'g', sortOrder: 1 }),
      ingredient({ id: 'rc3', name: 'Eigelb', amount: 3, unit: 'Stk', sortOrder: 2 }),
      ingredient({ id: 'rc4', name: 'Pecorino', amount: 60, unit: 'g', sortOrder: 3 }),
    ],
    steps: [
      recipeStep({ id: 'rcs1', stepNumber: 1, description: 'Spaghetti al dente kochen.' }),
      recipeStep({ id: 'rcs2', stepNumber: 2, description: 'Guanciale knusprig auslassen.' }),
      recipeStep({ id: 'rcs3', stepNumber: 3, description: 'Eigelb mit Pecorino und Pfeffer verrühren, alles zügig zu einer Creme verrühren.' }),
    ],
  }),
  recipe({
    id: 'r_lin', title: 'Herzhafte Linsensuppe',
    description: 'Wärmt an kalten Tagen und schmeckt aufgewärmt noch besser.',
    servings: 4, prepTimeMinutes: 15, cookTimeMinutes: 40, category: 'DINNER', createdBy: PARTNER,
    ingredients: [
      ingredient({ id: 'rl1', name: 'Tellerlinsen', amount: 250, unit: 'g', sortOrder: 0 }),
      ingredient({ id: 'rl2', name: 'Suppengrün', amount: 1, unit: 'Bund', sortOrder: 1 }),
      ingredient({ id: 'rl3', name: 'Kartoffeln', amount: 2, unit: 'Stk', sortOrder: 2 }),
      ingredient({ id: 'rl4', name: 'Gemüsebrühe', amount: 1, unit: 'l', sortOrder: 3 }),
    ],
    steps: [
      recipeStep({ id: 'rls1', stepNumber: 1, description: 'Suppengrün und Kartoffeln würfeln, kurz anschwitzen.' }),
      recipeStep({ id: 'rls2', stepNumber: 2, description: 'Linsen und Brühe zugeben, ca. 35 Minuten köcheln.' }),
      recipeStep({ id: 'rls3', stepNumber: 3, description: 'Mit Salz, Pfeffer und einem Schuss Essig abschmecken.' }),
    ],
  }),
  recipe({
    id: 'r_kuchen', title: 'Saftiger Schokoladenkuchen',
    description: 'Einfach, schokoladig, gelingt immer.',
    servings: 12, prepTimeMinutes: 20, cookTimeMinutes: 35, category: 'DESSERT', createdBy: ME,
    ingredients: [
      ingredient({ id: 'rk1', name: 'Mehl', amount: 200, unit: 'g', sortOrder: 0 }),
      ingredient({ id: 'rk2', name: 'Zucker', amount: 180, unit: 'g', sortOrder: 1 }),
      ingredient({ id: 'rk3', name: 'Kakao', amount: 40, unit: 'g', sortOrder: 2 }),
      ingredient({ id: 'rk4', name: 'Eier', amount: 3, unit: 'Stk', sortOrder: 3 }),
    ],
    steps: [
      recipeStep({ id: 'rks1', stepNumber: 1, description: 'Ofen auf 175 °C vorheizen, Form fetten.' }),
      recipeStep({ id: 'rks2', stepNumber: 2, description: 'Zutaten verrühren, in die Form geben und ca. 35 Minuten backen.' }),
    ],
  }),
  recipe({
    id: 'r_balls', title: 'Dattel-Energy-Balls', description: 'Schneller Snack ohne Backen.',
    servings: 10, prepTimeMinutes: 15, cookTimeMinutes: 0, category: 'SNACK', createdBy: PARTNER,
    ingredients: [
      ingredient({ id: 'rb1', name: 'Datteln', amount: 150, unit: 'g', sortOrder: 0 }),
      ingredient({ id: 'rb2', name: 'Haferflocken', amount: 100, unit: 'g', sortOrder: 1 }),
      ingredient({ id: 'rb3', name: 'Mandelmus', amount: 2, unit: 'EL', sortOrder: 2 }),
    ],
    steps: [
      recipeStep({ id: 'rbs1', stepNumber: 1, description: 'Datteln einweichen, dann alles fein pürieren.' }),
      recipeStep({ id: 'rbs2', stepNumber: 2, description: 'Zu kleinen Kugeln rollen und mindestens 30 Minuten kühlen.' }),
    ],
  }),
  recipe({
    id: 'r_tea', title: 'Pfirsich-Eistee', description: 'Erfrischend für warme Nachmittage.',
    servings: 4, prepTimeMinutes: 5, cookTimeMinutes: 0, category: 'DRINK', createdBy: ME,
    ingredients: [
      ingredient({ id: 'rt1', name: 'Schwarztee', amount: 3, unit: 'Beutel', sortOrder: 0 }),
      ingredient({ id: 'rt2', name: 'Pfirsich', amount: 2, unit: 'Stk', sortOrder: 1 }),
      ingredient({ id: 'rt3', name: 'Zitrone', amount: 1, unit: 'Stk', sortOrder: 2 }),
    ],
    steps: [
      recipeStep({ id: 'rts1', stepNumber: 1, description: 'Tee aufgießen und abkühlen lassen.' }),
      recipeStep({ id: 'rts2', stepNumber: 2, description: 'Pfirsich pürieren, mit Tee und Zitronensaft mischen, über Eis servieren.' }),
    ],
  }),
]

/**
 * Absence fixture for the calendar shots. The year is taken from `now` so the
 * default-opened year matches the pinned clock; the entries are spread across
 * that year so the grid shows colour-coded runs for both people.
 */
function buildAbsence(now: Date) {
  const year = now.getFullYear()
  const d = (month: number, day: number) => {
    const p = (n: number) => String(n).padStart(2, '0')
    return `${year}-${p(month)}-${p(day)}`
  }
  return {
    users: [ME, PARTNER],
    settings: [
      absSettings({ userId: ME, year, state: 'BE', allowance: 30, carryover: 5, carryoverExpires: d(9, 30), kindKrankCap: 15 }),
      absSettings({ userId: PARTNER, year, state: 'BY', allowance: 24, carryover: 0, kindKrankCap: 15 }),
    ],
    partTime: [
      partTimeRule({ id: 'pt1', userId: ME, weekday: 1, start: d(1, 1), end: d(4, 30) }),
      partTimeRule({ id: 'pt2', userId: PARTNER, weekday: 5, start: d(3, 1), end: null }),
    ],
    absences: [
      // Max — a taken week in March, a planned week in July, a half day, sick days
      absence({ id: 'a1', userId: ME, date: d(3, 16) }),
      absence({ id: 'a2', userId: ME, date: d(3, 17) }),
      absence({ id: 'a3', userId: ME, date: d(3, 18) }),
      absence({ id: 'a4', userId: ME, date: d(3, 19) }),
      absence({ id: 'a5', userId: ME, date: d(3, 20) }),
      absence({ id: 'a6', userId: ME, date: d(7, 27) }),
      absence({ id: 'a7', userId: ME, date: d(7, 28) }),
      absence({ id: 'a8', userId: ME, date: d(7, 29) }),
      absence({ id: 'a9', userId: ME, date: d(6, 2), half: 'vm' }),
      absence({ id: 'a10', userId: ME, date: d(5, 11), type: 'KRANK' }),
      absence({ id: 'a11', userId: ME, date: d(4, 21), type: 'KIND_KRANK' }),
      // Lea — planned July week, a sick day
      absence({ id: 'b1', userId: PARTNER, date: d(7, 27) }),
      absence({ id: 'b2', userId: PARTNER, date: d(7, 28) }),
      absence({ id: 'b3', userId: PARTNER, date: d(7, 29) }),
      absence({ id: 'b4', userId: PARTNER, date: d(2, 10), type: 'KRANK' }),
    ],
    kitaClosures: [
      kitaClosure({ id: 'k1', date: d(7, 27), label: 'Sommerschließung' }),
      kitaClosure({ id: 'k2', date: d(7, 28), label: 'Sommerschließung' }),
      kitaClosure({ id: 'k3', date: d(7, 29), label: 'Sommerschließung' }),
    ],
    customHolidays: [
      customHoliday({ id: 'h1', month: 12, day: 24, half: true, label: 'Heiligabend' }),
      customHoliday({ id: 'h2', month: 12, day: 31, half: true, label: 'Silvester' }),
    ],
  }
}

/**
 * Build a fully-seeded MockApi for the given "now". The same instance backs
 * every view, so navigating between tabs in one page shows a coherent household.
 */
export function buildMock(now: Date): MockApi {
  return new MockApi(buildTodos(now), TODO_LISTS, SHOPPING_LISTS, buildShopping(now))
    .seedNotes(buildNotes(now))
    .seedProjects(PROJECTS)
    .seedEntries(buildEntries(now))
    .seedTargets(TARGETS)
    .seedRecipes(RECIPES)
    .seedAbsence(buildAbsence(now))
}

export { ME, PARTNER }
