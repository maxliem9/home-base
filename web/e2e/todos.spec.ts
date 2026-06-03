import { test, expect, type Page } from '@playwright/test'
import { MockApi, todo, TOKEN } from './helpers/mockApi'

/** Logs in by seeding the token, then installs the given mock backend. */
async function openApp(page: Page, mock: MockApi) {
  await mock.install(page)
  await page.addInitScript((t) => localStorage.setItem('homebase_token', t), TOKEN)
  await page.goto('/')
  await expect(page.getByRole('heading', { name: 'Aufgaben' })).toBeVisible()
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

  test('adds a new todo via the quick-add field', async ({ page }) => {
    await openApp(page, new MockApi([]))

    await page.getByPlaceholder('Aufgabe hinzufügen …').fill('Pflanzen gießen')
    await page.getByRole('button', { name: 'Hinzufügen' }).click()

    await expect(page.getByText('Pflanzen gießen')).toBeVisible()
    await expect(page.getByText('Inbox ist leer')).toHaveCount(0)
  })

  test('plans an inbox todo, moving it to the Geplant segment', async ({ page }) => {
    const mock = new MockApi([todo({ id: 't1', title: 'Steuer machen' })])
    await openApp(page, mock)

    await page.getByRole('button', { name: 'Planen' }).click()
    const dialog = page.locator('.hb-modal')
    await dialog.getByPlaceholder('z. B. max').fill('bob')
    await dialog.getByRole('button', { name: 'Planen' }).click()

    // No longer in the Inbox segment...
    await expect(page.getByText('Steuer machen')).toHaveCount(0)

    // ...but present under Geplant.
    await page.getByRole('tab', { name: /^Geplant/ }).click()
    const row = page.locator('.hb-row', { hasText: 'Steuer machen' })
    await expect(row).toBeVisible()
    await expect(row.getByText('bob')).toBeVisible()
  })

  test('completes a planned todo so it appears under Erledigt', async ({ page }) => {
    const mock = new MockApi([
      todo({ id: 't1', title: 'Rechnung zahlen', status: 'PLANNED', assignee: 'alice' }),
    ])
    await openApp(page, mock)

    await page.getByRole('tab', { name: /^Geplant/ }).click()
    // Completing a todo is the row checkbox in the redesign.
    await page.locator('.hb-row', { hasText: 'Rechnung zahlen' }).getByRole('checkbox').click()

    await page.getByRole('tab', { name: /^Erledigt/ }).click()
    const doneRow = page.locator('.hb-row--done', { hasText: 'Rechnung zahlen' })
    await expect(doneRow).toBeVisible()
  })

  test('deletes a todo from the inbox', async ({ page }) => {
    const mock = new MockApi([
      todo({ id: 't1', title: 'Behalten' }),
      todo({ id: 't2', title: 'Löschen' }),
    ])
    await openApp(page, mock)

    await page
      .locator('.hb-row', { hasText: 'Löschen' })
      .getByRole('button', { name: 'Löschen' })
      .click()

    await expect(page.getByText('Löschen')).toHaveCount(0)
    await expect(page.getByText('Behalten')).toBeVisible()
  })

  test('typing in quick-add without submitting does not create a todo', async ({ page }) => {
    await openApp(page, new MockApi([]))

    // Type a title but never press Add/Enter — nothing should be created.
    await page.getByPlaceholder('Aufgabe hinzufügen …').fill('Verworfen')

    await expect(page.getByText('Verworfen')).toHaveCount(0)
    await expect(page.getByText('Inbox ist leer')).toBeVisible()
  })
})

test.describe('Navigation', () => {
  test('switches between the bottom-nav tabs', async ({ page }) => {
    await openApp(page, new MockApi([]))

    await page.getByRole('button', { name: 'Einkaufsliste' }).click()
    await expect(page.getByRole('heading', { name: 'Einkaufsliste' })).toBeVisible()

    await page.getByRole('button', { name: 'Rezepte' }).click()
    await expect(page.getByRole('heading', { name: 'Rezepte' })).toBeVisible()

    await page.getByRole('button', { name: 'Aufgaben' }).click()
    await expect(page.getByRole('heading', { name: 'Aufgaben' })).toBeVisible()
  })
})
