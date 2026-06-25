import { test, expect, type Page } from '@playwright/test'
import { MockApi, todo, mealPlanEntry, absence, kitaClosure, absSettings, TOKEN } from './helpers/mockApi'

// Pin the clock to a mid-month noon so the visible month (June 2026) is deterministic and the
// seeded dates below always land inside the rendered grid.
const FIXED_NOW = new Date('2026-06-17T12:00:00')

function seeded(): MockApi {
  return new MockApi(
    [
      // a due (open) todo on the 18th — must show; a DONE one on the 19th — must NOT show
      todo({ id: 't1', title: 'Zahnarzt', status: 'PLANNED', dueDate: '2026-06-18' }),
      todo({ id: 't2', title: 'Erledigt-Aufgabe', status: 'DONE', dueDate: '2026-06-19', doneAt: '2026-06-10T08:00:00Z' }),
    ],
  )
    .seedMealPlan([
      mealPlanEntry({ id: 'm1', date: '2026-06-18', slot: 'DINNER', recipeId: 'r1', recipeTitle: 'Lasagne' }),
    ])
    .seedAbsence({
      users: ['max', 'lea'],
      absences: [absence({ id: 'a1', userId: 'max', date: '2026-06-18', type: 'URLAUB' })],
      kitaClosures: [kitaClosure({ id: 'k1', date: '2026-06-18', label: 'Brückentag' })],
      settings: [absSettings({ userId: 'max' }), absSettings({ userId: 'lea' })],
    })
}

async function open(page: Page, mock: MockApi) {
  await page.clock.setFixedTime(FIXED_NOW)
  await mock.install(page)
  await page.addInitScript((tkn) => localStorage.setItem('homebase_token', tkn), TOKEN)
  await page.goto('/')
  await page.getByRole('button', { name: 'Familienkalender' }).first().click()
  await expect(page.getByRole('heading', { name: 'Familienkalender' })).toBeVisible()
}

test.describe('Familienkalender', () => {
  test('renders the month grid with seeded markers and hides done todos', async ({ page }) => {
    await open(page, seeded())

    // The grid renders a cell per day of June.
    const day18 = page.locator('.hb-cal__day[data-date="2026-06-18"]')
    await expect(day18).toBeVisible()

    // markers for the 18th: absence (Urlaub), todo (Zahnarzt), meal (Lasagne) + the kita badge
    await expect(day18.getByText('Zahnarzt')).toBeVisible()
    await expect(day18.getByText('Lasagne')).toBeVisible()
    await expect(day18.locator('.hb-cal__chip--absence')).toBeVisible()
    await expect(day18.locator('.hb-cal__kita')).toBeVisible()

    // DONE todo on the 19th is not shown anywhere in the grid
    await expect(page.locator('.hb-cal__grid').getByText('Erledigt-Aufgabe')).toHaveCount(0)
  })

  test('clicking a day opens the detail sheet with every domain section', async ({ page }) => {
    await open(page, seeded())

    await page.locator('.hb-cal__day[data-date="2026-06-18"]').click()
    const sheet = page.locator('.hb-sheet')
    await expect(sheet).toBeVisible()

    // each domain's detail row
    await expect(sheet.getByText('Urlaub')).toBeVisible()
    await expect(sheet.getByText('Brückentag')).toBeVisible()
    await expect(sheet.getByText('Zahnarzt')).toBeVisible()
    await expect(sheet.getByText('Lasagne')).toBeVisible()
  })

  test('an empty day shows the empty hint', async ({ page }) => {
    await open(page, seeded())
    // the 25th has nothing seeded
    await page.locator('.hb-cal__day[data-date="2026-06-25"]').click()
    await expect(page.locator('.hb-sheet').getByText('Nichts an diesem Tag.')).toBeVisible()
  })

  test('the subscribe modal shows the tokened iCal feed URL', async ({ page }) => {
    await open(page, seeded())
    await page.getByRole('button', { name: 'Abonnieren' }).click()
    const modal = page.locator('.hb-modal')
    await expect(modal).toBeVisible()
    const field = modal.locator('input.hb-input')
    await expect(field).toHaveValue(new RegExp(`/api/v1/calendar\\.ics\\?token=${TOKEN}`))
  })

  test('month navigation moves to the next month', async ({ page }) => {
    await open(page, seeded())
    // June seeds visible
    await expect(page.locator('.hb-cal__day[data-date="2026-06-18"]')).toBeVisible()
    await page.getByRole('button', { name: 'Nächster Monat' }).click()
    // July: the 18th of June is no longer a real in-month day; a July day exists instead
    await expect(page.locator('.hb-cal__day[data-date="2026-07-15"]')).toBeVisible()
  })
})
