import type { Page, Route } from '@playwright/test'

// e2e fixtures use the app's real domain types (src/types.ts) so that any drift —
// e.g. a newly-required field like Note.images — is caught by
// `npm run typecheck:e2e` instead of failing at runtime. Re-exported so the spec
// files can keep importing these names from this helper.
import type {
  Subtask, TodoList, Todo, ShoppingList, ShoppingItem, ShoppingSuggestion, ShoppingTemplate, ShoppingTemplateItem,
  ShoppingCategory, ShoppingCategoryRule,
  RecipeCategory, Ingredient, RecipeStep, Recipe, RecipeImage,
  MealSlot, MealPlanEntry,
  CalendarEvent, CalendarEventType,
  NoteVisibility, NoteImage, NoteAttachment, Note,
  Project, TimeEntry, WorkTarget, TimeForecast, UserForecast, TimeCredit,
  Absence, PartTimeRule, KitaClosure, CustomHoliday, AbsSettings,
} from '../../src/types'

export type {
  Subtask, TodoList, Todo, ShoppingList, ShoppingItem, ShoppingSuggestion, ShoppingTemplate, ShoppingTemplateItem,
  ShoppingCategory, ShoppingCategoryRule,
  RecipeCategory, Ingredient, RecipeStep, Recipe, RecipeImage,
  MealSlot, MealPlanEntry,
  CalendarEvent, CalendarEventType,
  NoteVisibility, NoteImage, NoteAttachment, Note,
  Project, TimeEntry, WorkTarget, TimeForecast,
  Absence, PartTimeRule, KitaClosure, CustomHoliday, AbsSettings,
}

export interface AbsenceSeed {
  users?: string[]
  absences?: Absence[]
  partTime?: PartTimeRule[]
  kitaClosures?: KitaClosure[]
  customHolidays?: CustomHoliday[]
  settings?: AbsSettings[]
}

export const TOKEN = 'test-jwt-token'

// iCal-feed categories (#427), in the backend's canonical display order. Mirrors
// CalendarFeedSection.all; the subscribe modal renders a checkbox per id.
const CALENDAR_FEED_SECTIONS = ['todos', 'absences', 'parttime', 'kita', 'meals', 'events']

// `Buffer` is a Node global present in the Playwright runtime; the e2e tsconfig
// has no @types/node, so declare just the calls we use here.
declare const Buffer: {
  from(input: string, encoding: 'base64'): Uint8Array
  from(input: string): { toString(encoding: 'base64'): string }
}

// An (unsigned but decodable) JWT whose payload carries {username: "max"}. TOKEN
// above is opaque, so views derive me = null and treat every entry as their own —
// fine for most specs. Specs that exercise partner semantics (cross-person confirm
// dialogs, "Für {name}" timer starts) log in with this token instead: me = "max",
// and the mock's default entry user "alice" becomes the partner.
export const TOKEN_MAX = ['e30', Buffer.from(JSON.stringify({ username: 'max' })).toString('base64'), 'sig'].join('.')

// 1×1 transparent PNG, served for note-image GETs so <AuthedImage>'s
// authFetch → res.blob() → URL.createObjectURL() path is exercised end-to-end.
const TINY_PNG = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==',
  'base64',
)

/**
 * In-memory backend stub for the HomeBase API. Intercepts every /api/v1/**
 * request so the app can run end-to-end without a real server, and stubs the
 * WebSocket so the realtime hook never opens a live connection.
 *
 * Mirrors the route contracts in backend/.../routes/*.kt: todo/shopping lists
 * live under /{todos,shopping}/lists, subtasks under /todos/{id}/subtasks, and
 * every subtask mutation responds with the freshly built parent todo.
 *
 * Every view (todos, shopping, recipes, notes and time) reflects its own
 * mutations from the REST response, so the UI updates without depending on the
 * socket. Time and todo-list mutations additionally attach an `x-ws-frames`
 * response header that the in-page bridge (see install) replays to the socket
 * after the fetch resolves, so the realtime dedupe path stays covered; todo
 * creation uses the `x-ws-frames-pre` variant, delivered synchronously BEFORE
 * the fetch resolves, pinning the echo-beats-REST ordering from issue #61.
 * `silenceRealtime()` suppresses all frames to reproduce a deployment whose WS
 * echo never reaches the originating client and prove the UI still updates
 * from REST alone (issue: TimeView live update).
 *
 * Todos/shopping data is seeded via the constructor; recipes/notes/time via the
 * fluent seed* helpers so existing call sites keep working unchanged.
 */
export class MockApi {
  private silent = false
  private householdName = 'Mäxchen'
  private password = 'geheim'
  // Per-digest config (#100/#182): time, in-app on/off, content-section selection. Defaults
  // mirror the backend — enabled on, all sections selected. telegramConfigured stays false in
  // tests (no creds), driving the "inactive" note while keeping the controls editable.
  private telegramConfigured = false
  private eveningSectionsAll = [
    'evening_done_today', 'evening_new_inbox', 'evening_due_tomorrow',
    'evening_absent_tomorrow', 'evening_kita_tomorrow',
  ]
  private morningSectionsAll = [
    'morning_due_today', 'morning_overdue', 'morning_inbox', 'morning_absent', 'morning_kita',
  ]
  private evening = { time: '20:00', enabled: true, sections: [...this.eveningSectionsAll] }
  private morning = { time: '07:00', enabled: true, sections: [...this.morningSectionsAll] }
  private recurringTime = '00:30'
  // Todo reminders config (#429 Phase 2a): enabled (default on) + an optional quiet-hours window.
  private remindersEnabled = true
  private reminderQuietStart = ''
  private reminderQuietEnd = ''
  // "Erledigt"-history window length in days (#356). Default mirrors the backend + clients;
  // TodosView reads it on mount. Kept at 14 so the #340 show-all window test stays accurate.
  private doneWindowDays = 14
  // Per-user iCal-feed category selection (#427). null = unset (all categories); the subscribe
  // modal reads it on open and PUTs the full selection on each toggle.
  private calendarFeedSections: string[] | null = null
  // Per-user key/value prefs (#100). The app loads these on mount (theme) and
  // upserts via PUT /user-prefs/{key}.
  private userPrefs: Record<string, string> = {}
  // Per-user avatar hue overrides, exposed via GET /users (avatarHue) and set via
  // PUT /users/me/avatar-color (Teil von #100). null/absent = automatic/derived.
  private avatarHues: Record<string, number> = {}
  private todos: Todo[]
  private lists: TodoList[]
  private shoppingLists: ShoppingList[]
  private shoppingItems: ShoppingItem[]
  private shoppingTemplates: ShoppingTemplate[] = []
  private shoppingSuggestions: ShoppingSuggestion[] = []
  // Editable grocery category catalog + auto-assignment rules (#411). Seeded with the 10 builtins
  // (mirrors BUILTIN_CATEGORIES / GroceryCatalog) and a couple of rules so the settings page and the
  // existing shopping spec (which now GETs /shopping/categories) have data without a seed* call.
  private shoppingCategories: ShoppingCategory[] = [
    { key: 'PRODUCE', label: 'Obst & Gemüse', emoji: '🥦', sortOrder: 0, isBuiltin: true },
    { key: 'BAKERY', label: 'Backwaren', emoji: '🥐', sortOrder: 1, isBuiltin: true },
    { key: 'DAIRY', label: 'Milchprodukte & Eier', emoji: '🧀', sortOrder: 2, isBuiltin: true },
    { key: 'MEAT_FISH', label: 'Fleisch & Fisch', emoji: '🥩', sortOrder: 3, isBuiltin: true },
    { key: 'FROZEN', label: 'Tiefkühl', emoji: '🧊', sortOrder: 4, isBuiltin: true },
    { key: 'PANTRY', label: 'Vorrat', emoji: '🥫', sortOrder: 5, isBuiltin: true },
    { key: 'SNACKS', label: 'Snacks & Süßes', emoji: '🍫', sortOrder: 6, isBuiltin: true },
    { key: 'DRINKS', label: 'Getränke', emoji: '🥤', sortOrder: 7, isBuiltin: true },
    { key: 'HOUSEHOLD', label: 'Haushalt & Hygiene', emoji: '🧽', sortOrder: 8, isBuiltin: true },
    { key: 'OTHER', label: 'Sonstiges', emoji: '❓', sortOrder: 9, isBuiltin: true },
  ]
  private shoppingCategoryRules: ShoppingCategoryRule[] = [
    { normalizedName: 'milch', displayName: 'Milch', category: 'DAIRY', icon: '🥛' },
    { normalizedName: 'pizza', displayName: 'Pizza', category: 'FROZEN', icon: '🍕' },
  ]
  private nextShopCategoryId = 100
  private recipes: Recipe[] = []
  private mealPlan: MealPlanEntry[] = []
  private events: CalendarEvent[] = []
  private notes: Note[] = []
  private projects: Project[] = []
  private entries: TimeEntry[] = []
  private targets: WorkTarget[] = []
  private credits: TimeCredit[] = []
  private absUsers: string[] = []
  private absences: Absence[] = []
  private partTime: PartTimeRule[] = []
  private kitaClosures: KitaClosure[] = []
  private customHolidays: CustomHoliday[] = []
  private absSettings: AbsSettings[] = []
  private nextAbsId = 100
  private nextId = 100
  private nextListId = 100
  private nextSubId = 100
  private nextShopId = 100
  private nextShopListId = 100
  private nextShopTemplateId = 100
  private nextShopTemplateItemId = 100
  private nextRecipeId = 100
  private nextMealPlanId = 100
  private nextNoteId = 100
  private nextNoteImageId = 100
  private nextNoteAttachmentId = 100
  private nextRecipeImageId = 100
  // optional HTTP status forced on the next note-image upload, to exercise the
  // editor's 413/415 error paths (#146). One-shot: consumed by the next upload.
  private nextImageUploadStatus: number | null = null
  // same one-shot forced status for the next note-attachment upload (#431).
  private nextAttachmentUploadStatus: number | null = null
  // optional gate that holds the NEXT note-image upload until releaseImageUpload()
  // is called — lets a test type into the editor *while the upload is in flight*
  // and prove the in-flight edits survive (#146 stale-draft regression).
  private imageUploadGate: Promise<void> | null = null
  private releaseImageUploadGate: (() => void) | null = null
  private nextProjectId = 100
  private nextEntryId = 100
  private nextChildId = 100

  constructor(
    initialTodos: Todo[] = [],
    initialLists: TodoList[] = [],
    initialShoppingLists: ShoppingList[] = [],
    initialShoppingItems: ShoppingItem[] = [],
  ) {
    this.todos = initialTodos.map((t) => ({ ...t, subtasks: (t.subtasks ?? []).map((s) => ({ ...s })) }))
    this.lists = initialLists.map((l) => ({ ...l }))
    this.shoppingLists = initialShoppingLists.map((l) => ({ ...l }))
    this.shoppingItems = initialShoppingItems.map((i) => ({ ...i }))
  }

  seedRecipes(recipes: Recipe[]): this {
    this.recipes = recipes.map((r) => ({ ...r }))
    return this
  }

  seedShoppingTemplates(templates: ShoppingTemplate[]): this {
    this.shoppingTemplates = templates.map((tpl) => ({ ...tpl, items: tpl.items.map((i) => ({ ...i })) }))
    return this
  }

  // Seed the "most used" autocomplete suggestions GET /shopping/suggestions serves (#389).
  seedShoppingSuggestions(suggestions: ShoppingSuggestion[]): this {
    this.shoppingSuggestions = suggestions.map((s) => ({ ...s }))
    return this
  }

  // Override the seeded grocery category catalog GET /shopping/categories serves (#411).
  seedShoppingCategories(categories: ShoppingCategory[]): this {
    this.shoppingCategories = categories.map((c) => ({ ...c }))
    return this
  }

  // Override the seeded auto-assignment rules GET /shopping/category-rules serves (#411).
  seedShoppingCategoryRules(rules: ShoppingCategoryRule[]): this {
    this.shoppingCategoryRules = rules.map((r) => ({ ...r }))
    return this
  }

  seedNotes(notes: Note[]): this {
    this.notes = notes.map((n) => ({ ...n }))
    return this
  }

  seedMealPlan(entries: MealPlanEntry[]): this {
    this.mealPlan = entries.map((e) => ({ ...e }))
    return this
  }

  seedEvents(events: CalendarEvent[]): this {
    this.events = events.map((e) => ({ ...e }))
    return this
  }

  /** Force the HTTP status of the NEXT note-attachment upload (e.g. 413/415) — one-shot (#431). */
  failNextAttachmentUpload(status: number): this {
    this.nextAttachmentUploadStatus = status
    return this
  }

  /** Force the HTTP status of the NEXT note-image upload (e.g. 413/415) — one-shot. */
  failNextImageUpload(status: number): this {
    this.nextImageUploadStatus = status
    return this
  }

  /** Hold the NEXT note-image upload open until releaseImageUpload() is called. */
  holdNextImageUpload(): this {
    this.imageUploadGate = new Promise((resolve) => { this.releaseImageUploadGate = resolve })
    return this
  }

  /** Release an upload held by holdNextImageUpload(), letting it respond. */
  releaseImageUpload(): void {
    this.releaseImageUploadGate?.()
    this.releaseImageUploadGate = null
    this.imageUploadGate = null
  }

  seedProjects(projects: Project[]): this {
    this.projects = projects.map((p) => ({ ...p }))
    return this
  }

