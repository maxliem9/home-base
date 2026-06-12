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

  test('puts the most recently checked item on top of the cart', async ({ page }) => {
    const mock = new MockApi([], [], [WOCHE], [
      // already in the cart, checked a while ago
      shoppingItem({ id: 'i1', name: 'Alt', listId: 'sl1', checked: true, checkedAt: '2026-06-10T08:00:00Z' }),
      shoppingItem({ id: 'i2', name: 'Neu', listId: 'sl1' }),
    ])
    await openShopping(page, mock)

    // check "Neu" now → its checkedAt is newer than "Alt"s
    await page.locator('.hb-row', { hasText: 'Neu' }).getByRole('checkbox').click()

    const cart = page.locator('.hb-row--done')
    await expect(cart).toHaveCount(2)
    await expect(cart.first()).toContainText('Neu') // last checked sits on top
    await expect(cart.last()).toContainText('Alt')
  })

  // Issue: ticking items in a store with no wifi must not silently lose them.
  test('remembers a check made offline and syncs it when back online', async ({ page }) => {
    const mock = new MockApi([], [], [WOCHE], [shoppingItem({ id: 'i1', name: 'Milch', listId: 'sl1' })])
    await openShopping(page, mock)

    // simulate no wifi: every shopping-item write is dropped before it reaches the backend
    await page.route('**/api/v1/shopping/**', (route) => {
      if (route.request().method() === 'PUT') return route.abort('internetdisconnected')
      return route.fallback()
    })

    await page.locator('.hb-row', { hasText: 'Milch' }).getByRole('checkbox').click()

    // optimistic: it moves into the cart and is NOT reverted…
    await expect(page.locator('.hb-row--done', { hasText: 'Milch' })).toBeVisible()
    // …but it's clearly flagged as not-yet-synced (item badge + banner), never silently dropped
    await expect(page.locator('.hb-syncbar')).toContainText('nachgeholt')
    await expect(page.locator('.hb-row--done', { hasText: 'Milch' }).locator('.hb-syncbadge')).toBeVisible()
    // and it's durably queued in localStorage, so it survives a reload / app close
    await expect
      .poll(() => page.evaluate(() => localStorage.getItem('homebase_shopping_pending')))
      .toContain('i1')

    // back online → "Jetzt versuchen" drains the queue
    await page.unroute('**/api/v1/shopping/**')
    const putPromise = page.waitForRequest((r) => r.url().includes('/shopping/i1') && r.method() === 'PUT')
    await page.locator('.hb-syncbar').getByRole('button', { name: 'Jetzt versuchen' }).click()
    await putPromise

    // marker + banner clear, the item stays checked, and the queue is empty
    await expect(page.locator('.hb-syncbar')).toHaveCount(0)
    await expect(page.locator('.hb-syncbadge')).toHaveCount(0)
    await expect(page.locator('.hb-row--done', { hasText: 'Milch' })).toBeVisible()
    await expect
      .poll(() => page.evaluate(() => localStorage.getItem('homebase_shopping_pending')))
      .toBeNull()
  })

  test('auto-retries the offline queue without manual action', async ({ page }) => {
    const mock = new MockApi([], [], [WOCHE], [shoppingItem({ id: 'i1', name: 'Eier', listId: 'sl1' })])
    await openShopping(page, mock)

    // first PUT is dropped (offline); once we go back online the periodic/online retry lands it
    await page.route('**/api/v1/shopping/**', (route) => {
      if (route.request().method() === 'PUT') return route.abort('internetdisconnected')
      return route.fallback()
    })
    await page.locator('.hb-row', { hasText: 'Eier' }).getByRole('checkbox').click()
    await expect(page.locator('.hb-syncbar')).toBeVisible()

    // restore connectivity and fire the OS "online" event — the queue drains on its own
    await page.unroute('**/api/v1/shopping/**')
    const putPromise = page.waitForRequest((r) => r.url().includes('/shopping/i1') && r.method() === 'PUT')
    await page.evaluate(() => window.dispatchEvent(new Event('online')))
    await putPromise

    await expect(page.locator('.hb-syncbar')).toHaveCount(0)
  })
})
