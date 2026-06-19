import { defineConfig, devices } from '@playwright/test'
import path from 'node:path'

/**
 * Playwright config for the README screenshot renderer (issue #300).
 *
 * Separate from web/playwright.config.ts so the renderer never runs as part of
 * the e2e suite (and vice-versa). It:
 *  - serves the real web app (default: a production `vite preview` build, so the
 *    shots match what users see; override with SCREENSHOT_SERVER for dev),
 *  - renders at 1440×920 @2× (2880×1840 px) — the resolution PR #287 used for
 *    web-dashboard.png and the size the README embeds expect,
 *  - pins locale de-DE + Europe/Berlin (the README shots are German).
 *
 * The renderer spec lives next to this file; testDir points back here. Paths are
 * resolved from this file so it can be invoked from web/ (where node_modules and
 * the Playwright browsers live).
 */
// Playwright transpiles this config to CommonJS, so __dirname is available
// (and import.meta is not). This file lives in scripts/screenshots/.
const HERE = __dirname
const WEB_DIR = path.resolve(HERE, '../../web')

const PORT = Number(process.env.SCREENSHOT_PORT ?? 4399)
const BASE_URL = `http://localhost:${PORT}`

// Default: build once + `vite preview` (production output, closest to prod).
// Set SCREENSHOT_SERVER=dev for the hot-reload dev server instead.
const useDev = process.env.SCREENSHOT_SERVER === 'dev'
const serverCommand = useDev
  ? `npm run dev -- --port ${PORT} --strictPort`
  : `npm run build && npm run preview -- --port ${PORT} --strictPort`

export default defineConfig({
  testDir: HERE,
  testMatch: 'render-web.spec.ts',
  // A renderer, not an assertion suite — never silently pass on a flaky run.
  fullyParallel: false,
  retries: 0,
  workers: 1,
  reporter: [['list']],
  timeout: 60_000,
  use: {
    baseURL: BASE_URL,
    ...devices['Desktop Chrome'],
    viewport: { width: 1440, height: 920 },
    deviceScaleFactor: 2,
    locale: 'de-DE',
    timezoneId: 'Europe/Berlin',
    reducedMotion: 'reduce',
  },
  webServer: {
    command: serverCommand,
    cwd: WEB_DIR,
    url: BASE_URL,
    reuseExistingServer: !process.env.CI,
    timeout: 180_000,
  },
})
