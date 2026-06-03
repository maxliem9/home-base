import type { Page, Route } from '@playwright/test'

export interface Todo {
  id: string
  title: string
  description?: string
  status: 'INBOX' | 'PLANNED' | 'DONE'
  assignee?: string
  dueDate?: string
  priority?: 'LOW' | 'MEDIUM' | 'HIGH'
  createdBy: string
  createdAt: string
  doneAt?: string
}

export const TOKEN = 'test-jwt-token'

/**
 * In-memory backend stub for the HomeBase API. Intercepts every /api/v1/**
 * request so the app can run end-to-end without a real server, and stubs the
 * WebSocket so the realtime hook never opens a live connection.
 */
export class MockApi {
  private todos: Todo[]
  private nextId = 100

  constructor(initialTodos: Todo[] = []) {
    this.todos = initialTodos.map((t) => ({ ...t }))
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

    // Todos collection
    if (path.endsWith('/todos') && method === 'GET') {
      return this.json(route, this.todos)
    }
    if (path.endsWith('/todos') && method === 'POST') {
      const { title } = JSON.parse(req.postData() ?? '{}')
      const todo: Todo = {
        id: `todo-${this.nextId++}`,
        title,
        status: 'INBOX',
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
