/*
 * HomeBase service worker.
 *
 * Two jobs, kept intentionally small (no build-time manifest, no Workbox — this stays a plain
 * static file so dev and prod behave identically and it's trivially reviewable):
 *
 *   1. Web Push (#429 Phase 2b): receive push messages and surface them as system notifications,
 *      and focus/open the app when one is clicked. Registering it (main.tsx / webpush.ts) is also
 *      what makes the app installable as a PWA.
 *   2. Offline app shell (#519): cache index.html + the hashed static assets so a cold reload with
 *      no connection still boots the SPA — which then renders its own localStorage read-caches
 *      (e.g. the shopping view, #518). Without this the SW never intercepts fetches, so an offline
 *      cold load / evicted tab shows a white screen and the read-cache never gets to run.
 *
 * Caching rules (the invariants that matter):
 *   - NEVER cache /api/* (REST) or the WebSocket — those must always hit the network. Offline they
 *     fail and the app's own read-caches / offline queues handle it. Caching them would serve
 *     stale/broken API data.
 *   - Navigations (SPA routes) → network-first, fall back to the cached index.html. So a new deploy
 *     is always picked up when online, and offline still boots the shell.
 *   - Hashed build output (/assets/*, content-hashed = immutable) and a fixed allow-list of static
 *     files (icons, manifest, favicons) → cache-first. Everything else same-origin (e.g. the vite
 *     dev-server modules under /src, /@vite, /node_modules) is deliberately NOT touched, so HMR and
 *     the Playwright e2e run against `npm run dev` are unaffected.
 *
 * Served from /sw.js at the origin root so its scope covers the whole app.
 */

// Version mitziehen, wenn sich eine der cache-first ausgelieferten Dateien aus
// SHELL_URLS ändert — sonst behalten installierte Clients die alten Assets.
// v2: neues App-Icon/Favicon (Haus-Outline auf Ton-Kachel).
const CACHE = 'homebase-shell-v2'

// Static files we can name up-front (all live in web/public/, copied to the dist root). index.html
// is the SPA entry / navigation fallback. The hashed /assets/* are discovered at install time from
// index.html (see precacheShell) since their names change every build.
const SHELL_URLS = [
  '/',
  '/index.html',
  '/manifest.webmanifest',
  '/favicon.svg',
  '/favicon-16.png',
  '/favicon-32.png',
  '/apple-touch-icon.png',
  '/icon-192.png',
  '/icon-512.png',
  '/maskable-192.png',
  '/maskable-512.png',
  '/app-icon.svg',
  '/app-icon-maskable.svg',
]

// Same-origin paths that are safe to serve cache-first. Hashed build output is immutable; the
// listed static files change rarely and refresh on the next SW version bump. Kept an explicit
// allow-list (rather than "cache any same-origin GET") so the vite dev server's module requests
// are never cached — that would break HMR and could make e2e flaky.
const STATIC_FILES = new Set(SHELL_URLS.filter((u) => u !== '/' && u !== '/index.html'))

function isStaticAsset(url) {
  return url.pathname.startsWith('/assets/') || STATIC_FILES.has(url.pathname)
}

async function precacheShell() {
  const cache = await caches.open(CACHE)
  // Best-effort per URL (allSettled) so one 404/offline entry can't abort the whole precache.
  await Promise.allSettled(SHELL_URLS.map((u) => cache.add(new Request(u, { cache: 'reload' }))))
  // Discover the hashed JS/CSS the current build references and precache them, so the very first
  // offline cold-load already has the app bundle (not only after a second, SW-controlled visit).
  try {
    const res = await fetch('/index.html', { cache: 'reload' })
    if (!res.ok) return
    await cache.put('/index.html', res.clone())
    const html = await res.text()
    const assets = new Set()
    const re = /(?:href|src)=["'](\/assets\/[^"']+)["']/g
    let m
    while ((m = re.exec(html)) !== null) assets.add(m[1])
    await Promise.allSettled([...assets].map((u) => cache.add(new Request(u, { cache: 'reload' }))))
  } catch (_e) {
    // Offline during install — runtime caching (cache-first below) backfills on the next online load.
  }
}

self.addEventListener('install', (event) => {
  // Activate immediately so a freshly registered worker can receive pushes and start serving the
  // shell without waiting for every tab to close.
  self.skipWaiting()
  event.waitUntil(precacheShell())
})

self.addEventListener('activate', (event) => {
  event.waitUntil(
    (async () => {
      // Drop caches from previous SW versions so an old shell can't be served after an update.
      const keys = await caches.keys()
      await Promise.all(
        keys.filter((k) => k.startsWith('homebase-shell-') && k !== CACHE).map((k) => caches.delete(k)),
      )
      await self.clients.claim()
    })(),
  )
})

// Navigations: try the network first (so a new deploy's index.html wins whenever we're online),
// fall back to the cached shell when offline. All SPA routes resolve to index.html server-side, so
// we key the shell under a single '/index.html' entry.
async function networkFirstNavigation(request) {
  const cache = await caches.open(CACHE)
  try {
    const res = await fetch(request)
    if (res && res.ok) cache.put('/index.html', res.clone())
    return res
  } catch (_e) {
    const cached = (await cache.match('/index.html')) || (await cache.match('/'))
    return cached || Response.error()
  }
}

// Hashed assets / known static files: serve from cache, fetch+store on a miss.
async function cacheFirst(request) {
  const cache = await caches.open(CACHE)
  const cached = await cache.match(request)
  if (cached) return cached
  try {
    const res = await fetch(request)
    // Only cache same-origin ('basic') successful responses; never opaque/error responses.
    if (res && res.ok && res.type === 'basic') cache.put(request, res.clone())
    return res
  } catch (_e) {
    return Response.error()
  }
}

self.addEventListener('fetch', (event) => {
  const request = event.request

  // Only GET is cacheable. Writes (POST/PUT/DELETE, incl. /api mutations) always pass through.
  if (request.method !== 'GET') return

  const url = new URL(request.url)

  // Same-origin only — never cache cross-origin responses.
  if (url.origin !== self.location.origin) return

  // Hard invariant: never intercept the API (REST) or the WebSocket upgrade path. They must always
  // hit the network; offline the app's own read-caches / offline queues take over.
  if (url.pathname.startsWith('/api/')) return

  if (request.mode === 'navigate') {
    event.respondWith(networkFirstNavigation(request))
    return
  }

  if (isStaticAsset(url)) {
    event.respondWith(cacheFirst(request))
    return
  }

  // Anything else same-origin (e.g. vite dev modules) is left to the browser — no caching.
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
    // No shared tag: the backend's fire-once guarantee means each todo pushes at most once, so two
    // distinct reminders arriving close together should each stay visible (a shared tag would let
    // the later one replace the earlier).
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
