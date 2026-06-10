import { test, expect, type Page } from '@playwright/test'
import { MockApi, project, timeEntry, workTarget, TOKEN } from './helpers/mockApi'

/** Logs in, installs the mock backend, and navigates to the time view. */
async function openTime(page: Page, mock: MockApi) {
  await mock.install(page)
  await page.addInitScript((t) => localStorage.setItem('homebase_token', t), TOKEN)
  await page.goto('/')
  await page.getByRole('button', { name: 'Zeiterfassung' }).click()
  await expect(page.getByRole('heading', { name: 'Zeiterfassung' })).toBeVisible()
}

const ARBEIT = project({ id: 'p1', name: 'Arbeit', color: '#4F7A52' })

test.describe('Time tracking', () => {
  test('shows the empty states with no projects', async ({ page }) => {
    await openTime(page, new MockApi())
    await expect(page.getByText('Noch keine Projekte')).toBeVisible()
    await expect(page.locator('.hb-timerhero__live')).toHaveText('Kein Timer aktiv')
  })

  test('renders projects and recent entries', async ({ page }) => {
    const mock = new MockApi()
      .seedProjects([ARBEIT])
      .seedEntries([timeEntry({ id: 'e1', projectId: 'p1', description: 'Meeting', durationSeconds: 3600 })])
    await openTime(page, mock)

    await expect(page.locator('.hb-projcard', { hasText: 'Arbeit' })).toBeVisible()
    await expect(page.getByText('Meeting')).toBeVisible()
  })

  test('starts a timer from a project and stops it', async ({ page }) => {
    await openTime(page, new MockApi().seedProjects([ARBEIT]))

    await page.locator('.hb-projcard', { hasText: 'Arbeit' }).getByRole('button', { name: 'Start' }).click()

    // hero switches to the running state (driven entirely by the WS frame)
    const hero = page.locator('.hb-timerhero')
    await expect(hero).toHaveClass(/is-running/)
    const stop = hero.getByRole('button', { name: 'Stoppen' })
    await expect(stop).toBeVisible()

    await stop.click()
    await expect(page.locator('.hb-timerhero__live')).toHaveText('Kein Timer aktiv')
    // the stopped timer now appears in the recent-entries list
    await expect(page.locator('.hb-list .hb-row')).toHaveCount(1)
  })

  // Regression: TimeView must reflect its own writes from the REST response, not
  // wait for the WebSocket echo. silenceRealtime() drops every WS frame, mirroring
  // a deployment whose realtime echo never reaches the originating client — the
  // exact symptom where a started timer / new entry only appeared after a reload.
  test('reflects writes from REST even when the realtime echo never arrives', async ({ page }) => {
    await openTime(page, new MockApi().seedProjects([ARBEIT]).silenceRealtime())

    // start a timer → hero must switch to running with no WS frame
    await page.locator('.hb-projcard', { hasText: 'Arbeit' }).getByRole('button', { name: 'Start' }).click()
    const hero = page.locator('.hb-timerhero')
    await expect(hero).toHaveClass(/is-running/)

    // stop it → it lands in the recent list (one row, no duplicate)
    await hero.getByRole('button', { name: 'Stoppen' }).click()
    await expect(page.locator('.hb-timerhero__live')).toHaveText('Kein Timer aktiv')
    await expect(page.locator('.hb-list .hb-row')).toHaveCount(1)

    // record a manual entry → appears immediately
    await page.getByRole('button', { name: 'Eintrag erfassen' }).click()
    const modal = page.locator('.hb-modal')
    await modal.getByRole('button', { name: 'Speichern' }).click()
    await expect(modal).toBeHidden()
    await expect(page.locator('.hb-list .hb-row')).toHaveCount(2)
  })

  test('creates a project', async ({ page }) => {
    await openTime(page, new MockApi())

    await page.getByRole('button', { name: 'Neues Projekt' }).click()
    const modal = page.locator('.hb-modal')
    await modal.getByPlaceholder('Projektname…').fill('Garten')
    await modal.getByRole('button', { name: 'Anlegen' }).click()

    await expect(page.locator('.hb-projcard', { hasText: 'Garten' })).toBeVisible()
  })

  test('records a manual entry', async ({ page }) => {
    await openTime(page, new MockApi().seedProjects([ARBEIT]))

    await page.getByRole('button', { name: 'Eintrag erfassen' }).click()
    const modal = page.locator('.hb-modal')
    // defaults: today, 09:00–10:00 → a 1-hour entry
    await modal.getByRole('button', { name: 'Speichern' }).click()
    await expect(modal).toBeHidden()

    await expect(page.locator('.hb-list .hb-row')).toHaveCount(1)
    await expect(page.locator('.hb-list .hb-row')).toContainText('1 Std 0 Min')
  })

  test('rejects a manual entry whose end is before its start', async ({ page }) => {
    await openTime(page, new MockApi().seedProjects([ARBEIT]))

    await page.getByRole('button', { name: 'Eintrag erfassen' }).click()
    const modal = page.locator('.hb-modal')
    await modal.getByLabel('Von').fill('10:00')
    await modal.getByLabel('Bis').fill('09:00')
    await modal.getByRole('button', { name: 'Speichern' }).click()

    await expect(modal.getByText('Ende muss nach dem Start liegen')).toBeVisible()
  })

  test('archives a project and reveals it again', async ({ page }) => {
    await openTime(page, new MockApi().seedProjects([ARBEIT]))

    await page.locator('.hb-projcard', { hasText: 'Arbeit' }).getByRole('button', { name: 'Archivieren' }).click()
    // archived projects are hidden until toggled on
    await expect(page.locator('.hb-projcard', { hasText: 'Arbeit' })).toHaveCount(0)

    await page.getByRole('button', { name: 'Archivierte anzeigen' }).click()
    await expect(page.locator('.hb-projcard', { hasText: 'Arbeit' })).toBeVisible()
  })

  test('opens the project detail page with totals and returns to the overview', async ({ page }) => {
    const mock = new MockApi()
      .seedProjects([ARBEIT])
      .seedEntries([
        timeEntry({ id: 'e1', projectId: 'p1', startedAt: '2026-06-03T08:00:00Z', stoppedAt: '2026-06-03T10:00:00Z', durationSeconds: 7200 }),
      ])
    await openTime(page, mock)

    // the project-name button opens the detail as its own page, not a modal (#32):
    // the project name becomes the page heading and no modal layer is present.
    await page.locator('.hb-projcard', { hasText: 'Arbeit' }).getByRole('button', { name: 'Arbeit', exact: true }).click()
    await expect(page.getByRole('heading', { name: 'Arbeit' })).toBeVisible()
    await expect(page.locator('.hb-modal')).toHaveCount(0)
    await expect(page.locator('.hb-detailpage')).toContainText('2 Std 0 Min') // total

    // the back button returns to the projects overview
    await page.getByRole('button', { name: 'Zurück' }).click()
    await expect(page.getByRole('heading', { name: 'Zeiterfassung' })).toBeVisible()
    await expect(page.locator('.hb-projcard', { hasText: 'Arbeit' })).toBeVisible()
  })

  // --- Wochensoll & Forecast (#31) ---------------------------------------

  test('shows the expected end at the running timer once a Wochensoll exists', async ({ page }) => {
    const mock = new MockApi()
      .seedProjects([ARBEIT])
      .seedTargets([workTarget({ userId: 'alice', projectId: 'p1', weeklyHours: 40, isDefault: true })])
      .seedEntries([timeEntry({ id: 'e1', projectId: 'p1', startedAt: new Date().toISOString(), stoppedAt: undefined, durationSeconds: undefined })])
    await openTime(page, mock)

    // hero is running and carries the forecast line (on weekends the daily target
    // is 0, so the projected end collapses into "Tagessoll erreicht")
    await expect(page.locator('.hb-timerhero')).toHaveClass(/is-running/)
    await expect(page.locator('.hb-timerhero__eta')).toBeVisible()
    await expect(page.locator('.hb-timerhero__eta')).toHaveText(/Voraussichtlich fertig um \d{1,2}:\d{2}|Tagessoll erreicht/)
  })

  test('shows the week balance card with soll, ist and per-project saldo', async ({ page }) => {
    const mock = new MockApi()
      .seedProjects([ARBEIT])
      .seedTargets([workTarget({ userId: 'alice', projectId: 'p1', weeklyHours: 40, isDefault: true })])
    await openTime(page, mock)

    const card = page.locator('.hb-weektargets')
    await expect(card.getByRole('heading', { name: 'Wochensoll' })).toBeVisible()
    // nothing recorded yet → 0:00 of 40:00, the full week still open
    await expect(card).toContainText('0:00 / 40:00')
    await expect(card).toContainText('noch 40:00')
    // per-project saldo row for the targeted project
    await expect(card.locator('.hb-weektarget__proj', { hasText: 'Arbeit' })).toContainText('-40:00')
  })

  test('configures a weekly target through the Wochensoll modal', async ({ page }) => {
    await openTime(page, new MockApi().seedProjects([ARBEIT]))

    // no targets yet → no week card, only the page-head button
    await expect(page.locator('.hb-weektargets')).toHaveCount(0)
    await page.getByRole('button', { name: 'Wochensoll' }).click()
    const modal = page.locator('.hb-modal')
    await expect(modal.getByText('Wochensoll konfigurieren')).toBeVisible()

    // 40 hours on Arbeit for Max, who also gets it as default project
    await modal.getByLabel('Std/Woche Arbeit Max').fill('40')
    await modal.getByLabel('Standard Arbeit Max').check()
    const requestPromise = page.waitForRequest((r) => r.url().includes('/time/targets/max/p1') && r.method() === 'PUT')
    await modal.getByRole('button', { name: 'Speichern' }).click()
    const request = await requestPromise
    expect(request.postDataJSON()).toEqual({ weeklyHours: 40, isDefault: true })

    // the modal closes and the freshly configured week balance appears
    await expect(modal).toBeHidden()
    const card = page.locator('.hb-weektargets')
    await expect(card).toBeVisible()
    await expect(card).toContainText('Max')
    await expect(card).toContainText('/ 40:00')
  })

  test('rejects invalid weekly hours inline', async ({ page }) => {
    await openTime(page, new MockApi().seedProjects([ARBEIT]))

    await page.getByRole('button', { name: 'Wochensoll' }).click()
    const modal = page.locator('.hb-modal')
    await modal.getByLabel('Std/Woche Arbeit Max').fill('200')
    await modal.getByRole('button', { name: 'Speichern' }).click()
    await expect(modal.getByText('Stunden müssen zwischen 0 und 168 liegen')).toBeVisible()
  })

  test('exports entries as a CSV download with the server-supplied filename', async ({ page }) => {
    const mock = new MockApi()
      .seedProjects([ARBEIT])
      .seedEntries([timeEntry({ id: 'e1', projectId: 'p1', description: 'Meeting', durationSeconds: 5400 })])
    await openTime(page, mock)

    await page.getByRole('button', { name: 'CSV-Export' }).click()
    const modal = page.locator('.hb-modal')
    await expect(modal).toBeVisible()

    // Clicking export must trigger a real browser download whose name is parsed
    // from the response's Content-Disposition header (unfiltered → default name).
    const downloadPromise = page.waitForEvent('download')
    await modal.getByRole('button', { name: 'Exportieren' }).click()
    const download = await downloadPromise

    expect(download.suggestedFilename()).toBe('zeiterfassung_export.csv')
    // the filter modal closes once the download is kicked off
    await expect(modal).toBeHidden()
  })

  test('scopes the export to the selected project and date range', async ({ page }) => {
    const mock = new MockApi()
      .seedProjects([
        project({ id: 'p1', name: 'ProjektEins', color: '#4F7A52' }),
        project({ id: 'p2', name: 'ProjektZwei', color: '#B4654A' }),
      ])
      .seedEntries([
        timeEntry({ id: 'e1', projectId: 'p1', startedAt: '2026-06-10T08:00:00Z', stoppedAt: '2026-06-10T09:00:00Z' }),
        timeEntry({ id: 'e2', projectId: 'p2', startedAt: '2026-06-10T10:00:00Z', stoppedAt: '2026-06-10T11:00:00Z' }),
      ])
    await openTime(page, mock)

    await page.getByRole('button', { name: 'CSV-Export' }).click()
    const modal = page.locator('.hb-modal')
    await modal.getByLabel('Von').fill('2026-06-01')
    await modal.getByLabel('Bis').fill('2026-06-30')
    await modal.locator('select').selectOption({ label: 'ProjektEins' })

    const requestPromise = page.waitForRequest((r) => r.url().includes('/time/export.csv'))
    const downloadPromise = page.waitForEvent('download')
    await modal.getByRole('button', { name: 'Exportieren' }).click()
    const [request, download] = await Promise.all([requestPromise, downloadPromise])

    // The modal inputs become query params: the chosen project + an ISO instant
    // range (the backend reuses the entry-list filters to scope the rows).
    const params = new URL(request.url()).searchParams
    expect(params.get('project_id')).toBe('p1')
    const from = params.get('from')
    const to = params.get('to')
    expect(from).toBeTruthy()
    expect(to).toBeTruthy()
    expect(new Date(from!).getTime()).toBeLessThan(new Date(to!).getTime())
    // and the ranged filename from the server header is applied (zone-independent shape)
    expect(download.suggestedFilename()).toMatch(/^zeiterfassung_\d{4}-\d{2}-\d{2}_\d{4}-\d{2}-\d{2}\.csv$/)
  })
})
