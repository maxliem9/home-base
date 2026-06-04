import { test, expect, type Page } from '@playwright/test'
import { MockApi, project, timeEntry, TOKEN } from './helpers/mockApi'

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
    await expect(page.getByText('Kein Timer aktiv')).toBeVisible()
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
    await expect(page.getByText('Kein Timer aktiv')).toBeVisible()
    // the stopped timer now appears in the recent-entries list
    await expect(page.locator('.hb-list .hb-row')).toHaveCount(1)
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

  test('opens the project detail with totals', async ({ page }) => {
    const mock = new MockApi()
      .seedProjects([ARBEIT])
      .seedEntries([
        timeEntry({ id: 'e1', projectId: 'p1', startedAt: '2026-06-03T08:00:00Z', stoppedAt: '2026-06-03T10:00:00Z', durationSeconds: 7200 }),
      ])
    await openTime(page, mock)

    // the project-name button opens the detail (exact: avoids matching "Bearbeiten")
    await page.locator('.hb-projcard', { hasText: 'Arbeit' }).getByRole('button', { name: 'Arbeit', exact: true }).click()
    const modal = page.locator('.hb-modal')
    await expect(modal).toBeVisible()
    await expect(modal).toContainText('2 Std 0 Min') // total
  })
})
