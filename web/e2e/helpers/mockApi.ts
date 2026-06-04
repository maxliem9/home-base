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
  color: string
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

export const TOKEN = 'test-jwt-token'

/**
 * In-memory backend stub for the HomeBase API. Intercepts every /api/v1/**
 * request so the app can run end-to-end without a real server, and stubs the
 * WebSocket so the realtime hook never opens a live connection.
 *
 * Mirrors the contract of backend/.../routes/TodoRoutes.kt: lists live under
 * /todos/lists, subtasks under /todos/{id}/subtasks, and every subtask mutation
 * responds with the freshly-built parent todo (incl. its subtasks array).
 */
export class MockApi {
  private todos: Todo[]
  private lists: TodoList[]
  private nextId = 100
  private nextListId = 100
  private nextSubId = 100

  constructor(initialTodos: Todo[] = [], initialLists: TodoList[] = []) {
    this.todos = initialTodos.map((t) => ({ ...t, subtasks: (t.subtasks ?? []).map((s) => ({ ...s })) }))
    this.lists = initialLists.map((l) => ({ ...l }))
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

    // ---- Lists (checked before the generic /todos/{id} matcher) ----
    if (path.endsWith('/todos/lists') && method === 'GET') {
      return this.json(route, this.lists)
    }
    if (path.endsWith('/todos/lists') && method === 'POST') {
      const { name, color } = JSON.parse(req.postData() ?? '{}')
      const list: TodoList = {
        id: `list-${this.nextListId++}`,
        name,
        color: color ?? '#6366f1',
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
        // Detach todos from the removed list — they survive, just lose the link.
        this.todos = this.todos.map((t) => (t.listId === id ? { ...t, listId: undefined } : t))
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
        // An empty listId clears the assignment (backend treats "" as null).
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

    // Anything else the views fetch (shopping, notes, time, recipes) → empty list.
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
    color: '#6366f1',
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
