import { useState, useEffect, useCallback } from 'react'
import { Todo } from '../types'
import { useWebSocket } from '../hooks/useWebSocket'

const WS_URL = import.meta.env.VITE_WS_URL ?? `ws://${window.location.host}/api/v1/ws/todos`
const API_BASE = '/api/v1'

export function InboxView() {
  const [todos, setTodos] = useState<Todo[]>([])
  const [loading, setLoading] = useState(true)
  const [showModal, setShowModal] = useState(false)
  const [newTitle, setNewTitle] = useState('')
  const [submitting, setSubmitting] = useState(false)

  const fetchTodos = useCallback(async () => {
    try {
      const res = await fetch(`${API_BASE}/todos`)
      if (!res.ok) return
      const data: Todo[] = await res.json()
      setTodos(data.filter((t) => t.status === 'INBOX'))
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => { fetchTodos() }, [fetchTodos])

  useWebSocket(WS_URL, (raw) => {
    try {
      const msg = JSON.parse(raw)
      if (msg.type === 'TODO_CREATED' && msg.payload?.status === 'INBOX') {
        setTodos((prev) => [msg.payload, ...prev])
      } else if (msg.type === 'TODO_UPDATED') {
        setTodos((prev) => prev
          .map((t) => t.id === msg.payload?.id ? msg.payload : t)
          .filter((t) => t.status === 'INBOX')
        )
      }
    } catch {
      // ignore malformed frames
    }
  })

  const handleAdd = async () => {
    if (!newTitle.trim()) return
    setSubmitting(true)
    try {
      await fetch(`${API_BASE}/todos`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ title: newTitle.trim() }),
      })
      setNewTitle('')
      setShowModal(false)
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div className="min-h-screen bg-gray-50 flex flex-col">
      <header className="bg-white shadow-sm px-4 py-3">
        <h1 className="text-xl font-semibold text-gray-800">HomeBase — Inbox</h1>
      </header>

      <main className="flex-1 px-4 py-4 max-w-xl mx-auto w-full">
        {loading ? (
          <p className="text-gray-400 text-center mt-10">Lädt…</p>
        ) : todos.length === 0 ? (
          <div className="text-center mt-20">
            <p className="text-gray-400 text-lg">Inbox ist leer</p>
            <p className="text-gray-300 text-sm mt-1">Füge eine Aufgabe hinzu</p>
          </div>
        ) : (
          <ul className="space-y-2">
            {todos.map((todo) => (
              <li
                key={todo.id}
                className="bg-white rounded-lg shadow-sm px-4 py-3 flex items-start gap-3"
              >
                <div className="flex-1 min-w-0">
                  <p className="text-gray-800 font-medium truncate">{todo.title}</p>
                  {todo.description && (
                    <p className="text-gray-500 text-sm mt-0.5 truncate">{todo.description}</p>
                  )}
                  <p className="text-gray-400 text-xs mt-1">von {todo.createdBy}</p>
                </div>
                {todo.priority && (
                  <span className={`text-xs px-2 py-0.5 rounded-full font-medium ${
                    todo.priority === 'HIGH' ? 'bg-red-100 text-red-700' :
                    todo.priority === 'MEDIUM' ? 'bg-yellow-100 text-yellow-700' :
                    'bg-green-100 text-green-700'
                  }`}>
                    {todo.priority}
                  </span>
                )}
              </li>
            ))}
          </ul>
        )}
      </main>

      {/* FAB */}
      <button
        onClick={() => setShowModal(true)}
        className="fixed bottom-6 right-6 w-14 h-14 rounded-full bg-indigo-600 text-white text-3xl shadow-lg hover:bg-indigo-700 active:scale-95 transition flex items-center justify-center"
        aria-label="Neue Aufgabe"
      >
        +
      </button>

      {/* Modal */}
      {showModal && (
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
              <button
                onClick={() => { setShowModal(false); setNewTitle('') }}
                className="px-4 py-2 rounded-lg text-gray-600 hover:bg-gray-100"
              >
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
    </div>
  )
}
