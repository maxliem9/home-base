/*
 * HomeBase service worker (#429 Phase 2b — Web Push).
 *
 * Deliberately minimal: HomeBase is not an offline-first PWA, so this worker does NOT cache assets
 * or intercept fetches (the nginx SPA + /api proxy handle delivery). Its only job is to receive
 * Web Push messages and surface them as system notifications, and to focus/open the app when one is
 * clicked. Registering it (main.tsx) is also what makes the app installable as a PWA.
 *
 * Served from /sw.js at the origin root so its scope covers the whole app.
 */

self.addEventListener('install', (event) => {
  // Activate immediately so a freshly registered worker can receive pushes without a reload.
  self.skipWaiting()
})

self.addEventListener('activate', (event) => {
  event.waitUntil(self.clients.claim())
})

self.addEventListener('push', (event) => {
  // The backend sends a small JSON payload: { title, body }. Fall back gracefully if the payload
  // is missing or not JSON (e.g. a provider keepalive), so we never throw inside the handler.
  let data = { title: 'HomeBase', body: '' }
  if (event.data) {
    try {
      data = event.data.json()
    } catch (_e) {
      data = { title: 'HomeBase', body: event.data.text() }
    }
  }
  const title = data.title || 'HomeBase'
  const options = {
    body: data.body || '',
    icon: '/icon-192.png',
    badge: '/icon-192.png',
    // Coalesce reminders under one tag so a burst doesn't stack endlessly.
    tag: 'homebase-reminder',
    renotify: true,
  }
  event.waitUntil(self.registration.showNotification(title, options))
})

self.addEventListener('notificationclick', (event) => {
  event.notification.close()
  // Focus an existing HomeBase tab if one is open; otherwise open a new one.
  event.waitUntil(
    self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then((clientList) => {
      for (const client of clientList) {
        if ('focus' in client) return client.focus()
      }
      if (self.clients.openWindow) return self.clients.openWindow('/')
    }),
  )
})
