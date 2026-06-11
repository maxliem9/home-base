import { test, expect, type Page } from '@playwright/test'
import { MockApi, shoppingList, shoppingItem, TOKEN } from './helpers/mockApi'

/** Logs in, installs the mock backend, and navigates to the shopping view. */
async function openShopping(page: Page, mock: MockApi) {
  await mock.install(page)
  await page.addInitScript((t) => localStorage.setItem('homebase_token', t), TOKEN)
  await page.goto('/')
  await page.getByRole('button', { name: 'Einkaufsliste' }).click()
  await expect(page.getByRole('heading', { name: 'Einkaufslisten' })).toBeVisible()
}

const WOCHE = shoppingList({ id: 'sl1', name: 'Wocheneinkauf' })

test.describe('Shopping lists', () => {
  test('shows the no-list empty state when there are no lists', async ({ page }) => {
    await openShopping(page, new MockApi([], [], []))
    await expect(page.getByText('Noch keine Liste')).toBeVisible()
  })

  test('renders items from the active list', async ({ page }) => {
    const mock = new MockApi([], [], [WOCHE], [
      shoppingItem({ id: 'i1', name: 'Äpfel', listId: 'sl1' }),
      shoppingItem({ id: 'i2', name: 'Milch', listId: 'sl1' }),
    ])
    await openShopping(page, mock)

    await expect(page.getByText('Äpfel')).toBeVisible()
    await expect(page.getByText('Milch')).toBeVisible()
  })

  test('adds an item to the active list', async ({ page }) => {
    await openShopping(page, new MockApi([], [], [WOCHE], []))

    await page.getByPlaceholder('Was fehlt in „Wocheneinkauf"? …').fill('Brot')
    await page.getByRole('button', { name: 'Hinzufügen' }).click()

    await expect(page.getByText('Brot')).toBeVisible()
  })

  test('checking an item moves it into the cart section', async ({ page }) => {
    const mock = new MockApi([], [], [WOCHE], [shoppingItem({ id: 'i1', name: 'Butter', listId: 'sl1' })])
    await openShopping(page, mock)

    await page.locator('.hb-row', { hasText: 'Butter' }).getByRole('checkbox').click()

    await expect(page.getByText(/Im Wagen/)).toBeVisible()
    await expect(page.locator('.hb-row--done', { hasText: 'Butter' })).toBeVisible()
  })

  test('switching tabs shows only that list\'s items', async ({ page }) => {
    const mock = new MockApi([], [], [
      shoppingList({ id: 'sl1', name: 'Wocheneinkauf' }),
      shoppingList({ id: 'sl2', name: 'Drogerie' }),
    ], [
      shoppingItem({ id: 'i1', name: 'Äpfel', listId: 'sl1' }),
      shoppingItem({ id: 'i2', name: 'Shampoo', listId: 'sl2' }),
    ])
    await openShopping(page, mock)

    await expect(page.getByText('Äpfel')).toBeVisible()
    await expect(page.getByText('Shampoo')).toHaveCount(0)

    await page.getByRole('tab', { name: 'Drogerie' }).click()
    await expect(page.getByText('Shampoo')).toBeVisible()
    await expect(page.getByText('Äpfel')).toHaveCount(0)
  })

  test('creates a new list via the modal', async ({ page }) => {
    await openShopping(page, new MockApi([], [], [WOCHE], []))

    await page.locator('.hb-tabs').getByRole('button', { name: 'Neue Liste' }).click()
    const modal = page.locator('.hb-modal')
    await modal.getByPlaceholder('z. B. Wocheneinkauf').fill('Baumarkt')
    await modal.getByRole('button', { name: 'Erstellen' }).click()

    const tab = page.locator('.hb-tabs').getByRole('tab', { name: 'Baumarkt' })
    await expect(tab).toBeVisible()
    await expect(tab).toHaveClass(/is-active/)
  })

  test('deletes the active list and its items', async ({ page }) => {
    const mock = new MockApi([], [], [
      shoppingList({ id: 'sl1', name: 'Wocheneinkauf' }),
      shoppingList({ id: 'sl2', name: 'Drogerie' }),
    ], [shoppingItem({ id: 'i2', name: 'Shampoo', listId: 'sl2' })])
    await openShopping(page, mock)

    await page.getByRole('tab', { name: 'Drogerie' }).click()
    await expect(page.getByText('Shampoo')).toBeVisible()

    await page.getByRole('button', { name: /Liste löschen „Drogerie/ }).click()
    // a confirmation modal must appear first — no immediate delete
    await expect(page.getByRole('heading', { name: 'Liste löschen?' })).toBeVisible()
    await page.locator('.hb-modal').getByRole('button', { name: 'Endgültig löschen' }).click()

    await expect(page.locator('.hb-tabs').getByRole('tab', { name: 'Drogerie' })).toHaveCount(0)
    await expect(page.getByText('Shampoo')).toHaveCount(0)
  })
})
