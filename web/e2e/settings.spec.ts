import { test, expect, type Page } from '@playwright/test'
import { MockApi, TOKEN } from './helpers/mockApi'

/** Logs in and lands on the dashboard with the mock backend installed. */
async function openApp(page: Page, mock: MockApi) {
  await mock.install(page)
  await page.addInitScript((t) => localStorage.setItem('homebase_token', t), TOKEN)
  await page.goto('/')
}

test.describe('Settings — Haushalt (#100)', () => {
  test('the gear opens the Haushalt subpage; renaming updates the sidebar brand live', async ({ page }) => {
    await openApp(page, new MockApi())

    // the brand starts at the seeded household name (mock default)
    const brand = page.locator('.hb-sidebar .hb-brand__sub')
    await expect(brand).toHaveText('Mäxchen')

    // the account-corner gear opens settings on the Haushalt subpage by default
    await page.locator('.hb-sidebar').getByRole('button', { name: 'Einstellungen' }).click()
    const body = page.locator('.hb-settings-body')
    await expect(body.getByRole('heading', { name: 'Haushaltsname' })).toBeVisible()

    await body.getByLabel('Name').fill('Familie Test')
    const reqP = page.waitForRequest((r) => r.url().endsWith('/api/v1/config') && r.method() === 'PUT')
    await body.getByRole('button', { name: 'Speichern' }).click()
    expect((await reqP).postDataJSON()).toEqual({ householdName: 'Familie Test' })

    // the live brand updates without a reload, and the saved hint appears
    await expect(brand).toHaveText('Familie Test')
    await expect(body.getByText('Gespeichert')).toBeVisible()
  })

  test('the Zeiterfassung subpage is still reachable from the sub-rail', async ({ page }) => {
    await openApp(page, new MockApi())
    await page.locator('.hb-sidebar').getByRole('button', { name: 'Einstellungen' }).click()
    await page.locator('.hb-settings-nav').getByRole('button', { name: 'Zeiterfassung' }).click()
    await expect(page.locator('.hb-settings-body').getByRole('heading', { name: 'Projekte' })).toBeVisible()
  })
})
