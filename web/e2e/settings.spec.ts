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

  test('theme selector persists the choice and applies data-theme on <html> (#100)', async ({ page }) => {
    await openKonto(page, new MockApi())
    const body = page.locator('.hb-settings-body')
    const html = page.locator('html')

    // the Darstellung card is on the Konto page; default starts at the index.html light
    await expect(body.getByRole('heading', { name: 'Darstellung' })).toBeVisible()
    await expect(html).toHaveAttribute('data-theme', 'light')

    // picking "Dunkel" PUTs the pref and flips the document immediately
    const reqP = page.waitForRequest((r) => r.url().endsWith('/user-prefs/theme') && r.method() === 'PUT')
    await body.getByRole('tab', { name: 'Dunkel', exact: true }).click()
    expect((await reqP).postDataJSON()).toEqual({ value: 'dark' })
    await expect(html).toHaveAttribute('data-theme', 'dark')

    // switching back to "Hell" persists + applies again
    const reqP2 = page.waitForRequest((r) => r.url().endsWith('/user-prefs/theme') && r.method() === 'PUT')
    await body.getByRole('tab', { name: 'Hell', exact: true }).click()
    expect((await reqP2).postDataJSON()).toEqual({ value: 'light' })
    await expect(html).toHaveAttribute('data-theme', 'light')
  })

  test('a stored dark theme is applied on load (#100)', async ({ page }) => {
    // the mock serves theme=dark from /user-prefs, so the app should resolve to dark
    const mock = new MockApi()
    await mock.install(page)
    await page.route('**/api/v1/user-prefs', (route) => {
      if (route.request().method() === 'GET') {
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ theme: 'dark' }) })
      }
      return route.fallback()
    })
    await page.addInitScript((t) => localStorage.setItem('homebase_token', t), TOKEN)
    await page.goto('/')
    await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark')
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
    // scope to the digest card — the page now also has a recurring-todo card with its own time
    const card = page.locator('.hb-settings-body .hb-card', { hasText: 'Telegram-Digest' })

    // the mock reports Telegram disabled → the inactive note shows, but the time is editable
    await expect(card.getByText(/Telegram ist nicht konfiguriert/)).toBeVisible()
    await expect(card.getByLabel('Uhrzeit', { exact: true })).toHaveValue('20:00')

    await card.getByLabel('Uhrzeit', { exact: true }).fill('07:30')
    const reqP = page.waitForRequest((r) => r.url().endsWith('/config/digest') && r.method() === 'PUT')
    await card.getByRole('button', { name: 'Speichern' }).click()
    expect((await reqP).postDataJSON()).toEqual({ time: '07:30' })
    await expect(card.getByText('Gespeichert')).toBeVisible()
  })

  test('shows the recurring-todo run time and saves a change (#100)', async ({ page }) => {
    await openDigest(page, new MockApi())
    // the recurring-todo card sits in the same Benachrichtigungen page, below the digest card
    const card = page.locator('.hb-settings-body .hb-card', { hasText: 'Wiederholungs-Planer' })
    await expect(card).toBeVisible()
    await expect(card.getByLabel('Uhrzeit für wiederkehrende Aufgaben')).toHaveValue('00:30')

    await card.getByLabel('Uhrzeit für wiederkehrende Aufgaben').fill('05:45')
    const reqP = page.waitForRequest((r) => r.url().endsWith('/config/recurring') && r.method() === 'PUT')
    await card.getByRole('button', { name: 'Speichern' }).click()
    expect((await reqP).postDataJSON()).toEqual({ time: '05:45' })
    await expect(card.getByText('Gespeichert')).toBeVisible()
  })
})
