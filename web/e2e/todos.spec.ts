import { test, expect, type Page } from '@playwright/test'
import { MockApi, todo, TOKEN } from './helpers/mockApi'

/** Logs in by seeding the token, then installs the given mock backend. */
async function openApp(page: Page, mock: MockApi) {
  await mock.install(page)
  await page.addInitScript((t) => localStorage.setItem('homebase_token', t), TOKEN)
  await page.goto('/')
  await expect(page.getByRole('heading', { name: 'HomeBase — Aufgaben' })).toBeVisible()
}

test.describe('Todos', () => {
  test('renders inbox items from the backend', async ({ page }) => {
    const mock = new MockApi([
      todo({ id: 't1', title: 'Milch kaufen' }),
      todo({ id: 't2', title: 'Spülmaschine ausräumen' }),
    ])
    await openApp(page, mock)

    await expect(page.getByText('Milch kaufen')).toBeVisible()
    await expect(page.getByText('Spülmaschine ausräumen')).toBeVisible()
  })

  test('shows the empty state when the inbox has no items', async ({ page }) => {
    await openApp(page, new MockApi([]))
    await expect(page.getByText('Inbox ist leer')).toBeVisible()
  })

  test('adds a new todo via the FAB', async ({ page }) => {
    await openApp(page, new MockApi([]))

    await page.getByRole('button', { name: 'Neue Aufgabe' }).click()
    await page.getByPlaceholder('Titel…').fill('Pflanzen gießen')
    await page.getByRole('button', { name: 'Hinzufügen' }).click()

    await expect(page.getByText('Pflanzen gießen')).toBeVisible()
    await expect(page.getByText('Inbox ist leer')).toHaveCount(0)
  })

  test('plans an inbox todo, moving it to the Geplant segment', async ({ page }) => {
    const mock = new MockApi([todo({ id: 't1', title: 'Steuer machen' })])
    await openApp(page, mock)

    await page.getByRole('button', { name: 'Planen' }).click()
    const dialog = page.locator('.fixed.inset-0')
    await dialog.getByPlaceholder('z. B. alice').fill('bob')
    await dialog.getByRole('button', { name: 'Planen' }).click()

    // No longer in the Inbox segment...
    await expect(page.getByText('Steuer machen')).toHaveCount(0)

    // ...but present under Geplant.
    await page.getByRole('banner').getByRole('button', { name: /^Geplant/ }).click()
    await expect(page.getByText('Steuer machen')).toBeVisible()
    await expect(page.getByText('👤 bob')).toBeVisible()
  })

  test('completes a planned todo so it appears under Erledigt', async ({ page }) => {
    const mock = new MockApi([
      todo({ id: 't1', title: 'Rechnung zahlen', status: 'PLANNED', assignee: 'alice' }),
    ])
    await openApp(page, mock)

    await page.getByRole('banner').getByRole('button', { name: /^Geplant/ }).click()
    await page
      .locator('li', { hasText: 'Rechnung zahlen' })
      .getByRole('button', { name: 'Erledigt' })
      .click()

    await page.getByRole('banner').getByRole('button', { name: /^Erledigt/ }).click()
    const item = page.getByText('Rechnung zahlen')
    await expect(item).toBeVisible()
    await expect(item).toHaveClass(/line-through/)
  })

  test('deletes a todo from the inbox', async ({ page }) => {
    const mock = new MockApi([
      todo({ id: 't1', title: 'Behalten' }),
      todo({ id: 't2', title: 'Löschen' }),
    ])
    await openApp(page, mock)

    await page
      .locator('li', { hasText: 'Löschen' })
      .getByRole('button', { name: 'Löschen' })
      .click()

    await expect(page.getByText('Löschen')).toHaveCount(0)
    await expect(page.getByText('Behalten')).toBeVisible()
  })

  test('cancelling the add dialog does not create a todo', async ({ page }) => {
    await openApp(page, new MockApi([]))

    await page.getByRole('button', { name: 'Neue Aufgabe' }).click()
    await page.getByPlaceholder('Titel…').fill('Verworfen')
    await page.getByRole('button', { name: 'Abbrechen' }).click()

    await expect(page.getByText('Verworfen')).toHaveCount(0)
    await expect(page.getByText('Inbox ist leer')).toBeVisible()
  })
})

test.describe('Navigation', () => {
  test('switches between the bottom-nav tabs', async ({ page }) => {
    await openApp(page, new MockApi([]))

    await page.getByRole('button', { name: 'Einkaufsliste' }).click()
    await expect(page.getByRole('button', { name: 'Abmelden' })).toBeVisible()

    await page.getByRole('button', { name: 'Rezepte' }).click()
    await expect(page.getByRole('button', { name: 'Abmelden' })).toBeVisible()

    await page.getByRole('button', { name: 'Aufgaben' }).click()
    await expect(page.getByRole('heading', { name: 'HomeBase — Aufgaben' })).toBeVisible()
  })
})
