import { useState, useEffect, useCallback } from 'react'
import { API_BASE, errorCode, notifyTransportError, safeFetch, withWsToken } from '../api'
import { t, errorText } from '../i18n'
import { ShoppingItem, ShoppingList } from '../types'
import { useWebSocket } from '../hooks/useWebSocket'
import { Icon } from '../ui/Icon'
import { useErrorToast } from '../ui/ErrorToast'
import { Avatar, Button, Card, Checkbox, EmptyState, Field, IconButton, Modal, PageHead, TextInput } from '../ui/primitives'

const WS_SCHEME = window.location.protocol === 'https:' ? 'wss' : 'ws'
const WS_URL = import.meta.env.VITE_WS_URL_SHOPPING ?? `${WS_SCHEME}://${window.location.host}/api/v1/ws/shopping`

interface ShoppingViewProps {
  token: string
  onLogout: () => void
}

export function ShoppingView({ token, onLogout }: ShoppingViewProps) {
  const [items, setItems] = useState<ShoppingItem[]>([])
  const [lists, setLists] = useState<ShoppingList[]>([])
  const [loading, setLoading] = useState(true)
  const [activeId, setActiveId] = useState<string | null>(null)
  const [newName, setNewName] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [newListOpen, setNewListOpen] = useState(false)
  const { flashError, errorToast } = useErrorToast()

  const fetchAll = useCallback(async () => {
    try {
      const [itemResult, listResult] = await Promise.all([
        safeFetch(token, `${API_BASE}/shopping`),
        safeFetch(token, `${API_BASE}/shopping/lists`),
      ])
      // a transport reject on either → fire the global toast once, keep existing data
      if (!itemResult.ok || !listResult.ok) {
        notifyTransportError()
        return
      }
      const { res: itemRes } = itemResult
      const { res: listRes } = listResult
      if (itemRes.status === 401 || listRes.status === 401) {
        onLogout()
        return
      }
      if (itemRes.ok) setItems(await itemRes.json())
      if (listRes.ok) setLists(await listRes.json())
    } finally {
      setLoading(false)
    }
  }, [onLogout, token])

  useEffect(() => { fetchAll() }, [fetchAll])

  // keep an active tab selected as lists load / change
  useEffect(() => {
    if (lists.length === 0) {
      if (activeId !== null) setActiveId(null)
    } else if (!activeId || !lists.some((l) => l.id === activeId)) {
      setActiveId(lists[0].id)
    }
  }, [lists, activeId])

  useWebSocket(withWsToken(WS_URL, token), (raw) => {
    try {
      const msg = JSON.parse(raw)
      if (!msg.payload) return
      switch (msg.type) {
        case 'SHOPPING_CREATED':
          setItems((prev) => (prev.some((i) => i.id === msg.payload.id) ? prev : [msg.payload, ...prev]))
          break
        case 'SHOPPING_UPDATED':
          setItems((prev) => prev.map((i) => (i.id === msg.payload.id ? msg.payload : i)))
          break
        case 'SHOPPING_DELETED':
          setItems((prev) => prev.filter((i) => i.id !== msg.payload.id))
          break
        case 'SHOPPING_LIST_CREATED':
          setLists((prev) => (prev.some((l) => l.id === msg.payload.id) ? prev : [...prev, msg.payload]))
          break
        case 'SHOPPING_LIST_UPDATED':
          setLists((prev) => prev.map((l) => (l.id === msg.payload.id ? msg.payload : l)))
          break
        case 'SHOPPING_LIST_DELETED':
          setLists((prev) => prev.filter((l) => l.id !== msg.payload.id))
          setItems((prev) => prev.filter((i) => i.listId !== msg.payload.id))
          break
      }
    } catch {
      // ignore malformed frames
    }
  })

  const handleAdd = async () => {
    if (!newName.trim() || !active) return
    setSubmitting(true)
    try {
      const result = await safeFetch(token, `${API_BASE}/shopping`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name: newName.trim(), listId: active.id }),
      })
      if (!result.ok) return flashError(errorText(null, t.shopping.addFailed))
      const { res } = result
      if (res.status === 401) return onLogout()
      if (res.ok) {
        const created: ShoppingItem = await res.json()
        setItems((prev) => (prev.some((i) => i.id === created.id) ? prev : [created, ...prev]))
        setNewName('')
      } else {
        flashError(errorText(await errorCode(res), t.shopping.addFailed))
      }
    } finally {
      setSubmitting(false)
    }
  }

  const toggleChecked = async (item: ShoppingItem) => {
    setItems((prev) => prev.map((i) => (i.id === item.id ? { ...i, checked: !i.checked } : i)))
    const result = await safeFetch(token, `${API_BASE}/shopping/${item.id}`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ checked: !item.checked }),
    })
    // On failure refetch to resync rather than restoring a captured snapshot,
    // which could clobber a concurrent WS update.
    if (!result.ok) {
      await fetchAll()
      return flashError(errorText(null, t.shopping.saveFailed))
    }
    const { res } = result
    if (res.status === 401) return onLogout()
    if (!res.ok) {
      await fetchAll()
      flashError(errorText(await errorCode(res), t.shopping.saveFailed))
    }
  }

  const handleDelete = async (id: string) => {
    setItems((prev) => prev.filter((i) => i.id !== id))
    const result = await safeFetch(token, `${API_BASE}/shopping/${id}`, { method: 'DELETE' })
    if (!result.ok) {
      await fetchAll()
      return flashError(errorText(null, t.shopping.deleteFailed))
    }
    const { res } = result
    if (res.status === 401) return onLogout()
    if (!res.ok) {
      await fetchAll()
      flashError(errorText(await errorCode(res), t.shopping.deleteFailed))
    }
  }

  const clearChecked = async () => {
    if (!active) return
    const checkedHere = items.filter((i) => i.checked && i.listId === active.id)
    setItems((prev) => prev.filter((i) => !(i.checked && i.listId === active.id)))
    const results = await Promise.all(
      checkedHere.map((i) => safeFetch(token, `${API_BASE}/shopping/${i.id}`, { method: 'DELETE' })),
    )
    if (results.some((r) => r.ok && r.res.status === 401)) return onLogout()
    // Any transport reject or HTTP error → refetch to resync the list.
    if (results.some((r) => !r.ok || !r.res.ok)) {
      await fetchAll()
      flashError(t.shopping.clearFailed)
    }
  }

  // Modal-based create returns an error message (null on success) so the modal
  // can show it inline and stay open for a retry (issue #96).
  const createList = async (name: string): Promise<string | null> => {
    const result = await safeFetch(token, `${API_BASE}/shopping/lists`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name }),
    })
    // transport reject → no Response; surface the inline create error so the modal stays open
    if (!result.ok) return errorText(null, t.shopping.listCreateFailed)
    const { res } = result
    if (res.status === 401) {
      onLogout()
      return null
    }
    if (!res.ok) return errorText(await errorCode(res), t.shopping.listCreateFailed)
    const created: ShoppingList = await res.json()
    setLists((prev) => (prev.some((l) => l.id === created.id) ? prev : [...prev, created]))
    setActiveId(created.id)
    setNewListOpen(false)
    return null
  }

  const removeList = async () => {
    if (!active || lists.length <= 1) return
    if (!confirm(`${t.shopping.deleteListConfirm}\n\n„${active.name}"`)) return
    const idx = lists.findIndex((l) => l.id === active.id)
    const next = lists[idx + 1] ?? lists[idx - 1]
    setLists((prev) => prev.filter((l) => l.id !== active.id))
    setItems((prev) => prev.filter((i) => i.listId !== active.id))
    setActiveId(next ? next.id : null)
    const result = await safeFetch(token, `${API_BASE}/shopping/lists/${active.id}`, { method: 'DELETE' })
    // On failure refetch to resync rather than restoring a captured snapshot,
    // which could clobber a concurrent WS update.
    if (!result.ok) {
      await fetchAll()
      return flashError(errorText(null, t.shopping.listDeleteFailed))
    }
    const { res } = result
    if (res.status === 401) return onLogout()
    if (!res.ok) {
      await fetchAll()
      flashError(errorText(await errorCode(res), t.shopping.listDeleteFailed))
    }
  }

  const active = lists.find((l) => l.id === activeId) ?? null
  const itemsOf = (id: string) => items.filter((i) => i.listId === id)
  const openCount = (id: string) => itemsOf(id).filter((i) => !i.checked).length

  const listItems = active ? itemsOf(active.id) : []
  const open = listItems.filter((i) => !i.checked)
  const checked = listItems.filter((i) => i.checked)
  const totalOpen = items.filter((i) => !i.checked).length

  return (
    <div className="hb-page">
      <PageHead
        eyebrow={`${lists.length} ${lists.length === 1 ? t.shopping.listOne : t.shopping.listMany} · ${totalOpen} ${t.shopping.open}`}
        title={t.shopping.title}
      />

      {/* Listen-Tabs */}
      <div className="hb-tabs" role="tablist">
        {lists.map((l) => (
          <button
            key={l.id}
            role="tab"
            aria-selected={active?.id === l.id}
            className={`hb-tab${active?.id === l.id ? ' is-active' : ''}`}
            onClick={() => setActiveId(l.id)}
          >
            {l.name}
            {openCount(l.id) > 0 && <span className="hb-tab__count">{openCount(l.id)}</span>}
          </button>
        ))}
        <button className="hb-tab hb-tab--add" onClick={() => setNewListOpen(true)}>
          <Icon name="plus" size={16} stroke={2.2} />
          {t.shopping.newList}
        </button>
      </div>

      {loading ? (
        <p className="hb-muted" style={{ textAlign: 'center', padding: 24 }}>{t.common.loading}</p>
      ) : !active ? (
        <Card className="hb-card--pad"><EmptyState icon="cart" title={t.shopping.noLists} hint={t.shopping.noListsHint} /></Card>
      ) : (
        <>
          <div className="hb-shop-add">
            <div className="hb-quickadd" style={{ flex: 1 }}>
              <Icon name="cart" size={19} stroke={2} style={{ color: 'var(--ink-3)' }} />
              <input
                value={newName}
                placeholder={`Was fehlt in „${active.name}"? …`}
                onChange={(e) => setNewName(e.target.value)}
                onKeyDown={(e) => e.key === 'Enter' && handleAdd()}
              />
            </div>
            <Button icon="plus" onClick={handleAdd} disabled={submitting || !newName.trim()}>{t.common.add}</Button>
          </div>

          {open.length === 0 && checked.length === 0 ? (
            <Card className="hb-card--pad"><EmptyState icon="cart" title={t.shopping.emptyTitle} hint={t.shopping.emptyHint} /></Card>
          ) : (
            <Card className="hb-card--pad" style={{ paddingTop: 6, paddingBottom: 6 }}>
              <div className="hb-list">
                {open.map((item) => (
                  <div key={item.id} className="hb-row" style={{ padding: '11px 4px' }}>
                    <Checkbox checked={false} onChange={() => toggleChecked(item)} />
                    <div className="hb-row__main"><div className="hb-row__title">{item.name}</div></div>
                    <div className="hb-row__right">
                      <Avatar user={item.createdBy} size={22} />
                      <div className="hb-row__actions">
                        <IconButton icon="trash" label={t.common.delete} danger onClick={() => handleDelete(item.id)} />
                      </div>
                    </div>
                  </div>
                ))}
                {open.length === 0 && <div className="hb-muted" style={{ padding: '14px 4px', fontSize: 14 }}>{t.shopping.allChecked}</div>}
              </div>
            </Card>
          )}

          {checked.length > 0 && (
            <div style={{ marginTop: 26 }}>
              <div className="hb-cardhead" style={{ marginBottom: 12 }}>
                <div className="hb-sectionlabel" style={{ margin: 0 }}>{t.shopping.inCart} · {checked.length}</div>
                <button className="hb-link" onClick={clearChecked}>
                  {t.shopping.clearChecked} <Icon name="trash" size={14} stroke={2} />
                </button>
              </div>
              <Card className="hb-card--pad" style={{ paddingTop: 6, paddingBottom: 6 }}>
                <div className="hb-list">
                  {checked.map((item) => (
                    <div key={item.id} className="hb-row hb-row--done" style={{ padding: '10px 4px' }}>
                      <Checkbox checked onChange={() => toggleChecked(item)} />
                      <div className="hb-row__main"><div className="hb-row__title">{item.name}</div></div>
                      <Avatar user={item.createdBy} size={22} />
                    </div>
                  ))}
                </div>
              </Card>
            </div>
          )}

          {lists.length > 1 && (
            <button className="hb-link hb-link--danger" style={{ marginTop: 26, display: 'block' }} onClick={removeList}>
              <Icon name="trash" size={14} stroke={2} style={{ verticalAlign: '-2px', marginRight: 5 }} />
              {t.shopping.deleteList} „{active.name}"
            </button>
          )}
        </>
      )}

      {newListOpen && <NewListModal onClose={() => setNewListOpen(false)} onCreate={createList} />}

      {errorToast}
    </div>
  )
}

function NewListModal({ onClose, onCreate }: { onClose: () => void; onCreate: (name: string) => Promise<string | null> }) {
  const [name, setName] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  // On failure the modal stays open and shows the reason inline (issue #96).
  const create = async () => {
    if (!name.trim() || busy) return
    setBusy(true)
    setError(null)
    try {
      const err = await onCreate(name.trim())
      if (err) setError(err)
    } catch {
      setError(t.shopping.listCreateFailed)
    } finally {
      setBusy(false)
    }
  }
  return (
    <Modal
      open
      onClose={onClose}
      title={t.shopping.newListTitle}
      width={420}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>{t.common.cancel}</Button>
          <Button variant="primary" icon="check" onClick={create} disabled={!name.trim() || busy}>{t.shopping.createList}</Button>
        </>
      }
    >
      <Field label={t.shopping.listName}>
        <TextInput value={name} onChange={setName} placeholder={t.shopping.listNamePlaceholder} autoFocus onKeyDown={(e) => e.key === 'Enter' && create()} />
      </Field>
      {error && <p className="hb-modal-error">{error}</p>}
    </Modal>
  )
}
