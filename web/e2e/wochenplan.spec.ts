import { test, expect, type Page } from '@playwright/test'
import { MockApi, recipe, ingredient, shoppingList, mealPlanEntry, TOKEN } from './helpers/mockApi'

// The view defaults to the week containing "today". Pin the clock to a Wednesday so the
// visible week is the deterministic Mon 2026-06-15 … Sun 2026-06-21 regardless of the runner.
// Noon (not midnight) keeps the local date stable across timezone offsets.
const FIXED_NOW = new Date('2026-06-17T12:00:00')

const LASAGNE = recipe({
  id: 'r1',
  title: 'Lasagne',
  category: 'DINNER',
  servings: 2,
  ingredients: [
    ingredient({ id: 'i1', name: 'Nudelplatten', amount: 250, unit: 'g', sortOrder: 0 }),
    ingredient({ id: 'i2', name: 'Hackfleisch', amount: 500, unit: 'g', sortOrder: 1 }),
  ],
})
const PFANNKUCHEN = recipe({
  id: 'r2',
  title: 'Pfannkuchen',
  category: 'BREAKFAST',
  servings: 2,
  ingredients: [
    ingredient({ id: 'i3', name: 'Mehl', amount: 200, unit: 'g', sortOrder: 0 }),
    ingredient({ id: 'i4', name: 'Milch', amount: 500, unit: 'ml', sortOrder: 1 }),
  ],
})

/** A household with two recipes and one shopping list; optionally pre-planned meals. */
function seeded(meals: ReturnType<typeof mealPlanEntry>[] = []): MockApi {
  return new MockApi()
    .seedRecipes([LASAGNE, PFANNKUCHEN])
    .seedMealPlan(meals)
}

async function open(page: Page, mock: MockApi) {
  await page.clock.setFixedTime(FIXED_NOW)
  await mock.install(page)
  await page.addInitScript((t) => localStorage.setItem('homebase_token', t), TOKEN)
  await page.goto('/')
  await page.getByRole('button', { name: 'Wochenplan' }).click()
  await expect(page.getByRole('heading', { name: 'Wochenplan' })).toBeVisible()
}

// A meal cell in the desktop matrix, addressed by date + slot.
const cell = (page: Page, date: string, slot: string) =>
  page.locator(`.hb-mealgrid [data-date="${date}"][data-slot="${slot}"]`)

