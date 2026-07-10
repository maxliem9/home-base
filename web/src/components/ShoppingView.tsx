import { useState, useEffect, useCallback, useRef, useMemo, useId } from 'react'
import { useTranslation } from 'react-i18next'
import { API_BASE, errorCode, notifyTransportError, safeFetch } from '../api'
import { errorText } from '../i18n'
import { ShoppingCategory, ShoppingCategoryRule, ShoppingItem, ShoppingList, ShoppingSuggestion, ShoppingTemplate } from '../types'
import { useWebSocket } from '../hooks/useWebSocket'
import { Icon } from '../ui/Icon'
import { useErrorToast } from '../ui/ErrorToast'
import { Button, Card, Checkbox, EmptyState, Field, IconButton, Modal, PageHead, Sheet, TextInput } from '../ui/primitives'
import { TemplatesSheet, ApplyTemplateSheet } from './ShoppingTemplates'
import { CategoriesCard, RulesCard } from './settings/ShoppingCategoriesSettings'
import { itemDisplayParts, splitQuantity } from './shoppingQuantity'
import {
  BUILTIN_CATEGORIES,
  categoryMeta,
  CategoryIcon,
  groupByCategory,
  iconMatchesQuery,
  ItemIcon,
  ITEM_ICON_CHOICES,
  DEFAULT_ITEM_ICON,
} from './shoppingCategories'

const WS_SCHEME = window.location.protocol === 'https:' ? 'wss' : 'ws'
const WS_URL = import.meta.env.VITE_WS_URL_SHOPPING ?? `${WS_SCHEME}://${window.location.host}/api/v1/ws/shopping`

// Offline-resilient check-offs: tapping a checkbox in a store with flaky/no wifi
// must not silently lose the change. Each toggle is mirrored into a small, durable
// queue (keyed by item id, latest desired state wins) that survives a reload and is
// retried on every connectivity signal until it lands. The item shows a "not synced"
// marker until then. Keyed by item UUID, so it's user-agnostic across one browser.
const PENDING_KEY = 'homebase_shopping_pending'
const FLUSH_INTERVAL_MS = 15000

// List vs. tile view (#440), persisted per browser. Tiles are the design default (Bring-style).
const VIEWMODE_KEY = 'homebase_shopping_viewmode'
type ViewMode = 'list' | 'tiles'
function loadViewMode(): ViewMode {
  try {
    return localStorage.getItem(VIEWMODE_KEY) === 'list' ? 'list' : 'tiles'
  } catch {
    return 'tiles'
  }
}

// Offline read-cache (#517): mirror the last-loaded lists + items so a launch/reload while the API is
// unreachable shows the previous state instead of an empty screen — the read-side twin of the
// check-off queue above. Best-effort; keyed by browser, not user (single account per browser).
// NB: fully offline the SPA shell itself may not load (the service worker is push-only, not an
// asset cache) — this covers the flaky-connection case where the shell is served from browser cache
// but the /api fetch fails, and gives an instant first paint online. True offline-shell → #519.
const CACHE_KEY = 'homebase_shopping_cache'

interface ShoppingCache { lists: ShoppingList[]; items: ShoppingItem[] }

function loadCache(): ShoppingCache | null {
  try {
    const raw = localStorage.getItem(CACHE_KEY)
    if (!raw) return null
    const parsed = JSON.parse(raw) as Partial<ShoppingCache>
    return { lists: parsed.lists ?? [], items: parsed.items ?? [] }
  } catch {
    return null // private-mode / corrupt value → no seed
  }
}

