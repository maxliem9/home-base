import { describe, it, expect, beforeEach } from 'vitest'
import { createWsManager, type WsLike, type WsManager } from './wsConnectionManager'

// A fake WebSocket driven by the test: it records construction args and lets the test fire
// open/message/close manually, so the manager's fan-out / ref-count / reconnect logic is
// exercised without a browser or real network.
class FakeSocket implements WsLike {
  onopen: (() => void) | null = null
  onmessage: ((ev: { data: string }) => void) | null = null
  onclose: (() => void) | null = null
  closed = false
  constructor(public url: string, public token?: string) {}
  close() { this.closed = true }
  fireOpen() { this.onopen?.() }
  fireMessage(data: string) { this.onmessage?.({ data }) }
  fireClose() { this.onclose?.() }
}

describe('wsConnectionManager', () => {
  let sockets: FakeSocket[]
  // A controllable timer so reconnects fire only when the test advances them.
  let pending: Array<{ cb: () => void; ms: number }>
  let mgr: WsManager

  const runTimers = () => {
    const due = pending
    pending = []
    due.forEach((t) => t.cb())
  }

  beforeEach(() => {
    sockets = []
    pending = []
    mgr = createWsManager({
      createSocket: (url, token) => {
        const s = new FakeSocket(url, token)
        sockets.push(s)
        return s
      },
      setTimer: (cb, ms) => {
        const handle = { cb, ms }
        pending.push(handle)
        return handle
      },
      clearTimer: (h) => {
        pending = pending.filter((p) => p !== h)
      },
      reconnectMs: 3000,
    })
  })

  it('opens exactly one socket for many subscribers on the same (url, token)', () => {
    const a: string[] = []
    const b: string[] = []
    mgr.subscribe('/ws/todos', 't', { onMessage: (d) => a.push(d) })
    mgr.subscribe('/ws/todos', 't', { onMessage: (d) => b.push(d) })

    expect(sockets).toHaveLength(1)
    expect(mgr.connectionCount()).toBe(1)

    sockets[0].fireOpen()
    sockets[0].fireMessage('hello')
    expect(a).toEqual(['hello'])
    expect(b).toEqual(['hello'])
  })

  it('uses separate sockets per channel and per token', () => {
    mgr.subscribe('/ws/todos', 't', { onMessage: () => {} })
    mgr.subscribe('/ws/shopping', 't', { onMessage: () => {} })
    mgr.subscribe('/ws/todos', 'other', { onMessage: () => {} })
    expect(mgr.connectionCount()).toBe(3)
  })

  it('fires onOpen for every subscriber on connect and reconnect', () => {
    let aOpens = 0
    let bOpens = 0
    mgr.subscribe('/ws/todos', 't', { onMessage: () => {}, onOpen: () => aOpens++ })
    mgr.subscribe('/ws/todos', 't', { onMessage: () => {}, onOpen: () => bOpens++ })

    sockets[0].fireOpen()
    expect([aOpens, bOpens]).toEqual([1, 1])

    // drop → reconnect → both get onOpen again
    sockets[0].fireClose()
    expect(sockets).toHaveLength(1)
    runTimers()
    expect(sockets).toHaveLength(2)
    sockets[1].fireOpen()
    expect([aOpens, bOpens]).toEqual([2, 2])
  })

  it('gives a late subscriber its onOpen immediately when the connection is already open', () => {
    mgr.subscribe('/ws/todos', 't', { onMessage: () => {} })
    sockets[0].fireOpen()

    let lateOpens = 0
    mgr.subscribe('/ws/todos', 't', { onMessage: () => {}, onOpen: () => lateOpens++ })
    // no second socket, and the one-shot onOpen fired synchronously on subscribe
    expect(sockets).toHaveLength(1)
    expect(lateOpens).toBe(1)
  })

  it('keeps the socket while any subscriber remains, closes it on the last unsubscribe', () => {
    const un1 = mgr.subscribe('/ws/todos', 't', { onMessage: () => {} })
    const un2 = mgr.subscribe('/ws/todos', 't', { onMessage: () => {} })
    sockets[0].fireOpen()

    un1()
    expect(sockets[0].closed).toBe(false)
    expect(mgr.connectionCount()).toBe(1)

    un2()
    expect(sockets[0].closed).toBe(true)
    expect(mgr.connectionCount()).toBe(0)
  })

  it('detaches onclose before closing so no reconnect fires after the last unsubscribe', () => {
    const un = mgr.subscribe('/ws/todos', 't', { onMessage: () => {} })
    sockets[0].fireOpen()
    un()
    // teardown nulled onclose; even a stray close event cannot schedule a reconnect
    expect(sockets[0].onclose).toBeNull()
    sockets[0].fireClose()
    expect(pending).toHaveLength(0)
    runTimers()
    expect(sockets).toHaveLength(1)
  })

  it('cancels a pending reconnect when the last subscriber leaves mid-backoff', () => {
    const un = mgr.subscribe('/ws/todos', 't', { onMessage: () => {} })
    sockets[0].fireOpen()
    sockets[0].fireClose() // schedules a reconnect
    expect(pending).toHaveLength(1)

    un() // last subscriber gone → pending reconnect must be cleared
    expect(pending).toHaveLength(0)
    runTimers()
    expect(sockets).toHaveLength(1)
    expect(mgr.connectionCount()).toBe(0)
  })

  it('stops delivering to a subscriber after it unsubscribes', () => {
    const got: string[] = []
    const un = mgr.subscribe('/ws/todos', 't', { onMessage: (d) => got.push(d) })
    mgr.subscribe('/ws/todos', 't', { onMessage: () => {} })
    sockets[0].fireOpen()
    un()
    sockets[0].fireMessage('after-unsub')
    expect(got).toEqual([])
  })
})