  /** Drop all WebSocket frames — reproduces a deployment where the realtime echo
   *  never reaches the originating client, so views must update from REST alone. */
  silenceRealtime(): this {
    this.silent = true
    return this
  }

  seedEntries(entries: TimeEntry[]): this {
    this.entries = entries.map((e) => ({ ...e }))
    return this
  }

  seedTargets(targets: WorkTarget[]): this {
    this.targets = targets.map((t) => ({ ...t }))
    return this
  }

  // Absence/holiday credits GET /time/credits serves (#31) — the Projekt-Detail
  // per-week list folds these in. Seeded raw; the range filter is applied in handle().
  seedCredits(credits: TimeCredit[]): this {
    this.credits = credits.map((c) => ({ ...c }))
    return this
  }

  /** Override the "Erledigt"-history window length GET /config/done-window serves (#356).
   *  Default is 14 (mirrors backend + clients); set a different value to exercise a
   *  configured window without hand-rolling a route in the spec. */
  seedDoneWindow(days: number): this {
    this.doneWindowDays = days
    return this
  }

  seedAbsence(seed: AbsenceSeed): this {
    this.absUsers = [...(seed.users ?? [])]
    this.absences = (seed.absences ?? []).map((a) => ({ ...a }))
    this.partTime = (seed.partTime ?? []).map((r) => ({ ...r }))
    this.kitaClosures = (seed.kitaClosures ?? []).map((k) => ({ ...k }))
    this.customHolidays = (seed.customHolidays ?? []).map((h) => ({ ...h }))
    this.absSettings = (seed.settings ?? []).map((s) => ({ ...s }))
    return this
  }

  async install(page: Page) {
    // Replace the realtime hook's socket with a fake that never opens a real
    // connection, and bridge REST → WebSocket: any response carrying an
    // `x-ws-frames` header is replayed as frame(s) onto the matching channel's
    // socket. TimeView depends on these frames to reflect its own mutations.
    await page.addInitScript(() => {
      const sockets: Array<{ url: string; onmessage: ((e: { data: string }) => void) | null }> = []
      class FakeWebSocket {
        onopen: (() => void) | null = null
        onclose: (() => void) | null = null
        onmessage: ((e: { data: string }) => void) | null = null
        onerror: (() => void) | null = null
        readyState = 1
        url: string
        // The app now opens `new WebSocket(url, ['bearer', token])` — accept and ignore the
        // subprotocols arg the same way a browser would.
        constructor(url: string, _protocols?: string | string[]) {
          this.url = String(url)
          sockets.push(this)
        }
        send() {}
        close() {
          const i = sockets.indexOf(this)
          if (i >= 0) sockets.splice(i, 1)
          this.readyState = 3
        }
      }
      // @ts-expect-error override for tests
      window.WebSocket = FakeWebSocket

      const origFetch = window.fetch.bind(window)
      const deliver = (header: string) => {
        try {
          const { channel, frames } = JSON.parse(header)
          for (const frame of frames) {
            for (const s of sockets) {
              if (s.url.includes('/ws/' + channel) && typeof s.onmessage === 'function') {
                s.onmessage({ data: JSON.stringify(frame) })
              }
            }
          }
        } catch {
          // ignore malformed bridge headers
        }
      }
      window.fetch = async (...args: Parameters<typeof fetch>) => {
        const res = await origFetch(...args)
        // x-ws-frames-pre is delivered synchronously, BEFORE the caller's await
        // resolves — pins the real-world ordering where the server's own echo
        // reaches the client before the REST response is applied (issue #61).
        const pre = res.headers.get('x-ws-frames-pre')
        if (pre) deliver(pre)
        const header = res.headers.get('x-ws-frames')
        if (header) {
          // Deliver asynchronously, mimicking a server-pushed frame so the
          // caller's await resolves first.
          setTimeout(() => deliver(header), 0)
        }
        return res
      }
    })

    await page.route('**/api/v1/**', (route) => this.handle(route))
  }

  private json(route: Route, body: unknown, status = 200) {
    return route.fulfill({
      status,
      contentType: 'application/json',
      body: JSON.stringify(body),
    })
  }

  // Shape of a digest GET/PUT response (#182). availableSections is the digest's full, ordered
  // section list; sections is the current selection (kept in that same order).
  private digestResponse(d: { time: string; enabled: boolean; sections: string[] }, all: string[]) {
    return {
      time: d.time,
      enabled: d.enabled,
      telegramConfigured: this.telegramConfigured,
      sections: d.sections,
      availableSections: all,
    }
  }

  // Mirrors the backend digest PUT: patch only the fields present, validate time + section ids,
  // store sections in canonical order. Mutates the passed-in digest state in place.
  private putDigest(route: Route, d: { time: string; enabled: boolean; sections: string[] }, all: string[]) {
    const body = JSON.parse(route.request().postData() ?? '{}')
    if (body.time != null) {
      const raw = String(body.time).trim()
      // Match the backend's LocalTime.parse: zero-padded HH:mm (seconds optional, dropped).
      const m = /^(\d{2}):(\d{2})(:\d{2})?$/.exec(raw)
      if (!m || Number(m[1]) > 23 || Number(m[2]) > 59) return this.json(route, { code: 'INVALID_TIME', message: 'bad' }, 400)
      d.time = `${m[1]}:${m[2]}`
    }
    if (body.enabled != null) d.enabled = Boolean(body.enabled)
    if (body.sections != null) {
      const ids: string[] = (body.sections as unknown[]).map((s) => String(s).trim())
      if (ids.some((id) => !all.includes(id))) return this.json(route, { code: 'INVALID_SECTION', message: 'bad' }, 400)
      d.sections = all.filter((id) => ids.includes(id))
    }
    return this.json(route, this.digestResponse(d, all))
  }

  // Like json(), but tags the response with WebSocket frames the in-page bridge
  // replays onto the given channel's socket(s) after the fetch resolves.
  // `pre = true` delivers them synchronously BEFORE the fetch resolves instead —
  // the echo-beats-REST ordering from issue #61.
  private jsonWithFrames(route: Route, body: unknown, status: number, channel: string, frames: unknown[], pre = false) {
    // silenceRealtime() → behave like a backend whose WS frame never arrives.
    if (this.silent) {
      if (status === 204) return route.fulfill({ status: 204, body: '' })
      return this.json(route, body, status)
    }
    // HTTP headers are Latin-1 — escape non-ASCII as \uXXXX so umlauts in frame
    // payloads (e.g. a todo title) survive the header round-trip intact.
    const headerJson = JSON.stringify({ channel, frames }).replace(
      /[\u0080-\uffff]/g,
      (c) => `\\u${c.charCodeAt(0).toString(16).padStart(4, '0')}`,
    )
    return route.fulfill({
      status,
      contentType: 'application/json',
      headers: { [pre ? 'x-ws-frames-pre' : 'x-ws-frames']: headerJson },
      body: JSON.stringify(body),
    })
  }

  // Build a Recipe from a create/update payload, assigning ids + sort/step order
  // the way the backend does. `prev` preserves createdBy/createdAt on update.
  private buildRecipe(id: string, b: Record<string, unknown>, prev?: Recipe): Recipe {
    const ts = new Date().toISOString()
    const ingredients = (b.ingredients as Array<Record<string, unknown>> | undefined) ?? []
    const steps = (b.steps as Array<Record<string, unknown>> | undefined) ?? []
    return {
      id,
      title: (b.title as string) ?? prev?.title ?? '',
      description: (b.description as string | undefined) ?? undefined,
      servings: (b.servings as number | undefined) ?? prev?.servings ?? 1,
      prepTimeMinutes: (b.prepTimeMinutes as number | undefined) ?? undefined,
      cookTimeMinutes: (b.cookTimeMinutes as number | undefined) ?? undefined,
      category: (b.category as RecipeCategory) ?? prev?.category ?? 'DINNER',
      ingredients: ingredients.map((i, n) => ({
        id: `ing-${this.nextChildId++}`,
        name: i.name as string,
        amount: i.amount as number | undefined,
        unit: i.unit as string | undefined,
        section: i.section as string | undefined,
        sortOrder: n,
      })),
      steps: steps.map((s, n) => ({
        id: `step-${this.nextChildId++}`,
        stepNumber: n + 1,
        description: s.description as string,
      })),
      // the cover image is managed via the dedicated endpoints, never the create/update body —
      // preserve any already attached on update.
      image: prev?.image,
      createdBy: prev?.createdBy ?? 'alice',
      createdAt: prev?.createdAt ?? ts,
      updatedAt: ts,
    }
  }

  // Build a ShoppingTemplate from a create/update payload (#215): assign item ids +
  // sortOrder by list position, drop blank names (backend does too), and preserve
  // createdBy/createdAt on update — mirrors ShoppingTemplateRoutes.
  private buildTemplate(id: string, name: string, items: Array<{ name?: string }> | undefined, prev?: ShoppingTemplate): ShoppingTemplate {
    const lines = (items ?? []).map((i) => (i.name ?? '').trim()).filter(Boolean)
    const built: ShoppingTemplateItem[] = lines.map((itemName, n) => ({
      id: `shoptplitem-${this.nextShopTemplateItemId++}`,
      name: itemName,
      sortOrder: n,
    }))
    return {
      id,
      name,
      items: built,
      createdBy: prev?.createdBy ?? 'alice',
      createdAt: prev?.createdAt ?? new Date().toISOString(),
    }
  }

  // Simplified mirror of GET /time/forecast (#31) without absences/holidays:
  // five workdays, no credits. Weekly target summed per user from the seeded
  // targets, recorded time from this ISO week's entries (running → elapsed),
  // today's target = open remainder spread over the remaining weekdays.
  private buildForecast(): TimeForecast {
    const now = new Date()
    const monday = new Date(now)
    monday.setHours(0, 0, 0, 0)
    monday.setDate(monday.getDate() - ((now.getDay() + 6) % 7))
    const nextMonday = new Date(monday)
    nextMonday.setDate(monday.getDate() + 7)
    const todayKey = ymdLocal(now)
    const secondsOf = (e: TimeEntry) =>
      e.stoppedAt ? (e.durationSeconds ?? 0) : Math.max(0, Math.floor((now.getTime() - Date.parse(e.startedAt)) / 1000))

    const mondayKey = ymdLocal(monday)
    const users: UserForecast[] = [...new Set(this.targets.map((t) => t.userId))].map((userId) => {
      // Use the period in force for this week (latest start ≤ the week's Monday).
      const mine = this.targets.filter((t) => t.userId === userId)
      const active = [...new Set(mine.map(periodOf))].sort().filter((p) => p <= mondayKey).pop() ?? BASE_TARGET_PERIOD
      const own = mine.filter((t) => periodOf(t) === active)
      const weeklyTargetHours = own.reduce((s, t) => s + t.weeklyHours, 0)
      const weekTargetSeconds = Math.round(weeklyTargetHours * 3600)
      const inWeek = this.entries.filter((e) => {
        const started = new Date(e.startedAt)
        return e.userId === userId && started >= monday && started < nextMonday
      })
      const weekRecordedSeconds = inWeek.reduce((s, e) => s + secondsOf(e), 0)
      const todayRecordedSeconds = inWeek
        .filter((e) => ymdLocal(new Date(e.startedAt)) === todayKey)
        .reduce((s, e) => s + secondsOf(e), 0)
      const recordedBefore = weekRecordedSeconds - todayRecordedSeconds
      const isoDow = ((now.getDay() + 6) % 7) + 1
      const remainingDays = isoDow <= 5 ? 5 - isoDow + 1 : 0
      const todayTargetSeconds = remainingDays > 0
        ? Math.round(Math.max(0, weekTargetSeconds - recordedBefore) / remainingDays)
        : 0
      const todayRemainingSeconds = todayTargetSeconds - todayRecordedSeconds
      const running = this.entries.some((e) => e.userId === userId && !e.stoppedAt)
      return {
        userId,
        weeklyTargetHours,
        workdayCount: 5,
        weekTargetSeconds,
        weekRecordedSeconds,
        weekCreditedSeconds: 0,
        weekRemainingSeconds: weekTargetSeconds - weekRecordedSeconds,
        todayTargetSeconds,
        todayRecordedSeconds,
        todayRemainingSeconds,
        expectedEndAt: running ? new Date(now.getTime() + Math.max(0, todayRemainingSeconds) * 1000).toISOString() : undefined,
        projects: own.filter((t) => t.weeklyHours > 0).map((t) => {
          const recordedSeconds = inWeek.filter((e) => e.projectId === t.projectId).reduce((s, e) => s + secondsOf(e), 0)
          return {
            projectId: t.projectId,
            weeklyHours: t.weeklyHours,
            recordedSeconds,
            creditedSeconds: 0,
            deltaSeconds: recordedSeconds - Math.round(t.weeklyHours * 3600),
          }
        }),
      }
    })
    return { date: todayKey, weekStart: ymdLocal(monday), users }
  }

