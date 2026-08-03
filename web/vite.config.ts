import { execFileSync } from 'node:child_process'
import { readFileSync } from 'node:fs'
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// Build-Version (#626). Single Source of Truth ist die Datei VERSION im Repo-Root; im
// Docker-Build liegt der Root nicht im Build-Context (context: ./web), deshalb reicht CI die
// Werte als Build-Args → env APP_VERSION/GIT_SHA durch (env hat darum Vorrang vor der Datei).
// Die Werte landen per `define` als Konstanten im Bundle — kein Runtime-Fetch nötig.
function appVersion(): string {
  const fromEnv = process.env.APP_VERSION?.trim()
  if (fromEnv) return fromEnv
  try {
    return readFileSync(new URL('../VERSION', import.meta.url), 'utf8').trim() || '0.0.0-dev'
  } catch {
    return '0.0.0-dev'
  }
}

function appCommit(): string {
  const fromEnv = process.env.GIT_SHA?.trim()
  if (fromEnv) return fromEnv.slice(0, 7)
  try {
    return execFileSync('git', ['rev-parse', 'HEAD'], { stdio: ['ignore', 'pipe', 'ignore'] })
      .toString()
      .trim()
      .slice(0, 7)
  } catch {
    // Build ohne Git-Kontext (Tarball, Docker) — Commit bleibt leer, rein informativ.
    return ''
  }
}

export default defineConfig({
  plugins: [react()],
  define: {
    __APP_VERSION__: JSON.stringify(appVersion()),
    __APP_COMMIT__: JSON.stringify(appCommit()),
  },
  server: {
    port: 5173,
    proxy: {
      // Proxies both HTTP requests and WebSocket upgrades (/api/v1/ws/*) to the
      // backend. ws: true is required, otherwise the WS upgrade is dropped and
      // live-sync stays dead in dev (issue #376).
      '/api': { target: 'http://localhost:8080', changeOrigin: true, ws: true },
    },
  },
  build: {
    // The app chunk (~510 KB / ~123 KB gzip) is deliberately kept whole: HomeBase is offline-first,
    // so the service worker (#519) precaches the entire entry graph up front — route-level lazy chunks
    // would need extra plumbing to stay offline-available and buy little for a 2-user app on a warm
    // cache. So we split only the stable third-party libs (below) and raise the size warning to match.
    chunkSizeWarningLimit: 600,
    rollupOptions: {
      output: {
        // Split react + i18next into their own hashed chunks. They stay statically imported, so they
        // remain in the entry's module graph → the offline SW precaches them exactly as before
        // (offline unaffected). The win is cache churn: an app-code change only re-hashes the app
        // chunk, so each deploy re-downloads ~195 KB less by keeping the rarely-changing vendor chunks
        // cached; the chunks also download in parallel.
        manualChunks(id) {
          if (id.includes('node_modules')) {
            if (id.includes('/react') || id.includes('/scheduler/')) return 'react-vendor'
            if (id.includes('i18next')) return 'i18n-vendor'
          }
        },
      },
    },
  },
})
