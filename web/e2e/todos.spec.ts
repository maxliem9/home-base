import { test, expect, type Page } from '@playwright/test'
import { MockApi, todo, list, subtask, TOKEN } from './helpers/mockApi'

/** Logs in by seeding the token, then installs the given mock backend. */
async function openApp(page: Page, mock: MockApi, token: string = TOKEN) {
  await mock.install(page)
  await page.addInitScript((t) => localStorage.setItem('homebase_token', t), token)
  await page.goto('/')
  // the app opens on the Dashboard tab; switch to the Aufgaben (todos) view.
  // exact: the Dashboard also has an "Alle Aufgaben" button that 'Aufgaben' would match.
  await page.getByRole('button', { name: 'Aufgaben', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Aufgaben' })).toBeVisible()
}

// A JWT whose middle segment base64-decodes to {"username":"alice"}, matching the mock's createdBy.
// The default TOKEN is not a real JWT, so `usernameFromToken` yields null; tests that depend on the
// app knowing the current user (e.g. an owner keeping their list after a shared→private flip) seed
// this instead. (payload = btoa('{"username":"alice"}'))
const ALICE_TOKEN = 'x.eyJ1c2VybmFtZSI6ImFsaWNlIn0=.y'

// The redesign shows todos per list tab, so most tests seed a default list.
const HAUSHALT = list({ id: 'l1', name: 'Haushalt' })

test.describe('Todos', () => {
  test('renders todos from the active list', async ({ page }) => {
    const mock = new MockApi(
      [
        todo({ id: 't1', title: 'Milch kaufen', listId: 'l1' }),
        todo({ id: 't2', title: 'Spülmaschine ausräumen', listId: 'l1' }),
      ],
      [HAUSHALT],
    )
    await openApp(page, mock)

    await expect(page.getByText('Milch kaufen')).toBeVisible()
    await expect(page.getByText('Spülmaschine ausräumen')).toBeVisible()
  })

  // Without any list the Inbox becomes the default tab (instead of the former
  // "Noch keine Liste" empty state), so quick-add keeps working and list-less
  // todos stay reachable on a fresh household (#69).
  test('defaults to the Inbox tab when there are no lists', async ({ page }) => {
    await openApp(page, new MockApi([]))

    await expect(page.getByRole('tab', { name: 'Inbox' })).toHaveClass(/is-active/)
    await expect(page.getByText('Inbox ist leer')).toBeVisible()
    await expect(page.getByPlaceholder('Neue Aufgabe in der Inbox …')).toBeVisible()
  })

  test('shows the all-done empty state for a list without open todos', async ({ page }) => {
    await openApp(page, new MockApi([], [HAUSHALT]))
    await expect(page.getByText('Alles erledigt')).toBeVisible()
  })

  test('adds a new todo into the active list via quick-add', async ({ page }) => {
    await openApp(page, new MockApi([], [HAUSHALT]))

    await page.getByPlaceholder('Neue Aufgabe in „Haushalt" …').fill('Pflanzen gießen')
    await page.getByRole('button', { name: 'Erfassen' }).click()

    await expect(page.getByText('Pflanzen gießen')).toBeVisible()
    await expect(page.getByText('Alles erledigt')).toHaveCount(0)
  })

  // Regression #61: the server's own TODO_CREATED echo can reach the client
  // before the REST response is applied (the mock delivers it synchronously via
  // x-ws-frames-pre). The todo must end up in the list exactly once —
  // toHaveCount(1) is the primary catcher (without the dedupe both copies
  // render). React's duplicate-key warning doubles as a second net for the
  // state-level duplicate; it only exists in the React dev build, which holds
  // as long as the Playwright webServer runs `npm run dev`.
  test('does not duplicate a fresh todo when the realtime echo beats the REST response', async ({ page }) => {
    const dupKeyWarnings: string[] = []
    page.on('console', (m) => {
      if (m.text().includes('two children with the same key')) dupKeyWarnings.push(m.text())
    })
    await openApp(page, new MockApi([], [HAUSHALT]))

    await page.getByPlaceholder('Neue Aufgabe in „Haushalt" …').fill('Pflanzen gießen')
    await page.getByRole('button', { name: 'Erfassen' }).click()

    await expect(page.getByText('Pflanzen gießen')).toHaveCount(1)
    expect(dupKeyWarnings).toHaveLength(0)
  })

  // Counterpart to the race spec above: with the realtime echo silenced the
  // REST response is the only source — the insert arm of the dedupe must still
  // add the todo (mirrors the #84 convention pinned for the time view).
  test('adds a todo from the REST response alone when the realtime echo never arrives', async ({ page }) => {
    await openApp(page, new MockApi([], [HAUSHALT]).silenceRealtime())

    await page.getByPlaceholder('Neue Aufgabe in „Haushalt" …').fill('Pflanzen gießen')
    await page.getByRole('button', { name: 'Erfassen' }).click()

    await expect(page.getByText('Pflanzen gießen')).toHaveCount(1)
  })

  test('plans a todo, assigning it', async ({ page }) => {
    const mock = new MockApi([todo({ id: 't1', title: 'Steuer machen', listId: 'l1' })], [HAUSHALT])
    await openApp(page, mock)

    await page.getByRole('button', { name: 'Planen' }).click()
    const dialog = page.locator('.hb-modal')
    // assignee is now a chip picker (names from /config) instead of a text field
    await dialog.locator('.hb-pick', { hasText: 'Max' }).click()
    await dialog.getByRole('button', { name: 'Planen' }).click()

    // Still in the list, but now shows the assignee avatar instead of a Planen button.
    const row = page.locator('.hb-row', { hasText: 'Steuer machen' })
    await expect(row).toBeVisible()
    await expect(row.getByRole('button', { name: 'Planen' })).toHaveCount(0)
  })

  test('completes a todo so it appears under the Erledigt section', async ({ page }) => {
    const mock = new MockApi(
      [todo({ id: 't1', title: 'Rechnung zahlen', status: 'PLANNED', assignee: 'alice', listId: 'l1' })],
      [HAUSHALT],
    )
    await openApp(page, mock)

    await page.locator('.hb-row', { hasText: 'Rechnung zahlen' }).getByRole('checkbox').click()

    // Reveal the collapsible "Erledigt" section.
    await page.locator('.hb-donehead').click()
    const doneRow = page.locator('.hb-row--done', { hasText: 'Rechnung zahlen' })
    await expect(doneRow).toBeVisible()
  })

  test('deletes a todo', async ({ page }) => {
    const mock = new MockApi(
      [
        todo({ id: 't1', title: 'Behalten', listId: 'l1' }),
        todo({ id: 't2', title: 'Löschen', listId: 'l1' }),
      ],
      [HAUSHALT],
    )
    await openApp(page, mock)

    await page.locator('.hb-row', { hasText: 'Löschen' }).getByRole('button', { name: 'Löschen' }).click()

    await expect(page.getByText('Löschen')).toHaveCount(0)
    await expect(page.getByText('Behalten')).toBeVisible()
  })
})

test.describe('Todo lists', () => {
  test('renders the Inbox tab first, a tab per list, plus the add-list tab', async ({ page }) => {
    const mock = new MockApi(
      [todo({ id: 't1', title: 'Müll rausbringen', listId: 'l1' })],
      [list({ id: 'l1', name: 'Haushalt' }), list({ id: 'l2', name: 'Arbeit' })],
    )
    await openApp(page, mock)

    const tabs = page.locator('.hb-tabs')
    await expect(tabs.getByRole('tab').first()).toContainText('Inbox')
    await expect(tabs.getByRole('tab', { name: 'Haushalt' })).toBeVisible()
    await expect(tabs.getByRole('tab', { name: 'Arbeit' })).toBeVisible()
    await expect(tabs.getByRole('button', { name: 'Neue Liste' })).toBeVisible()
    // with lists present the first list stays the default tab, not the Inbox
    await expect(tabs.getByRole('tab', { name: 'Haushalt' })).toHaveClass(/is-active/)
  })

  test('switching tabs shows only that list\'s todos', async ({ page }) => {
    const mock = new MockApi(
      [
        todo({ id: 't1', title: 'Steuer', listId: 'l1' }),
        todo({ id: 't2', title: 'Meeting', listId: 'l2' }),
      ],
      [list({ id: 'l1', name: 'Haushalt' }), list({ id: 'l2', name: 'Arbeit' })],
    )
    await openApp(page, mock)

    // The first list (Haushalt) is active by default — not the Inbox tab.
    await expect(page.getByText('Steuer')).toBeVisible()
    await expect(page.getByText('Meeting')).toHaveCount(0)

    await page.getByRole('tab', { name: 'Arbeit' }).click()
    await expect(page.getByText('Meeting')).toBeVisible()
    await expect(page.getByText('Steuer')).toHaveCount(0)
  })

  test('marks a private list with a lock icon', async ({ page }) => {
    const mock = new MockApi([], [list({ id: 'l1', name: 'Privat', visibility: 'PRIVATE' })])
    await openApp(page, mock)

    const tab = page.getByRole('tab', { name: 'Privat' })
    await expect(tab.locator('svg')).toHaveCount(1)
  })

  test('creates a new private list via the modal', async ({ page }) => {
    await openApp(page, new MockApi([], [HAUSHALT]))

    await page.locator('.hb-tabs').getByRole('button', { name: 'Neue Liste' }).click()
    const modal = page.locator('.hb-modal')
    await expect(modal.getByRole('heading', { name: 'Neue Liste' })).toBeVisible()

    await modal.getByPlaceholder('z. B. Renovierung').fill('Garten')
    await modal.getByRole('button', { name: 'Privat', exact: true }).click()
    await modal.getByRole('button', { name: 'Erstellen' }).click()

    // The new list becomes a tab and is auto-selected.
    const tab = page.locator('.hb-tabs').getByRole('tab', { name: 'Garten' })
    await expect(tab).toBeVisible()
    await expect(tab).toHaveClass(/is-active/)
  })

  test('edits a list: renames it and flips it to private, keeping it for the owner', async ({ page }) => {
    const mock = new MockApi(
      [todo({ id: 't1', title: 'Fenster putzen', listId: 'l1' })],
      [list({ id: 'l1', name: 'Haushalt', createdBy: 'alice' })],
    )
    await openApp(page, mock, ALICE_TOKEN)

    await page.getByRole('button', { name: /Liste bearbeiten „Haushalt/ }).click()
    const modal = page.locator('.hb-modal')
    await expect(modal.getByRole('heading', { name: 'Liste bearbeiten' })).toBeVisible()

    await modal.getByPlaceholder('z. B. Renovierung').fill('Heim')
    await modal.locator('.hb-pick', { hasText: 'Privat' }).click()
    await modal.getByRole('button', { name: 'Speichern' }).click()

    // The owner keeps the list even though the backend broadcasts a TODO_LIST_DELETED for the flip:
    // the tab stays, now renamed and marked private (lock icon), and its todos remain. (issue #75)
    await expect(page.locator('.hb-modal')).toHaveCount(0)
    const tab = page.locator('.hb-tabs').getByRole('tab', { name: 'Heim' })
    await expect(tab).toBeVisible()
    await expect(tab.locator('svg')).toHaveCount(1)
    await expect(page.getByText('Fenster putzen')).toBeVisible()
  })

  test('flips a private list to shared without duplicating its todos', async ({ page }) => {
    const mock = new MockApi(
      [todo({ id: 't1', title: 'Geschenk kaufen', listId: 'l1' })],
      [list({ id: 'l1', name: 'Geheim', visibility: 'PRIVATE', createdBy: 'alice' })],
    )
    await openApp(page, mock, ALICE_TOKEN)

    await page.getByRole('button', { name: /Liste bearbeiten „Geheim/ }).click()
    const modal = page.locator('.hb-modal')
    await modal.locator('.hb-pick', { hasText: 'Geteilt' }).click()
    await modal.getByRole('button', { name: 'Speichern' }).click()

    await expect(page.locator('.hb-modal')).toHaveCount(0)
    // The lock is gone (now shared) and the replayed TODO_CREATED frame must not duplicate the todo.
    const tab = page.locator('.hb-tabs').getByRole('tab', { name: 'Geheim' })
    await expect(tab.locator('svg')).toHaveCount(0)
    await expect(page.locator('.hb-row', { hasText: 'Geschenk kaufen' })).toHaveCount(1)
  })

  test('deletes the active list and its todos', async ({ page }) => {
    const mock = new MockApi(
      [todo({ id: 't1', title: 'Rasen mähen', listId: 'l2' })],
      [list({ id: 'l1', name: 'Haushalt' }), list({ id: 'l2', name: 'Garten' })],
    )
    await openApp(page, mock)

    await page.getByRole('tab', { name: 'Garten' }).click()
    await expect(page.getByText('Rasen mähen')).toBeVisible()

    await page.getByRole('button', { name: /Liste löschen „Garten/ }).click()
    // a confirmation modal must appear first — no immediate delete
    await expect(page.getByRole('heading', { name: 'Liste löschen?' })).toBeVisible()
    await page.getByRole('button', { name: 'Endgültig löschen' }).click()

    await expect(page.locator('.hb-tabs').getByRole('tab', { name: 'Garten' })).toHaveCount(0)
    await expect(page.getByText('Rasen mähen')).toHaveCount(0)
  })
})

// Inbox tab (#69): todos without a listId — created by the Dashboard quick-add
// or the Android FAB — live in a dedicated first tab of the todos view.
test.describe('Inbox', () => {
  test('shows a list-less todo in the Inbox tab and completes it there', async ({ page }) => {
    const mock = new MockApi([todo({ id: 't1', title: 'Glühbirnen kaufen' })], [HAUSHALT])
    await openApp(page, mock)

    // the first list is the default tab; the inbox todo is not part of it
    await expect(page.getByText('Glühbirnen kaufen')).toHaveCount(0)

    // the Inbox tab badge counts the open list-less todos
    const inboxTab = page.locator('.hb-tabs').getByRole('tab', { name: 'Inbox' })
    await expect(inboxTab.locator('.hb-tab__count')).toHaveText('1')

    await inboxTab.click()
    await expect(page.getByText('Glühbirnen kaufen')).toBeVisible()

    await page.locator('.hb-row', { hasText: 'Glühbirnen kaufen' }).getByRole('checkbox').click()
    await page.locator('.hb-donehead').click()
    await expect(page.locator('.hb-row--done', { hasText: 'Glühbirnen kaufen' })).toBeVisible()
    // done → no open inbox todos left, so the badge disappears
    await expect(inboxTab.locator('.hb-tab__count')).toHaveCount(0)
  })

  test('quick-add in the Inbox tab posts without a listId', async ({ page }) => {
    await openApp(page, new MockApi([], [HAUSHALT]))

    await page.locator('.hb-tabs').getByRole('tab', { name: 'Inbox' }).click()
    await expect(page.getByText('Inbox ist leer')).toBeVisible()

    const post = page.waitForRequest((r) => r.url().endsWith('/api/v1/todos') && r.method() === 'POST')
    await page.getByPlaceholder('Neue Aufgabe in der Inbox …').fill('Reifen wechseln')
    await page.getByRole('button', { name: 'Erfassen' }).click()

    // the request body must not carry a listId at all (backend then sets INBOX)
    const body = JSON.parse((await post).postData() ?? '{}')
    expect(body).toEqual({ title: 'Reifen wechseln' })

    // appears exactly once — the WS echo beats the REST response (#61 dedupe)
    await expect(page.locator('.hb-row', { hasText: 'Reifen wechseln' })).toHaveCount(1)
  })

  test('plans an inbox todo into a list, moving it out of the inbox', async ({ page }) => {
    const mock = new MockApi([todo({ id: 't1', title: 'Versicherung kündigen' })], [HAUSHALT])
    await openApp(page, mock)

    await page.locator('.hb-tabs').getByRole('tab', { name: 'Inbox' }).click()
    await page.locator('.hb-row', { hasText: 'Versicherung kündigen' }).getByRole('button', { name: 'Planen' }).click()

    const dialog = page.locator('.hb-modal')
    // inbox todos get an extra list picker in the plan modal
    await dialog.getByLabel('Liste').selectOption({ label: 'Haushalt' })
    await dialog.locator('.hb-pick', { hasText: 'Max' }).click()
    await dialog.getByRole('button', { name: 'Planen' }).click()

    // gone from the inbox …
    await expect(page.locator('.hb-modal')).toHaveCount(0)
    await expect(page.getByText('Versicherung kündigen')).toHaveCount(0)
    await expect(page.getByText('Inbox ist leer')).toBeVisible()

    // … and filed into the list
    await page.locator('.hb-tabs').getByRole('tab', { name: 'Haushalt' }).click()
    await expect(page.locator('.hb-row', { hasText: 'Versicherung kündigen' })).toBeVisible()
  })
})

test.describe('Subtasks', () => {
  const PARTY = (subtasks = [subtask({ id: 's1', title: 'Einladen' })]) =>
    new MockApi([todo({ id: 't1', title: 'Party', listId: 'l1', subtasks })], [HAUSHALT])

  test('expands a row to reveal the subtask editor', async ({ page }) => {
    await openApp(page, new MockApi([todo({ id: 't1', title: 'Umzug planen', listId: 'l1' })], [HAUSHALT]))

    const row = page.locator('.hb-todo', { hasText: 'Umzug planen' })
    await expect(row.locator('.hb-subadd')).toHaveCount(0)

    await row.getByRole('button', { name: 'Unteraufgaben' }).click()
    await expect(row.getByPlaceholder('Unteraufgabe hinzufügen …')).toBeVisible()
  })

  test('adds a subtask and shows the progress count', async ({ page }) => {
    await openApp(page, new MockApi([todo({ id: 't1', title: 'Umzug planen', listId: 'l1' })], [HAUSHALT]))

    const row = page.locator('.hb-todo', { hasText: 'Umzug planen' })
    await row.getByRole('button', { name: 'Unteraufgaben' }).click()
    const input = row.getByPlaceholder('Unteraufgabe hinzufügen …')
    await input.fill('Kartons besorgen')
    await input.press('Enter')

    await expect(row.locator('.hb-subtask', { hasText: 'Kartons besorgen' })).toBeVisible()
    await expect(row.locator('.hb-subtoggle__c')).toContainText('0/1')
  })

  test('checks off a subtask, updating the progress count', async ({ page }) => {
    await openApp(page, PARTY())

    const row = page.locator('.hb-todo', { hasText: 'Party' })
    await expect(row.locator('.hb-subtoggle__c')).toContainText('0/1')

    await row.getByRole('button', { name: 'Unteraufgaben' }).click()
    const sub = row.locator('.hb-subtask', { hasText: 'Einladen' })
    await sub.getByRole('checkbox').click()

    await expect(sub).toHaveClass(/hb-subtask--done/)
    await expect(row.locator('.hb-subtoggle__c')).toContainText('1/1')
  })

  test('deletes a subtask, removing the progress count', async ({ page }) => {
    await openApp(page, PARTY())

    const row = page.locator('.hb-todo', { hasText: 'Party' })
    await row.getByRole('button', { name: 'Unteraufgaben' }).click()
    await row.locator('.hb-subtask', { hasText: 'Einladen' }).getByRole('button', { name: 'Löschen' }).click()

    await expect(row.locator('.hb-subtask')).toHaveCount(0)
    await expect(row.locator('.hb-subtoggle__c')).toHaveCount(0)
  })
})

test.describe('Navigation', () => {
  test('switches between the sidebar nav tabs', async ({ page }) => {
    await openApp(page, new MockApi([]))

    await page.getByRole('button', { name: 'Einkaufsliste' }).click()
    await expect(page.getByRole('heading', { name: 'Einkaufslisten' })).toBeVisible()

    await page.getByRole('button', { name: 'Rezepte' }).click()
    await expect(page.getByRole('heading', { name: 'Rezepte' })).toBeVisible()

    await page.getByRole('button', { name: 'Aufgaben' }).click()
    await expect(page.getByRole('heading', { name: 'Aufgaben' })).toBeVisible()
  })
})
