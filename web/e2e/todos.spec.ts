import { test, expect, type Page } from '@playwright/test'
import { MockApi, todo, list, subtask, TOKEN } from './helpers/mockApi'

/** Logs in by seeding the token, then installs the given mock backend.
 *  `fixedTime` pins the browser clock so the date-driven smart views
 *  (Heute/Morgen/Erledigt, #256) bucket deterministically — no real-clock
 *  midnight-rollover window. (The earlier flakiness here was the nav badge, not
 *  the clock; the sidebar-scoped selector below is the actual fix.) */
async function openApp(page: Page, mock: MockApi, token: string = TOKEN, fixedTime?: Date) {
  await mock.install(page)
  if (fixedTime) await page.clock.setFixedTime(fixedTime)
  await page.addInitScript((t) => localStorage.setItem('homebase_token', t), token)
  await page.goto('/')
  // the app opens on the Dashboard tab; switch to the Aufgaben (todos) view.
  // Scope to the sidebar: the nav item carries an inbox-due badge ("Aufgaben 3")
  // so an exact-name match races the badge fetch, and the Dashboard's "Alle
  // Aufgaben" link (in the main area) would otherwise also match 'Aufgaben'.
  await page.locator('.hb-sidebar').getByRole('button', { name: 'Aufgaben' }).click()
  await expect(page.getByRole('heading', { name: 'Aufgaben' })).toBeVisible()
}

