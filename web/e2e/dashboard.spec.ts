import { test, expect, type Page } from '@playwright/test'
import { MockApi, TOKEN, project, shoppingItem, timeEntry, todo, workTarget } from './helpers/mockApi'

/**
 * Dashboard ("Heute") view — the app's default tab (#131). Date-bucket tests
 * pin the browser clock (page.clock, like time.spec.ts) so "today/tomorrow"
 * never drift with the real run date; tests that round-trip through the mock's
 * Node-side `new Date()` (quick-add, check-off, forecast) run on the real
 * clock instead, with fixtures computed relative to "now".
 */

/** Logs in, installs the mock backend and lands on the dashboard (default tab). */
async function openDashboard(page: Page, mock: MockApi, fixedTime?: Date) {
  await mock.install(page)
  if (fixedTime) await page.clock.setFixedTime(fixedTime)
  await page.addInitScript((t) => localStorage.setItem('homebase_token', t), TOKEN)
  await page.goto('/')
  // the stat row only renders once the initial reads resolved (loading gate)
  await expect(page.locator('.hb-stats')).toBeVisible()
}

/** Value of the stat tile carrying the given label. */
function tile(page: Page, label: string) {
  return page.locator('.hb-stat', { hasText: label }).locator('.hb-stat__value')
}

/** Count shown in the digest-preview line with the given label. */
function digestValue(page: Page, label: string) {
  return page.locator('.hb-digest__line', { hasText: label }).locator('span').last()
}

