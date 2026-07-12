import { describe, it, expect } from 'vitest'
import { reduceSyncEvent, type SyncEvents } from './useSyncedCollection'

interface Item { id: string; name?: string }
const events: SyncEvents = { created: 'CREATED', updated: 'UPDATED', deleted: 'DELETED' }
const A = { id: 'a', name: 'A' }
const B = { id: 'b', name: 'B' }

describe('reduceSyncEvent', () => {
  it('prepends a created item', () => {
    const r = reduceSyncEvent<Item>([A], { type: 'CREATED', payload: B }, events)
    expect(r.handled).toBe(true)
    expect(r.next).toEqual([B, A])
  })

  it('dedupes a created item already present (the #61 echo guard)', () => {
    const prev = [A]
    const r = reduceSyncEvent<Item>(prev, { type: 'CREATED', payload: { id: 'a', name: 'dup' } }, events)
    expect(r.next).toBe(prev) // same reference — no change
  })

  it('replaces an existing item on update', () => {
    const r = reduceSyncEvent<Item>([A, B], { type: 'UPDATED', payload: { id: 'a', name: 'A2' } }, events)
    expect(r.next).toEqual([{ id: 'a', name: 'A2' }, B])
  })

  it('upserts on update when the item was never seen', () => {
    const r = reduceSyncEvent<Item>([A], { type: 'UPDATED', payload: B }, events)
    expect(r.next).toEqual([B, A])
  })

  it('removes an item on delete', () => {
    const r = reduceSyncEvent<Item>([A, B], { type: 'DELETED', payload: { id: 'a' } }, events)
    expect(r.next).toEqual([B])
  })

  it('reports unknown types as unhandled and leaves the list untouched', () => {
    const prev = [A]
    const r = reduceSyncEvent<Item>(prev, { type: 'SOMETHING_ELSE', payload: B }, events)
    expect(r.handled).toBe(false)
    expect(r.next).toBe(prev)
  })

  it('treats an omitted event name as unknown (never matches a real type)', () => {
    // lists use created+updated only; their delete is the private-flip handled via onOtherMessage.
    const listEvents: SyncEvents = { created: 'TODO_LIST_CREATED', updated: 'TODO_LIST_UPDATED' }
    const r = reduceSyncEvent<Item>([A], { type: 'TODO_LIST_DELETED', payload: A }, listEvents)
    expect(r.handled).toBe(false)
  })

  it('appends a created item when insertAt is end (todo-lists order)', () => {
    const listEvents: SyncEvents = { created: 'CREATED', updated: 'UPDATED', insertAt: 'end' }
    const r = reduceSyncEvent<Item>([A], { type: 'CREATED', payload: B }, listEvents)
    expect(r.next).toEqual([A, B])
  })

  it('applies mergeUpdate to keep local fields over the server echo on update', () => {
    // e.g. an offline shopping check-off: keep local `name` (stand-in), take everything else from server.
    const merge = (incoming: Item, existing: Item): Item => ({ ...incoming, name: existing.name })
    const r = reduceSyncEvent<Item>([{ id: 'a', name: 'local' }], { type: 'UPDATED', payload: { id: 'a', name: 'server' } }, events, merge)
    expect(r.next).toEqual([{ id: 'a', name: 'local' }])
  })

  it('does not upsert an unseen item on update when upsertOnUpdate is false', () => {
    const listEvents: SyncEvents = { created: 'CREATED', updated: 'UPDATED', upsertOnUpdate: false }
    const prev = [A]
    const r = reduceSyncEvent<Item>(prev, { type: 'UPDATED', payload: B }, listEvents)
    expect(r.handled).toBe(true)
    expect(r.next).toBe(prev) // ignored, list untouched
  })

  it('treats a payload-less standard frame as handled without changing the list', () => {
    const prev = [A]
    const r = reduceSyncEvent<Item>(prev, { type: 'CREATED' }, events)
    expect(r.handled).toBe(true)
    expect(r.next).toBe(prev)
  })
})
