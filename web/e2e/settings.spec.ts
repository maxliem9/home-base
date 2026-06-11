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

test.describe('Settings — Konto (#100)', () => {
  async function openKonto(page: Page, mock: MockApi) {
    await openApp(page, mock)
    await page.locator('.hb-sidebar').getByRole('button', { name: 'Einstellungen' }).click()
    await page.locator('.hb-settings-nav').getByRole('button', { name: 'Konto' }).click()
    await expect(page.locator('.hb-settings-body').getByRole('heading', { name: 'Passwort ändern' })).toBeVisible()
  }

  test('changes the password — sends current+new and shows the saved hint', async ({ page }) => {
    await openKonto(page, new MockApi())
    const body = page.locator('.hb-settings-body')
    await body.getByLabel('Aktuelles Passwort').fill('geheim')
    await body.getByLabel('Neues Passwort', { exact: true }).fill('neuespasswort')
    await body.getByLabel('Neues Passwort wiederholen').fill('neuespasswort')

    const reqP = page.waitForRequest((r) => r.url().endsWith('/users/me/password') && r.method() === 'PUT')
    await body.getByRole('button', { name: 'Passwort ändern' }).click()
    expect((await reqP).postDataJSON()).toEqual({ currentPassword: 'geheim', newPassword: 'neuespasswort' })

    await expect(body.getByText('Passwort geändert')).toBeVisible()
  })

  test('surfaces the backend error when the current password is wrong', async ({ page }) => {
    await openKonto(page, new MockApi())
    const body = page.locator('.hb-settings-body')
    await body.getByLabel('Aktuelles Passwort').fill('falsch')
    await body.getByLabel('Neues Passwort', { exact: true }).fill('neuespasswort')
    await body.getByLabel('Neues Passwort wiederholen').fill('neuespasswort')
    await body.getByRole('button', { name: 'Passwort ändern' }).click()
    await expect(body.getByText('Aktuelles Passwort stimmt nicht.')).toBeVisible()
  })

  test('rejects mismatched new passwords client-side, without a request', async ({ page }) => {
    await openKonto(page, new MockApi())
    const body = page.locator('.hb-settings-body')
    let putFired = false
    page.on('request', (r) => { if (r.url().endsWith('/users/me/password')) putFired = true })
    await body.getByLabel('Aktuelles Passwort').fill('geheim')
    await body.getByLabel('Neues Passwort', { exact: true }).fill('neuespasswort')
    await body.getByLabel('Neues Passwort wiederholen').fill('anderspasswort')
    await body.getByRole('button', { name: 'Passwort ändern' }).click()
    await expect(body.getByText('Die neuen Passwörter stimmen nicht überein.')).toBeVisible()
    expect(putFired).toBe(false)
  })
})

test.describe('Settings — Benachrichtigungen (#100)', () => {
  async function openDigest(page: Page, mock: MockApi) {
    await openApp(page, mock)
    await page.locator('.hb-sidebar').getByRole('button', { name: 'Einstellungen' }).click()
    await page.locator('.hb-settings-nav').getByRole('button', { name: 'Benachrichtigungen' }).click()
    await expect(page.locator('.hb-settings-body').getByRole('heading', { name: 'Telegram-Digest' })).toBeVisible()
  }

  test('shows the digest time (+ inactive note) and saves a change', async ({ page }) => {
    await openDigest(page, new MockApi())
    const body = page.locator('.hb-settings-body')

    // the mock reports Telegram disabled → the inactive note shows, but the time is editable
    await expect(body.getByText(/Telegram ist nicht konfiguriert/)).toBeVisible()
    await expect(body.getByLabel('Uhrzeit')).toHaveValue('20:00')

    await body.getByLabel('Uhrzeit').fill('07:30')
    const reqP = page.waitForRequest((r) => r.url().endsWith('/config/digest') && r.method() === 'PUT')
    await body.getByRole('button', { name: 'Speichern' }).click()
    expect((await reqP).postDataJSON()).toEqual({ time: '07:30' })
    await expect(body.getByText('Gespeichert')).toBeVisible()
  })
})
