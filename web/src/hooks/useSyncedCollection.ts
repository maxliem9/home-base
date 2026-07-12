import { useCallback, useEffect, useRef, useState, type Dispatch, type SetStateAction } from 'react'
import { safeFetch, notifyTransportError } from '../api'
import { useWebSocket } from './useWebSocket'

// A synced collection is the data-layer pattern ~10 views hand-rolled identically (#550): fetch the
// list once on mount, drop to login on a 401, surface a transport toast on a rejected fetch, and keep
// the list live off a WebSocket channel with upsert/delete reducers. Copied ten times and locally
// mutated, it was the classic "fixed in view A, still broken in view B" hazard (#517 → #520). This
// hook is the single home for it; views keep their optimistic updates (via the exposed `setItems`)
// and any domain-specific cache. Per-view frames the standard reducer doesn't know (e.g. the
// TODO_LIST_DELETED private-flip, #75) are routed to `onOtherMessage`.

export interface SyncEvents {
  /** WS message type that adds an item (deduped by id — the #61 echo guard). */
  created?: string
  /** WS message type that replaces an item by id. */
  updated?: string
  /** WS message type that removes an item by id. */
  deleted?: string
  /**
   * Where a newly-seen item lands: 'start' (default, prepend — the todos/notes convention) or 'end'
   * (append — collections the server returns oldest-first, e.g. todo lists ordered by createdAt ASC).
   */
  insertAt?: 'start' | 'end'
  /**
   * On an update for an id not currently held: insert it (default true — a create missed while
   * disconnected) or ignore the frame (false — the pre-hook todo-lists reducer only mapped in place).
   */
  upsertOnUpdate?: boolean
}

/** A parsed realtime frame: a `type` plus an optional `payload` (absent on some domain frames). */
export interface SyncMessage<T> {
  type: string
  payload?: T
}

/**
 * Pure reducer for the standard created/updated/deleted frames — no React, no I/O, so the
 * upsert/dedupe semantics are unit-testable on their own. `handled` is false when `msg.type` is none
 * of the configured events, so the hook knows to fall back to `onOtherMessage`.
 */
export function reduceSyncEvent<T extends { id: string }>(
  prev: T[],
  msg: SyncMessage<T>,
  events: SyncEvents,
): { next: T[]; handled: boolean } {
  const p = msg.payload
  const insert = (list: T[], item: T) => (events.insertAt === 'end' ? [...list, item] : [item, ...list])
  if (msg.type === events.created) {
    if (!p) return { next: prev, handled: true }
    // Dedupe: the server echoes our own create back, and the POST response may have added it already.
    return { next: prev.some((x) => x.id === p.id) ? prev : insert(prev, p), handled: true }
  }
  if (msg.type === events.updated) {
    if (!p) return { next: prev, handled: true }
    if (prev.some((x) => x.id === p.id)) {
      return { next: prev.map((x) => (x.id === p.id ? p : x)), handled: true }
    }
    // Not held: upsert it (a create missed while disconnected) unless the view opted out.
    return { next: events.upsertOnUpdate === false ? prev : insert(prev, p), handled: true }
  }
  if (msg.type === events.deleted) {
    if (!p) return { next: prev, handled: true }
    return { next: prev.filter((x) => x.id !== p.id), handled: true }
  }
  return { next: prev, handled: false }
}

export interface SyncedCollectionOptions<T> {
  token: string
  /** REST endpoint returning the full collection as a JSON array. */
  endpoint: string
  /** WebSocket URL for the collection's channel. */
  wsUrl: string
  events: SyncEvents
  onLogout: () => void
  /** Seed items (e.g. from a durable read-cache) so the view can render offline before the fetch lands. */
  initial?: T[]
  /**
   * Normalize a raw item from the REST array or a WS payload before it enters state (e.g. filling
   * fields the server omits under encodeDefaults=false, #96). Applied uniformly to the fetch and to
   * created/updated frames so cached, fetched and pushed items share one shape.
   */
  mapItem?: (raw: unknown) => T
  /**
   * Start with `loading` already false — the view sets this when it seeded `initial` from a warm cache,
   * so no full-screen spinner flashes over content that is already on screen.
   */
  skipInitialLoading?: boolean
  /** Domain frames the standard reducer doesn't own (unknown `type`) — the view handles them here. */
  onOtherMessage?: (msg: SyncMessage<unknown>) => void
}

export interface SyncedCollection<T> {
  items: T[]
  setItems: Dispatch<SetStateAction<T[]>>
  loading: boolean
  /** Re-fetch the whole collection (same 401/transport handling as the mount fetch). */
  refresh: () => Promise<void>
}

export function useSyncedCollection<T extends { id: string }>(
  opts: SyncedCollectionOptions<T>,
): SyncedCollection<T> {
  const { token, endpoint, wsUrl, events, onLogout, initial, skipInitialLoading, onOtherMessage, mapItem } = opts
  const [items, setItems] = useState<T[]>(initial ?? [])
  const [loading, setLoading] = useState(!skipInitialLoading)

  // Hold the reducer inputs in refs so a re-render never churns the WS subscription (its identity must
  // stay stable across renders; only url+token should ever re-subscribe).
  const eventsRef = useRef(events)
  eventsRef.current = events
  const onOtherRef = useRef(onOtherMessage)
  onOtherRef.current = onOtherMessage
  const onLogoutRef = useRef(onLogout)
  onLogoutRef.current = onLogout
  const mapItemRef = useRef(mapItem)
  mapItemRef.current = mapItem

  const refresh = useCallback(async () => {
    try {
      const result = await safeFetch(token, endpoint)
      if (!result.ok) {
        notifyTransportError()
        return
      }
      const { res } = result
      if (res.status === 401) {
        onLogoutRef.current()
        return
      }
      if (res.ok) {
        const raw = (await res.json()) as unknown[]
        const map = mapItemRef.current
        setItems(map ? raw.map(map) : (raw as T[]))
      }
    } finally {
      setLoading(false)
    }
  }, [token, endpoint])

  useEffect(() => {
    refresh()
  }, [refresh])

  useWebSocket({ url: wsUrl, token }, (raw) => {
    let msg: SyncMessage<T>
    try {
      msg = JSON.parse(raw)
    } catch {
      return // ignore malformed frames
    }
    if (!msg || typeof msg.type !== 'string') return
    const ev = eventsRef.current
    // Classify outside the state updater so the updater stays pure (no side effects under StrictMode).
    const isStandard = msg.type === ev.created || msg.type === ev.updated || msg.type === ev.deleted
    if (isStandard) {
      // Normalize a created/updated payload the same way the fetch does (delete carries only an id).
      const map = mapItemRef.current
      const normalized: SyncMessage<T> =
        map && msg.payload != null ? { type: msg.type, payload: map(msg.payload) } : msg
      setItems((prev) => reduceSyncEvent(prev, normalized, ev).next)
    } else {
      onOtherRef.current?.(msg as SyncMessage<unknown>)
    }
  })

  return { items, setItems, loading, refresh }
}