  private async handle(route: Route) {
    const req = route.request()
    const url = new URL(req.url())
    const path = url.pathname
    const method = req.method()

    // Auth
    if (path.endsWith('/auth/login') && method === 'POST') {
      const { username, password } = JSON.parse(req.postData() ?? '{}')
      if (username && password) {
        return this.json(route, { token: TOKEN })
      }
      return this.json(route, { code: 'UNAUTHORIZED', message: 'invalid' }, 401)
    }

    // Household members — drives the assignee chips (and the shared-timer partner).
    // avatarHue rides this shared roster (Teil von #100): emitted only when set, so the
    // app sees the omitted-when-null shape it gets from the encodeDefaults=false backend.
    if (path.endsWith('/users') && method === 'GET') {
      const withHue = (username: string) =>
        this.avatarHues[username] != null ? { username, avatarHue: this.avatarHues[username] } : { username }
      return this.json(route, [withHue('max'), withHue('lea')])
    }

    // Set own avatar hue (Teil von #100). Mirrors UserRoutes: INVALID_HUE outside 0..359,
    // null clears, else 204. The mock identity is "max" (TOKEN_MAX), so it stores under max.
    if (path.endsWith('/users/me/avatar-color') && method === 'PUT') {
      const hue = JSON.parse(req.postData() ?? '{}').hue
      if (hue != null && (typeof hue !== 'number' || hue < 0 || hue > 359)) {
        return this.json(route, { code: 'INVALID_HUE', message: 'out of range' }, 400)
      }
      if (hue == null) delete this.avatarHues['max']
      else this.avatarHues['max'] = hue
      return route.fulfill({ status: 204, body: '' })
    }

    // App config — editable household name (#100). Mirrors ConfigRoutes: GET reads
    // the (in-memory) value, PUT trims + rejects blank with 400 INVALID_NAME.
    if (path.endsWith('/config') && method === 'GET') {
      return this.json(route, { householdName: this.householdName })
    }
    if (path.endsWith('/config') && method === 'PUT') {
      const name = (JSON.parse(req.postData() ?? '{}').householdName ?? '').trim()
      if (!name) return this.json(route, { code: 'INVALID_NAME', message: 'empty' }, 400)
      this.householdName = name
      return this.json(route, { householdName: name })
    }

    // Telegram digest config (#100/#182). Mirrors /config/digest: GET returns
    // {time, enabled, telegramConfigured, sections, availableSections}; PUT patches whichever of
    // {time, enabled, sections} the body carries, validating the time (INVALID_TIME) + section ids
    // (INVALID_SECTION) and persisting sections in canonical order.
    if (path.endsWith('/config/digest') && method === 'GET') {
      return this.json(route, this.digestResponse(this.evening, this.eveningSectionsAll))
    }
    if (path.endsWith('/config/digest') && method === 'PUT') {
      return this.putDigest(route, this.evening, this.eveningSectionsAll)
    }
    if (path.endsWith('/config/morning-digest') && method === 'GET') {
      return this.json(route, this.digestResponse(this.morning, this.morningSectionsAll))
    }
    if (path.endsWith('/config/morning-digest') && method === 'PUT') {
      return this.putDigest(route, this.morning, this.morningSectionsAll)
    }

    // Recurring-todo safety-net time (#100). Mirrors /config/recurring: GET returns {time},
    // PUT validates HH:mm (with INVALID_TIME), normalizes to HH:mm, stores. Always-on, so no
    // enabled flag.
    if (path.endsWith('/config/recurring') && method === 'GET') {
      return this.json(route, { time: this.recurringTime })
    }
    if (path.endsWith('/config/recurring') && method === 'PUT') {
      const raw = (JSON.parse(req.postData() ?? '{}').time ?? '').trim()
      const m = /^(\d{2}):(\d{2})(:\d{2})?$/.exec(raw)
      if (!m || Number(m[1]) > 23 || Number(m[2]) > 59) return this.json(route, { code: 'INVALID_TIME', message: 'bad' }, 400)
      this.recurringTime = `${m[1]}:${m[2]}`
      return this.json(route, { time: this.recurringTime })
    }

    // Todo reminders (#429 Phase 2a). Mirrors /config/reminders: GET returns {enabled, quietStart?,
    // quietEnd?}; PUT requires quiet bounds as a pair (INVALID_QUIET_HOURS), normalizes to HH:mm.
    if (path.endsWith('/config/reminders') && method === 'GET') {
      return this.json(route, {
        enabled: this.remindersEnabled,
        ...(this.reminderQuietStart ? { quietStart: this.reminderQuietStart } : {}),
        ...(this.reminderQuietEnd ? { quietEnd: this.reminderQuietEnd } : {}),
      })
    }
    if (path.endsWith('/config/reminders') && method === 'PUT') {
      const b = JSON.parse(req.postData() ?? '{}')
      const start = (b.quietStart ?? '').trim()
      const end = (b.quietEnd ?? '').trim()
      if ((start === '') !== (end === '')) return this.json(route, { code: 'INVALID_QUIET_HOURS', message: 'pair' }, 400)
      const norm = (v: string) => /^(\d{2}):(\d{2})$/.exec(v)
      if ((start && !norm(start)) || (end && !norm(end))) return this.json(route, { code: 'INVALID_TIME', message: 'bad' }, 400)
      // mirror the backend: a quiet window >= 12h (the scheduler's catch-up) is rejected
      if (start && end) {
        const min = (v: string) => Number(v.slice(0, 2)) * 60 + Number(v.slice(3, 5))
        const span = min(end) > min(start) ? min(end) - min(start) : 24 * 60 - (min(start) - min(end))
        if (span >= 12 * 60) return this.json(route, { code: 'INVALID_QUIET_HOURS', message: 'too long' }, 400)
      }
      this.remindersEnabled = !!b.enabled
      this.reminderQuietStart = start
      this.reminderQuietEnd = end
      return this.json(route, {
        enabled: this.remindersEnabled,
        ...(start ? { quietStart: start } : {}),
        ...(end ? { quietEnd: end } : {}),
      })
    }

    // "Erledigt"-history window length (#356). Mirrors /config/done-window: GET returns {days},
    // PUT validates an integer in [1, 3650] (INVALID_DAYS) and stores it. TodosView reads this on
    // mount; the default 14 keeps the #340 "Alle anzeigen" window test green without a stub change.
    if (path.endsWith('/config/done-window') && method === 'GET') {
      return this.json(route, { days: this.doneWindowDays })
    }
    if (path.endsWith('/config/done-window') && method === 'PUT') {
      const days = JSON.parse(req.postData() ?? '{}').days
      if (!Number.isInteger(days) || days < 1 || days > 3650) {
        return this.json(route, { code: 'INVALID_DAYS', message: 'out of range' }, 400)
      }
      this.doneWindowDays = days
      return this.json(route, { days: this.doneWindowDays })
    }

    // Per-user iCal-feed category selection (#427). Mirrors /config/calendar-feed: GET returns
    // {sections, availableSections} (unset = all); PUT validates a subset (INVALID_SECTION) and
    // stores it in canonical order. Per-user, but the mock is single-user so one field suffices.
    if (path.endsWith('/config/calendar-feed') && method === 'GET') {
      const sections = this.calendarFeedSections ?? CALENDAR_FEED_SECTIONS
      return this.json(route, { sections, availableSections: CALENDAR_FEED_SECTIONS })
    }
    if (path.endsWith('/config/calendar-feed') && method === 'PUT') {
      const ids: string[] = JSON.parse(req.postData() ?? '{}').sections ?? []
      if (ids.some((id) => !CALENDAR_FEED_SECTIONS.includes(id))) {
        return this.json(route, { code: 'INVALID_SECTION', message: 'unknown' }, 400)
      }
      this.calendarFeedSections = CALENDAR_FEED_SECTIONS.filter((id) => ids.includes(id))
      return this.json(route, { sections: this.calendarFeedSections, availableSections: CALENDAR_FEED_SECTIONS })
    }

    // Change own password (#100). Mirrors UserRoutes: WEAK_PASSWORD (<8 chars),
    // INVALID_PASSWORD (current wrong), else 204 and the stored password updates.
    if (path.endsWith('/users/me/password') && method === 'PUT') {
      const b = JSON.parse(req.postData() ?? '{}')
      if ((b.newPassword ?? '').length < 8) return this.json(route, { code: 'WEAK_PASSWORD', message: 'short' }, 400)
      if (b.currentPassword !== this.password) return this.json(route, { code: 'INVALID_PASSWORD', message: 'wrong' }, 400)
      this.password = b.newPassword
      return route.fulfill({ status: 204, body: '' })
    }

    // Per-user prefs (#100). Mirrors UserPrefsRoutes: GET returns the caller's
    // key→value map; PUT /user-prefs/{key} upserts one value and echoes the map.
    const prefMatch = path.match(/\/user-prefs\/([^/]+)$/)
    if (prefMatch && method === 'PUT') {
      const key = decodeURIComponent(prefMatch[1])
      const value = JSON.parse(req.postData() ?? '{}').value ?? ''
      this.userPrefs[key] = value
      return this.json(route, this.userPrefs)
    }
    if (path.endsWith('/user-prefs') && method === 'GET') {
      return this.json(route, this.userPrefs)
    }

    // ---- Todo lists (checked before the generic /todos/{id} matcher) ----
    if (path.endsWith('/todos/lists') && method === 'GET') {
      return this.json(route, this.lists)
    }
    if (path.endsWith('/todos/lists') && method === 'POST') {
      const { name, visibility } = JSON.parse(req.postData() ?? '{}')
      const list: TodoList = {
        id: `list-${this.nextListId++}`,
        name,
        visibility: visibility === 'PRIVATE' ? 'PRIVATE' : 'SHARED',
        createdBy: 'alice',
        createdAt: new Date().toISOString(),
      }
      this.lists.push(list)
      return this.json(route, list, 201)
    }

    const listIdMatch = path.match(/\/todos\/lists\/([^/]+)$/)
    if (listIdMatch) {
      const id = listIdMatch[1]
      const idx = this.lists.findIndex((l) => l.id === id)
      if (method === 'PUT') {
        if (idx === -1) return this.json(route, { message: 'not found' }, 404)
        const wasShared = this.lists[idx].visibility !== 'PRIVATE'
        const updated = { ...this.lists[idx], ...JSON.parse(req.postData() ?? '{}') }
        this.lists[idx] = updated
        const isShared = updated.visibility !== 'PRIVATE'
        // Mirror broadcastListUpdate in TodoRoutes.kt: translate the visibility transition for the
        // shared channel. private→shared additionally replays the now-visible todos (issue #75).
        const frames: unknown[] = []
        if (isShared && wasShared) {
          frames.push({ type: 'TODO_LIST_UPDATED', payload: updated })
        } else if (isShared) {
          frames.push({ type: 'TODO_LIST_CREATED', payload: updated })
          for (const t of this.todos.filter((t) => t.listId === updated.id)) frames.push({ type: 'TODO_CREATED', payload: t })
        } else if (wasShared) {
          frames.push({ type: 'TODO_LIST_DELETED', payload: updated })
        }
        return this.jsonWithFrames(route, updated, 200, 'todos', frames)
      }
      if (method === 'DELETE') {
        if (idx === -1) return this.json(route, { message: 'not found' }, 404)
        const removed = this.lists[idx]
        this.lists.splice(idx, 1)
        // Backend cascades: todos in the removed list go away with it.
        this.todos = this.todos.filter((t) => t.listId !== id)
        // A shared list's deletion is broadcast (private ones are silent), mirroring broadcastListDelete.
        if (removed.visibility !== 'PRIVATE') {
          return this.jsonWithFrames(route, '', 204, 'todos', [{ type: 'TODO_LIST_DELETED', payload: removed }])
        }
        return route.fulfill({ status: 204, body: '' })
      }
    }

    // ---- Subtasks: every mutation returns the updated parent todo ----
    const subCollMatch = path.match(/\/todos\/([^/]+)\/subtasks$/)
    if (subCollMatch && method === 'POST') {
      const todo = this.todos.find((t) => t.id === subCollMatch[1])
      if (!todo) return this.json(route, { message: 'not found' }, 404)
      const { title } = JSON.parse(req.postData() ?? '{}')
      const subs = (todo.subtasks ??= [])
      subs.push({ id: `sub-${this.nextSubId++}`, title, done: false, sortOrder: subs.length })
      return this.json(route, todo, 201)
    }

    const subItemMatch = path.match(/\/todos\/([^/]+)\/subtasks\/([^/]+)$/)
    if (subItemMatch) {
      const [, todoId, subId] = subItemMatch
      const todo = this.todos.find((t) => t.id === todoId)
      if (!todo) return this.json(route, { message: 'not found' }, 404)
      const subs = todo.subtasks ?? []
      const sIdx = subs.findIndex((s) => s.id === subId)
      if (method === 'PUT') {
        if (sIdx === -1) return this.json(route, { message: 'not found' }, 404)
        subs[sIdx] = { ...subs[sIdx], ...JSON.parse(req.postData() ?? '{}') }
        return this.json(route, todo)
      }
      if (method === 'DELETE') {
        if (sIdx !== -1) subs.splice(sIdx, 1)
        return this.json(route, todo)
      }
    }

    // Todos collection
    if (path.endsWith('/todos') && method === 'GET') {
      return this.json(route, this.todos)
    }
    if (path.endsWith('/todos') && method === 'POST') {
      const { title, listId, assignees, dueDate, dueTime, reminderLeadMinutes, priority, description } = JSON.parse(req.postData() ?? '{}')
      // Mirror TodoRoutes.kt: an assignee or due date on create makes the todo PLANNED (the
      // quick-add "all-at-once" flow); a bare title — or only description/priority — stays INBOX.
      const status = (assignees?.length || dueDate) ? 'PLANNED' : 'INBOX'
      const now = new Date().toISOString()
      const todo: Todo = {
        id: `todo-${this.nextId++}`,
        title,
        status,
        listId: listId || undefined,
        assignees: assignees?.length ? assignees : undefined,
        dueDate: dueDate || undefined,
        // a time/reminder is meaningless without a date (mirror the backend cascade)
        dueTime: (dueDate && dueTime) || undefined,
        reminderLeadMinutes: (dueDate && reminderLeadMinutes) || undefined,
        priority: priority || undefined,
        description: description || undefined,
        subtasks: [],
        createdBy: 'alice',
        createdAt: now,
        // create stamps updatedAt = createdAt (mirror the backend)
        updatedAt: now,
      }
      this.todos.unshift(todo)
      // The echo deliberately beats the REST response (pre-frame) — worst-case
      // ordering from issue #61; the views must dedupe by id.
      return this.jsonWithFrames(route, todo, 201, 'todos', [{ type: 'TODO_CREATED', payload: todo }], true)
    }

    // Single todo
    const idMatch = path.match(/\/todos\/([^/]+)$/)
    if (idMatch) {
      const id = idMatch[1]
      const idx = this.todos.findIndex((t) => t.id === id)
      if (method === 'PUT') {
        const body = JSON.parse(req.postData() ?? '{}')
        if (idx === -1) return this.json(route, { message: 'not found' }, 404)
        const updated: Todo = { ...this.todos[idx], ...body }
        // every PUT is an edit → bump the last-modified stamp (mirror the backend)
        updated.updatedAt = new Date().toISOString()
        if (body.listId === '') updated.listId = undefined
        // #265 clearing: "" clears the time; a negative reminder clears it (mirror the backend)
        if (body.dueTime === '') updated.dueTime = undefined
        if (typeof body.reminderLeadMinutes === 'number' && body.reminderLeadMinutes < 0) updated.reminderLeadMinutes = undefined
        // a time/reminder is meaningless without a date — clearing the date cascades them away
        if (body.dueDate === '') { updated.dueDate = undefined; updated.dueTime = undefined; updated.reminderLeadMinutes = undefined }
        if (body.status === 'DONE') updated.doneAt = new Date().toISOString()
        this.todos[idx] = updated
        return this.json(route, updated)
      }
      if (method === 'DELETE') {
        if (idx !== -1) this.todos.splice(idx, 1)
        return route.fulfill({ status: 204, body: '' })
      }
    }

    // ---- Shopping lists (checked before the generic /shopping/{id} matcher) ----
    if (path.endsWith('/shopping/lists') && method === 'GET') {
      return this.json(route, this.shoppingLists)
    }
    if (path.endsWith('/shopping/lists') && method === 'POST') {
      const { name, ownCategories } = JSON.parse(req.postData() ?? '{}')
      const list: ShoppingList = {
        id: `shoplist-${this.nextShopListId++}`,
        name,
        createdBy: 'alice',
        createdAt: new Date().toISOString(),
        ...(ownCategories ? { ownCategories: true } : {}), // #412
      }
      this.shoppingLists.push(list)
      return this.json(route, list, 201)
    }

    const shopListIdMatch = path.match(/\/shopping\/lists\/([^/]+)$/)
    if (shopListIdMatch) {
      const id = shopListIdMatch[1]
      const idx = this.shoppingLists.findIndex((l) => l.id === id)
      if (method === 'PUT') {
        if (idx === -1) return this.json(route, { message: 'not found' }, 404)
        this.shoppingLists[idx] = { ...this.shoppingLists[idx], ...JSON.parse(req.postData() ?? '{}') }
        return this.json(route, this.shoppingLists[idx])
      }
      if (method === 'DELETE') {
        if (idx === -1) return this.json(route, { message: 'not found' }, 404)
        this.shoppingLists.splice(idx, 1)
        this.shoppingItems = this.shoppingItems.filter((i) => i.listId !== id)
        return route.fulfill({ status: 204, body: '' })
      }
    }

    // Shopping items
    if (path.endsWith('/shopping') && method === 'GET') {
      return this.json(route, this.shoppingItems)
    }
    if (path.endsWith('/shopping') && method === 'POST') {
      const { name, listId, quantity } = JSON.parse(req.postData() ?? '{}')
      const item: ShoppingItem = {
        id: `shop-${this.nextShopId++}`,
        name,
        listId: listId || undefined,
        checked: false,
        createdBy: 'alice',
        createdAt: new Date().toISOString(),
        quantity: quantity?.trim() || undefined,
      }
      this.shoppingItems.unshift(item)
      return this.json(route, item, 201)
    }

    // Shopping: batch add recipe ingredients (mirrors POST /shopping/batch).
    // #554: writes each line like the web quick-add — a bare name + a separate
    // "200 g" quantity field — and merges amounts read from `quantity ?? parseQty(name)`
    // (the name parse is only the legacy fallback). Matched before /shopping/{id}.
    if (path.endsWith('/shopping/batch') && method === 'POST') {
      const { listId, items = [] } = JSON.parse(req.postData() ?? '{}') as {
        listId?: string
        items?: Array<{ name: string; amount?: number; unit?: string }>
      }
      const UNITS = new Set(['g', 'kg', 'mg', 'ml', 'l', 'el', 'tl', 'stk', 'stück', 'prise', 'bund', 'dose', 'pkg', 'pck', 'tasse', 'cup', 'msp'])
      const fmtAmt = (v: number) => String(Math.round(v * 1000) / 1000)
      const fmt = (a: number | null | undefined, u: string | null | undefined, n: string) =>
        [a != null ? fmtAmt(a) : null, u && u.trim() ? u : null, n].filter(Boolean).join(' ').trim()
      // "200 g" quantity label for the quantity field (null when there is no amount/unit).
      const buildQty = (a: number | null | undefined, u: string | null | undefined) =>
        [a != null ? fmtAmt(a) : null, u && u.trim() ? u : null].filter(Boolean).join(' ').trim() || undefined
      // legacy fallback: pull amount/unit/name back out of a composite "200 g Mehl" name
      const parseQty = (label: string): { amount: number | null; unit: string | null; name: string } => {
        const t = label.trim().split(/\s+/).filter(Boolean)
        const a = t.length ? Number(t[0].replace(',', '.')) : NaN
        if (!t.length || !/^[0-9]/.test(t[0]) || !Number.isFinite(a)) return { amount: null, unit: null, name: label.trim() }
        let i = 1
        let unit: string | null = null
        if (i < t.length) {
          const c = t[i]
          const isUnit = UNITS.has(c.toLowerCase()) || (c.length <= 4 && /[a-zA-ZäöüÄÖÜß]/.test(c) && !/[0-9]/.test(c))
          if (isUnit && i < t.length - 1) { unit = c; i++ }
        }
        const name = t.slice(i).join(' ')
        return name ? { amount: a, unit, name } : { amount: null, unit: null, name: label.trim() }
      }
      // parse a standalone "200 g" quantity label (KNOWN_UNITS gated, like the backend)
      const parseQtyField = (q: string): { amount: number | null; unit: string | null } => {
        const t = q.trim().split(/\s+/).filter(Boolean)
        const a = t.length ? Number(t[0].replace(',', '.')) : NaN
        if (!t.length || !Number.isFinite(a)) return { amount: null, unit: null }
        const c = t[1]
        return { amount: a, unit: c && UNITS.has(c.toLowerCase()) ? c : null }
      }
      // amount/unit/name for merge: quantity field wins, else the legacy composite name
      const effective = (it: ShoppingItem): { amount: number | null; unit: string | null; name: string } => {
        const q = it.quantity?.trim()
        if (q) { const p = parseQtyField(q); return { amount: p.amount, unit: p.unit, name: it.name.trim() } }
        return parseQty(it.name)
      }
      const unitEq = (a: string | null, b: string | null | undefined) => (a ?? '').toLowerCase() === (b ?? '').toLowerCase()
      const qtyEq = (a: string | undefined, b: string | undefined) => (a?.trim() ?? '').toLowerCase() === (b?.trim() ?? '').toLowerCase()
      const inList = () => this.shoppingItems.filter((it) => (it.listId ?? undefined) === (listId || undefined))
      const created: ShoppingItem[] = []
      const updated: ShoppingItem[] = []
      let skipped = 0
      for (const line of items) {
        const name = (line.name ?? '').trim()
        if (!name) continue
        const unit = line.unit && line.unit.trim() ? line.unit.trim() : undefined
        const amount = line.amount
        const newQty = buildQty(amount, unit)
        const display = fmt(amount, unit, name)
        const target = amount != null
          ? inList().find((it) => {
              const p = effective(it)
              return p.amount != null && p.name.toLowerCase() === name.toLowerCase() && unitEq(p.unit, unit ?? null)
            })
          : undefined
        if (target) {
          const p = effective(target)
          target.name = p.name
          target.quantity = buildQty((p.amount ?? 0) + (amount ?? 0), p.unit ?? unit)
          updated.push(target)
          continue
        }
        const isDup = inList().some((it) =>
          it.quantity != null
            ? it.name.toLowerCase() === name.toLowerCase() && qtyEq(it.quantity, newQty)
            : it.name.toLowerCase() === display.toLowerCase(),
        )
        if (isDup) {
          skipped++
          continue
        }
        const item: ShoppingItem = {
          id: `shop-${this.nextShopId++}`,
          name,
          quantity: newQty,
          listId: listId || undefined,
          checked: false,
          createdBy: 'alice',
          createdAt: new Date().toISOString(),
        }
        this.shoppingItems.unshift(item)
        created.push(item)
      }
      return this.json(route, { added: created.length, merged: updated.length, skipped, items: [...created, ...updated] })
    }

    // ---- Shopping templates (#215) — named "standard lists" of item names.
    // Matched BEFORE the generic /shopping/{id} item matcher, which would otherwise
    // swallow GET/POST /shopping/templates as item id="templates". Broadcasts ride the
    // same "shopping" WS channel as item/list mutations (single client subscription).
    if (path.endsWith('/shopping/templates') && method === 'GET') {
      return this.json(route, this.shoppingTemplates)
    }
    if (path.endsWith('/shopping/templates') && method === 'POST') {
      const b = JSON.parse(req.postData() ?? '{}') as { name?: string; items?: Array<{ name?: string }> }
      if (!b.name || !b.name.trim()) return this.json(route, { code: 'INVALID_TEMPLATE', message: 'blank' }, 400)
      const tpl = this.buildTemplate(`shoptpl-${this.nextShopTemplateId++}`, b.name.trim(), b.items)
      this.shoppingTemplates.push(tpl)
      return this.jsonWithFrames(route, tpl, 201, 'shopping', [{ type: 'SHOPPING_TEMPLATE_CREATED', payload: tpl }])
    }

    const shopTemplateMatch = path.match(/\/shopping\/templates\/([^/]+)$/)
    if (shopTemplateMatch) {
      const id = shopTemplateMatch[1]
      const idx = this.shoppingTemplates.findIndex((t) => t.id === id)
      if (method === 'PUT') {
        if (idx === -1) return this.json(route, { code: 'NOT_FOUND', message: 'not found' }, 404)
        const b = JSON.parse(req.postData() ?? '{}') as { name?: string; items?: Array<{ name?: string }> }
        if (b.name != null && !b.name.trim()) return this.json(route, { code: 'INVALID_TEMPLATE', message: 'blank' }, 400)
        const prev = this.shoppingTemplates[idx]
        // name omitted = unchanged; items omitted = unchanged, else full replace (mirrors backend).
        const tpl = this.buildTemplate(
          id,
          b.name != null ? b.name.trim() : prev.name,
          b.items !== undefined ? b.items : prev.items.map((i) => ({ name: i.name })),
          prev,
        )
        this.shoppingTemplates[idx] = tpl
        return this.jsonWithFrames(route, tpl, 200, 'shopping', [{ type: 'SHOPPING_TEMPLATE_UPDATED', payload: tpl }])
      }
      if (method === 'DELETE') {
        if (idx === -1) return this.json(route, { code: 'NOT_FOUND', message: 'not found' }, 404)
        const removed = this.shoppingTemplates[idx]
        this.shoppingTemplates.splice(idx, 1)
        return this.jsonWithFrames(route, '', 204, 'shopping', [{ type: 'SHOPPING_TEMPLATE_DELETED', payload: removed }])
      }
    }

    // Autocomplete suggestions (#389) — matched BEFORE the generic /shopping/{id} item matcher,
    // which would otherwise treat "suggestions" as an item id. Returns the seeded list (default []).
    if (path.endsWith('/shopping/suggestions') && method === 'GET') {
      return this.json(route, this.shoppingSuggestions)
    }

    // ---- Shopping category-rules (#411) — matched BEFORE /shopping/categories AND the generic
    // /shopping/{id} matcher. PUT upserts by normalized display name (category must be a live key);
    // DELETE removes by display-or-normalized name. Both broadcast SHOPPING_CATEGORY_RULE_CHANGED.
    // #501: scoped per list via ?listId — an own-categories list has its own private dictionary; a
    // shared list (or no listId) the shared household one. A rule's `listId` marks its scope.
    if (path.endsWith('/shopping/category-rules')) {
      const rulesListId = url.searchParams.get('listId') || undefined
      const rulesOwn = !!rulesListId && this.shoppingLists.find((l) => l.id === rulesListId)?.ownCategories
      const rulesScope = rulesOwn ? rulesListId : undefined // undefined = shared dictionary
      const inScope = (r: ShoppingCategoryRule) => (r.listId ?? undefined) === rulesScope
      if (method === 'GET') {
        return this.json(route, this.shoppingCategoryRules.filter(inScope).sort((a, b) => a.displayName.localeCompare(b.displayName)))
      }
      if (method === 'PUT') {
        const b = JSON.parse(req.postData() ?? '{}') as { displayName?: string; category?: string; icon?: string }
        const displayName = (b.displayName ?? '').trim()
        if (!displayName) return this.json(route, { code: 'INVALID_RULE', message: 'blank name' }, 400)
        // the category must be live in this scope: the list's own rows + OTHER, or the shared catalog
        const scopedKeys = rulesOwn
          ? this.shoppingCategories.filter((c) => c.listId === rulesListId || c.key === 'OTHER').map((c) => c.key)
          : this.shoppingCategories.filter((c) => !c.listId).map((c) => c.key)
        if (!scopedKeys.includes(b.category as string)) {
          return this.json(route, { code: 'INVALID_CATEGORY', message: 'unknown category' }, 400)
        }
        const normalizedName = displayName.toLowerCase()
        const existing = this.shoppingCategoryRules.find((r) => r.normalizedName === normalizedName && inScope(r))
        const icon = b.icon != null && b.icon.trim() ? b.icon.trim() : existing?.icon ?? '🛒'
        const rule: ShoppingCategoryRule = { normalizedName, displayName, category: b.category as string, icon, ...(rulesScope ? { listId: rulesScope } : {}) }
        if (existing) Object.assign(existing, rule)
        else this.shoppingCategoryRules.push(rule)
        return this.jsonWithFrames(route, rule, 200, 'shopping', [{ type: 'SHOPPING_CATEGORY_RULE_CHANGED', payload: rule }])
      }
    }
    const ruleMatch = path.match(/\/shopping\/category-rules\/([^/]+)$/)
    if (ruleMatch && method === 'DELETE') {
      const rulesListId = url.searchParams.get('listId') || undefined
      const rulesOwn = !!rulesListId && this.shoppingLists.find((l) => l.id === rulesListId)?.ownCategories
      const rulesScope = rulesOwn ? rulesListId : undefined
      const name = decodeURIComponent(ruleMatch[1]).trim().toLowerCase()
      const idx = this.shoppingCategoryRules.findIndex((r) => r.normalizedName === name && (r.listId ?? undefined) === rulesScope)
      if (idx !== -1) this.shoppingCategoryRules.splice(idx, 1)
      return this.jsonWithFrames(route, '', 204, 'shopping', [{ type: 'SHOPPING_CATEGORY_RULE_CHANGED' }])
    }

    // ---- Shopping categories (#411) — matched BEFORE the generic /shopping/{id} item matcher.
    // POST derives the key from the label (slug, uppercased); PUT patches label/emoji/sortOrder;
    // DELETE protects OTHER (400 CATEGORY_PROTECTED) and reassigns its items to OTHER. Each mutation
    // broadcasts SHOPPING_CATEGORY_CHANGED. GET returns the catalog sorted by sortOrder.
    if (path.endsWith('/shopping/categories') && method === 'GET') {
      // Per-list scope (#412): ?listId of an own-categories list → its custom rows + the shared OTHER;
      // else (no listId / a shared list) → the shared household catalog (rows without a listId).
      const listId = url.searchParams.get('listId') || undefined
      const own = !!listId && this.shoppingLists.find((l) => l.id === listId)?.ownCategories
      const scoped = own
        ? this.shoppingCategories.filter((c) => c.listId === listId || c.key === 'OTHER')
        : this.shoppingCategories.filter((c) => !c.listId)
      return this.json(route, [...scoped].sort((a, b) => a.sortOrder - b.sortOrder))
    }
    if (path.endsWith('/shopping/categories') && method === 'POST') {
      const b = JSON.parse(req.postData() ?? '{}') as { label?: string; emoji?: string; sortOrder?: number }
      const label = (b.label ?? '').trim()
      if (!label) return this.json(route, { code: 'INVALID_CATEGORY', message: 'blank label' }, 400)
      const listId = url.searchParams.get('listId') || undefined // #412: scope the new category to a list
      const base = label.toUpperCase().replace(/[^A-Z0-9]+/g, '_').replace(/^_+|_+$/g, '') || `CAT_${this.nextShopCategoryId}`
      let key = base
      let n = 2
      while (this.shoppingCategories.some((c) => c.key === key)) key = `${base}_${n++}`
      // default sort order is relative to the target scope (the list's own rows, or the shared set)
      const sortOrder = b.sortOrder ?? Math.max(-1, ...this.shoppingCategories.filter((c) => (c.listId ?? undefined) === listId).map((c) => c.sortOrder)) + 1
      const cat: ShoppingCategory = { key, label, emoji: (b.emoji ?? '').trim() || '🛒', sortOrder, isBuiltin: false, ...(listId ? { listId } : {}) }
      this.nextShopCategoryId++
      this.shoppingCategories.push(cat)
      return this.jsonWithFrames(route, cat, 201, 'shopping', [{ type: 'SHOPPING_CATEGORY_CHANGED', payload: cat }])
    }
    const catMatch = path.match(/\/shopping\/categories\/([^/]+)$/)
    if (catMatch) {
      const key = decodeURIComponent(catMatch[1])
      const idx = this.shoppingCategories.findIndex((c) => c.key === key)
      if (method === 'PUT') {
        if (idx === -1) return this.json(route, { code: 'NOT_FOUND', message: 'not found' }, 404)
        const b = JSON.parse(req.postData() ?? '{}') as { label?: string; emoji?: string; sortOrder?: number }
        const cur = this.shoppingCategories[idx]
        if (b.label != null && !b.label.trim()) return this.json(route, { code: 'INVALID_CATEGORY', message: 'blank label' }, 400)
        this.shoppingCategories[idx] = {
          ...cur,
          label: b.label != null ? b.label.trim() : cur.label,
          emoji: b.emoji != null ? (b.emoji.trim() || cur.emoji) : cur.emoji,
          sortOrder: b.sortOrder != null ? b.sortOrder : cur.sortOrder,
        }
        return this.jsonWithFrames(route, this.shoppingCategories[idx], 200, 'shopping', [{ type: 'SHOPPING_CATEGORY_CHANGED', payload: this.shoppingCategories[idx] }])
      }
      if (method === 'DELETE') {
        if (key === 'OTHER') return this.json(route, { code: 'CATEGORY_PROTECTED', message: 'OTHER is protected' }, 400)
        if (idx !== -1) {
          this.shoppingCategories.splice(idx, 1)
          // Reassign items of the deleted category to OTHER (mirrors the backend).
          for (const it of this.shoppingItems) if (it.category === key) it.category = 'OTHER'
        }
        return this.jsonWithFrames(route, '', 204, 'shopping', [{ type: 'SHOPPING_CATEGORY_CHANGED' }])
      }
    }

    const shopItemMatch = path.match(/\/shopping\/([^/]+)$/)
    if (shopItemMatch) {
      const id = shopItemMatch[1]
      const idx = this.shoppingItems.findIndex((i) => i.id === id)
      if (method === 'PUT') {
        const body = JSON.parse(req.postData() ?? '{}')
        if (idx === -1) return this.json(route, { message: 'not found' }, 404)
        const updated: ShoppingItem = { ...this.shoppingItems[idx], ...body }
        if (body.listId === '') updated.listId = undefined
        // Free-text details (#445): "" clears (mirrors the backend null), trimmed otherwise.
        if (body.quantity !== undefined) updated.quantity = body.quantity.trim() || undefined
        if (body.note !== undefined) updated.note = body.note.trim() || undefined
        // Icon override (#508/#511): "" clears it back to auto-resolution (mirrors the backend null).
        if (body.icon === '') updated.icon = undefined
        if (body.checked === true) updated.checkedAt = new Date().toISOString()
        if (body.checked === false) updated.checkedAt = undefined
        this.shoppingItems[idx] = updated
        return this.json(route, updated)
      }
      if (method === 'DELETE') {
        if (idx !== -1) this.shoppingItems.splice(idx, 1)
        return route.fulfill({ status: 204, body: '' })
      }
    }

    // ---- Recipes ----
    if (path.endsWith('/recipes') && method === 'GET') {
      const category = url.searchParams.get('category')
      return this.json(route, category ? this.recipes.filter((r) => r.category === category) : this.recipes)
    }
    if (path.endsWith('/recipes') && method === 'POST') {
      const recipe = this.buildRecipe(`recipe-${this.nextRecipeId++}`, JSON.parse(req.postData() ?? '{}'))
      this.recipes.unshift(recipe)
      return this.json(route, recipe, 201)
    }
    // ---- Recipes: URL import (stub mirroring POST /recipes/import, #430) ----
    // The real backend fetches the page + parses JSON-LD; the mock just maps the URL: a URL
    // containing "norecipe" returns 422 (no recipe data), anything else returns a fixed draft.
    if (path.endsWith('/recipes/import') && method === 'POST') {
      const importUrl = (JSON.parse(req.postData() ?? '{}').url ?? '') as string
      if (importUrl.includes('norecipe')) {
        return this.json(route, { code: 'NO_RECIPE_DATA', message: 'no recipe' }, 422)
      }
      return this.json(route, {
        title: 'Importierte Lasagne',
        description: 'Frisch aus dem Netz.',
        servings: 4,
        prepTimeMinutes: 30,
        cookTimeMinutes: 45,
        category: 'DINNER',
        ingredients: [
          { name: 'Lasagneplatten', amount: 250, unit: 'g' },
          { name: 'Hackfleisch', amount: 500, unit: 'g' },
        ],
        steps: [{ description: 'Soße kochen.' }, { description: 'Schichten und backen.' }],
        sourceUrl: importUrl,
      })
    }

    // ---- Recipes: single-recipe export (stub mirroring GET /recipes/{id}/export) ----
    // Returns markdown or a pdf-magic body plus a Content-Disposition filename, so the
    // in-app blob download flow can be exercised end-to-end.
    const recipeExportMatch = path.match(/\/recipes\/([^/]+)\/export$/)
    if (recipeExportMatch && method === 'GET') {
      const r = this.recipes.find((x) => x.id === recipeExportMatch[1])
      if (!r) return this.json(route, { message: 'not found' }, 404)
      const format = url.searchParams.get('format') ?? 'md'
      const slug =
        r.title
          .toLowerCase()
          .replace(/ä/g, 'ae').replace(/ö/g, 'oe').replace(/ü/g, 'ue').replace(/ß/g, 'ss')
          .normalize('NFD').replace(/[\u0300-\u036f]/g, '')
          .replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '') || 'rezept'
      if (format === 'pdf') {
        return route.fulfill({
          status: 200,
          contentType: 'application/pdf',
          headers: { 'content-disposition': `attachment; filename="rezept_${slug}.pdf"` },
          body: '%PDF-1.4\nmock',
        })
      }
      const lines = r.ingredients.map((i) => `- ${[i.amount, i.unit, i.name].filter(Boolean).join(' ')}`)
      const md = `# ${r.title}\n\n## Zutaten\n\n${lines.join('\n')}\n`
      return route.fulfill({
        status: 200,
        contentType: 'text/markdown; charset=UTF-8',
        headers: { 'content-disposition': `attachment; filename="rezept_${slug}.md"` },
        body: md,
      })
    }

