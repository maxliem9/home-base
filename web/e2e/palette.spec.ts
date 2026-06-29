import { test, expect, type Page } from '@playwright/test'
import { MockApi, recipe, note, TOKEN } from './helpers/mockApi'

/** Logs in + installs the mock backend, then lands on the dashboard (palette is global). */
async function openApp(page: Page, mock: MockApi) {
  await mock.install(page)
  await page.addInitScript((t) => localStorage.setItem('homebase_token', t), TOKEN)
  await page.goto('/')
  await expect(page.locator('.hb-brand__name').first()).toBeVisible()
}

test.describe('Command palette (⌘K)', () => {
  test('opens with Ctrl/⌘-K, searches across resources, and navigates to a hit', async ({ page }) => {
    const mock = new MockApi()
      .seedRecipes([recipe({ id: 'r1', title: 'Spaghetti Bolognese' })])
      .seedNotes([note({ id: 'n1', title: 'Urlaubsplanung' })])
    await openApp(page, mock)

    await page.keyboard.press('Control+k')
    const palette = page.locator('.hb-cmd')
    await expect(palette).toBeVisible()

    // quick-nav actions show with an empty query
    await expect(palette.getByRole('option', { name: 'Rezepte' })).toBeVisible()

    await palette.locator('.hb-cmd__input').fill('spag')
    const hit = palette.getByRole('option', { name: 'Spaghetti Bolognese' })
    await expect(hit).toBeVisible()
    await hit.click()

    // selecting a hit closes the palette and jumps to that resource's view
    await expect(page.locator('.hb-cmd')).toHaveCount(0)
    await expect(page.getByRole('heading', { name: 'Rezepte' })).toBeVisible()
  })

  test('closes on Escape', async ({ page }) => {
    await openApp(page, new MockApi())
    await page.keyboard.press('Control+k')
    await expect(page.locator('.hb-cmd')).toBeVisible()
    // Wait for the palette to take focus (the input is focused asynchronously on open) so
    // Escape lands deterministically; the close handler also listens globally as a backstop.
    await expect(page.locator('.hb-cmd__input')).toBeFocused()
    await page.keyboard.press('Escape')
    await expect(page.locator('.hb-cmd')).toHaveCount(0)
  })
})
