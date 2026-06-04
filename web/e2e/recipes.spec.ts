import { test, expect, type Page } from '@playwright/test'
import { MockApi, recipe, ingredient, recipeStep, shoppingList, TOKEN } from './helpers/mockApi'

/** Logs in, installs the mock backend, and navigates to the recipes view. */
async function openRecipes(page: Page, mock: MockApi) {
  await mock.install(page)
  await page.addInitScript((t) => localStorage.setItem('homebase_token', t), TOKEN)
  await page.goto('/')
  await page.getByRole('button', { name: 'Rezepte' }).click()
  await expect(page.getByRole('heading', { name: 'Rezepte' })).toBeVisible()
}

const PANCAKES = recipe({
  id: 'r1',
  title: 'Pfannkuchen',
  description: 'Klassisch',
  servings: 2,
  category: 'BREAKFAST',
  prepTimeMinutes: 10,
  cookTimeMinutes: 15,
  ingredients: [
    ingredient({ id: 'i1', name: 'Mehl', amount: 200, unit: 'g', sortOrder: 0 }),
    ingredient({ id: 'i2', name: 'Milch', amount: 500, unit: 'ml', sortOrder: 1 }),
  ],
  steps: [
    recipeStep({ id: 's1', stepNumber: 1, description: 'Zutaten verrühren' }),
    recipeStep({ id: 's2', stepNumber: 2, description: 'In der Pfanne backen' }),
  ],
})

test.describe('Recipes', () => {
  test('shows the empty state when there are no recipes', async ({ page }) => {
    await openRecipes(page, new MockApi())
    await expect(page.getByText('Noch keine Rezepte')).toBeVisible()
  })

  test('renders recipe cards from the backend', async ({ page }) => {
    await openRecipes(page, new MockApi().seedRecipes([PANCAKES]))

    const card = page.locator('.hb-recipecard', { hasText: 'Pfannkuchen' })
    await expect(card).toBeVisible()
    await expect(card.getByText('Frühstück')).toBeVisible() // category badge
  })

  test('filters recipes by category', async ({ page }) => {
    await openRecipes(page, new MockApi().seedRecipes([
      PANCAKES,
      recipe({ id: 'r2', title: 'Lasagne', category: 'DINNER' }),
    ]))
    await expect(page.getByText('Pfannkuchen')).toBeVisible()
    await expect(page.getByText('Lasagne')).toBeVisible()

    await page.getByRole('button', { name: 'Abend' }).click() // DINNER filter pill
    await expect(page.getByText('Lasagne')).toBeVisible()
    await expect(page.getByText('Pfannkuchen')).toHaveCount(0)
  })

  test('creates a recipe via the editor', async ({ page }) => {
    await openRecipes(page, new MockApi())

    await page.getByRole('button', { name: 'Neues Rezept' }).click()
    const modal = page.locator('.hb-modal')
    await modal.getByPlaceholder('Titel…').fill('Omelett')
    await modal.getByPlaceholder('Zutat').first().fill('Eier')
    await modal.getByRole('button', { name: 'Speichern' }).click()

    // editor closes and the new recipe opens on its detail page
    await expect(page.getByRole('heading', { name: 'Omelett' })).toBeVisible()
    await expect(page.locator('.hb-ing', { hasText: 'Eier' })).toBeVisible()
  })

  test('opens a recipe and scales ingredient amounts by servings', async ({ page }) => {
    await openRecipes(page, new MockApi().seedRecipes([PANCAKES]))
    await page.locator('.hb-recipecard', { hasText: 'Pfannkuchen' }).click()

    await expect(page.getByRole('heading', { name: 'Pfannkuchen' })).toBeVisible()
    const mehl = page.locator('.hb-ing', { hasText: 'Mehl' })
    await expect(mehl).toContainText('200 g') // base 2 servings

    // bump to 4 servings → amounts double
    await page.getByRole('button', { name: 'Mehr Portionen' }).click()
    await page.getByRole('button', { name: 'Mehr Portionen' }).click()
    await expect(mehl).toContainText('400 g')
  })

  test('edits a recipe', async ({ page }) => {
    await openRecipes(page, new MockApi().seedRecipes([PANCAKES]))
    await page.locator('.hb-recipecard', { hasText: 'Pfannkuchen' }).click()

    await page.getByRole('button', { name: 'Bearbeiten' }).click()
    const modal = page.locator('.hb-modal')
    await modal.getByPlaceholder('Titel…').fill('Crêpes')
    await modal.getByRole('button', { name: 'Speichern' }).click()

    await expect(page.getByRole('heading', { name: 'Crêpes' })).toBeVisible()
  })

  test('deletes a recipe', async ({ page }) => {
    await openRecipes(page, new MockApi().seedRecipes([PANCAKES]))
    await page.locator('.hb-recipecard', { hasText: 'Pfannkuchen' }).click()

    await page.getByRole('button', { name: 'Löschen' }).click()

    await expect(page.getByText('Noch keine Rezepte')).toBeVisible()
  })

  test('adds recipe ingredients to a shopping list', async ({ page }) => {
    const mock = new MockApi([], [], [shoppingList({ id: 'sl1', name: 'Wocheneinkauf' })], [])
      .seedRecipes([PANCAKES])
    await openRecipes(page, mock)
    await page.locator('.hb-recipecard', { hasText: 'Pfannkuchen' }).click()

    await page.getByRole('button', { name: 'Zutaten zur Liste' }).click()
    const modal = page.locator('.hb-modal')
    await expect(modal).toBeVisible()
    // both ingredients preselected → "2 hinzufügen"
    await modal.getByRole('button', { name: /hinzufügen/ }).click()

    await expect(page.getByText(/zur Einkaufsliste hinzugefügt/)).toBeVisible()
  })
})
