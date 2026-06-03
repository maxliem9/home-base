import { useState, useEffect, useCallback } from 'react'
import { API_BASE, authFetch, withWsToken } from '../api'
import { t } from '../i18n'
import { ShoppingItem } from '../types'
import { useWebSocket } from '../hooks/useWebSocket'

const WS_URL = import.meta.env.VITE_WS_URL_SHOPPING ?? `ws://${window.location.host}/api/v1/ws/shopping`

interface ShoppingViewProps {
  token: string
  onLogout: () => void
}

export function ShoppingView({ token, onLogout }: ShoppingViewProps) {
  const [items, setItems] = useState<ShoppingItem[]>([])
  const [loading, setLoading] = useState(true)
  const [showModal, setShowModal] = useState(false)
  const [newName, setNewName] = useState('')
  const [newCategory, setNewCategory] = useState('')
  const [submitting, setSubmitting] = useState(false)

  const fetchItems = useCallback(async () => {
    try {
      const res = await authFetch(token, `${API_BASE}/shopping`)
      if (res.status === 401) {
        onLogout()
        return
      }
      if (!res.ok) return
      const data: ShoppingItem[] = await res.json()
      setItems(data)
    } finally {
      setLoading(false)
    }
  }, [onLogout, token])

  useEffect(() => { fetchItems() }, [fetchItems])

  useWebSocket(withWsToken(WS_URL, token), (raw) => {
    try {
      const msg = JSON.parse(raw)
      if (msg.type === 'SHOPPING_CREATED' && msg.payload) {
        setItems((prev) => prev.some((i) => i.id === msg.payload.id) ? prev : [msg.payload, ...prev])
      } else if (msg.type === 'SHOPPING_UPDATED' && msg.payload) {
        setItems((prev) => prev.map((i) => i.id === msg.payload.id ? msg.payload : i))
      } else if (msg.type === 'SHOPPING_DELETED' && msg.payload) {
        setItems((prev) => prev.filter((i) => i.id !== msg.payload.id))
      }
    } catch {
      // ignore malformed frames
    }
  })

  const handleAdd = async () => {
    if (!newName.trim()) return
    setSubmitting(true)
    try {
      await authFetch(token, `${API_BASE}/shopping`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          name: newName.trim(),
          category: newCategory.trim() || undefined,
        }),
      })
      setNewName('')
      setNewCategory('')
      setShowModal(false)
    } finally {
      setSubmitting(false)
    }
  }

  const toggleChecked = async (item: ShoppingItem) => {
    // optimistic update; WS broadcast keeps the other client in sync
    setItems((prev) => prev.map((i) => i.id === item.id ? { ...i, checked: !i.checked } : i))
    await authFetch(token, `${API_BASE}/shopping/${item.id}`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ checked: !item.checked }),
    })
  }

  const handleDelete = async (id: string) => {
    setItems((prev) => prev.filter((i) => i.id !== id))
    await authFetch(token, `${API_BASE}/shopping/${id}`, { method: 'DELETE' })
  }

  // group by category, uncategorised last
  const grouped = items.reduce<Record<string, ShoppingItem[]>>((acc, item) => {
    const key = item.category?.trim() || t.shopping.uncategorized
    ;(acc[key] ??= []).push(item)
    return acc
  }, {})
  const categories = Object.keys(grouped).sort((a, b) =>
    a === t.shopping.uncategorized ? 1 : b === t.shopping.uncategorized ? -1 : a.localeCompare(b)
  )

  return (
    <div className="min-h-screen bg-gray-50 flex flex-col">
      <header className="bg-white shadow-sm px-4 py-3">
        <div className="flex items-center justify-between gap-3">
          <h1 className="text-xl font-semibold text-gray-800 truncate">{t.shopping.headerTitle}</h1>
          <button onClick={onLogout} className="text-sm text-gray-500 hover:text-gray-800">
            {t.common.logout}
          </button>
        </div>
      </header>

      <main className="flex-1 px-4 py-4 max-w-xl mx-auto w-full">
        {loading ? (
          <p className="text-gray-400 text-center mt-10">{t.common.loading}</p>
        ) : items.length === 0 ? (
          <div className="text-center mt-20">
            <p className="text-gray-400 text-lg">{t.shopping.emptyTitle}</p>
            <p className="text-gray-300 text-sm mt-1">{t.shopping.emptyHint}</p>
          </div>
        ) : (
          <div className="space-y-5">
            {categories.map((category) => (
              <section key={category}>
                <h2 className="text-xs font-semibold text-gray-400 uppercase tracking-wide mb-2 px-1">
                  {category}
                </h2>
                <ul className="space-y-2">
                  {grouped[category].map((item) => (
                    <li
                      key={item.id}
                      className="bg-white rounded-lg shadow-sm px-4 py-3 flex items-center gap-3"
                    >
                      <input
                        type="checkbox"
                        checked={item.checked}
                        onChange={() => toggleChecked(item)}
                        className="w-5 h-5 rounded accent-indigo-600 cursor-pointer"
                      />
                      <span className={`flex-1 min-w-0 truncate ${
                        item.checked ? 'text-gray-400 line-through' : 'text-gray-800'
                      }`}>
                        {item.name}
                      </span>
                      <button
                        onClick={() => handleDelete(item.id)}
                        className="text-gray-300 hover:text-red-500 transition px-1"
                        aria-label={t.common.delete}
                      >
                        ✕
                      </button>
                    </li>
                  ))}
                </ul>
              </section>
            ))}
          </div>
        )}
      </main>

      {/* FAB */}
      <button
        onClick={() => setShowModal(true)}
        className="fixed bottom-20 right-6 w-14 h-14 rounded-full bg-indigo-600 text-white text-3xl shadow-lg hover:bg-indigo-700 active:scale-95 transition flex items-center justify-center"
        aria-label={t.shopping.newItem}
      >
        +
      </button>

      {/* Modal */}
      {showModal && (
        <div className="fixed inset-0 bg-black/40 flex items-end sm:items-center justify-center p-4 z-50">
          <div className="bg-white rounded-2xl w-full max-w-md p-5 shadow-xl">
            <h2 className="text-lg font-semibold text-gray-800 mb-3">{t.shopping.newItem}</h2>
            <input
              autoFocus
              type="text"
              placeholder={t.shopping.namePlaceholder}
              value={newName}
              onChange={(e) => setNewName(e.target.value)}
              onKeyDown={(e) => e.key === 'Enter' && handleAdd()}
              className="w-full border border-gray-300 rounded-lg px-3 py-2 text-gray-800 focus:outline-none focus:ring-2 focus:ring-indigo-500"
            />
            <input
              type="text"
              placeholder={t.shopping.categoryPlaceholder}
              value={newCategory}
              onChange={(e) => setNewCategory(e.target.value)}
              onKeyDown={(e) => e.key === 'Enter' && handleAdd()}
              className="w-full border border-gray-300 rounded-lg px-3 py-2 text-gray-800 mt-2 focus:outline-none focus:ring-2 focus:ring-indigo-500"
            />
            <div className="flex justify-end gap-2 mt-4">
              <button
                onClick={() => { setShowModal(false); setNewName(''); setNewCategory('') }}
                className="px-4 py-2 rounded-lg text-gray-600 hover:bg-gray-100"
              >
                {t.common.cancel}
              </button>
              <button
                onClick={handleAdd}
                disabled={submitting || !newName.trim()}
                className="px-4 py-2 rounded-lg bg-indigo-600 text-white hover:bg-indigo-700 disabled:opacity-50"
              >
                {t.common.add}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