function saveCache(lists: ShoppingList[], items: ShoppingItem[]) {
  try {
    localStorage.setItem(CACHE_KEY, JSON.stringify({ lists, items }))
  } catch {
    /* quota / private mode — the in-memory state still works for this session */
  }
}

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
  // Seed from the durable read-cache (#517) so a launch with a flaky/absent connection shows the last
  // known lists + items instead of an empty screen; a successful fetch replaces them below. Read once
  // (useMemo, not a per-render localStorage hit) — it only feeds the initial state below.
  const initialCache = useMemo(() => loadCache(), [])
  const [items, setItems] = useState<ShoppingItem[]>(initialCache?.items ?? [])
  const [lists, setLists] = useState<ShoppingList[]>(initialCache?.lists ?? [])
  // Live editable category catalog (#411), seeded with the builtins so headers render before the fetch.
  const [categories, setCategories] = useState<ShoppingCategory[]>(BUILTIN_CATEGORIES)
  // Skip the full-screen spinner when we already have cached lists to show — refresh happens underneath.
  const [loading, setLoading] = useState(!(initialCache && initialCache.lists.length > 0))
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
  const [viewMode, setViewMode] = useState<ViewMode>(loadViewMode)
  const [editItem, setEditItem] = useState<ShoppingItem | null>(null)
  // Per-list category manager open (#412), for the active list's own set.
  const [manageCats, setManageCats] = useState(false)
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

  // Preload the most-used items for the quick-add autocomplete; filtered client-side as the user
  // types (#389). Scoped to the active list (#412) so a list with its own categories gets its own
  // scope's category mapping. Non-fatal — an empty list just means no suggestions are shown.
  const fetchSuggestions = useCallback(async () => {
    const url = activeId ? `${API_BASE}/shopping/suggestions?listId=${activeId}` : `${API_BASE}/shopping/suggestions`
    const result = await safeFetch(token, url)
    if (!result.ok || !result.res.ok) return
    setSuggestions((await result.res.json()) as ShoppingSuggestion[])
  }, [token, activeId])

  useEffect(() => { void fetchSuggestions() }, [fetchSuggestions])

  // Editable category catalog (#411): managed under Settings; reloaded here on a category change so
  // grouping headers + the "move to category" menu reflect edits/additions/deletions. Scoped to the
  // active list (#412): a list with `ownCategories` renders its own set (custom + shared „Sonstiges"),
  // every other list the shared household catalog.
  const fetchCategories = useCallback(async () => {
    const url = activeId ? `${API_BASE}/shopping/categories?listId=${activeId}` : `${API_BASE}/shopping/categories`
    const result = await safeFetch(token, url)
    if (!result.ok || !result.res.ok) return
    setCategories((await result.res.json()) as ShoppingCategory[])
  }, [token, activeId])

  useEffect(() => { void fetchCategories() }, [fetchCategories])

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

  // Mirror the current lists + items into the durable read-cache (#517) on every change — server
  // fetches and optimistic edits alike — so the next launch can show the last state offline. The
  // initial run re-writes the seeded cache (harmless); it never wipes it, since the state was seeded
  // from that same cache rather than starting empty.
  useEffect(() => {
    saveCache(lists, items)
  }, [lists, items])

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
        // A category edit/delete changes headers and reassigns items to OTHER (#411) — reload both.
        case 'SHOPPING_CATEGORY_CHANGED':
          void fetchCategories()
          void fetchAll()
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
    // Split a leading "<qty> <unit>" off the typed name (#445): "200 g Mehl" → name "Mehl" + quantity
    // "200 g". requireUnit so a bare leading number ("3 Musketiere") is NOT torn apart on persist.
    const { title, detail } = splitQuantity(name, true)
    try {
      const result = await safeFetch(token, `${API_BASE}/shopping`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name: title, listId: active.id, ...(detail ? { quantity: detail } : {}) }),
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

  // Save item-detail edits (#445): name, free-text quantity, note. Sends "" to clear quantity/note
  // (backend: null = unchanged, "" = clear). Optimistic from the returned DTO; refetch on failure.
  const saveItemDetails = async (
    item: ShoppingItem,
    fields: { name: string; quantity: string; note: string; icon?: string },
  ): Promise<boolean> => {
    const name = fields.name.trim()
    if (!name) return false
    // icon (#442/#508): a chosen svg-basename override, or "" to clear it (backend: null = unchanged,
    // "" = clear → fall back to auto-resolution). Omitted (undefined) when the picker wasn't touched.
    const body: Record<string, string> = { name, quantity: fields.quantity.trim(), note: fields.note.trim() }
    if (fields.icon !== undefined) body.icon = fields.icon
    const result = await safeFetch(token, `${API_BASE}/shopping/${item.id}`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    })
    if (!result.ok) {
      await fetchAll()
      flashError(errorText(null, t('shopping.editFailed')))
      return false
    }
    const { res } = result
    if (res.status === 401) {
      onLogout()
      return false
    }
    if (!res.ok) {
      await fetchAll()
      flashError(errorText(await errorCode(res), t('shopping.editFailed')))
      return false
    }
    const updated: ShoppingItem = await res.json()
    setItems((prev) => prev.map((i) => (i.id === updated.id ? updated : i)))
    return true
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

  // Toggle whether the active list uses its OWN category set instead of the shared catalog (#412).
  // Optimistic; on success refetch the scoped categories so the grouping headers update at once.
  // Non-destructive either way — the backend keeps a list's custom categories when reverting.
  const toggleOwnCategories = async (next: boolean) => {
    if (!active) return
    setLists((prev) => prev.map((l) => (l.id === active.id ? { ...l, ownCategories: next } : l)))
    if (!next) setManageCats(false)
    const result = await safeFetch(token, `${API_BASE}/shopping/lists/${active.id}`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ ownCategories: next }),
    })
    if (!result.ok) {
      await fetchAll()
      return flashError(errorText(null, t('shopping.listUpdateFailed')))
    }
    const { res } = result
    if (res.status === 401) return onLogout()
    if (!res.ok) {
      await fetchAll()
      return flashError(errorText(await errorCode(res), t('shopping.listUpdateFailed')))
    }
    const updated: ShoppingList = await res.json()
    setLists((prev) => prev.map((l) => (l.id === updated.id ? updated : l)))
    void fetchCategories()
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
  const groups = groupByCategory(open, categories)

  const changeViewMode = (m: ViewMode) => {
    setViewMode(m)
    try {
      localStorage.setItem(VIEWMODE_KEY, m)
    } catch {
      /* private mode — in-memory state still switches for this session */
    }
  }

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
            categories={categories}
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

          {(open.length > 0 || checked.length > 0) && (
            <div className="hb-viewtoggle" role="group" aria-label={t('shopping.viewToggleAria')}>
              <button
                type="button"
                className={`hb-viewtoggle__btn${viewMode === 'list' ? ' is-active' : ''}`}
                aria-pressed={viewMode === 'list'}
                aria-label={t('shopping.viewList')}
                title={t('shopping.viewList')}
                onClick={() => changeViewMode('list')}
              >
                <Icon name="list" size={18} stroke={2} />
              </button>
              <button
                type="button"
                className={`hb-viewtoggle__btn${viewMode === 'tiles' ? ' is-active' : ''}`}
                aria-pressed={viewMode === 'tiles'}
                aria-label={t('shopping.viewTiles')}
                title={t('shopping.viewTiles')}
                onClick={() => changeViewMode('tiles')}
              >
                <Icon name="grid" size={18} stroke={2} />
              </button>
            </div>
          )}

          {open.length === 0 && checked.length === 0 ? (
            <Card className="hb-card--pad"><EmptyState icon="cart" title={t('shopping.emptyTitle')} hint={t('shopping.emptyHint')} /></Card>
          ) : open.length === 0 ? (
            <Card className="hb-card--pad"><div className="hb-muted" style={{ padding: '8px 4px', fontSize: 14 }}>{t('shopping.allChecked')}</div></Card>
          ) : viewMode === 'tiles' ? (
            <div className="hb-tilewrap">
              {groups.map((group) => (
                <section key={group.category.key} className="hb-tilesection">
                  <div className="hb-cardhead hb-tilesection__head">
                    <span className="hb-cathead">
                      <CategoryIcon
                        catKey={group.category.key}
                        emoji={group.category.emoji}
                        className="hb-cathead__emoji"
                      />
                      {group.category.label}
                    </span>
                    <span className="hb-catcount">{group.items.length}</span>
                  </div>
                  <div className="hb-tilegrid">
                    {group.items.map((item) => {
                      const sq = itemDisplayParts(item)
                      return (
                        <div key={item.id} className="hb-tile">
                          <button
                            type="button"
                            className="hb-tile__check-btn"
                            onClick={() => toggleChecked(item)}
                            aria-label={t('shopping.checkOff', { name: item.name })}
                          >
                            <span className="hb-tile__iconwrap">
                              <ItemIcon item={item} variant="tile" />
                            </span>
                            <span className="hb-tile__name">{sq.title}</span>
                            {sq.detail && <span className="hb-tile__detail">{sq.detail}</span>}
                            {item.note && <span className="hb-tile__note">{item.note}</span>}
                          </button>
                          {pending[item.id] ? (
                            <span className="hb-tile__sync" title={t('shopping.notSynced')} aria-label={t('shopping.notSynced')}>
                              <Icon name="repeat" size={12} stroke={2} />
                            </span>
                          ) : (
                            <button
                              type="button"
                              className="hb-tile__edit"
                              aria-label={t('shopping.editItem', { name: item.name })}
                              onClick={() => setEditItem(item)}
                            >
                              <Icon name="edit" size={14} stroke={2} />
                            </button>
                          )}
                        </div>
                      )
                    })}
                  </div>
                </section>
              ))}
            </div>
          ) : (
            <div className="hb-shop-grid">
              {groups.map((group) => (
                <Card key={group.category.key} className="hb-card--pad">
                  <div className="hb-cardhead">
                    <span className="hb-cathead">
                      <CategoryIcon
                        catKey={group.category.key}
                        emoji={group.category.emoji}
                        className="hb-cathead__emoji"
                      />
                      {group.category.label}
                    </span>
                    <span className="hb-catcount">{group.items.length}</span>
                  </div>
                  <div className="hb-list">
                    {group.items.map((item) => {
                      const parts = itemDisplayParts(item)
                      return (
                      <div key={item.id} className="hb-row" style={{ padding: '11px 4px' }}>
                        <Checkbox checked={false} onChange={() => toggleChecked(item)} />
                        <ItemIcon item={item} />
                        <div className="hb-row__main">
                          <div className="hb-row__title">
                            {parts.title}
                            {parts.detail && <span className="hb-row__qty">{parts.detail}</span>}
                          </div>
                          {item.note && <div className="hb-row__note">{item.note}</div>}
                        </div>
                        <div className="hb-row__right">
                          {pending[item.id] && (
                            <span className="hb-syncbadge" title={t('shopping.notSynced')} aria-label={t('shopping.notSynced')}>
                              <Icon name="repeat" size={13} stroke={2} />
                            </span>
                          )}
                          <div className="hb-row__actions">
                            <IconButton icon="edit" label={t('shopping.editItem', { name: item.name })} onClick={() => setEditItem(item)} />
                            <IconButton id={`hb-move-${item.id}`} icon="tag" label={t('shopping.moveCategory')} onClick={() => setMenuFor(menuFor === item.id ? null : item.id)} />
                            <IconButton icon="trash" label={t('common.delete')} danger onClick={() => handleDelete(item.id)} />
                          </div>
                          {menuFor === item.id && (
                            <CategoryMenu triggerId={`hb-move-${item.id}`} current={item.category} categories={categories} onPick={(key) => moveItem(item, key)} onClose={() => setMenuFor(null)} />
                          )}
                        </div>
                      </div>
                      )
                    })}
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
              {viewMode === 'tiles' ? (
                <div className="hb-tilegrid">
                  {checked.map((item) => {
                    const sq = itemDisplayParts(item)
                    return (
                      <button
                        key={item.id}
                        type="button"
                        className="hb-tile hb-tile--done"
                        onClick={() => toggleChecked(item)}
                        aria-label={t('shopping.uncheck', { name: item.name })}
                      >
                        <span className="hb-tile__check" aria-hidden="true">
                          <Icon name="check" size={13} stroke={2.6} />
                        </span>
                        <span className="hb-tile__iconwrap">
                          <ItemIcon item={item} muted variant="tile" />
                        </span>
                        <span className="hb-tile__name">{sq.title}</span>
                        {sq.detail && <span className="hb-tile__detail">{sq.detail}</span>}
                        {item.note && <span className="hb-tile__note">{item.note}</span>}
                      </button>
                    )
                  })}
                </div>
              ) : (
                <Card className="hb-card--pad" style={{ paddingTop: 6, paddingBottom: 6 }}>
                  <div className="hb-list">
                    {checked.map((item) => {
                      const parts = itemDisplayParts(item)
                      return (
                      <div key={item.id} className="hb-row hb-row--done" style={{ padding: '10px 4px' }}>
                        <Checkbox checked onChange={() => toggleChecked(item)} />
                        <ItemIcon item={item} muted />
                        <div className="hb-row__main">
                          <div className="hb-row__title">
                            {parts.title}
                            {parts.detail && <span className="hb-row__qty">{parts.detail}</span>}
                          </div>
                          {item.note && <div className="hb-row__note">{item.note}</div>}
                        </div>
                        {pending[item.id] && (
                          <span className="hb-syncbadge" title={t('shopping.notSynced')} aria-label={t('shopping.notSynced')}>
                            <Icon name="repeat" size={13} stroke={2} />
                          </span>
                        )}
                      </div>
                      )
                    })}
                  </div>
                </Card>
              )}
            </div>
          )}

          {/* Per-list options (#412): own category set + management, grouped with the delete action. */}
          <div style={{ marginTop: 26, paddingTop: 18, borderTop: '1px solid var(--line)', display: 'grid', gap: 12 }}>
            <div style={{ display: 'flex', alignItems: 'flex-start', gap: 12, flexWrap: 'wrap' }}>
              <Checkbox checked={!!active.ownCategories} onChange={(v) => toggleOwnCategories(v)} />
              <div style={{ flex: 1, minWidth: 200 }}>
                <div
                  style={{ fontSize: 14, fontWeight: 500, cursor: 'pointer' }}
                  onClick={() => toggleOwnCategories(!active.ownCategories)}
                >
                  {t('shopping.ownCategories')}
                </div>
                <div className="hb-muted" style={{ fontSize: 13 }}>{t('shopping.ownCategoriesHint')}</div>
              </div>
              {active.ownCategories && (
                <Button size="sm" variant="ghost" icon="tag" onClick={() => setManageCats(true)}>{t('shopping.manageCategories')}</Button>
              )}
            </div>
            {lists.length > 1 && (
              <button className="hb-link hb-link--danger" style={{ display: 'block' }} onClick={() => setConfirmDeleteList(true)}>
                <Icon name="trash" size={14} stroke={2} style={{ verticalAlign: '-2px', marginRight: 5 }} />
                {t('shopping.deleteListNamed', { name: active.name })}
              </button>
            )}
          </div>
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

      {manageCats && active?.ownCategories && (
        <ListCategoriesSheet
          token={token}
          listId={active.id}
          listName={active.name}
          onLogout={onLogout}
          onClose={() => setManageCats(false)}
        />
      )}

      {editItem && (
        <EditItemSheet
          item={editItem}
          onClose={() => setEditItem(null)}
          onSave={saveItemDetails}
        />
      )}

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
  value, onChange, onAdd, suggestions, categories, placeholder, submitting,
}: {
  value: string
  onChange: (v: string) => void
  onAdd: (name: string) => void
  suggestions: ShoppingSuggestion[]
  categories: ShoppingCategory[]
  placeholder: string
  submitting: boolean
}) {
  const { t } = useTranslation()
  const [focused, setFocused] = useState(false)
  const [acIndex, setAcIndex] = useState(0)
  const wrapRef = useRef<HTMLDivElement>(null)
  // Stable ids tying the combobox input to its listbox + the active option (aria-activedescendant).
  const acId = useId()
  const listboxId = `${acId}-listbox`
  const optionId = (i: number) => `${acId}-opt-${i}`

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
            role="combobox"
            aria-expanded={open}
            aria-controls={open ? listboxId : undefined}
            aria-autocomplete="list"
            aria-activedescendant={open && matches[acIndex] ? optionId(acIndex) : undefined}
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
          <div className="hb-ac" role="listbox" id={listboxId} aria-label={t('shopping.suggestionsHint')}>
            <div className="hb-ac__hint" aria-hidden="true">
              <Icon name="sparkle" size={13} stroke={2} style={{ color: 'var(--accent)' }} />
              {t('shopping.suggestionsHint')}
            </div>
            {matches.map((s, i) => (
              <div
                key={s.name}
                id={optionId(i)}
                role="option"
                aria-selected={i === acIndex}
                className={`hb-ac__item${i === acIndex ? ' is-active' : ''}`}
                onMouseEnter={() => setAcIndex(i)}
                onMouseDown={(e) => { e.preventDefault(); submit(s.name) }}
              >
                <span className="hb-ac__emoji" aria-hidden="true">{s.icon || DEFAULT_ITEM_ICON}</span>
                <span className="hb-ac__name">{highlight(s.name)}</span>
                <span className="hb-ac__cat">{categoryMeta(s.category, categories).label}</span>
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
function CategoryMenu({ triggerId, current, categories, onPick, onClose }: { triggerId: string; current?: string; categories: ShoppingCategory[]; onPick: (key: string) => void; onClose: () => void }) {
  const { t } = useTranslation()
  const titleId = useId()
  const itemRefs = useRef<(HTMLButtonElement | null)[]>([])
  // Open with focus on the current category (or the first item if none is set yet).
  const currentIndex = Math.max(0, categories.findIndex((c) => c.key === current))
  const [focusIndex, setFocusIndex] = useState(currentIndex)

  // Return focus to the trigger once the menu closes. Resolved by id (not a captured node):
  // picking a category regroups the item into a different card, remounting the trigger — this
  // passive cleanup runs after that commit, so the (possibly new) trigger node is found by id.
  useEffect(() => () => document.getElementById(triggerId)?.focus(), [triggerId])

  // Move real DOM focus to the active item — on open to the current/first, then as the user arrows.
  useEffect(() => { itemRefs.current[focusIndex]?.focus() }, [focusIndex])

  return (
    <>
      <div className="hb-menu-backdrop" onClick={onClose} />
      <div
        className="hb-catmenu"
        role="menu"
        aria-labelledby={titleId}
        onKeyDown={(e) => {
          if (e.key === 'ArrowDown') { e.preventDefault(); setFocusIndex((i) => Math.min(i + 1, categories.length - 1)) }
          else if (e.key === 'ArrowUp') { e.preventDefault(); setFocusIndex((i) => Math.max(i - 1, 0)) }
          else if (e.key === 'Home') { e.preventDefault(); setFocusIndex(0) }
          else if (e.key === 'End') { e.preventDefault(); setFocusIndex(categories.length - 1) }
          else if (e.key === 'Escape') { e.preventDefault(); onClose() }
        }}
      >
        <div className="hb-catmenu__title" id={titleId}>{t('shopping.moveCategory')}</div>
        {categories.map((c, i) => (
          <button
            key={c.key}
            ref={(el) => { itemRefs.current[i] = el }}
            type="button"
            role="menuitemradio"
            aria-checked={c.key === current}
            tabIndex={i === focusIndex ? 0 : -1}
            className={`hb-catmenu__item${c.key === current ? ' is-current' : ''}`}
            onClick={() => onPick(c.key)}
          >
            <CategoryIcon catKey={c.key} emoji={c.emoji} className="em" />
            <span className="hb-catmenu__label">{c.label}</span>
            {c.key === current && <span className="ck"><Icon name="check" size={15} stroke={2.6} /></span>}
          </button>
        ))}
      </div>
    </>
  )
}

// The set of pickable svg-basenames, to tell a real icon override from a legacy emoji in `item.icon`
// (only a basename is a removable override; #508/#511).
const ITEM_ICON_KEYS = new Set(ITEM_ICON_CHOICES.map((c) => c.key))

// Edit an item's name + free-text details (#445). A Sheet per the Modal-vs-Sheet guideline (#29):
// a small multi-field form with mobile relevance. Empty quantity/note are sent as "" to clear.
function EditItemSheet({
  item,
  onClose,
  onSave,
}: {
  item: ShoppingItem
  onClose: () => void
  onSave: (
    item: ShoppingItem,
    fields: { name: string; quantity: string; note: string; icon?: string },
  ) => Promise<boolean>
}) {
  const { t } = useTranslation()
  const [name, setName] = useState(item.name)
  const [quantity, setQuantity] = useState(item.quantity ?? '')
  const [note, setNote] = useState(item.note ?? '')
  // iconKey (#442/#508): undefined = untouched, '' = clear the override, else the chosen basename.
  const [iconKey, setIconKey] = useState<string | undefined>(undefined)
  const [pickerOpen, setPickerOpen] = useState(false)
  const [busy, setBusy] = useState(false)

  const save = async () => {
    if (!name.trim() || busy) return
    setBusy(true)
    const ok = await onSave(item, { name, quantity, note, icon: iconKey })
    setBusy(false)
    if (ok) onClose()
  }

  // The override that would take effect: the pending pick (or its cleared state), else the item's own.
  const effectiveIcon = iconKey === undefined ? item.icon : iconKey || undefined
  const hasOverride = !!effectiveIcon && ITEM_ICON_KEYS.has(effectiveIcon)
  // Render the current (or freshly chosen) icon; '' (cleared) falls back to name-based resolution.
  const previewItem = iconKey === undefined ? item : { ...item, icon: iconKey || undefined }

  return (
    <Sheet
      open
      onClose={onClose}
      title={t('shopping.editItem', { name: item.name })}
      width={440}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>{t('common.cancel')}</Button>
          <Button variant="primary" icon="check" onClick={save} disabled={!name.trim() || busy}>{t('common.save')}</Button>
        </>
      }
    >
      <Field label={t('shopping.fieldName')}>
        <TextInput
          value={name}
          onChange={setName}
          autoFocus
          onKeyDown={(e) => { if (e.key === 'Enter') save() }}
        />
      </Field>
      <Field label={t('shopping.fieldIcon')} group>
        <div className="hb-iconfield">
          <span className="hb-iconfield__preview"><ItemIcon item={previewItem} variant="tile" /></span>
          <Button variant="ghost" icon="image" onClick={() => setPickerOpen(true)}>{t('shopping.chooseIcon')}</Button>
          {hasOverride && (
            <Button variant="ghost" icon="x" onClick={() => setIconKey('')}>{t('shopping.resetIcon')}</Button>
          )}
        </div>
      </Field>
      {pickerOpen && (
        <IconPicker
          current={iconKey ?? item.icon}
          onPick={(k) => { setIconKey(k); setPickerOpen(false) }}
          onClose={() => setPickerOpen(false)}
        />
      )}
      <Field label={t('shopping.fieldQuantity')} hint={t('shopping.fieldQuantityHint')}>
        <TextInput
          value={quantity}
          onChange={setQuantity}
          placeholder={t('shopping.fieldQuantityPlaceholder')}
          onKeyDown={(e) => { if (e.key === 'Enter') save() }}
        />
      </Field>
      <Field label={t('shopping.fieldNote')}>
        <TextInput
          value={note}
          onChange={setNote}
          placeholder={t('shopping.fieldNotePlaceholder')}
          onKeyDown={(e) => { if (e.key === 'Enter') save() }}
        />
      </Field>
    </Sheet>
  )
}

// Visual icon picker (#442): a searchable grid of the designed item icons. Returns the chosen svg
// basename, stored as the item's icon override. Search matches the English key and German names.
function IconPicker({
  current,
  onPick,
  onClose,
}: {
  current?: string
  onPick: (key: string) => void
  onClose: () => void
}) {
  const { t } = useTranslation()
  const [q, setQ] = useState('')
  const matches = useMemo(() => ITEM_ICON_CHOICES.filter((c) => iconMatchesQuery(c.key, q)), [q])
  return (
    <Modal open onClose={onClose} title={t('shopping.chooseIcon')} width={460}>
      <TextInput value={q} onChange={setQ} placeholder={t('shopping.iconSearch')} autoFocus />
      {matches.length === 0 ? (
        <p className="hb-muted" style={{ margin: '14px 4px', fontSize: 14 }}>{t('shopping.iconNoMatch')}</p>
      ) : (
        <div className="hb-iconpicker">
          {matches.map((c) => (
            <button
              key={c.key}
              type="button"
              className={`hb-iconpicker__item${c.key === current ? ' is-current' : ''}`}
              title={c.key}
              aria-label={c.key}
              aria-pressed={c.key === current}
              onClick={() => onPick(c.key)}
            >
              <img src={c.url} alt="" />
            </button>
          ))}
        </div>
      )}
    </Modal>
  )
}

// Per-list category manager (#412): a Sheet reusing the Settings CategoriesCard, scoped to this list.
// Only the list's OWN categories are editable here (custom rows); „Sonstiges" (the shared OTHER
// fallback) stays managed household-wide under Settings and is never shown as editable.
function ListCategoriesSheet({ token, listId, listName, onLogout, onClose }: {
  token: string
  listId: string
  listName: string
  onLogout: () => void
  onClose: () => void
}) {
  const { t } = useTranslation()
  const [cats, setCats] = useState<ShoppingCategory[]>([])
  const [rules, setRules] = useState<ShoppingCategoryRule[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  const fetchCats = useCallback(async () => {
    const result = await safeFetch(token, `${API_BASE}/shopping/categories?listId=${listId}`)
    if (!result.ok) return notifyTransportError()
    if (result.res.status === 401) return onLogout()
    if (result.res.ok) setCats((await result.res.json()) as ShoppingCategory[])
  }, [token, listId, onLogout])

  const fetchRules = useCallback(async () => {
    const result = await safeFetch(token, `${API_BASE}/shopping/category-rules?listId=${listId}`)
    if (!result.ok) return notifyTransportError()
    if (result.res.status === 401) return onLogout()
    if (result.res.ok) setRules((await result.res.json()) as ShoppingCategoryRule[])
  }, [token, listId, onLogout])

  useEffect(() => { void Promise.all([fetchCats(), fetchRules()]).finally(() => setLoading(false)) }, [fetchCats, fetchRules])

  // Live updates ride the shared "shopping" channel (a partner's edit) — refetch on the broadcast.
  useWebSocket({ url: WS_URL, token }, (raw) => {
    try {
      const type = JSON.parse(raw).type
      if (type === 'SHOPPING_CATEGORY_CHANGED') void fetchCats()
      else if (type === 'SHOPPING_CATEGORY_RULE_CHANGED') void fetchRules()
    } catch {
      // ignore malformed frames
    }
  })

  const own = cats.filter((c) => c.listId === listId)

  return (
    <Sheet open onClose={onClose} title={t('shopping.manageCategoriesTitle', { name: listName })} width={560}>
      <CategoriesCard
        token={token}
        onLogout={onLogout}
        categories={own}
        loading={loading}
        onChanged={fetchCats}
        onError={setError}
        listId={listId}
        title={t('shopping.ownCategoriesCardTitle')}
        hint={t('shopping.ownCategoriesCardHint')}
      />
      <div style={{ height: 16 }} />
      <RulesCard
        token={token}
        onLogout={onLogout}
        categories={cats}
        rules={rules}
        loading={loading}
        onChanged={fetchRules}
        onError={setError}
        listId={listId}
        title={t('shopping.ownRulesCardTitle')}
        hint={t('shopping.ownRulesCardHint')}
      />
      {error && (
        <div className="hb-toast hb-toast--error" role="alert" style={{ marginTop: 12 }}>
          <Icon name="x" size={18} stroke={2.4} />
          {error}
        </div>
      )}
    </Sheet>
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
