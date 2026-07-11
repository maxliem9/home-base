import { test, expect, type Page } from '@playwright/test'
import { MockApi, project, timeEntry, workTarget, TOKEN, TOKEN_MAX } from './helpers/mockApi'

/** Logs in, installs the mock backend, and navigates to the time view.
 *  Pass TOKEN_MAX as `token` for specs that need partner semantics (me = max). */
async function openTime(page: Page, mock: MockApi, token: string = TOKEN) {
  await mock.install(page)
  await page.addInitScript((t) => localStorage.setItem('homebase_token', t), token)
  await page.goto('/')
  await page.getByRole('button', { name: 'Zeiterfassung' }).click()
  await expect(page.getByRole('heading', { name: 'Zeiterfassung' })).toBeVisible()
}

/** Logs in, installs the mock backend, and opens Einstellungen → Zeiterfassung,
 *  where project management + the Wochensoll editor now live (#99). */
async function openTimeSettings(page: Page, mock: MockApi) {
  await mock.install(page)
  await page.addInitScript((t) => localStorage.setItem('homebase_token', t), TOKEN)
  await page.goto('/')
  // the gear in the account corner (sidebar foot); scoped so it never matches a
  // view's own "Einstellungen" button (e.g. the calendar's).
  await page.locator('.hb-sidebar').getByRole('button', { name: 'Einstellungen' }).click()
  // the gear opens the Haushalt subpage by default (#100) — switch to Zeiterfassung
  await page.locator('.hb-settings-nav').getByRole('button', { name: 'Zeiterfassung' }).click()
  await expect(page.locator('.hb-settings-body')).toBeVisible()
}

const ARBEIT = project({ id: 'p1', name: 'Arbeit', color: '#4F7A52' })

