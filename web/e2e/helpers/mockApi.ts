import type { Page, Route } from '@playwright/test'

// e2e fixtures use the app's real domain types (src/types.ts) so that any drift —
// e.g. a newly-required field like Note.images — is caught by
// `npm run typecheck:e2e` instead of failing at runtime. Re-exported so the spec
// files can keep importing these names from this helper.
import type {
  Subtask, TodoList, Todo, ShoppingList, ShoppingItem,
  RecipeCategory, Ingredient, RecipeStep, Recipe,
  NoteVisibility, NoteImage, Note,
  Project, TimeEntry, WorkTarget, TimeForecast, UserForecast,
  Absence, PartTimeRule, KitaClosure, CustomHoliday, AbsSettings,
} from '../../src/types'

export type {
  Subtask, TodoList, Todo, ShoppingList, ShoppingItem,
  RecipeCategory, Ingredient, RecipeStep, Recipe,
  NoteVisibility, NoteImage, Note,
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

// `Buffer` is a Node global present in the Playwright runtime; the e2e tsconfig
// has no @types/node, so declare just the one call we use here.
declare const Buffer: { from(input: string, encoding: 'base64'): Uint8Array }

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
  private digestTime = '20:00'
  private telegramEnabled = false
  private todos: Todo[]
  private lists: TodoList[]
  private shoppingLists: ShoppingList[]
  private shoppingItems: ShoppingItem[]
  private recipes: Recipe[] = []
  private notes: Note[] = []
  private projects: Project[] = []
  private entries: TimeEntry[] = []
  private targets: WorkTarget[] = []
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
  private nextRecipeId = 100
  private nextNoteId = 100
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

  seedNotes(notes: Note[]): this {
    this.notes = notes.map((n) => ({ ...n }))
    return this
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
      createdBy: prev?.createdBy ?? 'alice',
      createdAt: prev?.createdAt ?? ts,
      updatedAt: ts,
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

    const users: UserForecast[] = [...new Set(this.targets.map((t) => t.userId))].map((userId) => {
      const own = this.targets.filter((t) => t.userId === userId)
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
    if (path.endsWith('/users') && method === 'GET') {
      return this.json(route, [{ username: 'max' }, { username: 'lea' }])
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

    // Telegram digest time (#100). Mirrors /config/digest: GET returns {time, enabled},
    // PUT validates HH:mm (with INVALID_TIME), normalizes to HH:mm, stores.
    if (path.endsWith('/config/digest') && method === 'GET') {
      return this.json(route, { time: this.digestTime, enabled: this.telegramEnabled })
    }
    if (path.endsWith('/config/digest') && method === 'PUT') {
      const raw = (JSON.parse(req.postData() ?? '{}').time ?? '').trim()
      // Match the backend's LocalTime.parse: zero-padded HH:mm (seconds optional, dropped).
      const m = /^(\d{2}):(\d{2})(:\d{2})?$/.exec(raw)
      if (!m || Number(m[1]) > 23 || Number(m[2]) > 59) return this.json(route, { code: 'INVALID_TIME', message: 'bad' }, 400)
      this.digestTime = `${m[1]}:${m[2]}`
      return this.json(route, { time: this.digestTime, enabled: this.telegramEnabled })
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
      const { title, listId } = JSON.parse(req.postData() ?? '{}')
      const todo: Todo = {
        id: `todo-${this.nextId++}`,
        title,
        status: 'INBOX',
        listId: listId || undefined,
        subtasks: [],
        createdBy: 'alice',
        createdAt: new Date().toISOString(),
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
        if (body.listId === '') updated.listId = undefined
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
      const { name } = JSON.parse(req.postData() ?? '{}')
      const list: ShoppingList = {
        id: `shoplist-${this.nextShopListId++}`,
        name,
        createdBy: 'alice',
        createdAt: new Date().toISOString(),
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
      const { name, listId } = JSON.parse(req.postData() ?? '{}')
      const item: ShoppingItem = {
        id: `shop-${this.nextShopId++}`,
        name,
        listId: listId || undefined,
        checked: false,
        createdBy: 'alice',
        createdAt: new Date().toISOString(),
      }
      this.shoppingItems.unshift(item)
      return this.json(route, item, 201)
    }

    // Shopping: batch add recipe ingredients (mirrors POST /shopping/batch).
    // Formats each line as a "200 g Mehl" label and merges quantities into an
    // existing item with the same name + unit; matched before /shopping/{id}.
    if (path.endsWith('/shopping/batch') && method === 'POST') {
      const { listId, items = [] } = JSON.parse(req.postData() ?? '{}') as {
        listId?: string
        items?: Array<{ name: string; amount?: number; unit?: string }>
      }
      const UNITS = new Set(['g', 'kg', 'mg', 'ml', 'l', 'el', 'tl', 'stk', 'stück', 'prise', 'bund', 'dose', 'pkg', 'pck', 'tasse', 'cup', 'msp'])
      const fmtAmt = (v: number) => String(Math.round(v * 1000) / 1000)
      const fmt = (a: number | null | undefined, u: string | null | undefined, n: string) =>
        [a != null ? fmtAmt(a) : null, u && u.trim() ? u : null, n].filter(Boolean).join(' ').trim()
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
      const unitEq = (a: string | null, b: string | null | undefined) => (a ?? '').toLowerCase() === (b ?? '').toLowerCase()
      const inList = () => this.shoppingItems.filter((it) => (it.listId ?? undefined) === (listId || undefined))
      const created: ShoppingItem[] = []
      const updated: ShoppingItem[] = []
      let skipped = 0
      for (const line of items) {
        const name = (line.name ?? '').trim()
        if (!name) continue
        const unit = line.unit && line.unit.trim() ? line.unit.trim() : undefined
        const amount = line.amount
        const display = fmt(amount, unit, name)
        const target = amount != null
          ? inList().find((it) => {
              const p = parseQty(it.name)
              return p.amount != null && p.name.toLowerCase() === name.toLowerCase() && unitEq(p.unit, unit ?? null)
            })
          : undefined
        if (target) {
          const p = parseQty(target.name)
          target.name = fmt((p.amount ?? 0) + (amount ?? 0), p.unit ?? unit, p.name)
          updated.push(target)
          continue
        }
        if (inList().some((it) => it.name.toLowerCase() === display.toLowerCase())) {
          skipped++
          continue
        }
        const item: ShoppingItem = {
          id: `shop-${this.nextShopId++}`,
          name: display,
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

    const shopItemMatch = path.match(/\/shopping\/([^/]+)$/)
    if (shopItemMatch) {
      const id = shopItemMatch[1]
      const idx = this.shoppingItems.findIndex((i) => i.id === id)
      if (method === 'PUT') {
        const body = JSON.parse(req.postData() ?? '{}')
        if (idx === -1) return this.json(route, { message: 'not found' }, 404)
        const updated: ShoppingItem = { ...this.shoppingItems[idx], ...body }
        if (body.listId === '') updated.listId = undefined
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
        createdBy: 'alice',
        createdAt: ts,
        updatedAt: ts,
      }
      this.notes.unshift(note)
      return this.json(route, note, 201)
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
      const csv = '\uFEFF' + ['Projekt;Nutzer;Start;Ende;Dauer (h);Dauer (hh:mm);Beschreibung', ...lines].join('\r\n') + '\r\n'
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
    const targetMatch = path.match(/\/time\/targets\/([^/]+)\/([^/]+)$/)
    if (targetMatch && method === 'PUT') {
      const userId = decodeURIComponent(targetMatch[1])
      const projectId = targetMatch[2]
      const b = JSON.parse(req.postData() ?? '{}')
      let tgt = this.targets.find((x) => x.userId === userId && x.projectId === projectId)
      if (!tgt) {
        tgt = { userId, projectId, weeklyHours: 0, isDefault: false }
        this.targets.push(tgt)
      }
      if (typeof b.weeklyHours === 'number') tgt.weeklyHours = b.weeklyHours
      if (typeof b.isDefault === 'boolean') {
        // one default per person — mirrors the backend's clear-then-set
        if (b.isDefault) for (const o of this.targets) if (o.userId === userId) o.isDefault = false
        tgt.isDefault = b.isDefault
      }
      // hours > 0 ⇒ a default must exist — mirrors the backend's auto-assign (#59)
      if (tgt.weeklyHours > 0 && !this.targets.some((x) => x.userId === userId && x.isDefault)) {
        tgt.isDefault = true
      }
      return this.jsonWithFrames(route, tgt, 200, 'time', [{ type: 'TARGET_UPDATED', target: tgt }])
    }
    if (path.endsWith('/time/forecast') && method === 'GET') {
      return this.json(route, this.buildForecast())
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
        userId: 'alice',
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
        userId: 'alice',
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
