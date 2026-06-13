import { useState, useEffect, useCallback, useRef } from 'react'
import { useTranslation } from 'react-i18next'
import { API_BASE, errorCode, notifyTransportError, safeFetch } from '../api'
import { errorText } from '../i18n'
import { ShoppingItem, ShoppingList } from '../types'
import { useWebSocket } from '../hooks/useWebSocket'
import { Icon } from '../ui/Icon'
import { useErrorToast } from '../ui/ErrorToast'
import { Avatar, Button, Card, Checkbox, EmptyState, Field, IconButton, Modal, PageHead, TextInput } from '../ui/primitives'

const WS_SCHEME = window.location.protocol === 'https:' ? 'wss' : 'ws'
const WS_URL = import.meta.env.VITE_WS_URL_SHOPPING ?? `${WS_SCHEME}://${window.location.host}/api/v1/ws/shopping`

// Offline-resilient check-offs: tapping a checkbox in a store with flaky/no wifi
// must not silently lose the change. Each toggle is mirrored into a small, durable
// queue (keyed by item id, latest desired state wins) that survives a reload and is
// retried on every connectivity signal until it lands. The item shows a "not synced"
// marker until then. Keyed by item UUID, so it's user-agnostic across one browser.
const PENDING_KEY = 'homebase_shopping_pending'
const FLUSH_INTERVAL_MS = 15000

interface PendingCheck { checked: boolean; at: number }

function loadPending(): Record<string, PendingCheck> {
  try {
    const raw = localStorage.getItem(PENDING_KEY)
    return raw ? (JSON.parse(raw) as Record<string, PendingCheck>) : {}
  } catch {
    return {} // private-mode / corrupt value → start clean
  }
}

function savePending(pending: Record<string, PendingCheck>) {
  try {
    if (Object.keys(pending).length === 0) localStorage.removeItem(PENDING_KEY)
    else localStorage.setItem(PENDING_KEY, JSON.stringify(pending))
  } catch {
    /* quota / private mode — the in-memory queue still works for this session */
  }
}

interface ShoppingViewProps {
  token: string
  onLogout: () => void
}