    // Recipe cover image (mirror POST /recipes/{id}/images = set/replace, GET serve, DELETE).
    // Recipes are shared, so there's no visibility gate. A recipe has at most one image, so the
    // upload replaces any existing one. nextImageUploadStatus drives the 413/415 paths.
    const recipeImagesPost = path.match(/\/recipes\/([^/]+)\/images$/)
    if (recipeImagesPost && method === 'POST') {
      const recipeId = recipeImagesPost[1]
      const idx = this.recipes.findIndex((r) => r.id === recipeId)
      if (idx === -1) return this.json(route, { message: 'not found' }, 404)
      if (this.nextImageUploadStatus !== null) {
        const status = this.nextImageUploadStatus
        this.nextImageUploadStatus = null
        return this.json(route, { code: status === 413 ? 'PAYLOAD_TOO_LARGE' : 'UNSUPPORTED_MEDIA_TYPE', message: 'rejected' }, status)
      }
      const original = /filename="([^"]+)"/.exec(req.postData() ?? '')?.[1] ?? 'upload.png'
      const img: RecipeImage = {
        id: `recipeimg-${this.nextRecipeImageId++}`,
        recipeId,
        originalName: original,
        contentType: 'image/png',
        sizeBytes: TINY_PNG.length,
        createdBy: 'alice',
        createdAt: new Date().toISOString(),
      }
      this.recipes[idx] = { ...this.recipes[idx], image: img, updatedAt: new Date().toISOString() }
      return this.json(route, this.recipes[idx], 201)
    }

    const recipeImageMatch = path.match(/\/recipes\/([^/]+)\/images\/([^/]+)$/)
    if (recipeImageMatch && method === 'GET') {
      // serve a real blob so <AuthedImage>'s authFetch → blob path is exercised
      const [, recipeId, imageId] = recipeImageMatch
      const img = this.recipes.find((r) => r.id === recipeId)?.image
      if (!img || img.id !== imageId) return this.json(route, { message: 'not found' }, 404)
      return route.fulfill({ status: 200, contentType: img.contentType || 'image/png', body: TINY_PNG })
    }
    if (recipeImageMatch && method === 'DELETE') {
      const [, recipeId, imageId] = recipeImageMatch
      const idx = this.recipes.findIndex((r) => r.id === recipeId)
      if (idx === -1) return this.json(route, { message: 'not found' }, 404)
      if (this.recipes[idx].image?.id !== imageId) return this.json(route, { message: 'not found' }, 404)
      this.recipes[idx] = { ...this.recipes[idx], image: undefined, updatedAt: new Date().toISOString() }
      return this.json(route, this.recipes[idx])
    }

    const recipeIdMatch = path.match(/\/recipes\/([^/]+)$/)
    if (recipeIdMatch) {
      const id = recipeIdMatch[1]
      const idx = this.recipes.findIndex((r) => r.id === id)
      if (method === 'GET') {
        if (idx === -1) return this.json(route, { message: 'not found' }, 404)
        // optional ?servings=N scaling, mirroring the backend contract
        const servings = url.searchParams.get('servings')
        const r = this.recipes[idx]
        if (servings && r.servings > 0) {
          const factor = Number(servings) / r.servings
          return this.json(route, {
            ...r,
            servings: Number(servings),
            ingredients: r.ingredients.map((i) => ({ ...i, amount: i.amount != null ? i.amount * factor : i.amount })),
          })
        }
        return this.json(route, r)
      }
      if (method === 'PUT') {
        if (idx === -1) return this.json(route, { message: 'not found' }, 404)
        const updated = this.buildRecipe(id, JSON.parse(req.postData() ?? '{}'), this.recipes[idx])
        this.recipes[idx] = updated
        return this.json(route, updated)
      }
      if (method === 'DELETE') {
        if (idx !== -1) this.recipes.splice(idx, 1)
        return route.fulfill({ status: 204, body: '' })
      }
    }

    // ---- Wochenplan / meal planner (#218) — mirrors MealPlanRoutes ----
    // GET /meal-plan?from=&to= returns entries in the inclusive range (ISO strings sort
    // lexicographically, so plain string compares work). PUT/DELETE on /{date}/{slot} upsert
    // and clear; both broadcast MEAL_PLAN_CHANGED on the "meal-plan" channel.
    if (path.endsWith('/meal-plan') && method === 'GET') {
      const from = url.searchParams.get('from')
      const to = url.searchParams.get('to')
      const inRange = this.mealPlan.filter((e) => (!from || e.date >= from) && (!to || e.date <= to))
      return this.json(route, inRange)
    }

    // ---- Calendar events (#434) — mirrors EventRoutes GET /events?from=&to= ----
    if (path.endsWith('/events') && method === 'GET') {
      const from = url.searchParams.get('from')
      const to = url.searchParams.get('to')
      const inRange = this.events.filter((e) => (!from || e.date >= from) && (!to || e.date <= to))
      return this.json(route, inRange)
    }
    const mealPlanSlotMatch = path.match(/\/meal-plan\/([^/]+)\/([^/]+)$/)
    if (mealPlanSlotMatch) {
      const date = mealPlanSlotMatch[1]
      const slot = mealPlanSlotMatch[2].toUpperCase() as MealSlot
      if (method === 'PUT') {
        const body = JSON.parse(req.postData() ?? '{}') as { recipeId?: string; dishTitle?: string; servings?: number | null }
        // XOR: a recipe reference OR a free-text dish (#293), never both/neither.
        const dishTitle = body.dishTitle?.trim()
        if ((!body.recipeId) === (!dishTitle)) return this.json(route, { code: 'INVALID_ENTRY', message: 'recipeId xor dishTitle' }, 400)
        this.mealPlan = this.mealPlan.filter((e) => !(e.date === date && e.slot === slot))
        const base = { id: `meal-${this.nextMealPlanId++}`, date, slot, createdBy: 'alice', createdAt: new Date().toISOString() }
        let entry: MealPlanEntry
        if (dishTitle) {
          // free-text entry: mirror encodeDefaults=false — no recipe fields, no servings
          entry = { ...base, dishTitle }
        } else {
          const recipe = this.recipes.find((r) => r.id === body.recipeId)
          if (!recipe) return this.json(route, { code: 'NOT_FOUND', message: 'Recipe not found' }, 404)
          entry = {
            ...base,
            recipeId: recipe.id,
            recipeTitle: recipe.title,
            recipeCategory: recipe.category,
            // mirror encodeDefaults=false: only carry servings when set
            ...(body.servings != null ? { servings: body.servings } : {}),
          }
        }
        this.mealPlan.push(entry)
        return this.jsonWithFrames(route, entry, 200, 'meal-plan', [{ type: 'MEAL_PLAN_CHANGED' }])
      }
      if (method === 'DELETE') {
        this.mealPlan = this.mealPlan.filter((e) => !(e.date === date && e.slot === slot))
        return this.jsonWithFrames(route, '', 204, 'meal-plan', [{ type: 'MEAL_PLAN_CHANGED' }])
      }
    }

    // ---- Notes ----
    if (path.endsWith('/notes') && method === 'GET') {
      const q = (url.searchParams.get('q') ?? '').toLowerCase()
      const list = q
        ? this.notes.filter((n) => n.title.toLowerCase().includes(q) || n.content.toLowerCase().includes(q))
        : this.notes
      return this.json(route, list)
    }
    if (path.endsWith('/notes') && method === 'POST') {
      const b = JSON.parse(req.postData() ?? '{}')
      const ts = new Date().toISOString()
      const note: Note = {
        id: `note-${this.nextNoteId++}`,
        title: b.title,
        content: b.content ?? '',
        tags: b.tags ?? [],
        visibility: b.visibility === 'PRIVATE' ? 'PRIVATE' : 'SHARED',
        images: [],
        attachments: [],
        createdBy: 'alice',
        createdAt: ts,
        updatedAt: ts,
      }
      this.notes.unshift(note)
      return this.json(route, note, 201)
    }

    // Upload an image to a note (mirrors POST /notes/{id}/images, multipart). On
    // success appends a new NoteImage and returns the updated note (the contract the
    // editor's paste/drop flow relies on, #146); failNextImageUpload() forces a 413/415
    // to drive the error paths. The original_name is parsed from the multipart body.
    const noteImagesPost = path.match(/\/notes\/([^/]+)\/images$/)
    if (noteImagesPost && method === 'POST') {
      const noteId = noteImagesPost[1]
      // keep the request pending while a test holds it (in-flight-edit scenarios)
      if (this.imageUploadGate) await this.imageUploadGate
      const idx = this.notes.findIndex((n) => n.id === noteId)
      if (idx === -1) return this.json(route, { message: 'not found' }, 404)
      if (this.nextImageUploadStatus !== null) {
        const status = this.nextImageUploadStatus
        this.nextImageUploadStatus = null
        return this.json(route, { code: status === 413 ? 'PAYLOAD_TOO_LARGE' : 'UNSUPPORTED_MEDIA_TYPE', message: 'rejected' }, status)
      }
      // best-effort filename from the multipart payload; falls back to a default
      const original = /filename="([^"]+)"/.exec(req.postData() ?? '')?.[1] ?? 'upload.png'
      const id = `noteimg-${this.nextNoteImageId++}`
      const img: NoteImage = {
        id,
        noteId,
        originalName: original,
        contentType: 'image/png',
        sizeBytes: TINY_PNG.length,
        sortOrder: this.notes[idx].images.length,
        createdBy: 'alice',
        createdAt: new Date().toISOString(),
      }
      this.notes[idx] = { ...this.notes[idx], images: [...this.notes[idx].images, img], updatedAt: new Date().toISOString() }
      return this.json(route, this.notes[idx], 201)
    }

    // Serve a seeded note's image as a real image blob (mirrors GET
    // /notes/{id}/images/{imageId}). The JWT rides in the Authorization header,
    // never the URL — this exercises <AuthedImage>'s authFetch → blob path.
    const noteImageMatch = path.match(/\/notes\/([^/]+)\/images\/([^/]+)$/)
    if (noteImageMatch && method === 'GET') {
      const [, noteId, imageId] = noteImageMatch
      const img = this.notes.find((n) => n.id === noteId)?.images.find((i) => i.id === imageId)
      if (!img) return this.json(route, { message: 'not found' }, 404)
      return route.fulfill({ status: 200, contentType: img.contentType || 'image/png', body: TINY_PNG })
    }
    // Delete a single attached image (mirrors DELETE /notes/{id}/images/{imageId});
    // like the real backend it removes the image and returns the updated note.
    if (noteImageMatch && method === 'DELETE') {
      const [, noteId, imageId] = noteImageMatch
      const idx = this.notes.findIndex((n) => n.id === noteId)
      if (idx === -1) return this.json(route, { message: 'not found' }, 404)
      this.notes[idx] = {
        ...this.notes[idx],
        images: this.notes[idx].images.filter((i) => i.id !== imageId),
        updatedAt: new Date().toISOString(),
      }
      return this.json(route, this.notes[idx])
    }

    // Upload a file attachment (mirrors POST /notes/{id}/attachments, multipart, #431). Appends a
    // NoteAttachment and returns the updated note. failNextAttachmentUpload() forces 413/415.
    const noteAttachmentsPost = path.match(/\/notes\/([^/]+)\/attachments$/)
    if (noteAttachmentsPost && method === 'POST') {
      const noteId = noteAttachmentsPost[1]
      const idx = this.notes.findIndex((n) => n.id === noteId)
      if (idx === -1) return this.json(route, { message: 'not found' }, 404)
      if (this.nextAttachmentUploadStatus !== null) {
        const status = this.nextAttachmentUploadStatus
        this.nextAttachmentUploadStatus = null
        return this.json(route, { code: status === 413 ? 'ATTACHMENT_TOO_LARGE' : 'UNSUPPORTED_TYPE', message: 'rejected' }, status)
      }
      const original = /filename="([^"]+)"/.exec(req.postData() ?? '')?.[1] ?? 'datei.pdf'
      const att: NoteAttachment = {
        id: `noteatt-${this.nextNoteAttachmentId++}`,
        noteId,
        originalName: original,
        contentType: 'application/pdf',
        sizeBytes: 1234,
        sortOrder: (this.notes[idx].attachments ?? []).length,
        createdBy: 'alice',
        createdAt: new Date().toISOString(),
      }
      this.notes[idx] = {
        ...this.notes[idx],
        attachments: [...(this.notes[idx].attachments ?? []), att],
        updatedAt: new Date().toISOString(),
      }
      return this.json(route, this.notes[idx], 201)
    }

    // Serve an attachment's bytes (mirrors GET /notes/{id}/attachments/{attId}). The real backend
    // forces Content-Disposition: attachment; the in-app download flow re-fetches with auth, so we
    // return a tiny body + the disposition header here so downloadFile's filename path is exercised.
    const noteAttachmentMatch = path.match(/\/notes\/([^/]+)\/attachments\/([^/]+)$/)
    if (noteAttachmentMatch && method === 'GET') {
      const [, noteId, attId] = noteAttachmentMatch
      const att = this.notes.find((n) => n.id === noteId)?.attachments?.find((a) => a.id === attId)
      if (!att) return this.json(route, { message: 'not found' }, 404)
      return route.fulfill({
        status: 200,
        contentType: att.contentType || 'application/pdf',
        headers: { 'content-disposition': `attachment; filename="${att.originalName}"` },
        body: '%PDF-1.4',
      })
    }
    if (noteAttachmentMatch && method === 'DELETE') {
      const [, noteId, attId] = noteAttachmentMatch
      const idx = this.notes.findIndex((n) => n.id === noteId)
      if (idx === -1) return this.json(route, { message: 'not found' }, 404)
      this.notes[idx] = {
        ...this.notes[idx],
        attachments: (this.notes[idx].attachments ?? []).filter((a) => a.id !== attId),
        updatedAt: new Date().toISOString(),
      }
      return this.json(route, this.notes[idx])
    }

    const noteIdMatch = path.match(/\/notes\/([^/]+)$/)
    if (noteIdMatch) {
      const id = noteIdMatch[1]
      const idx = this.notes.findIndex((n) => n.id === id)
      if (method === 'PUT') {
        if (idx === -1) return this.json(route, { message: 'not found' }, 404)
        this.notes[idx] = { ...this.notes[idx], ...JSON.parse(req.postData() ?? '{}'), updatedAt: new Date().toISOString() }
        return this.json(route, this.notes[idx])
      }
      if (method === 'DELETE') {
        if (idx !== -1) this.notes.splice(idx, 1)
        return route.fulfill({ status: 204, body: '' })
      }
    }

    // ---- Time: CSV export (stub mirroring GET /time/export.csv) ----
    // Returns text/csv + a Content-Disposition filename so the in-app blob
    // download flow can be exercised end-to-end. Only completed entries are
    // exported; honours the project_id/from/to filters like the backend.
    if (path.endsWith('/time/export.csv') && method === 'GET') {
      const projectId = url.searchParams.get('project_id')
      const from = url.searchParams.get('from')
      const to = url.searchParams.get('to')
      const names = Object.fromEntries(this.projects.map((p) => [p.id, p.name]))
      let rows = this.entries.filter((e) => e.stoppedAt)
      if (projectId) rows = rows.filter((e) => e.projectId === projectId)
      if (from) rows = rows.filter((e) => e.startedAt >= from)
      if (to) rows = rows.filter((e) => e.startedAt <= to)
      const esc = (v: string) => (/[;"\n\r]/.test(v) ? `"${v.replace(/"/g, '""')}"` : v)
      const fmtH = (s: number) => (s / 3600).toFixed(2).replace('.', ',')
      const fmtHm = (s: number) =>
        `${String(Math.floor(s / 3600)).padStart(2, '0')}:${String(Math.floor((s % 3600) / 60)).padStart(2, '0')}`
      const lines = rows.map((e) =>
        [
          names[e.projectId] ?? '—',
          e.userId,
          e.startedAt,
          e.stoppedAt ?? '',
          fmtH(e.durationSeconds ?? 0),
          fmtHm(e.durationSeconds ?? 0),
          e.description ?? '',
        ]
          .map(esc)
          .join(';'),
      )
      const csv = '\uFEFFsep=;\r\n' + ['Projekt;Nutzer;Start;Ende;Dauer (h);Dauer (hh:mm);Beschreibung', ...lines].join('\r\n') + '\r\n'
      const filename =
        from && to ? `zeiterfassung_${from.slice(0, 10)}_${to.slice(0, 10)}.csv` : 'zeiterfassung_export.csv'
      return route.fulfill({
        status: 200,
        contentType: 'text/csv; charset=UTF-8',
        headers: { 'content-disposition': `attachment; filename="${filename}"` },
        body: csv,
      })
    }

    // ---- Time: Wochensoll targets + forecast (#31) ----
    if (path.endsWith('/time/targets') && method === 'GET') {
      return this.json(route, this.targets)
    }
    // Create a Wochensoll period (#31 follow-up), seeded from the effective one ≤ validFrom.
    const periodMatch = path.match(/\/time\/targets\/([^/]+)\/periods$/)
    if (periodMatch && method === 'POST') {
      const userId = decodeURIComponent(periodMatch[1])
      const vf = JSON.parse(req.postData() ?? '{}').validFrom as string
      if (this.targets.some((x) => x.userId === userId && periodOf(x) === vf)) {
        return this.json(route, { code: 'PERIOD_EXISTS' }, 409)
      }
      const mine = this.targets.filter((x) => x.userId === userId)
      const src = [...new Set(mine.map(periodOf))].sort().filter((p) => p <= vf).pop()
      const created = (src ? mine.filter((x) => periodOf(x) === src) : []).map((s) => ({
        userId, projectId: s.projectId, weeklyHours: s.weeklyHours, isDefault: s.isDefault, validFrom: vf,
      }))
      this.targets.push(...created)
      return this.jsonWithFrames(route, created, 201, 'time', [{ type: 'TARGET_UPDATED' }])
    }
    // Delete a whole period.
    const periodDelMatch = path.match(/\/time\/targets\/([^/]+)\/periods\/([^/]+)$/)
    if (periodDelMatch && method === 'DELETE') {
      const userId = decodeURIComponent(periodDelMatch[1])
      const vf = periodDelMatch[2]
      const before = this.targets.length
      this.targets = this.targets.filter((x) => !(x.userId === userId && periodOf(x) === vf))
      if (this.targets.length === before) return this.json(route, { code: 'PERIOD_NOT_FOUND' }, 404)
      return this.jsonWithFrames(route, '', 204, 'time', [{ type: 'TARGET_UPDATED' }])
    }
    const targetMatch = path.match(/\/time\/targets\/([^/]+)\/([^/]+)$/)
    if (targetMatch && method === 'PUT') {
      const userId = decodeURIComponent(targetMatch[1])
      const projectId = targetMatch[2]
      const b = JSON.parse(req.postData() ?? '{}')
      const vf = typeof b.validFrom === 'string' ? b.validFrom : BASE_TARGET_PERIOD
      let tgt = this.targets.find((x) => x.userId === userId && x.projectId === projectId && periodOf(x) === vf)
      if (!tgt) {
        tgt = { userId, projectId, weeklyHours: 0, isDefault: false, ...(vf !== BASE_TARGET_PERIOD ? { validFrom: vf } : {}) }
        this.targets.push(tgt)
      }
      if (typeof b.weeklyHours === 'number') tgt.weeklyHours = b.weeklyHours
      if (typeof b.isDefault === 'boolean') {
        // one default per person *and period* — mirrors the backend's clear-then-set
        if (b.isDefault) for (const o of this.targets) if (o.userId === userId && periodOf(o) === vf) o.isDefault = false
        tgt.isDefault = b.isDefault
      }
      // hours > 0 ⇒ a default must exist in this period — mirrors the backend's auto-assign (#59)
      if (tgt.weeklyHours > 0 && !this.targets.some((x) => x.userId === userId && periodOf(x) === vf && x.isDefault)) {
        tgt.isDefault = true
      }
      return this.jsonWithFrames(route, tgt, 200, 'time', [{ type: 'TARGET_UPDATED', target: tgt }])
    }
    if (path.endsWith('/time/forecast') && method === 'GET') {
      return this.json(route, this.buildForecast())
    }
    if (path.endsWith('/time/credits') && method === 'GET') {
      const from = url.searchParams.get('from')
      const to = url.searchParams.get('to')
      const inRange = this.credits.filter((c) => (!from || c.date >= from) && (!to || c.date <= to))
      return this.json(route, inRange)
    }

    // ---- Time: projects (checked before /time/entries matchers) ----
    if (path.endsWith('/time/projects') && method === 'GET') {
      return this.json(route, this.projects)
    }
    if (path.endsWith('/time/projects') && method === 'POST') {
      const b = JSON.parse(req.postData() ?? '{}')
      const project: Project = {
        id: `proj-${this.nextProjectId++}`,
        name: b.name,
        color: b.color ?? '#64748B',
        archived: false,
        createdBy: 'alice',
        createdAt: new Date().toISOString(),
      }
      this.projects.push(project)
      return this.jsonWithFrames(route, project, 201, 'time', [{ type: 'PROJECT_CREATED', project }])
    }

    const archiveMatch = path.match(/\/time\/projects\/([^/]+)\/archive$/)
    if (archiveMatch && method === 'PATCH') {
      const idx = this.projects.findIndex((p) => p.id === archiveMatch[1])
      if (idx === -1) return this.json(route, { message: 'not found' }, 404)
      const b = JSON.parse(req.postData() ?? '{}')
      this.projects[idx] = { ...this.projects[idx], archived: b.archived ?? !this.projects[idx].archived }
      return this.jsonWithFrames(route, this.projects[idx], 200, 'time', [{ type: 'PROJECT_UPDATED', project: this.projects[idx] }])
    }

    const projIdMatch = path.match(/\/time\/projects\/([^/]+)$/)
    if (projIdMatch && method === 'PUT') {
      const idx = this.projects.findIndex((p) => p.id === projIdMatch[1])
      if (idx === -1) return this.json(route, { message: 'not found' }, 404)
      this.projects[idx] = { ...this.projects[idx], ...JSON.parse(req.postData() ?? '{}') }
      return this.jsonWithFrames(route, this.projects[idx], 200, 'time', [{ type: 'PROJECT_UPDATED', project: this.projects[idx] }])
    }

    // ---- Time: running / start / stop (checked before /time/entries/{id}) ----
    // All running timers across the household (mirrors GET /time/running/all)
    // — the dashboard's timer peek reads this; backend orders by userId.
    if (path.endsWith('/time/running/all') && method === 'GET') {
      const running = this.entries.filter((e) => !e.stoppedAt).sort((a, b) => a.userId.localeCompare(b.userId))
      return this.json(route, running)
    }
    if (path.endsWith('/time/running') && method === 'GET') {
      const running = this.entries.find((e) => !e.stoppedAt)
      if (!running) return this.json(route, { message: 'no running timer' }, 404)
      return this.json(route, running)
    }
    if (path.endsWith('/time/entries/start') && method === 'POST') {
      const b = JSON.parse(req.postData() ?? '{}')
      if (!this.projects.some((p) => p.id === b.projectId)) return this.json(route, { message: 'not found' }, 404)
      const frames: unknown[] = []
      const ts = new Date().toISOString()
      // a new start stops the currently running timer
      const prev = this.entries.find((e) => !e.stoppedAt)
      if (prev) {
        prev.stoppedAt = ts
        prev.durationSeconds = Math.max(0, Math.floor((Date.parse(ts) - Date.parse(prev.startedAt)) / 1000))
        frames.push({ type: 'ENTRY_UPDATED', entry: prev })
      }
      const entry: TimeEntry = {
        id: `entry-${this.nextEntryId++}`,
        projectId: b.projectId,
        userId: b.userId ?? 'alice', // a partner start carries the target user (mirrors the backend)
        startedAt: ts,
        description: b.description,
        createdAt: ts,
        updatedAt: ts,
      }
      this.entries.unshift(entry)
      frames.push({ type: 'ENTRY_CREATED', entry })
      return this.jsonWithFrames(route, entry, 201, 'time', frames)
    }
    if (path.endsWith('/time/entries/stop') && method === 'POST') {
      const running = this.entries.find((e) => !e.stoppedAt)
      if (!running) return this.json(route, { message: 'no running timer' }, 404)
      const ts = new Date().toISOString()
      running.stoppedAt = ts
      running.durationSeconds = Math.max(0, Math.floor((Date.parse(ts) - Date.parse(running.startedAt)) / 1000))
      return this.jsonWithFrames(route, running, 200, 'time', [{ type: 'ENTRY_UPDATED', entry: running }])
    }

    // ---- Time: entries ----
    if (path.endsWith('/time/entries') && method === 'GET') {
      const projectId = url.searchParams.get('project_id')
      return this.json(route, projectId ? this.entries.filter((e) => e.projectId === projectId) : this.entries)
    }
    if (path.endsWith('/time/entries') && method === 'POST') {
      const b = JSON.parse(req.postData() ?? '{}')
      const started = Date.parse(b.startedAt)
      const stopped = Date.parse(b.stoppedAt)
      if (!(stopped > started)) return this.json(route, { code: 'BAD_REQUEST', message: 'end before start' }, 400)
      const ts = new Date().toISOString()
      const entry: TimeEntry = {
        id: `entry-${this.nextEntryId++}`,
        projectId: b.projectId,
        userId: b.userId ?? 'alice', // manual entries can target the partner (mirrors the backend)
        startedAt: b.startedAt,
        stoppedAt: b.stoppedAt,
        description: b.description,
        durationSeconds: Math.floor((stopped - started) / 1000),
        createdAt: ts,
        updatedAt: ts,
      }
      this.entries.unshift(entry)
      return this.jsonWithFrames(route, entry, 201, 'time', [{ type: 'ENTRY_CREATED', entry }])
    }

    // Split a completed entry at a cut time with an optional untracked break,
    // mirroring POST /time/entries/{id}/split (#62): part one keeps the id and
    // ends at the cut, part two starts after the break and inherits the rest.
    const splitMatch = path.match(/\/time\/entries\/([^/]+)\/split$/)
    if (splitMatch && method === 'POST') {
      const idx = this.entries.findIndex((e) => e.id === splitMatch[1])
      if (idx === -1) return this.json(route, { code: 'NOT_FOUND', message: 'not found' }, 404)
      const e = this.entries[idx]
      if (!e.stoppedAt) return this.json(route, { code: 'ENTRY_RUNNING', message: 'running' }, 409)
      const b = JSON.parse(req.postData() ?? '{}')
      const cut = Date.parse(b.splitAt)
      const started = Date.parse(e.startedAt)
      const stopped = Date.parse(e.stoppedAt)
      const secondStart = cut + (b.breakMinutes ?? 0) * 60000
      if (!(cut > started && cut < stopped) || !(secondStart < stopped) || (b.breakMinutes ?? 0) < 0) {
        return this.json(route, { code: 'INVALID_RANGE', message: 'bad cut' }, 400)
      }
      const ts = new Date().toISOString()
      const first: TimeEntry = { ...e, stoppedAt: new Date(cut).toISOString(), durationSeconds: Math.floor((cut - started) / 1000), updatedAt: ts }
      const second: TimeEntry = {
        ...e,
        id: `entry-${this.nextEntryId++}`,
        startedAt: new Date(secondStart).toISOString(),
        durationSeconds: Math.floor((stopped - secondStart) / 1000),
        createdAt: ts,
        updatedAt: ts,
      }
      this.entries[idx] = first
      this.entries.splice(idx + 1, 0, second)
      return this.jsonWithFrames(route, { first, second }, 200, 'time', [
        { type: 'ENTRY_UPDATED', entry: first },
        { type: 'ENTRY_CREATED', entry: second },
      ])
    }

    const entryIdMatch = path.match(/\/time\/entries\/([^/]+)$/)
    if (entryIdMatch) {
      const idx = this.entries.findIndex((e) => e.id === entryIdMatch[1])
      if (method === 'PUT') {
        if (idx === -1) return this.json(route, { message: 'not found' }, 404)
        this.entries[idx] = { ...this.entries[idx], ...JSON.parse(req.postData() ?? '{}'), updatedAt: new Date().toISOString() }
        return this.jsonWithFrames(route, this.entries[idx], 200, 'time', [{ type: 'ENTRY_UPDATED', entry: this.entries[idx] }])
      }
      if (method === 'DELETE') {
        // TimeView removes the row optimistically; no frame needed.
        if (idx !== -1) this.entries.splice(idx, 1)
        return route.fulfill({ status: 204, body: '' })
      }
    }

    // ---- Abwesenheit / Familienkalender ----
    if (path.endsWith('/absence') && method === 'GET') {
      return this.json(route, {
        users: this.absUsers,
        absences: this.absences,
        partTime: this.partTime,
        kitaClosures: this.kitaClosures,
        customHolidays: this.customHolidays,
        settings: this.absSettings,
      })
    }
    if (path.endsWith('/absence/entries/batch') && method === 'POST') {
      const b = JSON.parse(req.postData() ?? '{}')
      for (const d of (b.dates ?? []) as string[]) {
        this.absences = this.absences.filter((a) => !(a.userId === b.userId && a.date === d))
        if (b.type) this.absences.push({ id: `abs-${this.nextAbsId++}`, userId: b.userId, date: d, type: b.type, half: b.half ?? null })
      }
      return route.fulfill({ status: 204, body: '' })
    }
    if (path.endsWith('/absence/entries') && method === 'POST') {
      const b = JSON.parse(req.postData() ?? '{}')
      this.absences = this.absences.filter((a) => !(a.userId === b.userId && a.date === b.date))
      const abs: Absence = { id: `abs-${this.nextAbsId++}`, userId: b.userId, date: b.date, type: b.type, half: b.half ?? null }
      this.absences.push(abs)
      return this.json(route, abs, 201)
    }
    if (path.endsWith('/absence/entries') && method === 'DELETE') {
      const userId = url.searchParams.get('userId')
      const date = url.searchParams.get('date')
      this.absences = this.absences.filter((a) => !(a.userId === userId && a.date === date))
      return route.fulfill({ status: 204, body: '' })
    }
    if (path.endsWith('/absence/parttime') && method === 'POST') {
      const b = JSON.parse(req.postData() ?? '{}')
      const rule: PartTimeRule = { id: `pt-${this.nextAbsId++}`, userId: b.userId, weekday: b.weekday, start: b.start, end: b.end ?? null }
      this.partTime.push(rule)
      return this.json(route, rule, 201)
    }
    const ptMatch = path.match(/\/absence\/parttime\/([^/]+)$/)
    if (ptMatch) {
      const idx = this.partTime.findIndex((r) => r.id === ptMatch[1])
      if (method === 'PUT') {
        if (idx === -1) return this.json(route, { message: 'not found' }, 404)
        const b = JSON.parse(req.postData() ?? '{}')
        this.partTime[idx] = { ...this.partTime[idx], weekday: b.weekday, start: b.start, end: b.end ?? null }
        return this.json(route, this.partTime[idx])
      }
      if (method === 'DELETE') {
        if (idx !== -1) this.partTime.splice(idx, 1)
        return route.fulfill({ status: 204, body: '' })
      }
    }
    if (path.endsWith('/absence/kita/range') && method === 'POST') {
      const b = JSON.parse(req.postData() ?? '{}')
      let from = b.from as string
      let to = b.to as string
      if (from > to) { const tmp = from; from = to; to = tmp }
      for (let d = new Date(from + 'T12:00:00'); ymdLocal(d) <= to; d.setDate(d.getDate() + 1)) {
        const dow = d.getDay()
        if (dow !== 0 && dow !== 6) {
          this.kitaClosures.push({ id: `kita-${this.nextAbsId++}`, date: ymdLocal(d), label: (b.label && String(b.label).trim()) || 'Kita geschlossen' })
        }
      }
      return route.fulfill({ status: 204, body: '' })
    }
    if (path.endsWith('/absence/kita') && method === 'POST') {
      const b = JSON.parse(req.postData() ?? '{}')
      const k: KitaClosure = { id: `kita-${this.nextAbsId++}`, date: b.date, label: (b.label && String(b.label).trim()) || 'Kita geschlossen' }
      this.kitaClosures.push(k)
      return this.json(route, k, 201)
    }
    const kitaMatch = path.match(/\/absence\/kita\/([^/]+)$/)
    if (kitaMatch) {
      const idx = this.kitaClosures.findIndex((k) => k.id === kitaMatch[1])
      if (method === 'PUT') {
        if (idx === -1) return this.json(route, { message: 'not found' }, 404)
        this.kitaClosures[idx] = { ...this.kitaClosures[idx], ...JSON.parse(req.postData() ?? '{}') }
        return this.json(route, this.kitaClosures[idx])
      }
      if (method === 'DELETE') {
        if (idx !== -1) this.kitaClosures.splice(idx, 1)
        return route.fulfill({ status: 204, body: '' })
      }
    }
    // Custom holidays (#51) — mirrors the backend /absence/holidays routes.
    if (path.endsWith('/absence/holidays') && method === 'POST') {
      const b = JSON.parse(req.postData() ?? '{}')
      const existing = this.customHolidays.find((h) => h.month === b.month && h.day === b.day)
      if (existing) return this.json(route, existing) // idempotent, like the backend
      const h: CustomHoliday = {
        id: `hol-${this.nextAbsId++}`,
        month: b.month,
        day: b.day,
        half: b.half ?? false,
        label: (b.label && String(b.label).trim()) || 'Feiertag',
      }
      this.customHolidays.push(h)
      return this.json(route, h, 201)
    }
    const holMatch = path.match(/\/absence\/holidays\/([^/]+)$/)
    if (holMatch) {
      const idx = this.customHolidays.findIndex((h) => h.id === holMatch[1])
      if (method === 'PUT') {
        if (idx === -1) return this.json(route, { message: 'not found' }, 404)
        this.customHolidays[idx] = { ...this.customHolidays[idx], ...JSON.parse(req.postData() ?? '{}') }
        return this.json(route, this.customHolidays[idx])
      }
      if (method === 'DELETE') {
        if (idx !== -1) this.customHolidays.splice(idx, 1)
        return route.fulfill({ status: 204, body: '' })
      }
    }
    const setMatch = path.match(/\/absence\/settings\/([^/]+)\/(\d+)$/)
    if (setMatch && method === 'PUT') {
      const userId = decodeURIComponent(setMatch[1])
      const year = Number(setMatch[2])
      const b = JSON.parse(req.postData() ?? '{}')
      let s = this.absSettings.find((x) => x.userId === userId && x.year === year)
      if (!s) {
        s = {
          userId,
          year,
          state: b.state ?? 'BE',
          allowance: b.allowance ?? 30,
          carryover: b.carryover ?? 0,
          carryoverExpires: b.carryoverExpires ?? null,
          kindKrankCap: b.kindKrankCap ?? 15,
        }
        this.absSettings.push(s)
      } else {
        for (const k of Object.keys(b)) if (b[k] !== undefined) (s as unknown as Record<string, unknown>)[k] = b[k]
      }
      return this.json(route, s)
    }

    // Anything else the views fetch → empty list.
    if (method === 'GET') {
      return this.json(route, [])
    }
    return this.json(route, {})
  }
}

export function todo(partial: Partial<Todo> & { id: string; title: string }): Todo {
  return {
    status: 'INBOX',
    createdBy: 'alice',
    createdAt: '2026-06-01T08:00:00Z',
    updatedAt: '2026-06-01T08:00:00Z',
    ...partial,
  }
}

export function list(partial: Partial<TodoList> & { id: string; name: string }): TodoList {
  return {
    visibility: 'SHARED',
    createdBy: 'alice',
    createdAt: '2026-06-01T08:00:00Z',
    ...partial,
  }
}

export function shoppingList(partial: Partial<ShoppingList> & { id: string; name: string }): ShoppingList {
  return {
    createdBy: 'alice',
    createdAt: '2026-06-01T08:00:00Z',
    ...partial,
  }
}

export function shoppingItem(partial: Partial<ShoppingItem> & { id: string; name: string; listId: string }): ShoppingItem {
  return {
    checked: false,
    createdBy: 'alice',
    createdAt: '2026-06-01T08:00:00Z',
    ...partial,
  }
}

// Seed builder for a named shopping template (#215). Pass item names as strings;
// they get ids + sequential sortOrder so a seeded template matches the API shape.
export function shoppingTemplate(
  partial: Partial<Omit<ShoppingTemplate, 'items'>> & { id: string; name: string; items?: Array<string | ShoppingTemplateItem> },
): ShoppingTemplate {
  const items = (partial.items ?? []).map((it, n): ShoppingTemplateItem =>
    typeof it === 'string' ? { id: `${partial.id}-i${n}`, name: it, sortOrder: n } : it,
  )
  return {
    createdBy: 'alice',
    createdAt: '2026-06-01T08:00:00Z',
    ...partial,
    items,
  }
}

export function subtask(partial: Partial<Subtask> & { id: string; title: string }): Subtask {
  return {
    done: false,
    sortOrder: 0,
    ...partial,
  }
}

export function recipe(partial: Partial<Recipe> & { id: string; title: string }): Recipe {
  return {
    servings: 2,
    category: 'DINNER',
    ingredients: [],
    steps: [],
    createdBy: 'alice',
    createdAt: '2026-06-01T08:00:00Z',
    updatedAt: '2026-06-01T08:00:00Z',
    ...partial,
  }
}

export function ingredient(partial: Partial<Ingredient> & { id: string; name: string }): Ingredient {
  return {
    sortOrder: 0,
    ...partial,
  }
}

export function recipeStep(partial: Partial<RecipeStep> & { id: string; description: string }): RecipeStep {
  return {
    stepNumber: 1,
    ...partial,
  }
}

export function recipeImage(partial: Partial<RecipeImage> & { id: string; recipeId: string }): RecipeImage {
  return {
    originalName: 'foto.png',
    contentType: 'image/png',
    sizeBytes: 95,
    createdBy: 'alice',
    createdAt: '2026-06-01T08:00:00Z',
    ...partial,
  }
}

export function mealPlanEntry(
  partial: Partial<MealPlanEntry> & { id: string; date: string; slot: MealSlot; recipeId: string; recipeTitle: string },
): MealPlanEntry {
  return {
    recipeCategory: 'DINNER',
    createdBy: 'alice',
    createdAt: '2026-06-01T08:00:00Z',
    ...partial,
  }
}

export function calendarEvent(
  partial: Partial<CalendarEvent> & { id: string; title: string; date: string },
): CalendarEvent {
  return {
    type: 'OTHER' as CalendarEventType,
    allDay: true,
    createdBy: 'alice',
    createdAt: '2026-06-01T08:00:00Z',
    ...partial,
  }
}

export function note(partial: Partial<Note> & { id: string; title: string }): Note {
  return {
    content: '',
    tags: [],
    visibility: 'SHARED',
    images: [],
    createdBy: 'alice',
    createdAt: '2026-06-01T08:00:00Z',
    updatedAt: '2026-06-01T08:00:00Z',
    ...partial,
  }
}

export function noteImage(partial: Partial<NoteImage> & { id: string; noteId: string }): NoteImage {
  return {
    originalName: 'foto.png',
    contentType: 'image/png',
    sizeBytes: 95,
    sortOrder: 0,
    createdBy: 'alice',
    createdAt: '2026-06-01T08:00:00Z',
    ...partial,
  }
}

export function noteAttachment(partial: Partial<NoteAttachment> & { id: string; noteId: string }): NoteAttachment {
  return {
    originalName: 'vertrag.pdf',
    contentType: 'application/pdf',
    sizeBytes: 2048,
    sortOrder: 0,
    createdBy: 'alice',
    createdAt: '2026-06-01T08:00:00Z',
    ...partial,
  }
}

export function project(partial: Partial<Project> & { id: string; name: string }): Project {
  return {
    color: '#4F7A52',
    archived: false,
    createdBy: 'alice',
    createdAt: '2026-06-01T08:00:00Z',
    ...partial,
  }
}

export function workTarget(partial: Partial<WorkTarget> & { userId: string; projectId: string }): WorkTarget {
  return { weeklyHours: 0, isDefault: false, ...partial }
}

export function timeEntry(partial: Partial<TimeEntry> & { id: string; projectId: string }): TimeEntry {
  return {
    userId: 'alice',
    startedAt: '2026-06-03T08:00:00Z',
    stoppedAt: '2026-06-03T09:00:00Z',
    durationSeconds: 3600,
    createdAt: '2026-06-03T08:00:00Z',
    updatedAt: '2026-06-03T09:00:00Z',
    ...partial,
  }
}

/** Local YYYY-MM-DD (matches the app's date keying). */
function ymdLocal(d: Date): string {
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`
}

// Wochensoll period helpers (#31 follow-up): the API omits validFrom for the base period.
const BASE_TARGET_PERIOD = '1970-01-01'
const periodOf = (t: WorkTarget): string => t.validFrom ?? BASE_TARGET_PERIOD

export function absence(partial: Partial<Absence> & { id: string; userId: string; date: string }): Absence {
  return { type: 'URLAUB', half: null, ...partial }
}

export function partTimeRule(partial: Partial<PartTimeRule> & { id: string; userId: string; weekday: number; start: string }): PartTimeRule {
  return { end: null, ...partial }
}

export function kitaClosure(partial: Partial<KitaClosure> & { id: string; date: string }): KitaClosure {
  return { label: 'Kita geschlossen', ...partial }
}

export function customHoliday(partial: Partial<CustomHoliday> & { id: string; month: number; day: number }): CustomHoliday {
  return { half: false, label: 'Feiertag', ...partial }
}

// year defaults to the fixture year (2026), not the real clock — callers that care
// about the year should pass it explicitly so the seed never drifts with the calendar
// (the e2e clock is pinned via page.clock; see abwesenheit.spec.ts, issue #19).
export function absSettings(partial: Partial<AbsSettings> & { userId: string }): AbsSettings {
  return { year: 2026, state: 'BE', allowance: 30, carryover: 0, carryoverExpires: null, kindKrankCap: 15, ...partial }
}
