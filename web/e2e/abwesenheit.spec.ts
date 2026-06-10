import { test, expect, type Page } from '@playwright/test'
import { MockApi, absence, absSettings, customHoliday, kitaClosure, partTimeRule, TOKEN } from './helpers/mockApi'

const SHOTS = '/tmp/abw-screens'

// The whole fixture lives in this year. open() pins the browser clock to FIXED_NOW
// (see below) so AbwesenheitView opens YEAR by default regardless of the real date,
// and the settings are seeded for YEAR explicitly — otherwise both would silently
// drift to the system year and stop matching the 2026 seed after 2026-12-31 (issue #19).
const YEAR = 2026
// Noon on a plain working day in YEAR. Noon (not midnight) keeps the local date stable
// no matter the runner/browser timezone offset. Parsed as local time (no trailing Z).
const FIXED_NOW = new Date(`${YEAR}-06-04T12:00:00`)

/** A populated two-person household for 2026 (today is pinned to 2026-06-04 via page.clock). */
function seeded(): MockApi {
  return new MockApi().seedAbsence({
    users: ['max', 'lea'],
    settings: [
      absSettings({ userId: 'max', year: YEAR, state: 'BE', allowance: 30, carryover: 5, carryoverExpires: '2026-09-30', kindKrankCap: 15 }),
      absSettings({ userId: 'lea', year: YEAR, state: 'BY', allowance: 24, carryover: 0, kindKrankCap: 15 }),
    ],
    partTime: [
      partTimeRule({ id: 'pt1', userId: 'max', weekday: 1, start: '2026-01-01', end: '2026-04-30' }),
      partTimeRule({ id: 'pt2', userId: 'lea', weekday: 5, start: '2026-03-01', end: null }),
    ],
    absences: [
      // Max — a taken week in March, a planned week in July, a half day, sick days
      absence({ id: 'a1', userId: 'max', date: '2026-03-16' }),
      absence({ id: 'a2', userId: 'max', date: '2026-03-17' }),
      absence({ id: 'a3', userId: 'max', date: '2026-03-18' }),
      absence({ id: 'a4', userId: 'max', date: '2026-03-19' }),
      absence({ id: 'a5', userId: 'max', date: '2026-03-20' }),
      absence({ id: 'a6', userId: 'max', date: '2026-07-27' }),
      absence({ id: 'a7', userId: 'max', date: '2026-07-28' }),
      absence({ id: 'a8', userId: 'max', date: '2026-07-29' }),
      absence({ id: 'a9', userId: 'max', date: '2026-06-02', half: 'vm' }),
      absence({ id: 'a10', userId: 'max', date: '2026-05-11', type: 'KRANK' }),
      absence({ id: 'a11', userId: 'max', date: '2026-04-21', type: 'KIND_KRANK' }),
      // Lea — planned July week, a sick day
      absence({ id: 'b1', userId: 'lea', date: '2026-07-27' }),
      absence({ id: 'b2', userId: 'lea', date: '2026-07-28' }),
      absence({ id: 'b3', userId: 'lea', date: '2026-07-29' }),
      absence({ id: 'b4', userId: 'lea', date: '2026-02-10', type: 'KRANK' }),
    ],
    kitaClosures: [
      kitaClosure({ id: 'k1', date: '2026-07-27', label: 'Sommerschließung' }),
      kitaClosure({ id: 'k2', date: '2026-07-28', label: 'Sommerschließung' }),
      kitaClosure({ id: 'k3', date: '2026-07-29', label: 'Sommerschließung' }),
    ],
    // Household-wide custom holidays (#51): Heiligabend half-day, Silvester half-day.
    // 24.12.2026 is a Thursday (no statutory holiday) so it renders as a custom holiday.
    customHolidays: [
      customHoliday({ id: 'h1', month: 12, day: 24, half: true, label: 'Heiligabend' }),
      customHoliday({ id: 'h2', month: 12, day: 31, half: true, label: 'Silvester' }),
    ],
  })
}

async function open(page: Page, mock: MockApi) {
  // Pin the clock before any page script runs so the view reads YEAR from new Date().
  await page.clock.setFixedTime(FIXED_NOW)
  await mock.install(page)
  await page.addInitScript((t) => localStorage.setItem('homebase_token', t), TOKEN)
  await page.goto('/')
  await page.getByRole('button', { name: 'Kalender' }).click()
  await expect(page.getByRole('heading', { name: 'Kalender' })).toBeVisible()
}

