import { useState, useEffect, useCallback } from 'react'
import { API_BASE, authFetch, withWsToken } from '../api'
import { Todo, TodoStatus, TodoPriority } from '../types'
import { useWebSocket } from '../hooks/useWebSocket'

const WS_URL = import.meta.env.VITE_WS_URL ?? `ws://${window.location.host}/api/v1/ws/todos`

const SEGMENTS: { id: TodoStatus; label: string }[] = [
  { id: 'INBOX', label: 'Inbox' },
  { id: 'PLANNED', label: 'Geplant' },
  { id: 'DONE', label: 'Erledigt' },
]

const priorityClasses = (p: TodoPriority): string =>
  p === 'HIGH'
    ? 'bg-red-100 text-red-700'
    : p === 'MEDIUM'
      ? 'bg-yellow-100 text-yellow-700'
      : 'bg-green-100 text-green-700'

interface PlanDraft {
  id: string
  assignee: string
  dueDate: string
  priority: '' | TodoPriority
}

interface TodosViewProps {
  token: string
  onLogout: () => void
}

export function TodosView({ token, onLogout }: TodosViewProps) {
  const [todos, setTodos] = useState<Todo[]>([])
  const [loading, setLoading] = useState(true)
  const [segment, setSegment] = useState<TodoStatus>('INBOX')
  const [showAdd, setShowAdd] = useState(false)
  const [newTitle, setNewTitle] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [plan, setPlan] = useState<PlanDraft | null>(null)

  const fetchTodos = useCallback(async () => {
    try {
      const res = await authFetch(token, `${API_BASE}/todos`)
      if (res.status === 401) {
        onLogout()
        return
      }
      if (!res.ok) return
      setTodos(await res.json())
    } finally {
      setLoading(false)
    }
  }, [onLogout, token])

  useEffect(() => { fetchTodos() }, [fetchTodos])

  useWebSocket(withWsToken(WS_URL, token), (raw) => {
    try {
      const msg = JSON.parse(raw)
      if (!msg.payload) return
      if (msg.type === 'TODO_CREATED') {
        setTodos((prev) => (prev.some((t) => t.id === msg.payload.id) ? prev : [msg.payload, ...prev]))
      } else if (msg.type === 'TODO_UPDATED') {
        setTodos((prev) =>
          prev.some((t) => t.id === msg.payload.id)
            ? prev.map((t) => (t.id === msg.payload.id ? msg.payload : t))
            : [msg.payload, ...prev],
        )
      } else if (msg.type === 'TODO_DELETED') {
        setTodos((prev) => prev.filter((t) => t.id !== msg.payload.id))
      }
    } catch {
      // ignore malformed frames
    }
  })

  const patchTodo = async (id: string, body: Record<string, unknown>) => {
    const res = await authFetch(token, `${API_BASE}/todos/${id}`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    })
    if (res.ok) {
      const updated: Todo = await res.json()
      setTodos((prev) => prev.map((t) => (t.id === updated.id ? updated : t)))
    }
  }

  const handleAdd = async () => {
    if (!newTitle.trim()) return
    setSubmitting(true)
    try {
      const res = await authFetch(token, `${API_BASE}/todos`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ title: newTitle.trim() }),
      })
      if (res.ok) {
        const created: Todo = await res.json()
        setTodos((prev) => [created, ...prev])
      }
      setNewTitle('')
      setShowAdd(false)
    } finally {
      setSubmitting(false)
    }
  }

  const handlePlan = async () => {
    if (!plan) return
    // PLANNED requires at least an assignee or a due date
    if (!plan.assignee.trim() && !plan.dueDate) return
    await patchTodo(plan.id, {
      status: 'PLANNED',
      assignee: plan.assignee.trim() || undefined,
      dueDate: plan.dueDate || undefined,
      priority: plan.priority || undefined,
    })
    setPlan(null)
  }

  const completeTodo = (id: string) => patchTodo(id, { status: 'DONE' })

  const deleteTodo = async (id: string) => {
    setTodos((prev) => prev.filter((t) => t.id !== id))
    await authFetch(token, `${API_BASE}/todos/${id}`, { method: 'DELETE' })
  }

  const inbox = todos.filter((t) => t.status === 'INBOX')
  const planned = todos
    .filter((t) => t.status === 'PLANNED')
    .sort((a, b) => {
      if (a.dueDate && b.dueDate) return a.dueDate.localeCompare(b.dueDate)
      if (a.dueDate) return -1
      if (b.dueDate) return 1
      return 0
    })
  const done = todos
    .filter((t) => t.status === 'DONE')
    .sort((a, b) => (b.doneAt ?? '').localeCompare(a.doneAt ?? ''))

  const visible = segment === 'INBOX' ? inbox : segment === 'PLANNED' ? planned : done
  const counts: Record<TodoStatus, number> = { INBOX: inbox.length, PLANNED: planned.length, DONE: done.length }

  const emptyText =
    segment === 'INBOX' ? 'Inbox ist leer' : segment === 'PLANNED' ? 'Nichts geplant' : 'Noch nichts erledigt'

  return (
    <div className="min-h-screen bg-gray-50 flex flex-col">
      <header className="bg-white shadow-sm px-4 pt-3">
        <div className="flex items-center justify-between gap-3">
          <h1 className="text-xl font-semibold text-gray-800 truncate">HomeBase — Aufgaben</h1>
          <button onClick={onLogout} className="text-sm text-gray-500 hover:text-gray-800">
            Abmelden
          </button>
        </div>
        <div className="flex mt-3 -mb-px">
          {SEGMENTS.map(({ id, label }) => (
            <button
              key={id}
              onClick={() => setSegment(id)}
              className={`flex-1 pb-2 text-sm font-medium border-b-2 transition ${
                segment === id
                  ? 'border-indigo-600 text-indigo-600'
                  : 'border-transparent text-gray-400 hover:text-gray-600'
              }`}
            >
              {label}
              {counts[id] > 0 && <span className="ml-1 text-xs text-gray-400">({counts[id]})</span>}
            </button>
          ))}
        </div>
      </header>

      <main className="flex-1 px-4 py-4 max-w-xl mx-auto w-full">
        {loading ? (
          <p className="text-gray-400 text-center mt-10">Lädt…</p>
        ) : visible.length === 0 ? (
          <div className="text-center mt-20">
            <p className="text-gray-400 text-lg">{emptyText}</p>
            {segment === 'INBOX' && <p className="text-gray-300 text-sm mt-1">Füge eine Aufgabe hinzu</p>}
          </div>
        ) : (
          <ul className="space-y-2">
            {visible.map((todo) => (
              <li key={todo.id} className="bg-white rounded-lg shadow-sm px-4 py-3 flex items-start gap-3">
                <div className="flex-1 min-w-0">
                  <p className={`font-medium truncate ${todo.status === 'DONE' ? 'text-gray-400 line-through' : 'text-gray-800'}`}>
                    {todo.title}
                  </p>
                  {todo.description && <p className="text-gray-500 text-sm mt-0.5 truncate">{todo.description}</p>}
                  <div className="flex flex-wrap items-center gap-x-3 gap-y-0.5 text-xs text-gray-400 mt-1">
                    {todo.assignee && <span>👤 {todo.assignee}</span>}
                    {todo.dueDate && <span>📅 {todo.dueDate}</span>}
                    {todo.status === 'DONE' && todo.doneAt && <span>✅ {todo.doneAt.slice(0, 10)}</span>}
                    {todo.status === 'INBOX' && <span>von {todo.createdBy}</span>}
                  </div>
                </div>

                <div className="flex flex-col items-end gap-2 shrink-0">
                  {todo.priority && (
                    <span className={`text-xs px-2 py-0.5 rounded-full font-medium ${priorityClasses(todo.priority)}`}>
                      {todo.priority}
                    </span>
                  )}
                  <div className="flex items-center gap-1">
                    {todo.status === 'INBOX' && (
                      <button
                        onClick={() => setPlan({ id: todo.id, assignee: todo.assignee ?? '', dueDate: todo.dueDate ?? '', priority: todo.priority ?? '' })}
                        className="text-xs px-2 py-1 rounded-md text-indigo-600 hover:bg-indigo-50"
                      >
                        Planen
                      </button>
                    )}
                    {todo.status === 'PLANNED' && (
                      <button
                        onClick={() => completeTodo(todo.id)}
                        className="text-xs px-2 py-1 rounded-md text-green-600 hover:bg-green-50"
                      >
                        Erledigt
                      </button>
                    )}
                    <button
                      onClick={() => deleteTodo(todo.id)}
                      className="text-gray-300 hover:text-red-500 transition px-1"
                      aria-label="Löschen"
                    >
                      ✕
                    </button>
                  </div>
                </div>
              </li>
            ))}
          </ul>
        )}
      </main>

      {/* FAB — only meaningful in Inbox (new todos start there) */}
      {segment === 'INBOX' && (
        <button
          onClick={() => setShowAdd(true)}
          className="fixed bottom-20 right-6 w-14 h-14 rounded-full bg-indigo-600 text-white text-3xl shadow-lg hover:bg-indigo-700 active:scale-95 transition flex items-center justify-center"
          aria-label="Neue Aufgabe"
        >
          +
        </button>
      )}

      {/* Add-to-inbox modal */}
      {showAdd && (
        <div className="fixed inset-0 bg-black/40 flex items-end sm:items-center justify-center p-4 z-50">
          <div className="bg-white rounded-2xl w-full max-w-md p-5 shadow-xl">
            <h2 className="text-lg font-semibold text-gray-800 mb-3">Neue Aufgabe</h2>
            <input
              autoFocus
              type="text"
              placeholder="Titel…"
              value={newTitle}
              onChange={(e) => setNewTitle(e.target.value)}
              onKeyDown={(e) => e.key === 'Enter' && handleAdd()}
              className="w-full border border-gray-300 rounded-lg px-3 py-2 text-gray-800 focus:outline-none focus:ring-2 focus:ring-indigo-500"
            />
            <div className="flex justify-end gap-2 mt-4">
              <button onClick={() => { setShowAdd(false); setNewTitle('') }} className="px-4 py-2 rounded-lg text-gray-600 hover:bg-gray-100">
                Abbrechen
              </button>
              <button
                onClick={handleAdd}
                disabled={submitting || !newTitle.trim()}
                className="px-4 py-2 rounded-lg bg-indigo-600 text-white hover:bg-indigo-700 disabled:opacity-50"
              >
                Hinzufügen
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Plan modal: INBOX → PLANNED (needs assignee or due date) */}
      {plan && (
        <div className="fixed inset-0 bg-black/40 flex items-end sm:items-center justify-center p-4 z-50">
          <div className="bg-white rounded-2xl w-full max-w-md p-5 shadow-xl">
            <h2 className="text-lg font-semibold text-gray-800 mb-1">Aufgabe planen</h2>
            <p className="text-xs text-gray-400 mb-3">Mindestens Zuständige:r oder Fälligkeit angeben.</p>
            <label className="block text-sm text-gray-600 mb-1">Zuständig</label>
            <input
              autoFocus
              type="text"
              placeholder="z. B. alice"
              value={plan.assignee}
              onChange={(e) => setPlan({ ...plan, assignee: e.target.value })}
              className="w-full border border-gray-300 rounded-lg px-3 py-2 text-gray-800 focus:outline-none focus:ring-2 focus:ring-indigo-500"
            />
            <label className="block text-sm text-gray-600 mb-1 mt-3">Fällig am</label>
            <input
              type="date"
              value={plan.dueDate}
              onChange={(e) => setPlan({ ...plan, dueDate: e.target.value })}
              className="w-full border border-gray-300 rounded-lg px-3 py-2 text-gray-800 focus:outline-none focus:ring-2 focus:ring-indigo-500"
            />
            <label className="block text-sm text-gray-600 mb-1 mt-3">Priorität</label>
            <select
              value={plan.priority}
              onChange={(e) => setPlan({ ...plan, priority: e.target.value as PlanDraft['priority'] })}
              className="w-full border border-gray-300 rounded-lg px-3 py-2 text-gray-800 bg-white focus:outline-none focus:ring-2 focus:ring-indigo-500"
            >
              <option value="">—</option>
              <option value="LOW">LOW</option>
              <option value="MEDIUM">MEDIUM</option>
              <option value="HIGH">HIGH</option>
            </select>
            <div className="flex justify-end gap-2 mt-4">
              <button onClick={() => setPlan(null)} className="px-4 py-2 rounded-lg text-gray-600 hover:bg-gray-100">
                Abbrechen
              </button>
              <button
                onClick={handlePlan}
                disabled={!plan.assignee.trim() && !plan.dueDate}
                className="px-4 py-2 rounded-lg bg-indigo-600 text-white hover:bg-indigo-700 disabled:opacity-50"
              >
                Planen
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