// Wed 2026-06-10, 12:00 UTC — local calendar day is 2026-06-10 in UTC and
// Europe/Berlin alike, so every seeded instant below buckets identically.
const PINNED = new Date('2026-06-10T12:00:00Z')

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

  // #384 (sibling of #377): hitting Enter, then immediately typing + Enter for a second
  // todo (before the first POST resolves) must yield two todos — not one merged
  // "Pflanzen gießenMüll rausbringen". The field has to clear on submit, not only after
  // the await. We hold the first POST open and type the second todo character-by-character
  // so it would *append* to an uncleared field.
  test('adds two todos typed in quick succession without merging them', async ({ page }) => {
    await openApp(page, new MockApi([], [HAUSHALT]))

    // hold the first POST open so the second add starts while it's still in flight
    let release: () => void = () => {}
    const gate = new Promise<void>((r) => { release = r })
    let first = true
    await page.route('**/api/v1/todos', async (route) => {
      if (route.request().method() === 'POST' && first) {
        first = false
        await gate
      }
      return route.fallback()
    })

    const input = page.getByPlaceholder('Neue Aufgabe in „Haushalt" …')
    await input.pressSequentially('Pflanzen gießen')
    await input.press('Enter')          // first POST is now pending behind the gate
    // the field must already be empty (cleared on submit, not after the await) — otherwise
    // these keystrokes append and the next Enter posts the merged title
    await expect(input).toHaveValue('')
    await input.pressSequentially('Müll rausbringen')
    await input.press('Enter')
    release()                           // let the first POST complete

    await expect(page.locator('.hb-row', { hasText: 'Pflanzen gießen' })).toBeVisible()
    await expect(page.locator('.hb-row', { hasText: 'Müll rausbringen' })).toBeVisible()
    // crucially: no merged todo, and "Pflanzen gießen" stands alone
    await expect(page.getByText('Pflanzen gießenMüll rausbringen')).toHaveCount(0)
    await expect(page.locator('.hb-row')).toHaveCount(2)
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
    const dialog = page.locator('.hb-sheet')
    // assignee is now a chip picker (names from /config) instead of a text field
    await dialog.locator('.hb-pick', { hasText: 'Max' }).click()
    await dialog.getByRole('button', { name: 'Speichern' }).click()

    // Still in the list, but now shows the assignee avatar instead of a Planen button.
    const row = page.locator('.hb-row', { hasText: 'Steuer machen' })
    await expect(row).toBeVisible()
    await expect(row.getByRole('button', { name: 'Planen' })).toHaveCount(0)
  })

  test('assigns a todo to both household members via the plan sheet (multi-assignee)', async ({ page }) => {
    const mock = new MockApi([todo({ id: 't1', title: 'Wäsche', listId: 'l1' })], [HAUSHALT])
    await openApp(page, mock)

    await page.getByRole('button', { name: 'Planen' }).click()
    const dialog = page.locator('.hb-sheet')
    // multi-select: picking a second chip adds to the set rather than replacing it
    await dialog.locator('.hb-pick', { hasText: 'Max' }).click()
    await dialog.locator('.hb-pick', { hasText: 'Lea' }).click()
    const put = page.waitForRequest((r) => /\/todos\/t1$/.test(r.url()) && r.method() === 'PUT')
    await dialog.getByRole('button', { name: 'Speichern' }).click()
    expect(JSON.parse((await put).postData() ?? '{}').assignees).toEqual(['max', 'lea'])

    // the row now stacks both avatars and no longer offers a Planen button
    const row = page.locator('.hb-row', { hasText: 'Wäsche' })
    await expect(row.getByRole('button', { name: 'Planen' })).toHaveCount(0)
    await expect(row.locator('.hb-avstack .hb-avatar')).toHaveCount(2)
  })

  test('quick-edits assignees straight from the row (no plan sheet)', async ({ page }) => {
    const mock = new MockApi(
      [todo({ id: 't1', title: 'Müll rausbringen', status: 'PLANNED', assignees: ['max'], listId: 'l1' })],
      [HAUSHALT],
    )
    await openApp(page, mock)

    // clicking the assignee avatars opens the assignee-only quick edit
    await page.locator('.hb-row', { hasText: 'Müll rausbringen' }).locator('.hb-avstack').click()
    const modal = page.locator('.hb-modal')
    await expect(modal.getByText('Zuständig ändern')).toBeVisible()
    await modal.locator('.hb-pick', { hasText: 'Lea' }).click() // add Lea → both
    const put = page.waitForRequest((r) => /\/todos\/t1$/.test(r.url()) && r.method() === 'PUT')
    await modal.getByRole('button', { name: 'Speichern' }).click()
    expect(JSON.parse((await put).postData() ?? '{}').assignees).toEqual(['max', 'lea'])
  })

  test('quick-edits the due date straight from the row date badge', async ({ page }) => {
    const mock = new MockApi(
      [todo({ id: 't1', title: 'Termin', status: 'PLANNED', dueDate: '2026-06-20', listId: 'l1' })],
      [HAUSHALT],
    )
    await openApp(page, mock, TOKEN, PINNED)

    // clicking the due badge opens the date-only quick edit (labelled, saves with "Speichern")
    await page.locator('.hb-row', { hasText: 'Termin' }).locator('.hb-row__chip').first().click()
    const modal = page.locator('.hb-modal')
    await expect(modal.getByText('Fälligkeit ändern')).toBeVisible()
    await modal.locator('input[type="date"]').fill('2026-06-25')
    const put = page.waitForRequest((r) => /\/todos\/t1$/.test(r.url()) && r.method() === 'PUT')
    await modal.getByRole('button', { name: 'Speichern' }).click()
    expect(JSON.parse((await put).postData() ?? '{}').dueDate).toBe('2026-06-25')
  })

  test('plans a todo with a due time and shows it on the row badge (#429)', async ({ page }) => {
    const mock = new MockApi([todo({ id: 't1', title: 'Zahnarzt', listId: 'l1' })], [HAUSHALT])
    await openApp(page, mock)

    await page.getByRole('button', { name: 'Planen' }).click()
    const dialog = page.locator('.hb-sheet')
    // the time input is disabled until a date is picked
    await expect(dialog.locator('input[type="time"]')).toBeDisabled()
    await dialog.locator('input[type="date"]').fill('2026-09-01')
    await expect(dialog.locator('input[type="time"]')).toBeEnabled()
    await dialog.locator('input[type="time"]').fill('09:30')
    await dialog.getByRole('button', { name: 'Speichern' }).click()

    await expect(page.locator('.hb-sheet')).toHaveCount(0)
    // the row's due badge appends the time after the date label
    await expect(page.locator('.hb-row', { hasText: 'Zahnarzt' })).toContainText('09:30')
  })

  test('clears a due date in the plan sheet, removing the badge and sending dueDate "" (#468)', async ({ page }) => {
    // seed a PLANNED todo with both a due date and time so we can prove the cascade clears both
    const mock = new MockApi(
      [todo({ id: 't1', title: 'Reifen wechseln', listId: 'l1', status: 'PLANNED', dueDate: '2026-06-20', dueTime: '08:15' })],
      [HAUSHALT],
    )
    await openApp(page, mock)

    const row = page.locator('.hb-row', { hasText: 'Reifen wechseln' })
    // the due badge (date + time) is on the row to start
    await expect(row.locator('.hb-badge')).toContainText('08:15')

    // a dated todo shows no "Planen" button — the plan sheet opens via the row's edit (pencil) action
    await row.getByRole('button', { name: 'Bearbeiten' }).click()
    const dialog = page.locator('.hb-sheet')
    await expect(dialog.locator('input[type="date"]')).toHaveValue('2026-06-20')

    // emptying the date field must actually clear it on save (was a no-op: undefined = unchanged, #468)
    await dialog.locator('input[type="date"]').fill('')
    // the time input disables and clears in lockstep with the date
    await expect(dialog.locator('input[type="time"]')).toBeDisabled()

    const [req] = await Promise.all([
      page.waitForRequest((r) => r.url().endsWith('/todos/t1') && r.method() === 'PUT'),
      dialog.getByRole('button', { name: 'Speichern' }).click(),
    ])
    // '' clears the date (#265 convention); the time cascades to '' too
    expect(req.postDataJSON()).toMatchObject({ dueDate: '', dueTime: '' })

    await expect(page.locator('.hb-sheet')).toHaveCount(0)
    // the due badge is gone — no date, no leftover time
    await expect(row.locator('.hb-badge')).toHaveCount(0)
  })

  test('sets a priority via the chip row in the plan sheet (#407)', async ({ page }) => {
    const mock = new MockApi([todo({ id: 't1', title: 'Steuer machen', listId: 'l1' })], [HAUSHALT])
    await openApp(page, mock)

    await page.getByRole('button', { name: 'Planen' }).click()
    const dialog = page.locator('.hb-sheet')
    // priority is a chip row now (#407), not a dropdown
    await dialog.locator('.hb-pick', { hasText: 'Hoch' }).click()
    await dialog.locator('.hb-pick', { hasText: 'Max' }).click() // assignee → save allowed
    await dialog.getByRole('button', { name: 'Speichern' }).click()

    await expect(page.locator('.hb-sheet')).toHaveCount(0)
    // the row shows the chosen priority label (PriorityDot withLabel)
    await expect(page.locator('.hb-row', { hasText: 'Steuer machen' })).toContainText('Hoch')
  })

  test('edits a todo description in the plan sheet and shows it in the row', async ({ page }) => {
    const mock = new MockApi([todo({ id: 't1', title: 'Steuer machen', listId: 'l1' })], [HAUSHALT])
    await openApp(page, mock)

    await page.getByRole('button', { name: 'Planen' }).click()
    const dialog = page.locator('.hb-sheet')
    await dialog.getByPlaceholder('Optionale Notiz …').fill('Belege sammeln')
    // planning still needs an assignee or due date — pick one so the save is allowed
    await dialog.locator('.hb-pick', { hasText: 'Max' }).click()
    await dialog.getByRole('button', { name: 'Speichern' }).click()

    await expect(page.locator('.hb-sheet')).toHaveCount(0)
    // the saved description renders in the row meta
    await expect(page.locator('.hb-row', { hasText: 'Steuer machen' })).toContainText('Belege sammeln')
  })

  test('edits a todo title in the plan sheet, keeping it unplanned (#406)', async ({ page }) => {
    const mock = new MockApi([todo({ id: 't1', title: 'Steuer machen', listId: 'l1' })], [HAUSHALT])
    await openApp(page, mock)

    await page.getByRole('button', { name: 'Planen' }).click()
    const dialog = page.locator('.hb-sheet')
    // the title is editable in the sheet now; a title-only change needs no assignee/due date
    await dialog.getByLabel('Titel').fill('Steuererklärung abgeben')
    await dialog.getByRole('button', { name: 'Speichern' }).click()

    await expect(page.locator('.hb-sheet')).toHaveCount(0)
    // row shows the new title; the old one is gone
    await expect(page.locator('.hb-row', { hasText: 'Steuererklärung abgeben' })).toBeVisible()
    await expect(page.getByText('Steuer machen', { exact: true })).toHaveCount(0)
    // still unplanned → the "Planen" button stays (status wasn't flipped to PLANNED)
    await expect(
      page.locator('.hb-row', { hasText: 'Steuererklärung abgeben' }).getByRole('button', { name: 'Planen' }),
    ).toBeVisible()
  })

  test('completes a todo so it appears under the Erledigt section', async ({ page }) => {
    const mock = new MockApi(
      [todo({ id: 't1', title: 'Rechnung zahlen', status: 'PLANNED', assignees: ['alice'], listId: 'l1' })],
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

// Quick-add with an expandable "Details" panel (design handoff): one capture
// control supports fast title-only INBOX capture AND all-at-once PLANNED capture.
test.describe('Quick-add details', () => {
  test('captures a planned todo with assignee and due date via the Details panel', async ({ page }) => {
    await openApp(page, new MockApi([], [HAUSHALT]), TOKEN, PINNED)

    await page.getByPlaceholder('Neue Aufgabe in „Haushalt" …').fill('Auto anmelden')

    // expand the panel and set an assignee + due date
    const qa = page.locator('.hb-qa')
    await qa.getByRole('button', { name: 'Details' }).click()
    await expect(qa.locator('.hb-qa__panel')).toBeVisible()
    await qa.locator('.hb-pick', { hasText: 'Max' }).click()
    await qa.locator('input[type="date"]').fill('2026-06-15')
    // a set field lights the accent dot on the toggle even while the panel is open
    await expect(qa.locator('.hb-qa__dot')).toBeVisible()

    const post = page.waitForRequest((r) => r.url().endsWith('/api/v1/todos') && r.method() === 'POST')
    await page.getByRole('button', { name: 'Erfassen' }).click()
    // the POST carries the planning fields (backend then creates it PLANNED)
    const body = JSON.parse((await post).postData() ?? '{}')
    expect(body).toMatchObject({ title: 'Auto anmelden', listId: 'l1', assignees: ['max'], dueDate: '2026-06-15' })

    // born PLANNED → the row shows the assignee avatar, not a "Planen" button
    const row = page.locator('.hb-row', { hasText: 'Auto anmelden' })
    await expect(row).toBeVisible()
    await expect(row.getByRole('button', { name: 'Planen' })).toHaveCount(0)
    // panel STAYS open after a capture (#408) but its fields reset → the dot clears
    await expect(qa.locator('.hb-qa__panel')).toBeVisible()
    await expect(qa.locator('.hb-qa__dot')).toHaveCount(0)
  })

  test('keeps the Details panel open after capture for the next todo (#408)', async ({ page }) => {
    await openApp(page, new MockApi([], [HAUSHALT]), TOKEN, PINNED)
    const qa = page.locator('.hb-qa')
    const titleInput = page.getByPlaceholder('Neue Aufgabe in „Haushalt" …')

    await titleInput.fill('Erste Aufgabe')
    await qa.getByRole('button', { name: 'Details' }).click()
    await qa.locator('.hb-pick', { hasText: 'Max' }).click()
    await expect(qa.locator('.hb-qa__dot')).toBeVisible()
    await page.getByRole('button', { name: 'Erfassen' }).click()

    // #408: panel stays open and its fields reset (dot gone) → ready for the next capture
    await expect(page.locator('.hb-row', { hasText: 'Erste Aufgabe' })).toBeVisible()
    await expect(qa.locator('.hb-qa__panel')).toBeVisible()
    await expect(qa.locator('.hb-qa__dot')).toHaveCount(0)

    // capture a second todo via the still-open panel, no re-opening
    await titleInput.fill('Zweite Aufgabe')
    await qa.locator('input[type="date"]').fill('2026-06-20')
    await page.getByRole('button', { name: 'Erfassen' }).click()
    await expect(page.locator('.hb-row', { hasText: 'Zweite Aufgabe' })).toBeVisible()
    await expect(qa.locator('.hb-qa__panel')).toBeVisible()
  })

  test('toggles a priority chip and closes the panel with Escape, keeping the title', async ({ page }) => {
    await openApp(page, new MockApi([], [HAUSHALT]))
    const titleInput = page.getByPlaceholder('Neue Aufgabe in „Haushalt" …')
    await titleInput.fill('Reifen wechseln')

    const qa = page.locator('.hb-qa')
    await qa.getByRole('button', { name: 'Details' }).click()
    await expect(qa.locator('.hb-qa__panel')).toBeVisible()

    // a priority chip toggles on, then off again on re-click (clears it)
    const hoch = qa.locator('.hb-pick', { hasText: 'Hoch' })
    await hoch.click()
    await expect(hoch).toHaveClass(/is-active/)
    await expect(qa.locator('.hb-qa__dot')).toBeVisible()
    await hoch.click()
    await expect(hoch).not.toHaveClass(/is-active/)
    await expect(qa.locator('.hb-qa__dot')).toHaveCount(0)

    // Escape closes the panel but must not clear the typed title
    await titleInput.press('Escape')
    await expect(qa.locator('.hb-qa__panel')).toHaveCount(0)
    await expect(titleInput).toHaveValue('Reifen wechseln')
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

    await page.getByRole('button', { name: /Liste „Haushalt" bearbeiten/ }).click()
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

    await page.getByRole('button', { name: /Liste „Geheim" bearbeiten/ }).click()
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

    await page.getByRole('button', { name: /Liste „Garten" löschen/ }).click()
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

    // the Inbox tab badge counts the open list-less todos (asserted first —
    // it anchors the loaded state before the absence check below)
    const inboxTab = page.locator('.hb-tabs').getByRole('tab', { name: 'Inbox' })
    await expect(inboxTab.locator('.hb-tab__count')).toHaveText('1')

    // the first list is the default tab; the inbox todo is not part of it
    await expect(page.getByText('Glühbirnen kaufen')).toHaveCount(0)

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

    const dialog = page.locator('.hb-sheet')
    // inbox todos get an extra list picker in the plan sheet
    await dialog.getByLabel('Liste').selectOption({ label: 'Haushalt' })
    await dialog.locator('.hb-pick', { hasText: 'Max' }).click()
    await dialog.getByRole('button', { name: 'Speichern' }).click()

    // gone from the inbox …
    await expect(page.locator('.hb-sheet')).toHaveCount(0)
    await expect(page.getByText('Versicherung kündigen')).toHaveCount(0)
    await expect(page.getByText('Inbox ist leer')).toBeVisible()

    // … and filed into the list
    await page.locator('.hb-tabs').getByRole('tab', { name: 'Haushalt' }).click()
    await expect(page.locator('.hb-row', { hasText: 'Versicherung kündigen' })).toBeVisible()
  })

  // Inbox ordering (#306): the unplanned, undated todos ("Ohne Datum" — where
  // status-INBOX quick-adds land) render at the TOP of the Inbox tab, newest
  // first (createdAt desc); a dated todo's bucket follows below.
  test('shows undated inbox todos first, newest first, above dated ones', async ({ page }) => {
    const mock = new MockApi(
      [
        // dated → lands in a due bucket that must render BELOW "Ohne Datum" in the inbox
        todo({ id: 't1', title: 'Termin wahrnehmen', dueDate: '2026-06-12', createdAt: '2026-06-01T08:00:00Z' }),
        // two undated inbox todos with distinct createdAt — newer must precede older
        todo({ id: 't2', title: 'Älterer Eintrag', createdAt: '2026-06-02T08:00:00Z' }),
        todo({ id: 't3', title: 'Neuerer Eintrag', createdAt: '2026-06-05T08:00:00Z' }),
      ],
      [HAUSHALT],
    )
    await openApp(page, mock, TOKEN, PINNED)
    await page.locator('.hb-tabs').getByRole('tab', { name: 'Inbox' }).click()

    // "Ohne Datum" section heading renders before the dated bucket heading
    const labels = page.locator('.hb-sectionlabel')
    await expect(labels.first()).toContainText('Ohne Datum')

    // within the inbox, the three rows appear undated-newest, undated-older, then dated
    const rows = page.locator('.hb-row')
    await expect(rows.nth(0)).toContainText('Neuerer Eintrag')
    await expect(rows.nth(1)).toContainText('Älterer Eintrag')
    await expect(rows.nth(2)).toContainText('Termin wahrnehmen')
  })

  // Inbox semantics (#71): "everything unplanned" — a status-INBOX todo counts
  // even when it already sits in a list, and the tab badge uses the exact same
  // rule as the dashboard's inbox tile. Planning it removes it from the inbox
  // while it stays in its list.
  test('counts unplanned list todos like the dashboard tile and releases them once planned', async ({ page }) => {
    const mock = new MockApi(
      [
        todo({ id: 't1', title: 'Akku laden' }), // list-less
        todo({ id: 't2', title: 'Fenster putzen', listId: 'l1' }), // unplanned, in a list
      ],
      [HAUSHALT],
    )
    await mock.install(page)
    await page.addInitScript((t) => localStorage.setItem('homebase_token', t), TOKEN)
    await page.goto('/')

    // dashboard tile counts status INBOX — both todos
    await expect(page.locator('.hb-stat', { hasText: 'In der Inbox' }).locator('.hb-stat__value')).toHaveText('2')

    // the inbox tab badge agrees with the tile
    await page.getByRole('button', { name: 'Aufgaben', exact: true }).click()
    const inboxTab = page.locator('.hb-tabs').getByRole('tab', { name: 'Inbox' })
    await expect(inboxTab.locator('.hb-tab__count')).toHaveText('2')

    await inboxTab.click()
    await expect(page.getByText('Akku laden')).toBeVisible()
    // the unplanned list todo shows up too, marked with its source list
    const listRow = page.locator('.hb-row', { hasText: 'Fenster putzen' })
    await expect(listRow).toContainText('Haushalt')

    // planning it releases it from the inbox; the list picker now appears for filed
    // todos too (#409) and defaults to the current list …
    await listRow.getByRole('button', { name: 'Planen' }).click()
    const dialog = page.locator('.hb-sheet')
    await expect(dialog.getByLabel('Liste')).toHaveValue('l1')
    await dialog.locator('.hb-pick', { hasText: 'Max' }).click()
    await dialog.getByRole('button', { name: 'Speichern' }).click()

    await expect(page.locator('.hb-row', { hasText: 'Fenster putzen' })).toHaveCount(0)
    await expect(inboxTab.locator('.hb-tab__count')).toHaveText('1')

    // … while it stays in its list
    await page.locator('.hb-tabs').getByRole('tab', { name: 'Haushalt' }).click()
    await expect(page.locator('.hb-row', { hasText: 'Fenster putzen' })).toBeVisible()
  })

  test('moves a filed todo to a different list via the plan sheet (#409)', async ({ page }) => {
    const mock = new MockApi(
      [todo({ id: 't1', title: 'Bohrmaschine kaufen', listId: 'l1' })],
      [list({ id: 'l1', name: 'Haushalt' }), list({ id: 'l2', name: 'Garten' })],
    )
    await openApp(page, mock)

    // a filed todo now offers a list picker (#409), defaulting to its current list
    const row = page.locator('.hb-row', { hasText: 'Bohrmaschine kaufen' })
    await row.getByRole('button', { name: 'Planen' }).click()
    const dialog = page.locator('.hb-sheet')
    await expect(dialog.getByLabel('Liste')).toHaveValue('l1')
    await dialog.getByLabel('Liste').selectOption({ label: 'Garten' })
    await dialog.getByRole('button', { name: 'Speichern' }).click()
    await expect(page.locator('.hb-sheet')).toHaveCount(0)

    // moved into Garten, gone from Haushalt
    await page.locator('.hb-tabs').getByRole('tab', { name: 'Garten' }).click()
    await expect(page.locator('.hb-row', { hasText: 'Bohrmaschine kaufen' })).toBeVisible()
    await page.locator('.hb-tabs').getByRole('tab', { name: 'Haushalt' }).click()
    await expect(page.locator('.hb-row', { hasText: 'Bohrmaschine kaufen' })).toHaveCount(0)
  })

  test('removes a filed todo from its list via the plan sheet (#409)', async ({ page }) => {
    const mock = new MockApi([todo({ id: 't1', title: 'Altpapier rausbringen', listId: 'l1' })], [HAUSHALT])
    await openApp(page, mock)

    const row = page.locator('.hb-row', { hasText: 'Altpapier rausbringen' })
    await row.getByRole('button', { name: 'Planen' }).click()
    const dialog = page.locator('.hb-sheet')
    // "Ohne Liste" clears the list assignment (sends '' → backend #265 clears it)
    await dialog.getByLabel('Liste').selectOption({ value: '' })
    await dialog.getByRole('button', { name: 'Speichern' }).click()
    await expect(page.locator('.hb-sheet')).toHaveCount(0)

    // gone from the Haushalt list, now list-less in the Inbox
    await page.locator('.hb-tabs').getByRole('tab', { name: 'Haushalt' }).click()
    await expect(page.locator('.hb-row', { hasText: 'Altpapier rausbringen' })).toHaveCount(0)
    await page.locator('.hb-tabs').getByRole('tab', { name: 'Inbox' }).click()
    await expect(page.locator('.hb-row', { hasText: 'Altpapier rausbringen' })).toBeVisible()
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

// Cross-list "smart" tabs (#256): Alle / Heute / Morgen / Erledigt span every
// list, sit between the Inbox and the list tabs, and mirror the dashboard tiles.
test.describe('Smart views', () => {
  const SMART_LISTS = [list({ id: 'l1', name: 'Haushalt' }), list({ id: 'l2', name: 'Arbeit' })]
  const smartTodos = () => [
    todo({ id: 's1', title: 'Müll rausbringen', status: 'PLANNED', listId: 'l1', dueDate: '2026-06-10' }), // heute
    todo({ id: 's2', title: 'Standup', status: 'PLANNED', listId: 'l2', dueDate: '2026-06-10' }), // heute
    todo({ id: 's3', title: 'Einkaufen', status: 'PLANNED', listId: 'l1', dueDate: '2026-06-11' }), // morgen
    todo({ id: 's4', title: 'Rechnung zahlen', status: 'PLANNED', listId: 'l2', dueDate: '2026-06-08' }), // überfällig
    todo({ id: 's5', title: 'Notiz-Idee', status: 'INBOX' }),
    todo({ id: 's6', title: 'Lampe reparieren', status: 'INBOX', listId: 'l1' }),
    todo({ id: 's7', title: 'Mails beantworten', status: 'DONE', listId: 'l2', doneAt: '2026-06-10T07:00:00Z' }), // heute erledigt
    todo({ id: 's8', title: 'Steuererklärung', status: 'PLANNED', listId: 'l1', dueDate: '2026-06-20' }), // später
  ]
  const tabCount = (page: Page, name: string) => page.getByRole('tab', { name }).locator('.hb-tab__count')

  test('shows the cross-list tabs with counts that mirror the dashboard tiles', async ({ page }) => {
    await openApp(page, new MockApi(smartTodos(), SMART_LISTS), TOKEN, PINNED)

    // order: Inbox, Alle, Heute, Morgen, Erledigt, then the list tabs
    await expect(tabCount(page, 'Inbox')).toHaveText('2') // status INBOX (s5, s6)
    await expect(tabCount(page, 'Alle')).toHaveText('7') // every open todo
    await expect(tabCount(page, 'Heute')).toHaveText('2') // s1, s2
    await expect(tabCount(page, 'Morgen')).toHaveText('1') // s3
    await expect(tabCount(page, 'Erledigt')).toHaveText('1') // s7
    // a real list stays the default tab; quick-add is offered there
    await expect(page.getByRole('tab', { name: 'Haushalt' })).toHaveClass(/is-active/)
  })

  test('Heute lists only today\'s open todos across lists, with origin-list meta and no quick-add', async ({ page }) => {
    await openApp(page, new MockApi(smartTodos(), SMART_LISTS), TOKEN, PINNED)
    await page.getByRole('tab', { name: 'Heute' }).click()

    await expect(page.locator('.hb-row', { hasText: 'Müll rausbringen' })).toBeVisible()
    await expect(page.locator('.hb-row', { hasText: 'Standup' })).toBeVisible()
    // scoped to rows: getByText('Einkaufen') would also match the "Einkaufsliste" nav item
    await expect(page.locator('.hb-row', { hasText: 'Einkaufen' })).toHaveCount(0) // tomorrow
    await expect(page.locator('.hb-row', { hasText: 'Rechnung zahlen' })).toHaveCount(0) // overdue
    // cross-list rows carry their origin list as meta
    await expect(page.locator('.hb-row', { hasText: 'Standup' })).toContainText('Arbeit')
    // smart views are read/triage only — no quick-add input
    await expect(page.locator('.hb-quickadd')).toHaveCount(0)
  })

  test('Morgen lists only tomorrow\'s todos', async ({ page }) => {
    await openApp(page, new MockApi(smartTodos(), SMART_LISTS), TOKEN, PINNED)
    await page.getByRole('tab', { name: 'Morgen' }).click()

    await expect(page.locator('.hb-row', { hasText: 'Einkaufen' })).toBeVisible()
    await expect(page.locator('.hb-row', { hasText: 'Müll rausbringen' })).toHaveCount(0)
  })

  test('Erledigt lists today\'s completed todos directly (no collapse)', async ({ page }) => {
    await openApp(page, new MockApi(smartTodos(), SMART_LISTS), TOKEN, PINNED)
    await page.getByRole('tab', { name: 'Erledigt' }).click()

    await expect(page.locator('.hb-row--done', { hasText: 'Mails beantworten' })).toBeVisible()
    await expect(page.locator('.hb-row', { hasText: 'Müll rausbringen' })).toHaveCount(0)
  })

  // "Alle anzeigen" lifts the 14-day done window to reveal the full history (#340).
  // s9 is completed 2026-05-01 (>14 days before the pinned 2026-06-10) → hidden by default.
  test('Erledigt "Alle anzeigen" reveals done todos older than the 14-day window', async ({ page }) => {
    const todos = [
      ...smartTodos(),
      todo({ id: 's9', title: 'Altlast erledigt', status: 'DONE', listId: 'l1', doneAt: '2026-05-01T07:00:00Z' }),
    ]
    await openApp(page, new MockApi(todos, SMART_LISTS), TOKEN, PINNED)
    await page.getByRole('tab', { name: 'Erledigt' }).click()

    // within-window done shows; the old one is hidden behind the 14-day cap
    await expect(page.locator('.hb-row--done', { hasText: 'Mails beantworten' })).toBeVisible()
    await expect(page.locator('.hb-row', { hasText: 'Altlast erledigt' })).toHaveCount(0)
    // the tab COUNT stays on "today" (s7) — unaffected by show-all
    await expect(tabCount(page, 'Erledigt')).toHaveText('1')

    await page.getByRole('button', { name: 'Alle anzeigen' }).click()
    await expect(page.locator('.hb-row--done', { hasText: 'Altlast erledigt' })).toBeVisible()
    await expect(page.locator('.hb-row--done', { hasText: 'Mails beantworten' })).toBeVisible()
    await expect(tabCount(page, 'Erledigt')).toHaveText('1') // still today-only

    // toggling back re-applies the window
    await page.getByRole('button', { name: 'Nur letzte 14 Tage' }).click()
    await expect(page.locator('.hb-row', { hasText: 'Altlast erledigt' })).toHaveCount(0)
  })

  // A *configured* done window (#356/#357) drives the Erledigt view, not the hardcoded 14.
  // s10 is completed 2026-05-20 — >14 but ≤30 days before the pinned 2026-06-10, so it is
  // OUTSIDE the default 14-day window yet INSIDE a configured 30-day one. The mock's
  // GET /config/done-window stub is parameterized via seedDoneWindow(30) (TodosView fetches
  // it on mount); no per-spec route hand-rolling.
  test('Erledigt honours a configured (non-default) done window from /config/done-window', async ({ page }) => {
    const olderDone = todo({ id: 's10', title: 'Vor-3-Wochen erledigt', status: 'DONE', listId: 'l1', doneAt: '2026-05-20T07:00:00Z' })

    // Default 14-day window: the 3-weeks-ago todo is hidden (proves the window is what drives it).
    await openApp(page, new MockApi([...smartTodos(), olderDone], SMART_LISTS), TOKEN, PINNED)
    await page.getByRole('tab', { name: 'Erledigt' }).click()
    await expect(page.locator('.hb-row--done', { hasText: 'Mails beantworten' })).toBeVisible() // today, in window
    await expect(page.locator('.hb-row', { hasText: 'Vor-3-Wochen erledigt' })).toHaveCount(0) // >14 days → hidden

    // Configured 30-day window: the same todo is now visible WITHOUT toggling "Alle anzeigen".
    const mock30 = new MockApi([...smartTodos(), olderDone], SMART_LISTS).seedDoneWindow(30)
    await openApp(page, mock30, TOKEN, PINNED)
    await page.getByRole('tab', { name: 'Erledigt' }).click()
    await expect(page.locator('.hb-row--done', { hasText: 'Vor-3-Wochen erledigt' })).toBeVisible() // ≤30 days → shown
    await expect(page.locator('.hb-row--done', { hasText: 'Mails beantworten' })).toBeVisible()
    // the tab COUNT still tracks "today" (s7) — the window only governs the list, not the badge
    await expect(tabCount(page, 'Erledigt')).toHaveText('1')
    // the window note above the list reflects the configured number, not the hardcoded 14
    await expect(page.locator('.hb-donewindowbar')).toContainText('Letzte 30 Tage')
  })

  test('Alle buckets every list\'s open todos and keeps a collapsible done section', async ({ page }) => {
    await openApp(page, new MockApi(smartTodos(), SMART_LISTS), TOKEN, PINNED)
    await page.getByRole('tab', { name: 'Alle' }).click()

    // open todos from both lists, grouped by due bucket
    await expect(page.locator('.hb-row', { hasText: 'Rechnung zahlen' })).toBeVisible() // Überfällig
    await expect(page.locator('.hb-row', { hasText: 'Müll rausbringen' })).toBeVisible() // Heute
    await expect(page.locator('.hb-row', { hasText: 'Notiz-Idee' })).toBeVisible() // Ohne Datum
    // done is hidden behind the collapsible section, not shown inline
    await expect(page.locator('.hb-row', { hasText: 'Mails beantworten' })).toHaveCount(0)
    await page.locator('.hb-donehead').click()
    await expect(page.locator('.hb-row--done', { hasText: 'Mails beantworten' })).toBeVisible()
  })
})
