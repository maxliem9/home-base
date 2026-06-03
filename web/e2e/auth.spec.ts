import { test, expect } from '@playwright/test'
import { MockApi } from './helpers/mockApi'

test.describe('Authentication', () => {
  test('shows the login screen when no token is stored', async ({ page }) => {
    await new MockApi().install(page)
    await page.goto('/')

    await expect(page.getByRole('heading', { name: 'HomeBase' })).toBeVisible()
    await expect(page.getByPlaceholder('Benutzername')).toBeVisible()
    await expect(page.getByPlaceholder('Passwort')).toBeVisible()
  })

  test('Anmelden button is disabled until both fields are filled', async ({ page }) => {
    await new MockApi().install(page)
    await page.goto('/')

    const submit = page.getByRole('button', { name: 'Anmelden' })
    await expect(submit).toBeDisabled()

    await page.getByPlaceholder('Benutzername').fill('alice')
    await expect(submit).toBeDisabled()

    await page.getByPlaceholder('Passwort').fill('secret')
    await expect(submit).toBeEnabled()
  })

  test('logs in and lands on the Aufgaben view', async ({ page }) => {
    await new MockApi().install(page)
    await page.goto('/')

    await page.getByPlaceholder('Benutzername').fill('alice')
    await page.getByPlaceholder('Passwort').fill('secret')
    await page.getByRole('button', { name: 'Anmelden' }).click()

    await expect(page.getByRole('heading', { name: 'HomeBase — Aufgaben' })).toBeVisible()
    // Token persisted for subsequent reloads.
    await expect.poll(() => page.evaluate(() => localStorage.getItem('homebase_token'))).not.toBeNull()
  })

  test('shows an error message on rejected credentials', async ({ page }) => {
    await new MockApi().install(page)
    // Force the login endpoint to fail.
    await page.route('**/api/v1/auth/login', (route) =>
      route.fulfill({ status: 401, contentType: 'application/json', body: '{"message":"nope"}' }),
    )
    await page.goto('/')

    await page.getByPlaceholder('Benutzername').fill('alice')
    await page.getByPlaceholder('Passwort').fill('wrong')
    await page.getByRole('button', { name: 'Anmelden' }).click()

    await expect(page.getByText('Login fehlgeschlagen')).toBeVisible()
  })

  test('logout returns to the login screen and clears the token', async ({ page }) => {
    await new MockApi().install(page)
    await page.addInitScript(() => localStorage.setItem('homebase_token', 'test-jwt-token'))
    await page.goto('/')

    await page.getByRole('button', { name: 'Abmelden' }).click()

    await expect(page.getByPlaceholder('Benutzername')).toBeVisible()
    expect(await page.evaluate(() => localStorage.getItem('homebase_token'))).toBeNull()
  })
})
