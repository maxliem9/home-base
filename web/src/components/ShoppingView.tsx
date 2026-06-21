import { useState, useEffect, useCallback, useRef, useMemo } from 'react'
import { useTranslation } from 'react-i18next'
import { API_BASE, errorCode, notifyTransportError, safeFetch } from '../api'
import { errorText } from '../i18n'
import { ShoppingItem, ShoppingList, ShoppingSuggestion, ShoppingTemplate } from '../types'
import { useWebSocket } from '../hooks/useWebSocket'
import { Icon } from '../ui/Icon'
import { useErrorToast } from '../ui/ErrorToast'
import { Avatar, Button, Card, Checkbox, EmptyState, Field, IconButton, Modal, PageHead, TextInput } from '../ui/primitives'
import { TemplatesSheet, ApplyTemplateSheet } from './ShoppingTemplates'
import { CATEGORIES, categoryMeta, groupByCategory, ItemIcon, DEFAULT_ITEM_ICON } from './shoppingCategories'

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
  // Named standard/template lists (#215). The management Sheet and the apply-selection
  // Sheet read this; refetched on the template WS broadcasts (same shopping channel).
  const [templates, setTemplates] = useState<ShoppingTemplate[]>([])
  const [templatesOpen, setTemplatesOpen] = useState(false)
  const [applyingTemplate, setApplyingTemplate] = useState<ShoppingTemplate | null>(null)
  const [templateToast, setTemplateToast] = useState<string | null>(null)
  // Durable queue of check-offs not yet acknowledged by the backend (offline-safe).
  const [pending, setPending] = useState<Record<string, PendingCheck>>(loadPending)
  const pendingRef = useRef(pending)
  pendingRef.current = pending
  const flushingRef = useRef(false)
  // Autocomplete "most used" suggestions (#389), preloaded once and filtered client-side.
  const [suggestions, setSuggestions] = useState<ShoppingSuggestion[]>([])
  // Item id whose "In Kategorie verschieben" menu is open (one at a time).
  const [menuFor, setMenuFor] = useState<string | null>(null)
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

  // Load the named templates. `items` is omitted by the backend when empty
  // (encodeDefaults=false) — normalize to [] so the UI can treat it as an array.
  const fetchTemplates = useCallback(async () => {
    const result = await safeFetch(token, `${API_BASE}/shopping/templates`)
    if (!result.ok) return notifyTransportError()
    const { res } = result
    if (res.status === 401) return onLogout()
    if (res.ok) {
      const list = (await res.json()) as ShoppingTemplate[]
      setTemplates(list.map((tpl) => ({ ...tpl, items: tpl.items ?? [] })))
    }
  }, [onLogout, token])

  useEffect(() => { fetchTemplates() }, [fetchTemplates])

  // Preload the most-used items once for the quick-add autocomplete; filtered client-side as the
  // user types (#389). Non-fatal — an empty list just means no suggestions are shown.
  const fetchSuggestions = useCallback(async () => {
    const result = await safeFetch(token, `${API_BASE}/shopping/suggestions`)
    if (!result.ok || !result.res.ok) return
    setSuggestions((await result.res.json()) as ShoppingSuggestion[])
  }, [token])

  useEffect(() => { void fetchSuggestions() }, [fetchSuggestions])

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
        // Template mutations ride the same shopping channel (#215). Refetch the full
        // set — they're few and rarely change, so a reload is simpler than reconciling.
        case 'SHOPPING_TEMPLATE_CREATED':
        case 'SHOPPING_TEMPLATE_UPDATED':
        case 'SHOPPING_TEMPLATE_DELETED':
          void fetchTemplates()
          break
      }
    } catch {
      // ignore malformed frames
    }
  }, () => void flushPending()) // onOpen: a (re)connected socket means the server is reachable — drain the queue

  const handleAdd = async (nameArg?: string) => {
    const name = (nameArg ?? newName).trim()
    if (!name || !active) return
    // Clear the input *before* the await — the field is controlled, so leaving the old
    // text in it lets fast follow-up keystrokes append and the next Enter post the merged
    // value (#377). Each add captures its own `name`, so a quick second Enter starts an
    // independent POST instead of re-posting the first; restore below only on a genuine
    // failure (and only if the field is still untouched).
    setNewName('')
    setSubmitting(true)
    try {
      const result = await safeFetch(token, `${API_BASE}/shopping`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name, listId: active.id }),
      })
      if (!result.ok) {
        restoreName(name)
        return flashError(errorText(null, t('shopping.addFailed')))
      }
      const { res } = result
      if (res.status === 401) return onLogout()
      if (res.ok) {
        const created: ShoppingItem = await res.json()
        setItems((prev) => (prev.some((i) => i.id === created.id) ? prev : [created, ...prev]))
      } else {
        restoreName(name)
        flashError(errorText(await errorCode(res), t('shopping.addFailed')))
      }
    } finally {
      setSubmitting(false)
    }
  }

  // Put a failed add's text back so it isn't lost — but only if the user hasn't already
  // started typing the next item into the now-empty field (don't clobber their input).
  const restoreName = (name: string) => setNewName((cur) => (cur ? cur : name))

  // Reassign an item's category via the "In Kategorie verschieben" menu (#389). Optimistic; the
  // backend also remembers the choice for future adds of that name. On failure, refetch to resync.
  const moveItem = async (item: ShoppingItem, category: string) => {
    setMenuFor(null)
    if (item.category === category) return
    setItems((prev) => prev.map((i) => (i.id === item.id ? { ...i, category } : i)))
    const result = await safeFetch(token, `${API_BASE}/shopping/${item.id}`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ category }),
    })
    if (!result.ok) {
      await fetchAll()
      return flashError(errorText(null, t('shopping.moveFailed')))
    }
    const { res } = result
    if (res.status === 401) return onLogout()
    if (!res.ok) {
      await fetchAll()
      flashError(errorText(await errorCode(res), t('shopping.moveFailed')))
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

  // Apply a template: batch-add the chosen item names to the active list, reusing the
  // same /shopping/batch endpoint the recipe→shopping flow uses (#215, no apply endpoint).
  // Names only — no amount/unit. New items arrive via the batch REST response + WS echo.
  const applyTemplate = async (listId: string, names: string[]) => {
    setApplyingTemplate(null)
    const lines = names.map((name) => ({ name }))
    if (lines.length === 0) return
    const result = await safeFetch(token, `${API_BASE}/shopping/batch`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ listId, items: lines }),
    })
    if (!result.ok) return flashError(errorText(null, t('shopping.templates.applyFailed')))
    const { res } = result
    if (res.status === 401) return onLogout()
    if (!res.ok) return flashError(errorText(await errorCode(res), t('shopping.templates.applyFailed')))
    const summary = (await res.json()) as { added: number; merged: number; items: ShoppingItem[] }
    // Reflect the change immediately (the batch response is authoritative; the WS echo
    // can lag or be suppressed). Update items already present (merged quantities) in
    // place and prepend the genuinely-new ones, keeping the existing newest-first order.
    setItems((prev) => {
      const returned = summary.items ?? []
      const present = new Set(prev.map((i) => i.id))
      const updatedById = new Map(returned.map((i) => [i.id, i]))
      const merged = prev.map((i) => updatedById.get(i.id) ?? i)
      const fresh = returned.filter((i) => !present.has(i.id))
      return [...fresh, ...merged]
    })
    const parts: string[] = []
    if (summary.added > 0) parts.push(`${summary.added} ${t('shopping.templates.added')}`)
    if (summary.merged > 0) parts.push(`${summary.merged} ${t('shopping.templates.merged')}`)
    setTemplateToast(parts.length ? parts.join(' · ') : t('shopping.templates.nothingToAdd'))
    setTimeout(() => setTemplateToast(null), 2600)
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
  // Open items bucketed into category sections in fixed shopping-route order (#389).
  const groups = groupByCategory(open)

  return (
    <div className="hb-page">
      <PageHead
        eyebrow={`${lists.length} ${lists.length === 1 ? t('shopping.listOne') : t('shopping.listMany')} · ${totalOpen} ${t('shopping.open')}`}
        title={t('shopping.title')}
      />

      {/* Listen-Tabs */}
      <div className="hb-tabs" role="tablist" aria-label={t('shopping.listsAria')}>
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
        <button className="hb-tab hb-tab--add" onClick={() => setTemplatesOpen(true)}>
          <Icon name="cart" size={16} stroke={2.2} />
          {t('shopping.templates.open')}
        </button>
      </div>

      {loading ? (
        <p className="hb-muted" style={{ textAlign: 'center', padding: 24 }}>{t('common.loading')}</p>
      ) : !active ? (
        <Card className="hb-card--pad"><EmptyState icon="cart" title={t('shopping.noLists')} hint={t('shopping.noListsHint')} action={<Button size="sm" icon="plus" onClick={() => setNewListOpen(true)}>{t('shopping.newList')}</Button>} /></Card>
      ) : (
        <>
          <ShoppingQuickAdd
            value={newName}
            onChange={setNewName}
            onAdd={(name) => handleAdd(name)}
            suggestions={suggestions}
            placeholder={t('shopping.namePlaceholder', { name: active.name })}
            submitting={submitting}
          />

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
          ) : open.length === 0 ? (
            <Card className="hb-card--pad"><div className="hb-muted" style={{ padding: '8px 4px', fontSize: 14 }}>{t('shopping.allChecked')}</div></Card>
          ) : (
            <div className="hb-shop-grid">
              {groups.map((group) => (
                <Card key={group.category.key} className="hb-card--pad">
                  <div className="hb-cardhead">
                    <span className="hb-cathead">
                      <span className="hb-cathead__emoji" aria-hidden="true">{group.category.emoji}</span>
                      {group.category.label}
                    </span>
                    <span className="hb-catcount">{group.items.length}</span>
                  </div>
                  <div className="hb-list">
                    {group.items.map((item) => (
                      <div key={item.id} className="hb-row" style={{ padding: '11px 4px' }}>
                        <Checkbox checked={false} onChange={() => toggleChecked(item)} />
                        <ItemIcon item={item} />
                        <div className="hb-row__main"><div className="hb-row__title">{item.name}</div></div>
                        <div className="hb-row__right">
                          {pending[item.id] && (
                            <span className="hb-syncbadge" title={t('shopping.notSynced')} aria-label={t('shopping.notSynced')}>
                              <Icon name="repeat" size={13} stroke={2} />
                            </span>
                          )}
                          <Avatar user={item.createdBy} size={22} />
                          <div className="hb-row__actions">
                            <IconButton icon="tag" label={t('shopping.moveCategory')} onClick={() => setMenuFor(menuFor === item.id ? null : item.id)} />
                            <IconButton icon="trash" label={t('common.delete')} danger onClick={() => handleDelete(item.id)} />
                          </div>
                          {menuFor === item.id && (
                            <CategoryMenu current={item.category} onPick={(key) => moveItem(item, key)} onClose={() => setMenuFor(null)} />
                          )}
                        </div>
                      </div>
                    ))}
                  </div>
                </Card>
              ))}
            </div>
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
                      <ItemIcon item={item} muted />
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
              {t('shopping.deleteListNamed', { name: active.name })}
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
            {t('shopping.deleteListBody', { name: active.name })}{' '}
            {t('shopping.deleteListWarn')}
          </p>
        )}
      </Modal>

      {newListOpen && <NewListModal onClose={() => setNewListOpen(false)} onCreate={createList} />}

      {templatesOpen && (
        <TemplatesSheet
          token={token}
          templates={templates}
          onClose={() => setTemplatesOpen(false)}
          onChanged={fetchTemplates}
          onLogout={onLogout}
          onApply={(tpl) => { setTemplatesOpen(false); setApplyingTemplate(tpl) }}
        />
      )}

      {applyingTemplate && (
        <ApplyTemplateSheet
          template={applyingTemplate}
          lists={lists}
          activeListId={activeId}
          onClose={() => setApplyingTemplate(null)}
          onApply={applyTemplate}
        />
      )}

      {templateToast && (
        <div className="hb-toast">
          <Icon name="check" size={18} stroke={2.4} style={{ color: 'var(--accent)' }} />
          {templateToast}
        </div>
      )}

      {errorToast}
    </div>
  )
}