test.describe('Abwesenheit', () => {
  test('renders both summary cards and the year grid', async ({ page }) => {
    await open(page, seeded())

    await expect(page.locator('.abw-sumcard__name', { hasText: 'Max' })).toBeVisible()
    await expect(page.locator('.abw-sumcard__name', { hasText: 'Lea' })).toBeVisible()
    // states resolve from the Bundesland setting
    await expect(page.getByText('Berlin')).toBeVisible()
    await expect(page.getByText('Bayern')).toBeVisible()
    // the year grid renders day cells
    await expect(page.locator('.abw-raster .abw-rcell--day').first()).toBeVisible()

    await page.screenshot({ path: `${SHOTS}/abw-year.png`, fullPage: true })
  })

  test('switches to the month layout', async ({ page }) => {
    await open(page, seeded())
    await page.getByRole('tab', { name: 'Monat' }).click()
    await expect(page.locator('.abw-month')).toBeVisible()
    await expect(page.locator('.abw-mcell').first()).toBeVisible()
    await page.screenshot({ path: `${SHOTS}/abw-month.png`, fullPage: true })
  })

  test('opens the day editor and books vacation for a person', async ({ page }) => {
    await open(page, seeded())

    // a plain working Wednesday for both people
    await page.locator('button.abw-rcell--day[title^="2026-06-10"]').click()
    // the day editor is a slide-over panel now (was a centered modal) — #44,
    // unified onto the shared Sheet primitive (.hb-sheet) — #48 follow-up
    const sheet = page.locator('.hb-sheet')
    await expect(sheet.getByRole('heading', { name: 'Mittwoch, 10. Juni 2026' })).toBeVisible()

    // book Urlaub for the first person (Max)
    const maxRow = sheet.locator('.abw-ed-person').first()
    await maxRow.getByRole('button', { name: 'Urlaub', exact: true }).click()

    // refetch reflects it: the pill is now active and the half-day toggle appears
    await expect(maxRow.locator('.abw-pick.is-active', { hasText: 'Urlaub' })).toBeVisible()
    await expect(sheet.getByRole('button', { name: 'Vormittag (AM)' })).toBeVisible()

    await page.screenshot({ path: `${SHOTS}/abw-day-editor.png` })
  })

  test('opens calendar settings', async ({ page }) => {
    await open(page, seeded())
    await page.getByRole('button', { name: 'Einstellungen' }).click()
    // settings is a full page now (was a modal) — #43
    await expect(page.getByRole('heading', { name: 'Kalender-Einstellungen' })).toBeVisible()
    await expect(page.getByText('Teilzeit · feste freie Tage').first()).toBeVisible()
    await expect(page.getByText('Kita-Schließtage')).toBeVisible()
    // the "Eigene Feiertage" section (#51) lists the seeded recurring holidays
    await expect(page.getByText('Eigene Feiertage')).toBeVisible()
    await expect(page.locator('input[value="Heiligabend"]')).toBeVisible()
    await expect(page.locator('input[value="Silvester"]')).toBeVisible()
    await page.screenshot({ path: `${SHOTS}/abw-settings.png` })
  })

  test('renders a custom holiday with a ½ marker on the calendar', async ({ page }) => {
    await open(page, seeded())
    // Year grid: the 24.12. cell is tinted as a holiday and carries the recurring label.
    const cell = page.locator('button.abw-rcell--day[title^="2026-12-24"]')
    await expect(cell).toHaveAttribute('title', /Heiligabend \(½\)/)
    await expect(cell.locator('.abw-rcell__half')).toBeVisible()

    // Month view (December): the chip shows the ½-prefixed holiday name for each person.
    await page.getByRole('tab', { name: 'Monat' }).click()
    for (let i = 0; i < 6; i++) await page.getByRole('button', { name: 'Nächster Monat' }).click()
    await expect(page.locator('.abw-mchip__txt', { hasText: '½ Heiligabend' }).first()).toBeVisible()
  })

  test('books a vacation period across working days', async ({ page }) => {
    await open(page, seeded())
    await page.getByRole('button', { name: 'Zeitraum' }).click()
    const modal = page.locator('.hb-modal')
    await expect(modal.getByRole('heading', { name: 'Zeitraum eintragen' })).toBeVisible()
    // set a one-week range
    await modal.locator('input[type="date"]').first().fill('2026-08-10')
    await modal.locator('input[type="date"]').nth(1).fill('2026-08-14')
    await modal.getByRole('button', { name: 'Übernehmen' }).click()
    await expect(modal).toBeHidden()
  })
})
