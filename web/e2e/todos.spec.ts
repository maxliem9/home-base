import { test, expect, type Page } from '@playwright/test'
import { MockApi, todo, list, subtask, TOKEN } from './helpers/mockApi'

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

test.describe('Todo lists', () => {
  test('renders the list filter chips from the backend', async ({ page }) => {
    const mock = new MockApi(
      [todo({ id: 't1', title: 'Müll rausbringen' })],
      [list({ id: 'l1', name: 'Haushalt' }), list({ id: 'l2', name: 'Arbeit' })],
    )
    await openApp(page, mock)

    const bar = page.locator('.hb-listbar')
    await expect(bar.getByRole('button', { name: 'Alle' })).toBeVisible()
    await expect(bar.getByRole('button', { name: 'Haushalt' })).toBeVisible()
    await expect(bar.getByRole('button', { name: 'Arbeit' })).toBeVisible()
    // "Ohne Liste" only appears once at least one list exists.
    await expect(bar.getByRole('button', { name: 'Ohne Liste' })).toBeVisible()
    await expect(bar.getByRole('button', { name: 'Neue Liste' })).toBeVisible()
  })

  test('filters the inbox by the selected list', async ({ page }) => {
    const mock = new MockApi(
      [
        todo({ id: 't1', title: 'Steuer', listId: 'l1' }),
        todo({ id: 't2', title: 'Frei schwebend' }),
      ],
      [list({ id: 'l1', name: 'Haushalt' })],
    )
    await openApp(page, mock)
    const bar = page.locator('.hb-listbar')

    // All todos by default.
    await expect(page.getByText('Steuer')).toBeVisible()
    await expect(page.getByText('Frei schwebend')).toBeVisible()

    // Only the list's todo when filtered by it.
    await bar.getByRole('button', { name: 'Haushalt' }).click()
    await expect(page.getByText('Steuer')).toBeVisible()
    await expect(page.getByText('Frei schwebend')).toHaveCount(0)

    // Only the unassigned todo under "Ohne Liste".
    await bar.getByRole('button', { name: 'Ohne Liste' }).click()
    await expect(page.getByText('Frei schwebend')).toBeVisible()
    await expect(page.getByText('Steuer')).toHaveCount(0)

    // Back to everything.
    await bar.getByRole('button', { name: 'Alle' }).click()
    await expect(page.getByText('Steuer')).toBeVisible()
    await expect(page.getByText('Frei schwebend')).toBeVisible()
  })

  test('creates a new list via the manage-lists modal', async ({ page }) => {
    await openApp(page, new MockApi([]))

    await page.locator('.hb-listbar').getByRole('button', { name: 'Neue Liste' }).click()
    const modal = page.locator('.hb-modal')
    await expect(modal.getByRole('heading', { name: 'Listen verwalten' })).toBeVisible()

    await modal.getByPlaceholder('z. B. Haushalt, Kind, Arbeit, Verein').fill('Garten')
    // Pick a colour swatch, then create the list.
    await modal.locator('.hb-swatch').nth(2).click()
    await modal.getByRole('button', { name: 'Liste erstellen' }).click()

    // The list shows up both inside the modal and as a new filter chip.
    await expect(modal.locator('.hb-row', { hasText: 'Garten' })).toBeVisible()
    await expect(page.locator('.hb-listbar').getByRole('button', { name: 'Garten' })).toBeVisible()
  })

  test('quick-add assigns the active list to the new todo', async ({ page }) => {
    const mock = new MockApi([], [list({ id: 'l1', name: 'Haushalt' })])
    await openApp(page, mock)

    await page.locator('.hb-listbar').getByRole('button', { name: 'Haushalt' }).click()
    await page.getByPlaceholder('Aufgabe hinzufügen …').fill('Fenster putzen')
    await page.getByRole('button', { name: 'Hinzufügen' }).click()

    const row = page.locator('.hb-row', { hasText: 'Fenster putzen' })
    await expect(row).toBeVisible()
    await expect(row.locator('.hb-listtag')).toHaveText(/Haushalt/)
  })

  test('assigns a todo to a list via the row picker', async ({ page }) => {
    const mock = new MockApi(
      [todo({ id: 't1', title: 'Reifen wechseln' })],
      [list({ id: 'l1', name: 'Auto' })],
    )
    await openApp(page, mock)

    const row = page.locator('.hb-row', { hasText: 'Reifen wechseln' })
    await expect(row.locator('.hb-listtag')).toHaveCount(0)

    await row.locator('.hb-listpick select').selectOption({ label: 'Auto' })
    await expect(row.locator('.hb-listtag')).toHaveText(/Auto/)
  })

  test('deleting a list keeps its todos but drops the assignment', async ({ page }) => {
    const mock = new MockApi(
      [todo({ id: 't1', title: 'Rasen mähen', listId: 'l1' })],
      [list({ id: 'l1', name: 'Garten' })],
    )
    await openApp(page, mock)

    const row = page.locator('.hb-row', { hasText: 'Rasen mähen' })
    await expect(row.locator('.hb-listtag')).toHaveText(/Garten/)

    // Deletion asks for confirmation via window.confirm — accept it.
    page.once('dialog', (dialog) => dialog.accept())
    await page.locator('.hb-listbar').getByRole('button', { name: 'Neue Liste' }).click()
    const modal = page.locator('.hb-modal')
    await modal.locator('.hb-row', { hasText: 'Garten' }).getByRole('button', { name: 'Löschen' }).click()

    // Chip gone; the todo survives but loses its list tag.
    await expect(page.locator('.hb-listbar').getByRole('button', { name: 'Garten' })).toHaveCount(0)
    await page.locator('.hb-modal__head').getByRole('button', { name: 'Schließen' }).click()
    await expect(row).toBeVisible()
    await expect(row.locator('.hb-listtag')).toHaveCount(0)
  })
})

