// Browser Web Push helpers (#429 Phase 2b). Wraps the Service Worker + Push API dance so the
// settings UI stays declarative. All functions are no-ops / report "unsupported" when the browser
// lacks the APIs (older Safari, insecure context) rather than throwing.
//
// Flow to enable on a device:
//   1. register the service worker (/sw.js)
//   2. fetch the server's VAPID public key (GET /push/vapid-public-key)
//   3. request Notification permission
//   4. PushManager.subscribe({ applicationServerKey })
//   5. POST the subscription to /push/subscribe
// Disabling reverses 4/5 (unsubscribe locally + DELETE /push/subscribe).

import { API_BASE, safeFetch } from '../api'

/** Whether this browser can do Web Push at all (service workers + Push API + Notifications). */
export function pushSupported(): boolean {
  return (
    typeof navigator !== 'undefined' &&
    'serviceWorker' in navigator &&
    typeof window !== 'undefined' &&
    'PushManager' in window &&
    'Notification' in window
  )
}

/** Registers (or returns the existing) service worker registration. */
export async function registerServiceWorker(): Promise<ServiceWorkerRegistration> {
  return navigator.serviceWorker.register('/sw.js')
}

/** Current push state for this device: is a subscription active here? */
export async function isSubscribedHere(): Promise<boolean> {
  if (!pushSupported()) return false
  const reg = await navigator.serviceWorker.getRegistration()
  if (!reg) return false
  const sub = await reg.pushManager.getSubscription()
  return sub !== null
}

/** Fetches the server VAPID public key, or null when web push is not configured server-side (404). */
export async function fetchVapidPublicKey(token: string): Promise<string | null> {
  const result = await safeFetch(token, `${API_BASE}/push/vapid-public-key`)
  if (!result.ok || !result.res.ok) return null
  const data: { publicKey?: string } = await result.res.json()
  return data.publicKey ?? null
}

/**
 * Enables push on this device. Returns 'ok', or a reason it couldn't:
 *  - 'unsupported' — the browser lacks the APIs
 *  - 'denied'      — the user blocked notification permission
 *  - 'disabled'    — the server has no VAPID key (web push not configured)
 *  - 'error'       — anything else (network, subscribe failure)
 */
export async function enablePush(token: string): Promise<'ok' | 'unsupported' | 'denied' | 'disabled' | 'error'> {
  if (!pushSupported()) return 'unsupported'
  try {
    const vapidKey = await fetchVapidPublicKey(token)
    if (!vapidKey) return 'disabled'

    const permission = await Notification.requestPermission()
    if (permission !== 'granted') return 'denied'

    const reg = await registerServiceWorker()
    await navigator.serviceWorker.ready

    // Reuse an existing subscription if present, otherwise create one.
    let sub = await reg.pushManager.getSubscription()
    if (!sub) {
      sub = await reg.pushManager.subscribe({
        userVisibleOnly: true,
        applicationServerKey: urlBase64ToArrayBuffer(vapidKey),
      })
    }

    const json = sub.toJSON() as { endpoint?: string; keys?: { p256dh?: string; auth?: string } }
    if (!json.endpoint || !json.keys?.p256dh || !json.keys?.auth) return 'error'

    const result = await safeFetch(token, `${API_BASE}/push/subscribe`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ endpoint: json.endpoint, keys: { p256dh: json.keys.p256dh, auth: json.keys.auth } }),
    })
    if (!result.ok || !result.res.ok) return 'error'
    return 'ok'
  } catch {
    return 'error'
  }
}

/** Disables push on this device: unsubscribes locally and removes the server-side row. */
export async function disablePush(token: string): Promise<'ok' | 'error'> {
  if (!pushSupported()) return 'ok'
  try {
    const reg = await navigator.serviceWorker.getRegistration()
    const sub = await reg?.pushManager.getSubscription()
    if (sub) {
      const endpoint = sub.endpoint
      await sub.unsubscribe()
      await safeFetch(token, `${API_BASE}/push/subscribe`, {
        method: 'DELETE',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ endpoint }),
      })
    }
    return 'ok'
  } catch {
    return 'error'
  }
}

/**
 * Converts a base64url VAPID public key (as the server returns it) to the ArrayBuffer
 * PushManager.subscribe expects for applicationServerKey. Standard helper from the Web Push
 * spec examples; returns a plain ArrayBuffer to satisfy the BufferSource type.
 */
function urlBase64ToArrayBuffer(base64String: string): ArrayBuffer {
  const padding = '='.repeat((4 - (base64String.length % 4)) % 4)
  const base64 = (base64String + padding).replace(/-/g, '+').replace(/_/g, '/')
  const rawData = atob(base64)
  const buffer = new ArrayBuffer(rawData.length)
  const view = new Uint8Array(buffer)
  for (let i = 0; i < rawData.length; ++i) view[i] = rawData.charCodeAt(i)
  return buffer
}