export function ShoppingView({ token, onLogout }: ShoppingViewProps) {
  const { t } = useTranslation()
  const [items, setItems] = useState<ShoppingItem[]>([])
  const [lists, setLists] = useState<ShoppingList[]>([])
  const [loading, setLoading] = useState(true)
  const [activeId, setActiveId] = useState<string | null>(null)
  const [newName, setNewName] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [newListOpen, setNewListOpen] = useState(false)
  const [confirmDeleteList, setConfirmDeleteList] = useState(false)
  // Durable queue of check-offs not yet acknowledged by the backend (offline-safe).
  const [pending, setPending] = useState<Record<string, PendingCheck>>(loadPending)
  const pendingRef = useRef(pending)
  pendingRef.current = pending
  const flushingRef = useRef(false)
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

  const dequeue = useCallback((id: string) => {
    setPending((prev) => {
      if (!(id in prev)) return prev
      const { [id]: _drop, ...rest } = prev
      return rest
    })
  }, [])

  // Drain the pending-check queue. Each queued PUT is kept-and-retried on a
  // transport reject (offline) OR a 5xx (transient backend/proxy hiccup) — both are
  // the "silently lost check-off" this feature exists to prevent. Only a success or
  // a terminal 4xx (e.g. 404, item already gone — retrying can't fix it) drops the
  // entry, and even then not if it was re-toggled meanwhile (a newer `at` survives).
  const flushPending = useCallback(async () => {
    if (flushingRef.current) return
    const entries = Object.entries(pendingRef.current)
    if (entries.length === 0) return
    flushingRef.current = true
    try {
      for (const [id, p] of entries) {
        const result = await safeFetch(token, `${API_BASE}/shopping/${id}`, {
          method: 'PUT',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ checked: p.checked }),
        })
        if (!result.ok) break // transport reject (offline) → keep the queue, retry later
        if (result.res.status === 401) return onLogout()
        if (result.res.status >= 500) break // transient server error → keep, retry later
        if (result.res.ok) {
          const updated: ShoppingItem = await result.res.json()
          // Don't let this (now possibly stale) response overwrite a newer toggle the
          // user made while it was in flight — the optimistic state already reflects it.
          if (pendingRef.current[id]?.at === p.at) {
            setItems((prev) => prev.map((i) => (i.id === updated.id ? updated : i)))
          }
        }
        setPending((prev) => {
          if (prev[id]?.at !== p.at) return prev // re-toggled meanwhile — keep the newer intent
          const { [id]: _drop, ...rest } = prev
          return rest
        })
      }
    } finally {
      flushingRef.current = false
    }
  }, [token, onLogout])

  // Persist the queue on every change and attempt a flush right away (covers a new
  // toggle and leftovers restored from a previous session on mount).
  useEffect(() => {
    savePending(pending)
    if (Object.keys(pending).length > 0) void flushPending()
  }, [pending, flushPending])

  // Retry on connectivity signals beyond the immediate attempt: the OS `online`
  // event and a periodic backstop (flaky store wifi often regains internet without
  // ever firing `online`). The WS `onOpen` below adds a third, server-reachable signal.
  useEffect(() => {
    const onOnline = () => void flushPending()
    window.addEventListener('online', onOnline)
    const interval = window.setInterval(() => {
      if (Object.keys(pendingRef.current).length > 0) void flushPending()
    }, FLUSH_INTERVAL_MS)
    return () => {
      window.removeEventListener('online', onOnline)
      clearInterval(interval)
    }
  }, [flushPending])

  // keep an active tab selected as lists load / change
  useEffect(() => {
    if (lists.length === 0) {
      if (activeId !== null) setActiveId(null)
    } else if (!activeId || !lists.some((l) => l.id === activeId)) {
      setActiveId(lists[0].id)
    }
  }, [lists, activeId])

  useWebSocket({ url: WS_URL, token }, (raw) => {
    try {
      const msg = JSON.parse(raw)
      if (!msg.payload) return
      switch (msg.type) {
        case 'SHOPPING_CREATED':
          setItems((prev) => (prev.some((i) => i.id === msg.payload.id) ? prev : [msg.payload, ...prev]))
          break
        case 'SHOPPING_UPDATED':
          // A not-yet-synced local check intent wins over a server echo for that item
          // (the echo may carry an older state, e.g. our own in-flight PUT after we
          // re-toggled). Other fields (name/list) still take the server value.
          setItems((prev) =>
            prev.map((i) => {
              if (i.id !== msg.payload.id) return i
              return pendingRef.current[i.id] ? { ...msg.payload, checked: i.checked, checkedAt: i.checkedAt } : msg.payload
            }),
          )
          break
        case 'SHOPPING_DELETED':
          setItems((prev) => prev.filter((i) => i.id !== msg.payload.id))
          dequeue(msg.payload.id) // a queued check for a now-deleted item can never land
          break
        case 'SHOPPING_LIST_CREATED':
          setLists((prev) => (prev.some((l) => l.id === msg.payload.id) ? prev : [...prev, msg.payload]))
          break
        case 'SHOPPING_LIST_UPDATED':
          setLists((prev) => prev.map((l) => (l.id === msg.payload.id ? msg.payload : l)))
          break
        case 'SHOPPING_LIST_DELETED': {
          // dequeue outside the setItems updater (no setState-in-updater); the closure's
          // `items` is current enough and dequeue is idempotent.
          const goneList = msg.payload.id
          items.filter((i) => i.listId === goneList).forEach((i) => dequeue(i.id))
          setItems((prev) => prev.filter((i) => i.listId !== goneList))
          setLists((prev) => prev.filter((l) => l.id !== goneList))
          break
        }
      }
    } catch {
      // ignore malformed frames
    }
  }, () => void flushPending()) // onOpen: a (re)connected socket means the server is reachable — drain the queue

  const handleAdd = async () => {
    if (!newName.trim() || !active) return
    setSubmitting(true)
    try {
      const result = await safeFetch(token, `${API_BASE}/shopping`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name: newName.trim(), listId: active.id }),
      })
      if (!result.ok) return flashError(errorText(null, t('shopping.addFailed')))
      const { res } = result
      if (res.status === 401) return onLogout()
      if (res.ok) {
        const created: ShoppingItem = await res.json()
        setItems((prev) => (prev.some((i) => i.id === created.id) ? prev : [created, ...prev]))
        setNewName('')
      } else {
        flashError(errorText(await errorCode(res), t('shopping.addFailed')))
      }
    } finally {
      setSubmitting(false)
    }
  }

  // Toggle a check-off optimistically and queue it for delivery. The queue (not an
  // inline fetch) does the network work, so a tap in a dead zone is remembered and
  // retried instead of lost — the item just carries a "not synced yet" marker until
  // it lands. checkedAt is set locally too, so the cart ordering is right immediately.
  const toggleChecked = (item: ShoppingItem) => {
    const next = !item.checked
    const at = Date.now()
    setItems((prev) =>
      prev.map((i) => (i.id === item.id ? { ...i, checked: next, checkedAt: next ? new Date(at).toISOString() : undefined } : i)),
    )
    setPending((prev) => ({ ...prev, [item.id]: { checked: next, at } }))
  }

  const handleDelete = async (id: string) => {
    setItems((prev) => prev.filter((i) => i.id !== id))
    dequeue(id) // drop any queued check for an item we're deleting
    const result = await safeFetch(token, `${API_BASE}/shopping/${id}`, { method: 'DELETE' })
    if (!result.ok) {
      await fetchAll()
      return flashError(errorText(null, t('shopping.deleteFailed')))
    }
    const { res } = result
    if (res.status === 401) return onLogout()
    if (!res.ok) {
      await fetchAll()
      flashError(errorText(await errorCode(res), t('shopping.deleteFailed')))
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
      flashError(t('shopping.clearFailed'))
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
    if (!result.ok) return errorText(null, t('shopping.listCreateFailed'))
    const { res } = result
    if (res.status === 401) {
      onLogout()
      return null
    }
    if (!res.ok) return errorText(await errorCode(res), t('shopping.listCreateFailed'))
    const created: ShoppingList = await res.json()
    setLists((prev) => (prev.some((l) => l.id === created.id) ? prev : [...prev, created]))
    setActiveId(created.id)
    setNewListOpen(false)
    return null
  }

  const removeList = async () => {
    if (!active || lists.length <= 1) return
    const removedId = active.id
    const idx = lists.findIndex((l) => l.id === removedId)
    const next = lists[idx + 1] ?? lists[idx - 1]
    setConfirmDeleteList(false)
    setLists((prev) => prev.filter((l) => l.id !== removedId))
    setItems((prev) => prev.filter((i) => i.listId !== removedId))
    setActiveId(next ? next.id : null)
    const result = await safeFetch(token, `${API_BASE}/shopping/lists/${removedId}`, { method: 'DELETE' })
    // On failure refetch to resync rather than restoring a captured snapshot,
    // which could clobber a concurrent WS update.
    if (!result.ok) {
      await fetchAll()
      return flashError(errorText(null, t('shopping.listDeleteFailed')))
    }
    const { res } = result
    if (res.status === 401) return onLogout()
    if (!res.ok) {
      await fetchAll()
      flashError(errorText(await errorCode(res), t('shopping.listDeleteFailed')))
    }
  }

  const active = lists.find((l) => l.id === activeId) ?? null
  const itemsOf = (id: string) => items.filter((i) => i.listId === id)
  const openCount = (id: string) => itemsOf(id).filter((i) => !i.checked).length

  const listItems = active ? itemsOf(active.id) : []
  const open = listItems.filter((i) => !i.checked)
  // Most-recently-checked first: ISO checkedAt sorts lexicographically = chronologically;
  // anything without a timestamp (legacy item) sinks to the bottom.
  const checked = listItems
    .filter((i) => i.checked)
    .sort((a, b) => (b.checkedAt ?? '').localeCompare(a.checkedAt ?? ''))
  const totalOpen = items.filter((i) => !i.checked).length
  // Scope the banner to the active list so its count always matches the ↻ badges
  // visible on screen (queued items on other lists still retry silently in the
  // background and surface their banner when that list is open).
  const pendingCount = listItems.filter((i) => pending[i.id]).length

  return (
    <div className="hb-page">
      <PageHead
        eyebrow={`${lists.length} ${lists.length === 1 ? t('shopping.listOne') : t('shopping.listMany')} · ${totalOpen} ${t('shopping.open')}`}
        title={t('shopping.title')}
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
          {t('shopping.newList')}
        </button>
      </div>

      {loading ? (
        <p className="hb-muted" style={{ textAlign: 'center', padding: 24 }}>{t('common.loading')}</p>
      ) : !active ? (
        <Card className="hb-card--pad"><EmptyState icon="cart" title={t('shopping.noLists')} hint={t('shopping.noListsHint')} /></Card>
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
            <Button icon="plus" onClick={handleAdd} disabled={submitting || !newName.trim()}>{t('common.add')}</Button>
          </div>

          {pendingCount > 0 && (
            <div className="hb-syncbar" role="status">
              <Icon name="repeat" size={15} stroke={2} />
              <span>
                {pendingCount === 1 ? t('shopping.offlineQueuedOne') : t('shopping.offlineQueuedMany', { n: String(pendingCount) })}
              </span>
              <button className="hb-link" onClick={() => void flushPending()}>{t('shopping.retryNow')}</button>
            </div>
          )}

          {open.length === 0 && checked.length === 0 ? (
            <Card className="hb-card--pad"><EmptyState icon="cart" title={t('shopping.emptyTitle')} hint={t('shopping.emptyHint')} /></Card>
          ) : (
            <Card className="hb-card--pad" style={{ paddingTop: 6, paddingBottom: 6 }}>
              <div className="hb-list">
                {open.map((item) => (
                  <div key={item.id} className="hb-row" style={{ padding: '11px 4px' }}>
                    <Checkbox checked={false} onChange={() => toggleChecked(item)} />
                    <div className="hb-row__main"><div className="hb-row__title">{item.name}</div></div>
                    <div className="hb-row__right">
                      {pending[item.id] && (
                        <span className="hb-syncbadge" title={t('shopping.notSynced')} aria-label={t('shopping.notSynced')}>
                          <Icon name="repeat" size={13} stroke={2} />
                        </span>
                      )}
                      <Avatar user={item.createdBy} size={22} />
                      <div className="hb-row__actions">
                        <IconButton icon="trash" label={t('common.delete')} danger onClick={() => handleDelete(item.id)} />
                      </div>
                    </div>
                  </div>
                ))}
                {open.length === 0 && <div className="hb-muted" style={{ padding: '14px 4px', fontSize: 14 }}>{t('shopping.allChecked')}</div>}
              </div>
            </Card>
          )}

          {checked.length > 0 && (
            <div style={{ marginTop: 26 }}>
              <div className="hb-cardhead" style={{ marginBottom: 12 }}>
                <div className="hb-sectionlabel" style={{ margin: 0 }}>{t('shopping.inCart')} · {checked.length}</div>
                <button className="hb-link" onClick={clearChecked}>
                  {t('shopping.clearChecked')} <Icon name="trash" size={14} stroke={2} />
                </button>
              </div>
              <Card className="hb-card--pad" style={{ paddingTop: 6, paddingBottom: 6 }}>
                <div className="hb-list">
                  {checked.map((item) => (
                    <div key={item.id} className="hb-row hb-row--done" style={{ padding: '10px 4px' }}>
                      <Checkbox checked onChange={() => toggleChecked(item)} />
                      <div className="hb-row__main"><div className="hb-row__title">{item.name}</div></div>
                      {pending[item.id] && (
                        <span className="hb-syncbadge" title={t('shopping.notSynced')} aria-label={t('shopping.notSynced')}>
                          <Icon name="repeat" size={13} stroke={2} />
                        </span>
                      )}
                      <Avatar user={item.createdBy} size={22} />
                    </div>
                  ))}
                </div>
              </Card>
            </div>
          )}

          {lists.length > 1 && (
            <button className="hb-link hb-link--danger" style={{ marginTop: 26, display: 'block' }} onClick={() => setConfirmDeleteList(true)}>
              <Icon name="trash" size={14} stroke={2} style={{ verticalAlign: '-2px', marginRight: 5 }} />
              {t('shopping.deleteList')} „{active.name}"
            </button>
          )}
        </>
      )}

      <Modal
        open={confirmDeleteList && !!active}
        onClose={() => setConfirmDeleteList(false)}
        title={t('shopping.deleteListTitle')}
        width={440}
        footer={
          <>
            <Button variant="ghost" onClick={() => setConfirmDeleteList(false)}>{t('common.cancel')}</Button>
            <Button variant="danger" icon="trash" onClick={removeList}>{t('shopping.deleteListBtn')}</Button>
          </>
        }
      >
        {active && (
          <p className="hb-muted" style={{ margin: 0, fontSize: 14, lineHeight: 1.55 }}>
            Die Liste „<strong>{active.name}</strong>" und alle Einträge darin werden gelöscht.{' '}
            {t('shopping.deleteListWarn')}
          </p>
        )}
      </Modal>

      {newListOpen && <NewListModal onClose={() => setNewListOpen(false)} onCreate={createList} />}

      {errorToast}
    </div>
  )
}

function NewListModal({ onClose, onCreate }: { onClose: () => void; onCreate: (name: string) => Promise<string | null> }) {
  const { t } = useTranslation()
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
      setError(t('shopping.listCreateFailed'))
    } finally {
      setBusy(false)
    }
  }
  return (
    <Modal
      open
      onClose={onClose}
      title={t('shopping.newListTitle')}
      width={420}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>{t('common.cancel')}</Button>
          <Button variant="primary" icon="check" onClick={create} disabled={!name.trim() || busy}>{t('shopping.createList')}</Button>
        </>
      }
    >
      <Field label={t('shopping.listName')}>
        <TextInput value={name} onChange={setName} placeholder={t('shopping.listNamePlaceholder')} autoFocus onKeyDown={(e) => e.key === 'Enter' && create()} />
      </Field>
      {error && <p className="hb-modal-error">{error}</p>}
    </Modal>
  )
}
