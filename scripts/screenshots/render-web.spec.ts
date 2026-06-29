/**
 * Reproducible web screenshot renderer for the README showcase (issue #300).
 *
 * Renders every web view of the *real* app — there is no separate mockup any
 * more (removed in PR #287) — at 1440×920 @2× (2880×1840 px), frameless, into
 * docs/screenshots/. This matches how PR #287 re-rendered web-dashboard.png.
 *
 * It runs on the Playwright *test runner* (so TypeScript "just works" and the
 * dev server is managed by the config's `webServer`), but it is a renderer, not
 * an assertion suite: each `test` drives a view and writes a PNG. The backend is
 * faked with the same in-memory `MockApi` the e2e suite uses, seeded with a
 * realistic German household (./seed.ts), so the app needs no server/DB.
 *
 * Auth: a JWT carrying {username:"max"} goes into localStorage; the app
 * auto-logs-in from it (no login form) and treats "max" as the current user
 * (greeting "Hallo, Max", "lea" as the partner). Dark variants emulate
 * prefers-color-scheme: dark — the default theme is 'system', which resolves to
 * the dark token set.
 *
 * Run via the wrapper (from repo root):  scripts/screenshots/render-web.sh
 * or directly (from web/):  npx playwright test -c ../scripts/screenshots/playwright.screenshots.config.ts
 *
 * Viewport, deviceScaleFactor, locale and timezone come from the config's `use`.
 */
import { test, type Page } from '@playwright/test'
import path from 'node:path'
import { buildMock } from './seed'
import { TOKEN_MAX } from '../../web/e2e/helpers/mockApi'

// Playwright transpiles specs to CommonJS, so __dirname is available. This file
// lives in scripts/screenshots/; the shots go to the repo's docs/screenshots/.
const OUT_DIR = path.resolve(__dirname, '../../docs/screenshots')

// A plain working Wednesday at local noon. Noon keeps the local calendar day
// stable across timezones; a fixed instant keeps "heute/morgen" buckets and the
// running-timer elapsed value deterministic between runs. June so the absence
// fixture's summer entries fall in the opened year.
const NOW = new Date('2026-06-10T12:00:00')

/** Wait until the first matching element is visible (views render fast once reads resolve). */
async function waitFor(page: Page, selector: string, timeout = 15_000) {
  await page.locator(selector).first().waitFor({ state: 'visible', timeout })
}

/** Faked backend + auto-login token, then land on '/'. Mirrors the e2e openX() helpers. */
async function boot(page: Page) {
  await buildMock(NOW).install(page)
  await page.clock.setFixedTime(NOW)
  await page.addInitScript((t) => localStorage.setItem('homebase_token', t), TOKEN_MAX)
  await page.goto('/', { waitUntil: 'domcontentloaded' })
}

/** Settle layout/fonts so text metrics are stable, then write the PNG. */
async function shoot(page: Page, name: string) {
  await page.waitForLoadState('networkidle').catch(() => {})
  await page.waitForTimeout(400)
  await page.screenshot({ path: path.join(OUT_DIR, `${name}.png`) })
}

// Each test renders exactly one shot. Kept as separate tests so a single view
// can be re-rendered with `--grep`, and so one failure doesn't block the rest.
test.describe('web screenshots', () => {
  test('web-dashboard', async ({ page }) => {
    await boot(page)
    await waitFor(page, '.hb-stats')
    await shoot(page, 'web-dashboard')
  })

  test('web-aufgaben', async ({ page }) => {
    await boot(page)
    // Scope to the desktop sidebar: the mobile tabbar renders a second button
    // whose short label is also exactly "Aufgaben" (Einkauf/Zeit differ from the
    // sidebar's full labels, so only this nav collides). Both can be in the a11y
    // tree before the responsive CSS settles → strict-mode violation in CI.
    await page.locator('.hb-nav').getByRole('button', { name: 'Aufgaben' }).click()
    // Land on the "Haushalt" list tab — richest content (due-date groups,
    // priorities, assignees, subtasks).
    const haushalt = page.getByRole('tab', { name: 'Haushalt' })
    if (await haushalt.count()) await haushalt.click()
    await waitFor(page, '.hb-row, .hb-todo')
    await shoot(page, 'web-aufgaben')
  })

  test('web-einkauf', async ({ page }) => {
    await boot(page)
    await page.getByRole('button', { name: 'Einkaufsliste' }).click()
    // The shopping view defaults to tile view since the #440 overhaul (items
    // render as .hb-tile); list view still uses .hb-row — tolerate either.
    await waitFor(page, '.hb-tile, .hb-row')
    await shoot(page, 'web-einkauf')
  })

  test('web-notizen', async ({ page }) => {
    await boot(page)
    await page.getByRole('button', { name: 'Notizen' }).click()
    await waitFor(page, '.hb-noteitem')
    // Select the richest note so the detail pane renders populated Markdown.
    await page.locator('.hb-noteitem', { hasText: 'Urlaubsplanung Sommer' }).click()
    await waitFor(page, '.hb-note-doc__title')
    await shoot(page, 'web-notizen')
  })

  test('web-zeit', async ({ page }) => {
    await boot(page)
    await page.getByRole('button', { name: 'Zeiterfassung' }).click()
    // Running-timer hero, project tiles, day-grouped entry rows.
    await waitFor(page, '.hb-timerhero, .hb-projcard, .hb-row')
    await shoot(page, 'web-zeit')
  })

  test('web-rezepte', async ({ page }) => {
    await boot(page)
    await page.getByRole('button', { name: 'Rezepte' }).click()
    await waitFor(page, '.hb-recipecard')
    await shoot(page, 'web-rezepte')
  })

  test('web-abwesenheit', async ({ page }) => {
    await boot(page)
    await page.getByRole('button', { name: 'Kalender', exact: true }).click()
    await waitFor(page, '.abw-raster, .abw-sumcard')
    await shoot(page, 'web-abwesenheit')
  })
})

// Dark variants — same views, prefers-color-scheme: dark (theme = 'system').
test.describe('web screenshots (dark)', () => {
  test.use({ colorScheme: 'dark' })

  test('web-dashboard-dark', async ({ page }) => {
    await boot(page)
    await waitFor(page, '.hb-stats')
    await shoot(page, 'web-dashboard-dark')
  })

  test('web-abwesenheit-dark', async ({ page }) => {
    await boot(page)
    await page.getByRole('button', { name: 'Kalender', exact: true }).click()
    await waitFor(page, '.abw-raster, .abw-sumcard')
    await shoot(page, 'web-abwesenheit-dark')
  })
})
