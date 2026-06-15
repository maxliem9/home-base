import { test, expect, type Page } from '@playwright/test'
import { MockApi, recipe, ingredient, recipeStep, recipeImage, shoppingList, TOKEN } from './helpers/mockApi'

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

  test('creates a recipe via the editor page', async ({ page }) => {
    await openRecipes(page, new MockApi())

    await page.locator('.hb-pagehead').getByRole('button', { name: 'Neues Rezept' }).click()
    // the editor is its own full page now (issue #123), not a modal
    await expect(page.locator('.hb-modal')).toHaveCount(0)
    const form = page.locator('.hb-recipe-form')
    await form.getByPlaceholder('Titel…').fill('Omelett')
    await form.getByPlaceholder('Zutat').first().fill('Eier')
    // scope the save to the form (the page header also carries a Speichern button)
    await form.getByRole('button', { name: 'Speichern' }).click()

    // editor closes and the new recipe opens on its detail page
    await expect(page.getByRole('heading', { name: 'Omelett' })).toBeVisible()
    await expect(page.locator('.hb-ing', { hasText: 'Eier' })).toBeVisible()
  })

  test('groups ingredients into named sections', async ({ page }) => {
    await openRecipes(page, new MockApi())

    await page.locator('.hb-pagehead').getByRole('button', { name: 'Neues Rezept' }).click()
    const form = page.locator('.hb-recipe-form')
    await form.getByPlaceholder('Titel…').fill('Käsekuchen')

    // a fresh recipe is a flat list — no section-name field until sections are introduced
    await expect(form.getByPlaceholder('Abschnitt (optional)')).toHaveCount(0)
    await form.getByPlaceholder('Zutat').first().fill('Mehl')

    // "+ Abschnitt" reveals name fields on every section (incl. the first) — name them
    await form.getByRole('button', { name: '+ Abschnitt' }).click()
    await form.getByPlaceholder('Abschnitt (optional)').first().fill('Boden')
    await form.getByPlaceholder('Abschnitt (optional)').nth(1).fill('Füllung')
    await form.getByPlaceholder('Zutat').nth(1).fill('Quark')

    await form.getByRole('button', { name: 'Speichern' }).click()

    // detail page shows both section sub-headings over their ingredient runs
    await expect(page.getByRole('heading', { name: 'Käsekuchen' })).toBeVisible()
    await expect(page.locator('.hb-ingsubhead', { hasText: 'Boden' })).toBeVisible()
    await expect(page.locator('.hb-ingsubhead', { hasText: 'Füllung' })).toBeVisible()
    await expect(page.locator('.hb-ing', { hasText: 'Mehl' })).toBeVisible()
    await expect(page.locator('.hb-ing', { hasText: 'Quark' })).toBeVisible()
  })

  test('section-name field sticks after removing back to a single section', async ({ page }) => {
    await openRecipes(page, new MockApi())

    await page.locator('.hb-pagehead').getByRole('button', { name: 'Neues Rezept' }).click()
    const form = page.locator('.hb-recipe-form')
    await form.getByPlaceholder('Titel…').fill('Test')

    // introduce a second section, name the first, then remove the second
    await form.getByRole('button', { name: '+ Abschnitt' }).click()
    const nameField = form.getByPlaceholder('Abschnitt (optional)')
    await nameField.first().fill('Boden')
    await form.getByRole('button', { name: 'Abschnitt entfernen' }).nth(1).click()

    // back to a single section, but the name field must persist (and keep "Boden"),
    // not vanish mid-edit just because there is one section again
    await expect(nameField).toHaveCount(1)
    await expect(nameField.first()).toHaveValue('Boden')

    // and clearing the name keeps the field present (sticky), no jolt
    await nameField.first().fill('')
    await expect(nameField).toHaveCount(1)
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
    const form = page.locator('.hb-recipe-form')
    await form.getByPlaceholder('Titel…').fill('Crêpes')
    await form.getByRole('button', { name: 'Speichern' }).click()

    await expect(page.getByRole('heading', { name: 'Crêpes' })).toBeVisible()
  })

  test('deletes a recipe', async ({ page }) => {
    await openRecipes(page, new MockApi().seedRecipes([PANCAKES]))
    await page.locator('.hb-recipecard', { hasText: 'Pfannkuchen' }).click()

    await page.getByRole('button', { name: 'Löschen' }).click()

    await expect(page.getByText('Noch keine Rezepte')).toBeVisible()
  })

  test('exports a recipe as a Markdown download with the server filename', async ({ page }) => {
    await openRecipes(page, new MockApi().seedRecipes([PANCAKES]))
    await page.locator('.hb-recipecard', { hasText: 'Pfannkuchen' }).click()

    await page.getByRole('button', { name: 'Exportieren' }).click()
    const modal = page.locator('.hb-modal')
    await expect(modal).toBeVisible()

    const requestPromise = page.waitForRequest((r) => r.url().includes('/recipes/r1/export'))
    const downloadPromise = page.waitForEvent('download')
    await modal.getByRole('button', { name: 'Als Markdown' }).click()
    const [request, download] = await Promise.all([requestPromise, downloadPromise])

    expect(new URL(request.url()).searchParams.get('format')).toBe('md')
    expect(download.suggestedFilename()).toBe('rezept_pfannkuchen.md')
    await expect(modal).toBeHidden()
  })

  test('exports a recipe as a PDF scaled to the chosen servings', async ({ page }) => {
    await openRecipes(page, new MockApi().seedRecipes([PANCAKES]))
    await page.locator('.hb-recipecard', { hasText: 'Pfannkuchen' }).click()

    // bump to 4 servings → the export request carries servings=4 so the file matches the view
    await page.getByRole('button', { name: 'Mehr Portionen' }).click()
    await page.getByRole('button', { name: 'Mehr Portionen' }).click()

    await page.getByRole('button', { name: 'Exportieren' }).click()
    const modal = page.locator('.hb-modal')
    const requestPromise = page.waitForRequest((r) => r.url().includes('/recipes/r1/export'))
    const downloadPromise = page.waitForEvent('download')
    await modal.getByRole('button', { name: 'Als PDF' }).click()
    const [request, download] = await Promise.all([requestPromise, downloadPromise])

    const params = new URL(request.url()).searchParams
    expect(params.get('format')).toBe('pdf')
    expect(params.get('servings')).toBe('4')
    expect(download.suggestedFilename()).toBe('rezept_pfannkuchen.pdf')
  })

  test('opens a recipe whose JSON omits empty ingredients/steps (issue #46)', async ({ page }) => {
    const mock = new MockApi()
    await mock.install(page)
    // The backend serializes with encodeDefaults=false, so a recipe that has no
    // ingredients and no steps comes back WITHOUT those keys (not as empty arrays).
    // Serve exactly that raw shape and make sure opening it does not crash the
    // detail page on r.ingredients.length / r.steps.map.
    await page.route('**/api/v1/recipes', (route) => {
      if (route.request().method() !== 'GET') return route.fallback()
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify([{
          id: 'r-bare',
          title: 'Leitungswasser',
          servings: 1,
          category: 'DRINK',
          createdBy: 'alice',
          createdAt: '2026-06-01T08:00:00Z',
          updatedAt: '2026-06-01T08:00:00Z',
        }]),
      })
    })
    await page.addInitScript((t) => localStorage.setItem('homebase_token', t), TOKEN)
    await page.goto('/')
    await page.getByRole('button', { name: 'Rezepte' }).click()
    await expect(page.getByRole('heading', { name: 'Rezepte' })).toBeVisible()

    // opening the card renders the detail page (heading visible) instead of
    // blanking out on an undefined ingredients/steps array
    await page.locator('.hb-recipecard', { hasText: 'Leitungswasser' }).click()
    await expect(page.getByRole('heading', { name: 'Leitungswasser' })).toBeVisible()
    await expect(page.locator('.hb-ing')).toHaveCount(0)
    await expect(page.locator('.hb-step')).toHaveCount(0)
  })

  test('bulk-adds ingredients via the free-text paste mode', async ({ page }) => {
    await openRecipes(page, new MockApi())

    await page.locator('.hb-pagehead').getByRole('button', { name: 'Neues Rezept' }).click()
    const form = page.locator('.hb-recipe-form')
    await form.getByPlaceholder('Titel…').fill('Suppe')

    // switch the ingredient editor to free text and paste a whole list at once
    await form.getByRole('button', { name: 'Als Text' }).click()
    await form.locator('textarea.hb-mono-area').fill('200 g Mehl\n3 Eier\n# Topping\n100 g Zucker')
    await form.getByRole('button', { name: 'Speichern' }).click()

    // the lines parsed into amount/unit/name rows; "# Topping" became a named section,
    // and "3 Eier" kept "Eier" as the name (not the unit)
    await expect(page.getByRole('heading', { name: 'Suppe' })).toBeVisible()
    await expect(page.locator('.hb-ing', { hasText: 'Mehl' })).toContainText('200 g')
    await expect(page.locator('.hb-ing', { hasText: 'Eier' })).toBeVisible()
    await expect(page.locator('.hb-ingsubhead', { hasText: 'Topping' })).toBeVisible()
    await expect(page.locator('.hb-ing', { hasText: 'Zucker' })).toContainText('100 g')
  })

  test('renders the recipe cover image on the card and the detail hero', async ({ page }) => {
    const withImage = recipe({
      id: 'r4',
      title: 'Pizza',
      image: recipeImage({ id: 'ri9', recipeId: 'r4', originalName: 'pizza.png' }),
    })
    await openRecipes(page, new MockApi().seedRecipes([withImage]))

    // the list card shows the cover, loaded via the shared <AuthedImage> (authFetch → blob)
    await expect(page.locator('.hb-recipecard__photo')).toHaveAttribute('src', /^blob:/)

    // the detail page shows the same image as the hero
    await page.locator('.hb-recipecard', { hasText: 'Pizza' }).click()
    await expect(page.locator('.hb-recipe-hero img')).toHaveAttribute('src', /^blob:/)
  })

  test('removes the cover image from the detail page', async ({ page }) => {
    const withImage = recipe({
      id: 'r3',
      title: 'Toast',
      image: recipeImage({ id: 'ri3', recipeId: 'r3', originalName: 'toast.png' }),
    })
    await openRecipes(page, new MockApi().seedRecipes([withImage]))
    await page.locator('.hb-recipecard', { hasText: 'Toast' }).click()

    // the cover renders as the hero …
    await expect(page.locator('.hb-recipe-hero img')).toHaveAttribute('src', /^blob:/)

    // … and "Bild entfernen" clears it (DELETE → updated recipe with no image)
    await page.getByRole('button', { name: 'Bild entfernen' }).click()
    await expect(page.locator('.hb-recipe-hero')).toHaveCount(0)
  })

  test('adds serving-scaled recipe ingredients to a shopping list', async ({ page }) => {
    const mock = new MockApi([], [], [shoppingList({ id: 'sl1', name: 'Wocheneinkauf' })], [])
      .seedRecipes([PANCAKES])
    await openRecipes(page, mock)
    await page.locator('.hb-recipecard', { hasText: 'Pfannkuchen' }).click()

    // scale to 4 servings so the picker carries doubled amounts onto the list
    await page.getByRole('button', { name: 'Mehr Portionen' }).click()
    await page.getByRole('button', { name: 'Mehr Portionen' }).click()

    await page.getByRole('button', { name: 'Zutaten zur Liste' }).click()
    // the ingredient picker is a slide-over now (issue #48), not a centered modal
    const sheet = page.locator('.hb-sheet')
    await expect(sheet).toBeVisible()
    await expect(sheet.getByText('Mengen für 4 Portionen')).toBeVisible()
    // both ingredients preselected → "2 hinzufügen"
    await sheet.getByRole('button', { name: /hinzufügen/ }).click()

    // toast confirms the add, then the scaled, unit-labelled items show on the list
    await expect(page.getByText(/hinzugefügt/)).toBeVisible()
    await page.getByRole('button', { name: 'Einkaufsliste' }).click()
    await expect(page.getByText('400 g Mehl')).toBeVisible()
    await expect(page.getByText('1000 ml Milch')).toBeVisible()
  })
})
