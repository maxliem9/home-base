import type { Page, Route } from '@playwright/test'

export interface Subtask {
  id: string
  title: string
  done: boolean
  sortOrder: number
}

export interface TodoList {
  id: string
  name: string
  visibility: 'SHARED' | 'PRIVATE'
  createdBy: string
  createdAt: string
}

export interface Todo {
  id: string
  title: string
  description?: string
  status: 'INBOX' | 'PLANNED' | 'DONE'
  assignee?: string
  dueDate?: string
  priority?: 'LOW' | 'MEDIUM' | 'HIGH'
  listId?: string
  subtasks?: Subtask[]
  createdBy: string
  createdAt: string
  doneAt?: string
}

export interface ShoppingList {
  id: string
  name: string
  createdBy: string
  createdAt: string
}

export interface ShoppingItem {
  id: string
  name: string
  listId?: string
  checked: boolean
  createdBy: string
  createdAt: string
  checkedAt?: string
}

export const TOKEN = 'test-jwt-token'

/**
 * In-memory backend stub for the HomeBase API. Intercepts every /api/v1/**
 * request so the app can run end-to-end without a real server, and stubs the
 * WebSocket so the realtime hook never opens a live connection.
 *
 * Mirrors the route contracts in backend/.../routes/{Todo,Shopping}Routes.kt:
 * lists live under /{todos,shopping}/lists, subtasks under
 * /todos/{id}/subtasks, and every subtask mutation responds with the freshly
 * built parent todo (incl. its subtasks array).
 */
export class MockApi {
  private todos: Todo[]
  private lists: TodoList[]
  private shoppingLists: ShoppingList[]
  private shoppingItems: ShoppingItem[]
  private nextId = 100
  private nextListId = 100
  private nextSubId = 100
  private nextShopId = 100
  private nextShopListId = 100

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

  async install(page: Page) {
    // Prevent the realtime hook from opening a real socket.
    await page.addInitScript(() => {
      class FakeWebSocket {
        onopen: (() => void) | null = null
        onclose: (() => void) | null = null
        onmessage: (() => void) | null = null
        onerror: (() => void) | null = null
        readyState = 1
        constructor() {}
        send() {}
        close() {}
      }
      // @ts-expect-error override for tests
      window.WebSocket = FakeWebSocket
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
        this.lists[idx] = { ...this.lists[idx], ...JSON.parse(req.postData() ?? '{}') }
        return this.json(route, this.lists[idx])
      }
      if (method === 'DELETE') {
        if (idx === -1) return this.json(route, { message: 'not found' }, 404)
        this.lists.splice(idx, 1)
        // Backend cascades: todos in the removed list go away with it.
        this.todos = this.todos.filter((t) => t.listId !== id)
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
      return this.json(route, todo, 201)
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

    // Anything else the views fetch (notes, time, recipes) → empty list.
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