test.describe('Wochenplan', () => {
  test('renders the weekly grid and a seeded meal in the right slot', async ({ page }) => {
    await open(page, seeded([mealPlanEntry({ id: 'm1', date: '2026-06-17', slot: 'DINNER', recipeId: 'r1', recipeTitle: 'Lasagne' })]))

    // current week label + range
    await expect(page.locator('.hb-weeknav__rel')).toHaveText('Diese Woche')
    await expect(page.locator('.hb-weeknav__range')).toContainText('15.–21. Juni')

    // the seeded dinner shows in its cell, not anywhere else
    await expect(cell(page, '2026-06-17', 'DINNER')).toContainText('Lasagne')
    await expect(cell(page, '2026-06-15', 'DINNER')).not.toContainText('Lasagne')
  })

  test('plans a recipe into an empty slot via the picker', async ({ page }) => {
    await open(page, seeded())

    await cell(page, '2026-06-15', 'BREAKFAST').click() // empty "+" cell
    await expect(page.locator('.hb-sheet')).toBeVisible()
    await page.locator('.hb-mealpick__item', { hasText: 'Pfannkuchen' }).click() // select
    await page.locator('.hb-sheet').getByRole('button', { name: 'Übernehmen' }).click() // confirm

    await expect(cell(page, '2026-06-15', 'BREAKFAST')).toContainText('Pfannkuchen')
  })

  test('replaces a planned recipe via the picker', async ({ page }) => {
    await open(page, seeded([mealPlanEntry({ id: 'm1', date: '2026-06-17', slot: 'DINNER', recipeId: 'r1', recipeTitle: 'Lasagne' })]))

    await cell(page, '2026-06-17', 'DINNER').locator('.hb-mealcell__body').click()
    await expect(page.locator('.hb-sheet')).toBeVisible()
    await page.locator('.hb-mealpick__item', { hasText: 'Pfannkuchen' }).click() // select
    await page.locator('.hb-sheet').getByRole('button', { name: 'Übernehmen' }).click() // confirm

    await expect(cell(page, '2026-06-17', 'DINNER')).toContainText('Pfannkuchen')
    await expect(cell(page, '2026-06-17', 'DINNER')).not.toContainText('Lasagne')
  })

  test('removes a planned recipe via the picker', async ({ page }) => {
    await open(page, seeded([mealPlanEntry({ id: 'm1', date: '2026-06-17', slot: 'DINNER', recipeId: 'r1', recipeTitle: 'Lasagne' })]))

    await cell(page, '2026-06-17', 'DINNER').locator('.hb-mealcell__body').click()
    await page.locator('.hb-sheet').getByRole('button', { name: 'Entfernen' }).click()

    // the slot becomes an empty "+" cell again
    await expect(
      page.locator('.hb-mealgrid button[data-date="2026-06-17"][data-slot="DINNER"][aria-label="Rezept einplanen"]'),
    ).toBeVisible()
    await expect(cell(page, '2026-06-17', 'DINNER')).not.toContainText('Lasagne')
  })

  test('navigates between weeks', async ({ page }) => {
    await open(page, seeded([mealPlanEntry({ id: 'm1', date: '2026-06-17', slot: 'DINNER', recipeId: 'r1', recipeTitle: 'Lasagne' })]))
    await expect(cell(page, '2026-06-17', 'DINNER')).toContainText('Lasagne')

    await page.getByRole('button', { name: 'Nächste Woche' }).click()
    await expect(page.locator('.hb-weeknav__range')).toContainText('22.–28. Juni')
    await expect(page.locator('.hb-mealgrid')).not.toContainText('Lasagne') // no meals next week

    await page.getByRole('button', { name: 'Diese Woche' }).click()
    await expect(cell(page, '2026-06-17', 'DINNER')).toContainText('Lasagne')
  })

  test('adds the week’s ingredients to a shopping list', async ({ page }) => {
    const mock = new MockApi([], [], [shoppingList({ id: 'sl1', name: 'Wocheneinkauf' })], [])
      .seedRecipes([LASAGNE, PFANNKUCHEN])
      .seedMealPlan([
        mealPlanEntry({ id: 'm1', date: '2026-06-17', slot: 'DINNER', recipeId: 'r1', recipeTitle: 'Lasagne' }),
        mealPlanEntry({ id: 'm2', date: '2026-06-15', slot: 'BREAKFAST', recipeId: 'r2', recipeTitle: 'Pfannkuchen', recipeCategory: 'BREAKFAST' }),
      ])
    await open(page, mock)

    await page.locator('.hb-pagehead').getByRole('button', { name: 'In Einkaufsliste' }).click()
    await expect(page.locator('.hb-sheet')).toBeVisible()
    // summary interpolates the counts (4 ingredients across 2 dishes) — guards the {placeholder} format
    await expect(page.locator('.hb-sheet')).toContainText('4 Zutaten aus 2')
    await page.locator('.hb-sheet').getByRole('button', { name: 'Hinzufügen' }).click()

    // 4 distinct ingredient lines across the two dishes → "4 hinzugefügt"
    await expect(page.locator('.hb-toast')).toContainText('4 hinzugefügt')
  })

  test('scales ingredient amounts by the chosen portions when adding to a list', async ({ page }) => {
    const mock = new MockApi([], [], [shoppingList({ id: 'sl1', name: 'Wocheneinkauf' })], [])
      .seedRecipes([LASAGNE]) // servings 2, Nudelplatten 250 g + Hackfleisch 500 g
      .seedMealPlan([])
    await open(page, mock)

    // plan Lasagne and bump portions 2 → 4 (×2)
    await cell(page, '2026-06-15', 'DINNER').click()
    await page.locator('.hb-mealpick__item', { hasText: 'Lasagne' }).click()
    await page.locator('.hb-sheet').getByRole('button', { name: 'Mehr Portionen' }).click()
    await page.locator('.hb-sheet').getByRole('button', { name: 'Mehr Portionen' }).click()
    await page.locator('.hb-sheet').getByRole('button', { name: 'Übernehmen' }).click()

    // the chosen portions show on the chip
    await expect(cell(page, '2026-06-15', 'DINNER')).toContainText('4 Port.')

    // the batch payload carries the doubled amounts
    const reqP = page.waitForRequest((r) => r.url().includes('/shopping/batch') && r.method() === 'POST')
    await page.locator('.hb-pagehead').getByRole('button', { name: 'In Einkaufsliste' }).click()
    await page.locator('.hb-sheet').getByRole('button', { name: 'Hinzufügen' }).click()
    const items = JSON.parse((await reqP).postData() || '{}').items as Array<{ name: string; amount: number; unit: string }>
    expect(items.find((i) => i.name === 'Nudelplatten')?.amount).toBe(500)
    expect(items.find((i) => i.name === 'Hackfleisch')?.amount).toBe(1000)
  })
})