test.describe('Subtasks', () => {
  test('expands a row to reveal the subtask editor', async ({ page }) => {
    await openApp(page, new MockApi([todo({ id: 't1', title: 'Umzug planen' })]))

    const row = page.locator('.hb-todo', { hasText: 'Umzug planen' })
    await expect(row.locator('.hb-subadd')).toHaveCount(0)

    await row.getByRole('button', { name: 'Unteraufgaben' }).click()
    await expect(row.getByPlaceholder('Unteraufgabe hinzufügen …')).toBeVisible()
  })

  test('adds a subtask and shows the progress badge', async ({ page }) => {
    await openApp(page, new MockApi([todo({ id: 't1', title: 'Umzug planen' })]))

    const row = page.locator('.hb-todo', { hasText: 'Umzug planen' })
    await row.getByRole('button', { name: 'Unteraufgaben' }).click()
    const input = row.getByPlaceholder('Unteraufgabe hinzufügen …')
    await input.fill('Kartons besorgen')
    await input.press('Enter')

    await expect(row.locator('.hb-subtask', { hasText: 'Kartons besorgen' })).toBeVisible()
    await expect(row.locator('.hb-subbadge')).toContainText('0/1')
  })

  test('checks off a subtask, updating the progress badge', async ({ page }) => {
    const mock = new MockApi([
      todo({ id: 't1', title: 'Party', subtasks: [subtask({ id: 's1', title: 'Einladen' })] }),
    ])
    await openApp(page, mock)

    const row = page.locator('.hb-todo', { hasText: 'Party' })
    await expect(row.locator('.hb-subbadge')).toContainText('0/1')

    await row.getByRole('button', { name: 'Unteraufgaben' }).click()
    const sub = row.locator('.hb-subtask', { hasText: 'Einladen' })
    await sub.getByRole('checkbox').click()

    await expect(sub).toHaveClass(/hb-subtask--done/)
    await expect(row.locator('.hb-subbadge')).toContainText('1/1')
  })

  test('deletes a subtask, removing the progress badge', async ({ page }) => {
    const mock = new MockApi([
      todo({ id: 't1', title: 'Party', subtasks: [subtask({ id: 's1', title: 'Einladen' })] }),
    ])
    await openApp(page, mock)

    const row = page.locator('.hb-todo', { hasText: 'Party' })
    await row.getByRole('button', { name: 'Unteraufgaben' }).click()
    await row.locator('.hb-subtask', { hasText: 'Einladen' }).getByRole('button', { name: 'Löschen' }).click()

    await expect(row.locator('.hb-subtask')).toHaveCount(0)
    await expect(row.locator('.hb-subbadge')).toHaveCount(0)
  })

  test('the progress badge toggles the row open', async ({ page }) => {
    const mock = new MockApi([
      todo({ id: 't1', title: 'Party', subtasks: [subtask({ id: 's1', title: 'Einladen' })] }),
    ])
    await openApp(page, mock)

    const row = page.locator('.hb-todo', { hasText: 'Party' })
    // Collapsed by default: badge shows, subtasks hidden.
    await expect(row.locator('.hb-subbadge')).toBeVisible()
    await expect(row.locator('.hb-subtasks')).toHaveCount(0)

    await row.locator('.hb-subbadge').click()
    await expect(row.locator('.hb-subtasks')).toBeVisible()
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
