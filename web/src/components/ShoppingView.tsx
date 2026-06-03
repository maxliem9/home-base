import { useState, useEffect, useCallback } from 'react'
import { API_BASE, authFetch, withWsToken } from '../api'
import { t } from '../i18n'
import { ShoppingItem } from '../types'
import { useWebSocket } from '../hooks/useWebSocket'
import { Icon } from '../ui/Icon'
import { Avatar, Button, Card, Checkbox, EmptyState, IconButton, PageHead, TextInput } from '../ui/primitives'

const WS_SCHEME = window.location.protocol === 'https:' ? 'wss' : 'ws'
const WS_URL = import.meta.env.VITE_WS_URL_SHOPPING ?? `${WS_SCHEME}://${window.location.host}/api/v1/ws/shopping`

interface ShoppingViewProps {
  token: string
  onLogout: () => void
}

export function ShoppingView({ token, onLogout }: ShoppingViewProps) {
  const [items, setItems] = useState<ShoppingItem[]>([])
  const [loading, setLoading] = useState(true)
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
      setItems(await res.json())
    } finally {
      setLoading(false)
    }
  }, [onLogout, token])

  useEffect(() => { fetchItems() }, [fetchItems])

  useWebSocket(withWsToken(WS_URL, token), (raw) => {
    try {
      const msg = JSON.parse(raw)
      if (msg.type === 'SHOPPING_CREATED' && msg.payload) {
        setItems((prev) => (prev.some((i) => i.id === msg.payload.id) ? prev : [msg.payload, ...prev]))
      } else if (msg.type === 'SHOPPING_UPDATED' && msg.payload) {
        setItems((prev) => prev.map((i) => (i.id === msg.payload.id ? msg.payload : i)))
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
        body: JSON.stringify({ name: newName.trim(), category: newCategory.trim() || undefined }),
      })
      setNewName('')
      setNewCategory('')
    } finally {
      setSubmitting(false)
    }
  }

  const toggleChecked = async (item: ShoppingItem) => {
    setItems((prev) => prev.map((i) => (i.id === item.id ? { ...i, checked: !i.checked } : i)))
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

  const clearChecked = async () => {
    const checked = items.filter((i) => i.checked)
    setItems((prev) => prev.filter((i) => !i.checked))
    await Promise.all(checked.map((i) => authFetch(token, `${API_BASE}/shopping/${i.id}`, { method: 'DELETE' })))
  }

  // group by category, uncategorised last
  const grouped = items.reduce<Record<string, ShoppingItem[]>>((acc, item) => {
    const key = item.category?.trim() || t.shopping.uncategorized
    ;(acc[key] ??= []).push(item)
    return acc
  }, {})
  const categories = Object.keys(grouped).sort((a, b) =>
    a === t.shopping.uncategorized ? 1 : b === t.shopping.uncategorized ? -1 : a.localeCompare(b),
  )

  const openCount = items.filter((i) => !i.checked).length
  const anyChecked = items.some((i) => i.checked)

  return (
    <div className="hb-page">
      <PageHead
        eyebrow={`${openCount} ${t.shopping.open}`}
        title={t.shopping.title}
        actions={anyChecked ? <Button variant="ghost" size="sm" icon="trash" onClick={clearChecked}>{t.shopping.clearChecked}</Button> : undefined}
      />

      <div className="hb-shop-add">
        <div className="hb-quickadd">
          <Icon name="cart" size={18} stroke={2} style={{ color: 'var(--ink-3)' }} />
          <input
            value={newName}
            placeholder={t.shopping.namePlaceholder}
            onChange={(e) => setNewName(e.target.value)}
            onKeyDown={(e) => e.key === 'Enter' && handleAdd()}
          />
        </div>
        <TextInput
          value={newCategory}
          onChange={setNewCategory}
          placeholder={t.shopping.categoryPlaceholder}
          onKeyDown={(e) => e.key === 'Enter' && handleAdd()}
          style={{ maxWidth: 220 }}
        />
        <Button icon="plus" onClick={handleAdd} disabled={submitting || !newName.trim()}>{t.common.add}</Button>
      </div>

      {loading ? (
        <p className="hb-muted" style={{ textAlign: 'center', padding: 24 }}>{t.common.loading}</p>
      ) : items.length === 0 ? (
        <Card className="hb-card--pad"><EmptyState icon="cart" title={t.shopping.emptyTitle} hint={t.shopping.emptyHint} /></Card>
      ) : (
        <div className="hb-shop-grid">
          {categories.map((category) => (
            <Card key={category} className="hb-card--pad">
              <div className="hb-cardhead">
                <h3>{category}</h3>
                <span className="hb-badge hb-badge--neutral">{grouped[category].length}</span>
              </div>
              <div className="hb-list">
                {grouped[category].map((item) => (
                  <div key={item.id} className={`hb-row${item.checked ? ' hb-row--done' : ''}`}>
                    <Checkbox checked={item.checked} onChange={() => toggleChecked(item)} />
                    <div className="hb-row__main">
                      <div className="hb-row__title">{item.name}</div>
                    </div>
                    <div className="hb-row__right">
                      <Avatar user={item.createdBy} size={22} />
                      <div className="hb-row__actions">
                        <IconButton icon="trash" label={t.common.delete} danger onClick={() => handleDelete(item.id)} />
                      </div>
                    </div>
                  </div>
                ))}
              </div>
            </Card>
          ))}
        </div>
      )}
    </div>
  )
}