test.describe('Time tracking', () => {
  test('shows the empty states with no projects', async ({ page }) => {
    await openTime(page, new MockApi())
    await expect(page.getByText('Noch keine Projekte')).toBeVisible()
    await expect(page.locator('.hb-timerhero__live')).toHaveText('Kein Timer aktiv')
  })

  test('renders projects and recent entries', async ({ page }) => {
    const mock = new MockApi()
      .seedProjects([ARBEIT])
      .seedEntries([timeEntry({ id: 'e1', projectId: 'p1', description: 'Meeting', durationSeconds: 3600 })])
    await openTime(page, mock)

    await expect(page.locator('.hb-projcard', { hasText: 'Arbeit' })).toBeVisible()
    await expect(page.getByText('Meeting')).toBeVisible()
  })

  test('starts a timer from a project and stops it', async ({ page }) => {
    await openTime(page, new MockApi().seedProjects([ARBEIT]))

    await page.locator('.hb-projcard', { hasText: 'Arbeit' }).getByRole('button', { name: 'Start' }).click()

    // hero switches to the running state (driven entirely by the WS frame)
    const hero = page.locator('.hb-timerhero')
    await expect(hero).toHaveClass(/is-running/)
    const stop = hero.getByRole('button', { name: 'Stoppen' })
    await expect(stop).toBeVisible()

    await stop.click()
    await expect(page.locator('.hb-timerhero__live')).toHaveText('Kein Timer aktiv')
    // the stopped timer now appears in the recent-entries list
    await expect(page.locator('.hb-list .hb-row')).toHaveCount(1)
  })

  // Regression: TimeView must reflect its own writes from the REST response, not
  // wait for the WebSocket echo. silenceRealtime() drops every WS frame, mirroring
  // a deployment whose realtime echo never reaches the originating client — the
  // exact symptom where a started timer / new entry only appeared after a reload.
  test('reflects writes from REST even when the realtime echo never arrives', async ({ page }) => {
    await openTime(page, new MockApi().seedProjects([ARBEIT]).silenceRealtime())

    // start a timer → hero must switch to running with no WS frame
    await page.locator('.hb-projcard', { hasText: 'Arbeit' }).getByRole('button', { name: 'Start' }).click()
    const hero = page.locator('.hb-timerhero')
    await expect(hero).toHaveClass(/is-running/)

    // stop it → it lands in the recent list (one row, no duplicate)
    await hero.getByRole('button', { name: 'Stoppen' }).click()
    await expect(page.locator('.hb-timerhero__live')).toHaveText('Kein Timer aktiv')
    await expect(page.locator('.hb-list .hb-row')).toHaveCount(1)

    // record a manual entry → appears immediately
    await page.getByRole('button', { name: 'Eintrag erfassen' }).click()
    const sheet = page.locator('.hb-sheet')
    await sheet.getByRole('button', { name: 'Speichern' }).click()
    await expect(sheet).toBeHidden()
    await expect(page.locator('.hb-list .hb-row')).toHaveCount(2)
  })

  test('creates a project', async ({ page }) => {
    await openTime(page, new MockApi())

    await page.locator('.hb-pagehead').getByRole('button', { name: 'Neues Projekt' }).click()
    const modal = page.locator('.hb-modal')
    await modal.getByPlaceholder('Projektname…').fill('Garten')
    await modal.getByRole('button', { name: 'Anlegen' }).click()

    await expect(page.locator('.hb-projcard', { hasText: 'Garten' })).toBeVisible()
  })

  test('records a manual entry', async ({ page }) => {
    await openTime(page, new MockApi().seedProjects([ARBEIT]))

    await page.getByRole('button', { name: 'Eintrag erfassen' }).click()
    const sheet = page.locator('.hb-sheet')
    // defaults: today, 09:00–10:00 → a 1-hour entry
    await sheet.getByRole('button', { name: 'Speichern' }).click()
    await expect(sheet).toBeHidden()

    await expect(page.locator('.hb-list .hb-row')).toHaveCount(1)
    await expect(page.locator('.hb-list .hb-row')).toContainText('1 Std 0 Min')
  })

  test('rejects a manual entry whose end is before its start', async ({ page }) => {
    await openTime(page, new MockApi().seedProjects([ARBEIT]))

    await page.getByRole('button', { name: 'Eintrag erfassen' }).click()
    const sheet = page.locator('.hb-sheet')
    await sheet.getByLabel('Von').fill('10:00')
    await sheet.getByLabel('Bis').fill('09:00')
    await sheet.getByRole('button', { name: 'Speichern' }).click()

    await expect(sheet.getByText('Ende muss nach dem Start liegen')).toBeVisible()
  })

  test('archives a project and reveals it again (in settings)', async ({ page }) => {
    await openTimeSettings(page, new MockApi().seedProjects([ARBEIT]))
    const body = page.locator('.hb-settings-body')

    await body.locator('.hb-row', { hasText: 'Arbeit' }).getByRole('button', { name: 'Archivieren' }).click()
    // archived projects are hidden until toggled on
    await expect(body.locator('.hb-row', { hasText: 'Arbeit' })).toHaveCount(0)

    await body.getByRole('button', { name: 'Archivierte anzeigen' }).click()
    await expect(body.locator('.hb-row', { hasText: 'Arbeit' })).toBeVisible()
  })

  test('opens the project detail page with totals and returns to the overview', async ({ page }) => {
    const mock = new MockApi()
      .seedProjects([ARBEIT])
      .seedEntries([
        timeEntry({ id: 'e1', projectId: 'p1', startedAt: '2026-06-03T08:00:00Z', stoppedAt: '2026-06-03T10:00:00Z', durationSeconds: 7200 }),
      ])
    await openTime(page, mock)

    // the project-name button opens the detail as its own page, not a modal (#32):
    // the project name becomes the page heading and no modal layer is present.
    await page.locator('.hb-projcard', { hasText: 'Arbeit' }).getByRole('button', { name: 'Arbeit', exact: true }).click()
    await expect(page.getByRole('heading', { name: 'Arbeit' })).toBeVisible()
    await expect(page.locator('.hb-modal')).toHaveCount(0)
    await expect(page.locator('.hb-detailpage')).toContainText('2 Std 0 Min') // total

    // the back button returns to the projects overview
    await page.getByRole('button', { name: 'Zurück' }).click()
    await expect(page.getByRole('heading', { name: 'Zeiterfassung' })).toBeVisible()
    await expect(page.locator('.hb-projcard', { hasText: 'Arbeit' })).toBeVisible()
  })

  test('project detail folds absence credits into the per-week list (#31)', async ({ page }) => {
    const mock = new MockApi()
      .seedProjects([ARBEIT])
      .seedTargets([workTarget({ userId: 'alice', projectId: 'p1', weeklyHours: 40, isDefault: true })])
      .seedEntries([
        timeEntry({ id: 'e1', projectId: 'p1', startedAt: '2026-06-03T08:00:00Z', stoppedAt: '2026-06-03T10:00:00Z', durationSeconds: 7200 }),
      ])
      // a full sick day the same week credits the daily target (8h) to the default project
      .seedCredits([{ userId: 'alice', date: '2026-06-03', projectId: 'p1', seconds: 28800, type: 'KRANK' }])
    await openTime(page, mock)

    await page.locator('.hb-projcard', { hasText: 'Arbeit' }).getByRole('button', { name: 'Arbeit', exact: true }).click()
    // total = 2h recorded + 8h credited
    await expect(page.locator('.hb-detailpage')).toContainText('10 Std 0 Min')
    // the week row surfaces the credited hours next to the entry count
    const week = page.locator('.hb-weekrow').first()
    await expect(week.locator('.hb-weekrow__sub')).toContainText('gutgeschrieben')
    await expect(week.locator('.hb-weekrow__ms')).toContainText('10 Std 0 Min')
  })

  // --- Wochensoll & Forecast (#31) ---------------------------------------

  test('shows the expected end at the running timer once a Wochensoll exists', async ({ page }) => {
    const mock = new MockApi()
      .seedProjects([ARBEIT])
      .seedTargets([workTarget({ userId: 'alice', projectId: 'p1', weeklyHours: 40, isDefault: true })])
      .seedEntries([timeEntry({ id: 'e1', projectId: 'p1', startedAt: new Date().toISOString(), stoppedAt: undefined, durationSeconds: undefined })])
    await openTime(page, mock)

    // hero is running and carries the forecast line (on weekends the daily target
    // is 0, so the projected end collapses into "Tagessoll erreicht")
    await expect(page.locator('.hb-timerhero')).toHaveClass(/is-running/)
    await expect(page.locator('.hb-timerhero__eta')).toBeVisible()
    await expect(page.locator('.hb-timerhero__eta')).toHaveText(/Voraussichtlich fertig um \d{1,2}:\d{2}|Tagessoll erreicht/)
  })

  test('shows the week balance card with soll, ist and per-project saldo', async ({ page }) => {
    const mock = new MockApi()
      .seedProjects([ARBEIT])
      .seedTargets([workTarget({ userId: 'alice', projectId: 'p1', weeklyHours: 40, isDefault: true })])
    await openTime(page, mock)

    const card = page.locator('.hb-weektargets')
    await expect(card.getByRole('heading', { name: 'Wochensoll' })).toBeVisible()
    // nothing recorded yet → 0:00 of 40:00, the full week still open
    await expect(card).toContainText('0:00 / 40:00')
    await expect(card).toContainText('noch 40:00')
    // per-project saldo row for the targeted project
    await expect(card.locator('.hb-weektarget__proj', { hasText: 'Arbeit' })).toContainText('-40:00')
  })

  test('configures a weekly target through the Wochensoll editor (in settings)', async ({ page }) => {
    await openTimeSettings(page, new MockApi().seedProjects([ARBEIT]))
    const body = page.locator('.hb-settings-body')

    // no targets yet → the summary says so
    await expect(body.getByText('Noch kein Wochensoll festgelegt.')).toBeVisible()
    await body.getByRole('button', { name: 'Wochensoll bearbeiten' }).click()
    // the editor is now its own full page, not a modal (#128) — no modal layer,
    // the editor title becomes the page heading
    await expect(body.locator('.hb-modal')).toHaveCount(0)
    await expect(body.getByRole('heading', { name: 'Wochensoll konfigurieren' })).toBeVisible()

    // 40 hours on Arbeit for Max — entering the first hours auto-selects the
    // project as the (required) default (#59)
    await body.getByLabel('Std/Woche Arbeit Max').fill('40')
    await expect(body.getByLabel('Standard Arbeit Max')).toBeChecked()
    const requestPromise = page.waitForRequest((r) => r.url().includes('/time/targets/max/p1') && r.method() === 'PUT')
    await body.getByRole('button', { name: 'Speichern' }).click()
    const request = await requestPromise
    expect(request.postDataJSON()).toEqual({ weeklyHours: 40, isDefault: true })

    // saving returns to the overview, whose summary now lists the configured target
    await expect(body.getByRole('heading', { name: 'Wochensoll konfigurieren' })).toBeHidden()
    await expect(body).toContainText('Max')
    await expect(body).toContainText('40 Std/Woche')
  })

  test('schedules a weekly-target change from a future date (effective-dated period)', async ({ page }) => {
    const mock = new MockApi()
      .seedProjects([ARBEIT])
      .seedTargets([workTarget({ userId: 'max', projectId: 'p1', weeklyHours: 40, isDefault: true })])
    await openTimeSettings(page, mock)
    const body = page.locator('.hb-settings-body')

    await body.getByRole('button', { name: 'Wochensoll bearbeiten' }).click()
    await expect(body.getByRole('heading', { name: 'Wochensoll konfigurieren' })).toBeVisible()

    // schedule a new period from 1 Sep 2026 — it is seeded from the current 40h
    const postPromise = page.waitForRequest(
      (r) => r.url().includes('/time/targets/max/periods') && r.method() === 'POST',
    )
    await body.getByRole('button', { name: 'Neuer Zeitraum' }).click()
    const modal = page.locator('.hb-modal')
    await modal.getByLabel('Gültig ab').fill('2026-09-01')
    await modal.getByRole('button', { name: 'Neuer Zeitraum' }).click()
    expect((await postPromise).postDataJSON()).toEqual({ validFrom: '2026-09-01' })
    // still in the editor, now on the new period
    await expect(body.getByRole('heading', { name: 'Wochensoll konfigurieren' })).toBeVisible()
    await expect(body.getByRole('combobox').first()).toHaveValue('2026-09-01')

    // the grid now edits that period (seeded to 40h) — lower it to 32h
    await expect(body.getByLabel('Std/Woche Arbeit Max')).toHaveValue('40')
    await body.getByLabel('Std/Woche Arbeit Max').fill('32')
    const putPromise = page.waitForRequest(
      (r) => r.url().includes('/time/targets/max/p1') && r.method() === 'PUT',
    )
    await body.getByRole('button', { name: 'Speichern' }).click()
    // the PUT carries the period start so the change lands in the new period only
    expect((await putPromise).postDataJSON()).toEqual({ weeklyHours: 32, validFrom: '2026-09-01' })

    // overview summary: still 40h now, with the 32h change noted as scheduled
    await expect(body).toContainText('40 Std/Woche')
    await expect(body).toContainText('32 Std./Woche')
  })

  test('rejects invalid weekly hours inline (in settings)', async ({ page }) => {
    await openTimeSettings(page, new MockApi().seedProjects([ARBEIT]))
    const body = page.locator('.hb-settings-body')

    await body.getByRole('button', { name: 'Wochensoll bearbeiten' }).click()
    await body.getByLabel('Std/Woche Arbeit Max').fill('200')
    await body.getByRole('button', { name: 'Speichern' }).click()
    // the inline error keeps us on the editor page (no navigation back)
    await expect(body.getByText('Stunden müssen zwischen 0 und 168 liegen')).toBeVisible()
    await expect(body.getByRole('heading', { name: 'Wochensoll konfigurieren' })).toBeVisible()
  })

  test('deep-links from the tracker Wochensoll card into settings', async ({ page }) => {
    const mock = new MockApi()
      .seedProjects([ARBEIT])
      .seedTargets([workTarget({ userId: 'alice', projectId: 'p1', weeklyHours: 40, isDefault: true })])
    await openTime(page, mock)

    // the read-only week-balance card stays in the tracker; its gear deep-links into
    // Einstellungen → Zeiterfassung, where the Wochensoll is actually edited (#99).
    const card = page.locator('.hb-weektargets')
    await expect(card).toBeVisible()
    await card.getByRole('button', { name: 'Wochensoll bearbeiten' }).click()
    await expect(page.locator('.hb-settings-body')).toBeVisible()
    await expect(page.locator('.hb-settings-body')).toContainText('Wochensoll')
  })

  test('opens settings from the mobile top-bar gear', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 })
    await new MockApi().seedProjects([ARBEIT]).install(page)
    await page.addInitScript((t) => localStorage.setItem('homebase_token', t), TOKEN)
    await page.goto('/')

    // on mobile the sidebar (and its settings entry) is hidden; the gear lives in
    // the top bar instead (#99). Tapping it opens the same settings surface.
    await expect(page.locator('.hb-sidebar')).toBeHidden()
    await page.locator('.hb-topbar__gear').click()
    const body = page.locator('.hb-settings-body')
    await expect(body).toBeVisible()
    // the gear opens the Haushalt subpage by default (#100)
    await expect(body.getByRole('heading', { name: 'Haushaltsname' })).toBeVisible()
  })

  // The tile specs pin the browser clock (like abwesenheit.spec.ts) so day labels
  // and fallback windows never drift with the real run date.
  test('project tiles show today/this week, falling back to the last active day and week', async ({ page }) => {
    const mock = new MockApi()
      .seedProjects([ARBEIT])
      .seedEntries([
        // one 2h entry on Wed 2026-06-03 — the week before the pinned "today"
        timeEntry({ id: 'e1', projectId: 'p1', startedAt: '2026-06-03T08:00:00Z', stoppedAt: '2026-06-03T10:00:00Z', durationSeconds: 7200 }),
      ])
    await mock.install(page)
    await page.clock.setFixedTime(new Date('2026-06-10T12:00:00Z'))
    await page.addInitScript((t) => localStorage.setItem('homebase_token', t), TOKEN)
    await page.goto('/')
    await page.getByRole('button', { name: 'Zeiterfassung' }).click()

    const tile = page.locator('.hb-projcard', { hasText: 'Arbeit' })
    // no entry today/this week → both lines fall back to the last active day/week
    await expect(tile.locator('.hb-projcard__stat')).toContainText('2:00')
    await expect(tile.locator('.hb-projcard__stat')).toContainText('3. Juni')
    await expect(tile.locator('.hb-projcard__stat2')).toContainText('2:00')
    await expect(tile.locator('.hb-projcard__stat2')).toContainText('Letzte Woche')
  })

  test('project tiles count a running timer into today live', async ({ page }) => {
    const mock = new MockApi()
      .seedProjects([ARBEIT])
      .seedEntries([
        // running since 30 min before the pinned "now"
        timeEntry({ id: 'e1', projectId: 'p1', startedAt: '2026-06-10T11:30:00Z', stoppedAt: undefined, durationSeconds: undefined }),
      ])
    await mock.install(page)
    await page.clock.setFixedTime(new Date('2026-06-10T12:00:00Z'))
    await page.addInitScript((t) => localStorage.setItem('homebase_token', t), TOKEN)
    await page.goto('/')
    await page.getByRole('button', { name: 'Zeiterfassung' }).click()

    const tile = page.locator('.hb-projcard', { hasText: 'Arbeit' })
    await expect(tile.locator('.hb-projcard__stat')).toContainText('Heute')
    await expect(tile.locator('.hb-projcard__stat')).toContainText('0:30')
    await expect(tile.locator('.hb-projcard__stat2')).toContainText('Diese Woche')
  })

  // --- Eintrag splitten (#62) --------------------------------------------

  test('splits an entry into two parts with an untracked break', async ({ page }) => {
    const mock = new MockApi()
      .seedProjects([ARBEIT])
      .seedEntries([
        timeEntry({ id: 'e1', projectId: 'p1', description: 'Meeting', startedAt: '2026-06-03T08:00:00Z', stoppedAt: '2026-06-03T16:00:00Z', durationSeconds: 8 * 3600 }),
      ])
    await openTime(page, mock)

    await page.locator('.hb-list .hb-row').getByRole('button', { name: 'Splitten' }).click()
    const modal = page.locator('.hb-modal')
    await expect(modal.getByText('Eintrag splitten')).toBeVisible()

    // 12:00 local lies inside the entry in UTC and Berlin alike; 30 min break
    await modal.getByLabel('Trennzeit').fill('2026-06-03T12:00')
    await modal.getByLabel('Pause in Minuten (optional)').fill('30')
    const requestPromise = page.waitForRequest((r) => r.url().includes('/time/entries/e1/split') && r.method() === 'POST')
    await modal.getByRole('button', { name: 'Speichern' }).click()
    const request = await requestPromise
    expect(request.postDataJSON().breakMinutes).toBe(30)

    // two parts in the list; the day total shrank by the 30-minute gap
    await expect(modal).toBeHidden()
    await expect(page.locator('.hb-list .hb-row')).toHaveCount(2)
    await expect(page.locator('.hb-daysep__sum')).toHaveText('7 Std 30 Min')
  })

  test('rejects a cut outside the entry inline', async ({ page }) => {
    const mock = new MockApi()
      .seedProjects([ARBEIT])
      .seedEntries([
        timeEntry({ id: 'e1', projectId: 'p1', startedAt: '2026-06-03T08:00:00Z', stoppedAt: '2026-06-03T16:00:00Z', durationSeconds: 8 * 3600 }),
      ])
    await openTime(page, mock)

    await page.locator('.hb-list .hb-row').getByRole('button', { name: 'Splitten' }).click()
    const modal = page.locator('.hb-modal')
    // 22:00 local is after the entry's end in UTC and Berlin alike
    await modal.getByLabel('Trennzeit').fill('2026-06-03T22:00')
    await modal.getByRole('button', { name: 'Speichern' }).click()

    await expect(modal.getByText('Die Trennzeit muss zwischen Start und Ende liegen')).toBeVisible()
    await expect(page.locator('.hb-list .hb-row')).toHaveCount(1)
  })

  test('exports entries as a CSV download with the server-supplied filename', async ({ page }) => {
    const mock = new MockApi()
      .seedProjects([ARBEIT])
      .seedEntries([timeEntry({ id: 'e1', projectId: 'p1', description: 'Meeting', durationSeconds: 5400 })])
    await openTimeSettings(page, mock)

    await page.locator('.hb-settings-body').getByRole('button', { name: 'CSV herunterladen' }).click()
    const modal = page.locator('.hb-modal')
    await expect(modal).toBeVisible()

    // Clicking export must trigger a real browser download whose name is parsed
    // from the response's Content-Disposition header (unfiltered → default name).
    const downloadPromise = page.waitForEvent('download')
    await modal.getByRole('button', { name: 'Exportieren' }).click()
    const download = await downloadPromise

    expect(download.suggestedFilename()).toBe('zeiterfassung_export.csv')
    // the filter modal closes once the download is kicked off
    await expect(modal).toBeHidden()
  })

  test('scopes the export to the selected project and date range', async ({ page }) => {
    const mock = new MockApi()
      .seedProjects([
        project({ id: 'p1', name: 'ProjektEins', color: '#4F7A52' }),
        project({ id: 'p2', name: 'ProjektZwei', color: '#B4654A' }),
      ])
      .seedEntries([
        timeEntry({ id: 'e1', projectId: 'p1', startedAt: '2026-06-10T08:00:00Z', stoppedAt: '2026-06-10T09:00:00Z' }),
        timeEntry({ id: 'e2', projectId: 'p2', startedAt: '2026-06-10T10:00:00Z', stoppedAt: '2026-06-10T11:00:00Z' }),
      ])
    await openTimeSettings(page, mock)

    await page.locator('.hb-settings-body').getByRole('button', { name: 'CSV herunterladen' }).click()
    const modal = page.locator('.hb-modal')
    await modal.getByLabel('Von').fill('2026-06-01')
    await modal.getByLabel('Bis').fill('2026-06-30')
    await modal.locator('select').selectOption({ label: 'ProjektEins' })

    const requestPromise = page.waitForRequest((r) => r.url().includes('/time/export.csv'))
    const downloadPromise = page.waitForEvent('download')
    await modal.getByRole('button', { name: 'Exportieren' }).click()
    const [request, download] = await Promise.all([requestPromise, downloadPromise])

    // The modal inputs become query params: the chosen project + an ISO instant
    // range (the backend reuses the entry-list filters to scope the rows).
    const params = new URL(request.url()).searchParams
    expect(params.get('project_id')).toBe('p1')
    const from = params.get('from')
    const to = params.get('to')
    expect(from).toBeTruthy()
    expect(to).toBeTruthy()
    expect(new Date(from!).getTime()).toBeLessThan(new Date(to!).getTime())
    // and the ranged filename from the server header is applied (zone-independent shape)
    expect(download.suggestedFilename()).toMatch(/^zeiterfassung_\d{4}-\d{2}-\d{2}_\d{4}-\d{2}-\d{2}\.csv$/)
  })

  test('quick month buttons fill the from/to range with whole-month bounds', async ({ page }) => {
    const mock = new MockApi().seedProjects([project({ id: 'p1', name: 'ProjektEins', color: '#4F7A52' })])
    await openTimeSettings(page, mock)

    await page.locator('.hb-settings-body').getByRole('button', { name: 'CSV herunterladen' }).click()
    const modal = page.locator('.hb-modal')
    await modal.getByRole('button', { name: 'Letzter Monat' }).click()

    // A whole month spans from the 1st to the last day of the same month.
    const from = await modal.getByLabel('Von').inputValue()
    const to = await modal.getByLabel('Bis').inputValue()
    expect(from).toMatch(/^\d{4}-\d{2}-01$/)
    expect(to.slice(0, 7)).toBe(from.slice(0, 7))
    expect(new Date(`${to}T00:00:00`).getTime()).toBeGreaterThanOrEqual(new Date(`${from}T00:00:00`).getTime())
  })

  // --- Partner-Einträge: Aktionen mit Confirm-Dialog (#129) -----------------

  test('deleting a partner entry asks via the custom confirm dialog', async ({ page }) => {
    const mock = new MockApi()
      .seedProjects([ARBEIT])
      .seedEntries([
        timeEntry({ id: 'e1', projectId: 'p1', userId: 'max', description: 'Eigener Eintrag', durationSeconds: 3600 }),
        timeEntry({ id: 'e2', projectId: 'p1', userId: 'lea', description: 'Partner-Eintrag', durationSeconds: 1800 }),
      ])
    await openTime(page, mock, TOKEN_MAX)

    // the partner's row now carries the full action set (no lock icon anymore)
    const partnerRow = page.locator('.hb-list .hb-row', { hasText: 'Partner-Eintrag' })
    await partnerRow.getByRole('button', { name: 'Löschen' }).click()
    const dialog = page.locator('.hb-modal')
    await expect(dialog).toContainText('Eintrag von Lea löschen?')

    // cancelling keeps the entry
    await dialog.getByRole('button', { name: 'Abbrechen' }).click()
    await expect(dialog).toBeHidden()
    await expect(partnerRow).toBeVisible()

    // confirming deletes it
    await partnerRow.getByRole('button', { name: 'Löschen' }).click()
    await page.locator('.hb-modal').getByRole('button', { name: 'Bestätigen' }).click()
    await expect(page.locator('.hb-list .hb-row', { hasText: 'Partner-Eintrag' })).toHaveCount(0)

    // an own entry deletes directly, no confirm dialog involved
    await page.locator('.hb-list .hb-row', { hasText: 'Eigener Eintrag' }).getByRole('button', { name: 'Löschen' }).click()
    await expect(page.locator('.hb-modal')).toHaveCount(0)
    await expect(page.locator('.hb-list .hb-row')).toHaveCount(0)
  })

  test('editing a partner entry opens the editor only after confirming', async ({ page }) => {
    const mock = new MockApi()
      .seedProjects([ARBEIT])
      .seedEntries([timeEntry({ id: 'e1', projectId: 'p1', userId: 'lea', description: 'Partner-Eintrag', durationSeconds: 1800 })])
    await openTime(page, mock, TOKEN_MAX)

    await page.locator('.hb-list .hb-row').getByRole('button', { name: 'Bearbeiten' }).click()
    const dialog = page.locator('.hb-modal')
    await expect(dialog).toContainText('Eintrag von Lea bearbeiten?')
    await dialog.getByRole('button', { name: 'Bestätigen' }).click()
    // the edit sheet opens after the confirm
    await expect(page.locator('.hb-sheet')).toContainText('Eintrag bearbeiten')
  })

  test('records an entry for the partner after an explicit confirm', async ({ page }) => {
    await openTime(page, new MockApi().seedProjects([ARBEIT]), TOKEN_MAX)

    await page.getByRole('button', { name: 'Eintrag erfassen' }).click()
    const sheet = page.locator('.hb-sheet')
    // the person selector defaults to self; pick the partner
    await sheet.getByLabel('Person').selectOption('lea')
    await sheet.getByRole('button', { name: 'Speichern' }).click()

    // the sheet stays open underneath; the confirm dialog stacks above it
    const dialog = page.locator('.hb-modal')
    await expect(dialog).toContainText('Eintrag für Lea erfassen?')
    const createPromise = page.waitForRequest(
      (r) => new URL(r.url()).pathname.endsWith('/time/entries') && r.method() === 'POST',
    )
    await dialog.getByRole('button', { name: 'Bestätigen' }).click()
    expect((await createPromise).postDataJSON().userId).toBe('lea')
    await expect(sheet).toBeHidden()
    await expect(page.locator('.hb-list .hb-row')).toHaveCount(1)
  })

  test('Escape on the stacked confirm closes only the dialog, leaving the sheet', async ({ page }) => {
    await openTime(page, new MockApi().seedProjects([ARBEIT]), TOKEN_MAX)

    await page.getByRole('button', { name: 'Eintrag erfassen' }).click()
    const sheet = page.locator('.hb-sheet')
    await sheet.getByLabel('Person').selectOption('lea')
    await sheet.getByRole('button', { name: 'Speichern' }).click()
    const dialog = page.locator('.hb-modal')
    await expect(dialog).toContainText('Eintrag für Lea erfassen?')

    // Escape must dismiss only the topmost overlay (the dialog); the form beneath
    // stays open with its input intact — no entry is created.
    await page.keyboard.press('Escape')
    await expect(dialog).toBeHidden()
    await expect(sheet).toBeVisible()
    await expect(sheet.getByLabel('Person')).toHaveValue('lea')
    await expect(page.locator('.hb-list .hb-row')).toHaveCount(0)
  })

  test('starting the partner timer confirms via the custom dialog', async ({ page }) => {
    await openTime(page, new MockApi().seedProjects([ARBEIT]), TOKEN_MAX)

    // the idle partner strip offers a start on lea's behalf (scoped to its card —
    // the idle hero shows a project pick row of its own)
    const partnerCard = page.locator('.hb-card', { has: page.getByRole('button', { name: 'Für Lea' }) })
    await partnerCard.getByRole('button', { name: 'Für Lea' }).click()
    await partnerCard.locator('.hb-pick', { hasText: 'Arbeit' }).click()
    const dialog = page.locator('.hb-modal')
    await expect(dialog).toContainText('Timer für Lea starten?')
    const startPromise = page.waitForRequest((r) => r.url().includes('/time/entries/start') && r.method() === 'POST')
    await dialog.getByRole('button', { name: 'Bestätigen' }).click()
    expect((await startPromise).postDataJSON().userId).toBe('lea')
    await expect(dialog).toBeHidden()
  })

  // Regression #541: a shift spanning a week boundary must bucket by its START
  // week (matching Android/backend/CSV), not its stop week. Noon→noon so start
  // stays Sunday and stop stays Monday in every realistic TZ (±11h of UTC) — no
  // browser-TZ pin needed. durationSeconds is decoupled from the timestamps and
  // is what the week sum uses.
  test('per-week list buckets an over-week-boundary entry by its START week', async ({ page }) => {
    const mock = new MockApi()
      .seedProjects([ARBEIT])
      // starts Sun 2026-06-07 (week of Mon 1.–7. Juni), stops Mon 2026-06-08
      // (week of Mon 8.–14. Juni) → the earlier week; the old stoppedAt bug put
      // it in the later one.
      .seedEntries([
        timeEntry({ id: 'e1', projectId: 'p1', startedAt: '2026-06-07T12:00:00Z', stoppedAt: '2026-06-08T12:00:00Z', durationSeconds: 7200 }),
      ])
    await openTime(page, mock)

    await page.locator('.hb-projcard', { hasText: 'Arbeit' }).getByRole('button', { name: 'Arbeit', exact: true }).click()

    const week = page.locator('.hb-weekrow')
    await expect(week).toHaveCount(1)
    await expect(week).toContainText('1.–7. Juni')
    await expect(week).not.toContainText('8.–14. Juni')
    await expect(week.locator('.hb-weekrow__ms')).toContainText('2 Std 0 Min')
  })
})