/** Local YYYY-MM-DD, `offset` days from now (test process and browser share the TZ). */
function isoDaysFromNow(offset: number): string {
  const d = new Date()
  d.setDate(d.getDate() + offset)
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`
}

// Wed 2026-06-10, 12:00 UTC — the local calendar day is 2026-06-10 in UTC and
// Europe/Berlin alike, so every seeded instant below buckets identically.
const PINNED = new Date('2026-06-10T12:00:00Z')

test.describe('Dashboard (Heute)', () => {
  test('shows zeros and the empty states with no data', async ({ page }) => {
    await openDashboard(page, new MockApi())

    for (const label of ['Heute fällig', 'In der Inbox', 'Morgen fällig', 'Heute erledigt']) {
      await expect(tile(page, label)).toHaveText('0')
    }
    await expect(page.getByText('Für heute nichts geplant')).toBeVisible()
    await expect(page.getByText('Kein Timer läuft')).toBeVisible()
    await expect(page.getByText('Alles eingekauft')).toBeVisible()
    for (const label of ['Heute erledigt', 'Neu in der Inbox', 'Morgen fällig']) {
      await expect(digestValue(page, label)).toHaveText('0')
    }
  })

  test('stat tiles bucket todos by the (pinned) local day', async ({ page }) => {
    const mock = new MockApi([
      // counts into "Heute fällig" + the "Heute dran" card
      todo({ id: 't-due-today', title: 'Heute-Task', status: 'PLANNED', dueDate: '2026-06-10', assignee: 'max' }),
      // overdue is its own bucket — must NOT inflate "Heute fällig"
      todo({ id: 't-overdue', title: 'Überfällig-Task', status: 'PLANNED', dueDate: '2026-06-08' }),
      todo({ id: 't-inbox-old', title: 'Alte Inbox-Idee', createdAt: '2026-06-09T08:00:00Z' }),
      todo({ id: 't-inbox-new', title: 'Neue Inbox-Idee', createdAt: '2026-06-10T08:00:00Z' }),
      todo({ id: 't-tomorrow', title: 'Morgen-Task', status: 'PLANNED', dueDate: '2026-06-11' }),
      todo({ id: 't-done-today', title: 'Frisch erledigt', status: 'DONE', doneAt: '2026-06-10T07:00:00Z' }),
      // done yesterday — must NOT count into "Heute erledigt"
      todo({ id: 't-done-old', title: 'Gestern erledigt', status: 'DONE', doneAt: '2026-06-09T18:00:00Z' }),
    ])
    await openDashboard(page, mock, PINNED)

    await expect(tile(page, 'Heute fällig')).toHaveText('1')
    await expect(tile(page, 'In der Inbox')).toHaveText('2') // whole inbox, age-independent (#71)
    await expect(tile(page, 'Morgen fällig')).toHaveText('1')
    await expect(tile(page, 'Heute erledigt')).toHaveText('1')

    // "Heute dran" lists exactly the todo due today
    await expect(page.locator('.hb-row', { hasText: 'Heute-Task' })).toBeVisible()
    await expect(page.locator('.hb-row', { hasText: 'Überfällig-Task' })).toHaveCount(0)
  })

  // Regression for #76: the digest preview must mirror DigestService — "Neu in
  // der Inbox" counts only INBOX todos *created today*, while the stat tile
  // keeps showing the whole inbox (#71). An old inbox todo therefore appears
  // in the tile but not in the preview line.
  test('digest preview counts only today-created inbox todos, unlike the inbox tile', async ({ page }) => {
    const mock = new MockApi([
      todo({ id: 't-inbox-old', title: 'Alte Inbox-Idee', createdAt: '2026-06-09T08:00:00Z' }),
      todo({ id: 't-inbox-new', title: 'Neue Inbox-Idee', createdAt: '2026-06-10T08:00:00Z' }),
      todo({ id: 't-done-today', title: 'Frisch erledigt', status: 'DONE', doneAt: '2026-06-10T07:00:00Z' }),
      todo({ id: 't-tomorrow', title: 'Morgen-Task', status: 'PLANNED', dueDate: '2026-06-11', assignee: 'max' }),
    ])
    await openDashboard(page, mock, PINNED)

    // tile: whole inbox (2) — digest line: only the todo created today (1)
    await expect(tile(page, 'In der Inbox')).toHaveText('2')
    await expect(digestValue(page, 'Neu in der Inbox')).toHaveText('1')
    await expect(digestValue(page, 'Heute erledigt')).toHaveText('1')
    await expect(digestValue(page, 'Morgen fällig')).toHaveText('1')
    // tomorrow's todos are previewed by title incl. assignee
    await expect(page.locator('.hb-digest__sub')).toHaveText('· Morgen-Task (Max)')
  })

  test('quick-add posts only the title and counts once into inbox tile and digest line', async ({ page }) => {
    await openDashboard(page, new MockApi())
    await expect(tile(page, 'In der Inbox')).toHaveText('0')

    const input = page.getByPlaceholder('Schnell erfassen – landet in der Inbox …')
    const requestPromise = page.waitForRequest((r) => r.url().includes('/api/v1/todos') && r.method() === 'POST')
    await input.fill('Windeln kaufen')
    await input.press('Enter')

    // the inbox quick-add sends nothing but the title (no list, no status)
    const request = await requestPromise
    expect(request.postDataJSON()).toEqual({ title: 'Windeln kaufen' })

    // the mock echoes TODO_CREATED *before* the REST response resolves (#61) —
    // the todo must still count exactly once, not twice
    await expect(tile(page, 'In der Inbox')).toHaveText('1')
    // created just now ⇒ also part of tonight's digest (#76 semantics)
    await expect(digestValue(page, 'Neu in der Inbox')).toHaveText('1')
    await expect(input).toHaveValue('')
  })

  test('checking off in "Heute dran" completes optimistically and moves the counts', async ({ page }) => {
    // real clock: the mock stamps doneAt with Node's `new Date()`, which must
    // land on the same local day the page judges "today" by
    const mock = new MockApi([
      todo({ id: 't1', title: 'Blumen gießen', status: 'PLANNED', dueDate: isoDaysFromNow(0), assignee: 'max' }),
    ])
    await openDashboard(page, mock)

    await expect(tile(page, 'Heute fällig')).toHaveText('1')
    await expect(tile(page, 'Heute erledigt')).toHaveText('0')

    const row = page.locator('.hb-row', { hasText: 'Blumen gießen' })
    const requestPromise = page.waitForRequest((r) => r.url().includes('/api/v1/todos/t1') && r.method() === 'PUT')
    await row.getByRole('checkbox').click()

    const request = await requestPromise
    expect(request.postDataJSON()).toEqual({ status: 'DONE' })

    // the row leaves "Heute dran" immediately; the tiles follow
    await expect(page.getByText('Für heute nichts geplant')).toBeVisible()
    await expect(tile(page, 'Heute fällig')).toHaveText('0')
    await expect(tile(page, 'Heute erledigt')).toHaveText('1')
  })

  test('shopping peek lists only open items and checks one off', async ({ page }) => {
    const mock = new MockApi([], [], [], [
      shoppingItem({ id: 's1', name: 'Milch', listId: 'sl1' }),
      shoppingItem({ id: 's2', name: 'Brot', listId: 'sl1' }),
      shoppingItem({ id: 's3', name: 'Butter', listId: 'sl1', checked: true }),
    ])
    await openDashboard(page, mock)

    await expect(page.locator('.hb-row', { hasText: 'Milch' })).toBeVisible()
    await expect(page.locator('.hb-row', { hasText: 'Brot' })).toBeVisible()
    // already checked items don't appear in the peek
    await expect(page.locator('.hb-row', { hasText: 'Butter' })).toHaveCount(0)

    const requestPromise = page.waitForRequest((r) => r.url().includes('/api/v1/shopping/s1') && r.method() === 'PUT')
    await page.locator('.hb-row', { hasText: 'Milch' }).getByRole('checkbox').click()

    const request = await requestPromise
    expect(request.postDataJSON()).toEqual({ checked: true })
    await expect(page.locator('.hb-row', { hasText: 'Milch' })).toHaveCount(0)
    await expect(page.locator('.hb-row', { hasText: 'Brot' })).toBeVisible()
  })

  // The timer peek reads GET /time/running/all (#142) and decorates the line
  // with the forecast's expected end once a Wochensoll exists (#31). On
  // weekends the daily target is 0, so the suffix collapses into "Soll erreicht".
  test('running timer peek shows the live clock plus the expected end, and stops it', async ({ page }) => {
    const mock = new MockApi()
      .seedProjects([project({ id: 'p1', name: 'Arbeit', color: '#4F7A52' })])
      .seedTargets([workTarget({ userId: 'alice', projectId: 'p1', weeklyHours: 40, isDefault: true })])
      .seedEntries([
        timeEntry({
          id: 'e1',
          projectId: 'p1',
          description: 'Konzept',
          startedAt: new Date(Date.now() - 30 * 60_000).toISOString(),
          stoppedAt: undefined,
          durationSeconds: undefined,
        }),
      ])
    await openDashboard(page, mock)

    const widget = page.locator('.hb-runwidget')
    await expect(widget).toContainText('Arbeit')
    await expect(widget).toContainText('Konzept')
    await expect(widget).toContainText(/bis ca\. \d{1,2}:\d{2}|Soll erreicht/)
    // running for ~30 minutes
    await expect(widget.locator('.hb-runwidget__clock')).toHaveText(/^00:3\d:\d{2}$/)

    // stopping another person's timer asks for confirmation first (#142)
    page.once('dialog', (dialog) => dialog.accept())
    const stopPromise = page.waitForRequest((r) => r.url().includes('/time/entries/stop') && r.method() === 'POST')
    await widget.getByRole('button', { name: 'Stoppen' }).click()

    expect((await stopPromise).postDataJSON()).toEqual({ userId: 'alice' })
    await expect(page.getByText('Kein Timer läuft')).toBeVisible()
  })
})