// Quick-add pill with a "most used" autocomplete dropdown (#389). Suggestions are filtered
// client-side from the preloaded list as the user types (prefix matches first, then substring),
// ranked by purchase frequency. Enter / click / the Add button add the highlighted suggestion or
// the raw typed text; the field is controlled by the parent so the #377 clear-on-submit holds.
function ShoppingQuickAdd({
  value, onChange, onAdd, suggestions, placeholder, submitting,
}: {
  value: string
  onChange: (v: string) => void
  onAdd: (name: string) => void
  suggestions: ShoppingSuggestion[]
  placeholder: string
  submitting: boolean
}) {
  const { t } = useTranslation()
  const [focused, setFocused] = useState(false)
  const [acIndex, setAcIndex] = useState(0)
  const wrapRef = useRef<HTMLDivElement>(null)

  const q = value.trim().toLowerCase()
  const matches = useMemo(() => {
    if (!q) return []
    const pre: ShoppingSuggestion[] = []
    const sub: ShoppingSuggestion[] = []
    for (const s of suggestions) {
      const n = s.name.toLowerCase()
      if (n.startsWith(q)) pre.push(s)
      else if (n.includes(q)) sub.push(s)
    }
    return [...pre, ...sub].slice(0, 6)
  }, [suggestions, q])
  const maxCount = useMemo(() => suggestions.reduce((m, s) => Math.max(m, s.count), 1), [suggestions])
  const open = focused && matches.length > 0

  // reset the highlight whenever the query (and thus the match set) changes
  useEffect(() => { setAcIndex(0) }, [q])

  // close the dropdown on an outside click
  useEffect(() => {
    if (!open) return
    const onDown = (e: MouseEvent) => {
      if (wrapRef.current && !wrapRef.current.contains(e.target as Node)) setFocused(false)
    }
    document.addEventListener('mousedown', onDown)
    return () => document.removeEventListener('mousedown', onDown)
  }, [open])

  const submit = (name: string) => {
    const n = name.trim()
    if (!n) return
    onAdd(n)
    setAcIndex(0)
  }

  const highlight = (name: string) => {
    const i = q ? name.toLowerCase().indexOf(q) : -1
    if (i < 0) return name
    return (
      <>
        {name.slice(0, i)}
        <mark>{name.slice(i, i + q.length)}</mark>
        {name.slice(i + q.length)}
      </>
    )
  }

  return (
    <div className="hb-shop-add">
      <div className="hb-addwrap" ref={wrapRef}>
        <div className="hb-quickadd">
          <Icon name="cart" size={19} stroke={2} style={{ color: 'var(--ink-3)' }} />
          <input
            value={value}
            placeholder={placeholder}
            autoComplete="off"
            onChange={(e) => onChange(e.target.value)}
            onFocus={() => setFocused(true)}
            onKeyDown={(e) => {
              if (open && e.key === 'ArrowDown') { e.preventDefault(); setAcIndex((i) => Math.min(i + 1, matches.length - 1)) }
              else if (open && e.key === 'ArrowUp') { e.preventDefault(); setAcIndex((i) => Math.max(i - 1, 0)) }
              else if (e.key === 'Enter') { e.preventDefault(); submit(open && matches[acIndex] ? matches[acIndex].name : value) }
              else if (e.key === 'Escape') setFocused(false)
            }}
          />
        </div>
        {open && (
          <div className="hb-ac" role="listbox">
            <div className="hb-ac__hint">
              <Icon name="sparkle" size={13} stroke={2} style={{ color: 'var(--accent)' }} />
              {t('shopping.suggestionsHint')}
            </div>
            {matches.map((s, i) => (
              <div
                key={s.name}
                role="option"
                aria-selected={i === acIndex}
                className={`hb-ac__item${i === acIndex ? ' is-active' : ''}`}
                onMouseEnter={() => setAcIndex(i)}
                onMouseDown={(e) => { e.preventDefault(); submit(s.name) }}
              >
                <span className="hb-ac__emoji" aria-hidden="true">{s.icon || DEFAULT_ITEM_ICON}</span>
                <span className="hb-ac__name">{highlight(s.name)}</span>
                <span className="hb-ac__cat">{categoryMeta(s.category).label}</span>
                <span className="hb-ac__count">
                  <span className="hb-ac__bar"><i style={{ width: `${Math.round((s.count / maxCount) * 100)}%` }} /></span>
                  {s.count}×
                </span>
              </div>
            ))}
          </div>
        )}
      </div>
      <Button icon="plus" onClick={() => submit(value)} disabled={submitting || !value.trim()}>{t('common.add')}</Button>
    </div>
  )
}

// "In Kategorie verschieben" popover anchored to a row. An invisible full-screen backdrop captures
// the outside click to close (so re-clicking the trigger can't immediately reopen it).
function CategoryMenu({ current, onPick, onClose }: { current?: string; onPick: (key: string) => void; onClose: () => void }) {
  const { t } = useTranslation()
  return (
    <>
      <div className="hb-menu-backdrop" onClick={onClose} />
      <div className="hb-catmenu" role="menu">
        <div className="hb-catmenu__title">{t('shopping.moveCategory')}</div>
        {CATEGORIES.map((c) => (
          <button
            key={c.key}
            type="button"
            role="menuitemradio"
            aria-checked={c.key === current}
            className={`hb-catmenu__item${c.key === current ? ' is-current' : ''}`}
            onClick={() => onPick(c.key)}
          >
            <span className="em" aria-hidden="true">{c.emoji}</span>
            <span className="hb-catmenu__label">{c.label}</span>
            {c.key === current && <span className="ck"><Icon name="check" size={15} stroke={2.6} /></span>}
          </button>
        ))}
      </div>
    </>
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
