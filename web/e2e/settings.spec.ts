import { test, expect, type Page } from '@playwright/test'
import { MockApi, TOKEN, TOKEN_MAX } from './helpers/mockApi'

/** Logs in and lands on the dashboard with the mock backend installed. */
async function openApp(page: Page, mock: MockApi) {
  await mock.install(page)
  await page.addInitScript((t) => localStorage.setItem('homebase_token', t), TOKEN)
  await page.goto('/')
}

test.describe('Settings — Haushalt (#100)', () => {
  test('the gear opens the Haushalt subpage; renaming updates the sidebar brand live', async ({ page }) => {
    await openApp(page, new MockApi())

    // the brand starts at the seeded household name (mock default)
    const brand = page.locator('.hb-sidebar .hb-brand__sub')
    await expect(brand).toHaveText('Mäxchen')

    // the account-corner gear opens settings on the Haushalt subpage by default
    await page.locator('.hb-sidebar').getByRole('button', { name: 'Einstellungen' }).click()
    const body = page.locator('.hb-settings-body')
    await expect(body.getByRole('heading', { name: 'Haushaltsname' })).toBeVisible()

    await body.getByLabel('Name').fill('Familie Test')
    const reqP = page.waitForRequest((r) => r.url().endsWith('/api/v1/config') && r.method() === 'PUT')
    await body.getByRole('button', { name: 'Speichern' }).click()
    expect((await reqP).postDataJSON()).toEqual({ householdName: 'Familie Test' })

    // the live brand updates without a reload, and the saved hint appears
    await expect(brand).toHaveText('Familie Test')
    await expect(body.getByText('Gespeichert')).toBeVisible()
  })

  test('the Zeiterfassung subpage is still reachable from the sub-rail', async ({ page }) => {
    await openApp(page, new MockApi())
    await page.locator('.hb-sidebar').getByRole('button', { name: 'Einstellungen' }).click()
    await page.locator('.hb-settings-nav').getByRole('button', { name: 'Zeiterfassung' }).click()
    await expect(page.locator('.hb-settings-body').getByRole('heading', { name: 'Projekte' })).toBeVisible()
  })

  test('shows the household members list with avatars and capitalised names (#100)', async ({ page }) => {
    // The mock's /users stub returns [{ username: 'max' }, { username: 'lea' }].
    await openApp(page, new MockApi())
    await page.locator('.hb-sidebar').getByRole('button', { name: 'Einstellungen' }).click()

    const body = page.locator('.hb-settings-body')
    // The Mitglieder section heading must be visible
    await expect(body.getByRole('heading', { name: 'Mitglieder' })).toBeVisible()

    const card = body.locator('.hb-members-card')
    // Each member must be rendered with their display name (capitalised username)
    await expect(card.getByText('Max')).toBeVisible()
    await expect(card.getByText('Lea')).toBeVisible()
    // Each member must have an avatar (hb-avatar)
    const avatars = card.locator('.hb-avatar')
    await expect(avatars).toHaveCount(2)
  })
})

test.describe('Settings — Konto (#100)', () => {
  async function openKonto(page: Page, mock: MockApi) {
    await openApp(page, mock)
    await page.locator('.hb-sidebar').getByRole('button', { name: 'Einstellungen' }).click()
    await page.locator('.hb-settings-nav').getByRole('button', { name: 'Konto' }).click()
    await expect(page.locator('.hb-settings-body').getByRole('heading', { name: 'Passwort ändern' })).toBeVisible()
  }

  test('changes the password — sends current+new and shows the saved hint', async ({ page }) => {
    await openKonto(page, new MockApi())
    const body = page.locator('.hb-settings-body')
    await body.getByLabel('Aktuelles Passwort').fill('geheim')
    await body.getByLabel('Neues Passwort', { exact: true }).fill('neuespasswort')
    await body.getByLabel('Neues Passwort wiederholen').fill('neuespasswort')

    const reqP = page.waitForRequest((r) => r.url().endsWith('/users/me/password') && r.method() === 'PUT')
    await body.getByRole('button', { name: 'Passwort ändern' }).click()
    expect((await reqP).postDataJSON()).toEqual({ currentPassword: 'geheim', newPassword: 'neuespasswort' })

    await expect(body.getByText('Passwort geändert')).toBeVisible()
  })

  test('surfaces the backend error when the current password is wrong', async ({ page }) => {
    await openKonto(page, new MockApi())
    const body = page.locator('.hb-settings-body')
    await body.getByLabel('Aktuelles Passwort').fill('falsch')
    await body.getByLabel('Neues Passwort', { exact: true }).fill('neuespasswort')
    await body.getByLabel('Neues Passwort wiederholen').fill('neuespasswort')
    await body.getByRole('button', { name: 'Passwort ändern' }).click()
    await expect(body.getByText('Aktuelles Passwort stimmt nicht.')).toBeVisible()
  })

  test('rejects mismatched new passwords client-side, without a request', async ({ page }) => {
    await openKonto(page, new MockApi())
    const body = page.locator('.hb-settings-body')
    let putFired = false
    page.on('request', (r) => { if (r.url().endsWith('/users/me/password')) putFired = true })
    await body.getByLabel('Aktuelles Passwort').fill('geheim')
    await body.getByLabel('Neues Passwort', { exact: true }).fill('neuespasswort')
    await body.getByLabel('Neues Passwort wiederholen').fill('anderspasswort')
    await body.getByRole('button', { name: 'Passwort ändern' }).click()
    await expect(body.getByText('Die neuen Passwörter stimmen nicht überein.')).toBeVisible()
    expect(putFired).toBe(false)
  })

  test('rejects a new password equal to the current one client-side, without a request', async ({ page }) => {
    await openKonto(page, new MockApi())
    const body = page.locator('.hb-settings-body')
    let putFired = false
    page.on('request', (r) => { if (r.url().endsWith('/users/me/password')) putFired = true })
    // an 8+ char value identical to the current one — long enough to clear the length
    // guard so the same-as-old guard is the one that fires.
    await body.getByLabel('Aktuelles Passwort').fill('geheim42')
    await body.getByLabel('Neues Passwort', { exact: true }).fill('geheim42')
    await body.getByLabel('Neues Passwort wiederholen').fill('geheim42')
    await body.getByRole('button', { name: 'Passwort ändern' }).click()
    await expect(body.getByText('Neues Passwort muss sich vom alten unterscheiden.')).toBeVisible()
    expect(putFired).toBe(false)
  })

  test('theme selector persists the choice and applies data-theme on <html> (#100)', async ({ page }) => {
    await openKonto(page, new MockApi())
    const body = page.locator('.hb-settings-body')
    const html = page.locator('html')

    // the Darstellung card is on the Konto page; default starts at the index.html light
    await expect(body.getByRole('heading', { name: 'Darstellung' })).toBeVisible()
    await expect(html).toHaveAttribute('data-theme', 'light')

    // picking "Dunkel" PUTs the pref and flips the document immediately
    const reqP = page.waitForRequest((r) => r.url().endsWith('/user-prefs/theme') && r.method() === 'PUT')
    await body.getByRole('tab', { name: 'Dunkel', exact: true }).click()
    expect((await reqP).postDataJSON()).toEqual({ value: 'dark' })
    await expect(html).toHaveAttribute('data-theme', 'dark')

    // switching back to "Hell" persists + applies again
    const reqP2 = page.waitForRequest((r) => r.url().endsWith('/user-prefs/theme') && r.method() === 'PUT')
    await body.getByRole('tab', { name: 'Hell', exact: true }).click()
    expect((await reqP2).postDataJSON()).toEqual({ value: 'light' })
    await expect(html).toHaveAttribute('data-theme', 'light')
  })

  test('avatar-colour picker persists a swatch and recolours the avatar (#100)', async ({ page }) => {
    // The picker only renders for a resolved `me`, so log in as "max" (TOKEN_MAX).
    const mock = new MockApi()
    await mock.install(page)
    await page.addInitScript((t) => localStorage.setItem('homebase_token', t), TOKEN_MAX)
    await page.goto('/')
    await page.locator('.hb-sidebar').getByRole('button', { name: 'Einstellungen' }).click()
    await page.locator('.hb-settings-nav').getByRole('button', { name: 'Konto' }).click()

    const body = page.locator('.hb-settings-body')
    await expect(body.getByRole('heading', { name: 'Avatar-Farbe' })).toBeVisible()
    const card = body.locator('.hb-card', { hasText: 'Avatar-Farbe' })

    // Defaults to "Automatisch" (no override stored) → the auto pill is active.
    await expect(card.locator('.hb-avatar-auto')).toHaveClass(/is-active/)

    // Pick the hue-210 swatch → PUT /users/me/avatar-color {hue:210}.
    const swatch210 = card.getByRole('button', { name: 'Farbe 210' })
    const reqP = page.waitForRequest((r) => r.url().endsWith('/users/me/avatar-color') && r.method() === 'PUT')
    await swatch210.click()
    expect((await reqP).postDataJSON()).toEqual({ hue: 210 })

    // The chosen swatch becomes active and the auto pill is no longer active (optimistic
    // update through the shared AvatarHues context, no reload).
    await expect(swatch210).toHaveClass(/is-active/)
    await expect(card.locator('.hb-avatar-auto')).not.toHaveClass(/is-active/)

    // The live preview avatar (header of the card) recolours to the hue-210 avatar.
    await expect(card.locator('.hb-cardhead .hb-avatar')).toHaveAttribute(
      'style',
      /oklch\(0\.92 0\.045 210\)/,
    )

    // "Automatisch" clears it back → PUT {hue:null}, auto pill active again.
    const clearP = page.waitForRequest((r) => r.url().endsWith('/users/me/avatar-color') && r.method() === 'PUT')
    await card.locator('.hb-avatar-auto').click()
    expect((await clearP).postDataJSON()).toEqual({ hue: null })
    await expect(card.locator('.hb-avatar-auto')).toHaveClass(/is-active/)
  })

  test('a stored dark theme is applied on load (#100)', async ({ page }) => {
    // the mock serves theme=dark from /user-prefs, so the app should resolve to dark
    const mock = new MockApi()
    await mock.install(page)
    await page.route('**/api/v1/user-prefs', (route) => {
      if (route.request().method() === 'GET') {
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ theme: 'dark' }) })
      }
      return route.fallback()
    })
    await page.addInitScript((t) => localStorage.setItem('homebase_token', t), TOKEN)
    await page.goto('/')
    await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark')
  })

  test('language switcher flips the UI to English and persists the choice (#6)', async ({ page }) => {
    await openKonto(page, new MockApi())
    const body = page.locator('.hb-settings-body')
    // German is the default: the card and the sidebar read German.
    await expect(body.getByRole('heading', { name: 'Sprache' })).toBeVisible()
    await expect(page.locator('.hb-sidebar').getByRole('button', { name: 'Aufgaben' })).toBeVisible()
    // The document lang attribute reflects the active language (#208).
    await expect(page.locator('html')).toHaveAttribute('lang', 'de')

    // Switching to "Englisch" re-renders every consumer live (no reload).
    await body.getByRole('tab', { name: 'Englisch', exact: true }).click()
    await expect(body.getByRole('heading', { name: 'Language' })).toBeVisible()
    await expect(page.locator('.hb-sidebar').getByRole('button', { name: 'Tasks' })).toBeVisible()
    // …and the lang attribute follows the switch (#208).
    await expect(page.locator('html')).toHaveAttribute('lang', 'en')

    // The choice is persisted for the next load.
    expect(await page.evaluate(() => localStorage.getItem('homebase_lang'))).toBe('en')
  })
})

test.describe('Settings — Benachrichtigungen (#100)', () => {
  async function openDigest(page: Page, mock: MockApi) {
    await openApp(page, mock)
    await page.locator('.hb-sidebar').getByRole('button', { name: 'Einstellungen' }).click()
    await page.locator('.hb-settings-nav').getByRole('button', { name: 'Benachrichtigungen' }).click()
    await expect(page.locator('.hb-settings-body').getByRole('heading', { name: 'Morgen-Digest' })).toBeVisible()
  }

  test('shows the morning-digest time (+ inactive note) and saves a change', async ({ page }) => {
    await openDigest(page, new MockApi())
    // scope to the morning card — the page also has the evening + recurring cards
    const card = page.locator('.hb-settings-body .hb-card', { hasText: 'Morgen-Digest' })

    // the mock reports Telegram unconfigured → the inactive note shows, but the controls are editable
    await expect(card.getByText(/Telegram ist nicht konfiguriert/)).toBeVisible()
    await expect(card.getByLabel('Uhrzeit', { exact: true })).toHaveValue('07:00')
    // enabled defaults on; all morning sections selected
    await expect(card.getByRole('checkbox', { name: 'Digest aktiv' })).toHaveAttribute('aria-checked', 'true')
    await expect(card.getByRole('checkbox', { name: 'Heute fällig', exact: true })).toHaveAttribute('aria-checked', 'true')

    await card.getByLabel('Uhrzeit', { exact: true }).fill('06:15')
    const reqP = page.waitForRequest((r) => r.url().endsWith('/config/morning-digest') && r.method() === 'PUT')
    await card.getByRole('button', { name: 'Speichern' }).click()
    // #182: time + enabled + the full ordered section selection go in one PUT
    expect((await reqP).postDataJSON()).toEqual({
      time: '06:15',
      enabled: true,
      sections: ['morning_due_today', 'morning_overdue', 'morning_inbox', 'morning_absent', 'morning_kita'],
    })
    await expect(card.getByText('Gespeichert')).toBeVisible()
  })

  test('shows the evening digest time and saves a change', async ({ page }) => {
    await openDigest(page, new MockApi())
    // scope to the evening card — the page also has the morning + recurring cards
    const card = page.locator('.hb-settings-body .hb-card', { hasText: 'Abend-Digest' })

    await expect(card.getByLabel('Uhrzeit', { exact: true })).toHaveValue('20:00')
    // the evening tomorrow-preview sections (#182) are offered + selected by default
    await expect(card.getByRole('checkbox', { name: 'Morgen abwesend (Vorschau)', exact: true })).toHaveAttribute('aria-checked', 'true')

    await card.getByLabel('Uhrzeit', { exact: true }).fill('07:30')
    const reqP = page.waitForRequest((r) => r.url().endsWith('/config/digest') && r.method() === 'PUT')
    await card.getByRole('button', { name: 'Speichern' }).click()
    expect((await reqP).postDataJSON()).toEqual({
      time: '07:30',
      enabled: true,
      sections: [
        'evening_done_today', 'evening_new_inbox', 'evening_due_tomorrow',
        'evening_absent_tomorrow', 'evening_kita_tomorrow',
      ],
    })
    await expect(card.getByText('Gespeichert')).toBeVisible()
  })

  test('toggles a digest off and deselects a section, persisting both (#182)', async ({ page }) => {
    await openDigest(page, new MockApi())
    const card = page.locator('.hb-settings-body .hb-card', { hasText: 'Abend-Digest' })

    // turn the whole digest off and drop the "done today" section
    await card.getByRole('checkbox', { name: 'Digest aktiv' }).click()
    await card.getByRole('checkbox', { name: 'Heute erledigt', exact: true }).click()

    const reqP = page.waitForRequest((r) => r.url().endsWith('/config/digest') && r.method() === 'PUT')
    await card.getByRole('button', { name: 'Speichern' }).click()
    const body = (await reqP).postDataJSON()
    expect(body.enabled).toBe(false)
    // the dropped section is gone; the rest keep their order
    expect(body.sections).toEqual([
      'evening_new_inbox', 'evening_due_tomorrow', 'evening_absent_tomorrow', 'evening_kita_tomorrow',
    ])
    await expect(card.getByText('Gespeichert')).toBeVisible()
    // the toggle reflects the saved (off) state
    await expect(card.getByRole('checkbox', { name: 'Digest aktiv' })).toHaveAttribute('aria-checked', 'false')
  })

  test('shows the recurring-todo run time and saves a change (#100)', async ({ page }) => {
    await openDigest(page, new MockApi())
    // the recurring-todo card sits in the same Benachrichtigungen page, below the digest card
    const card = page.locator('.hb-settings-body .hb-card', { hasText: 'Wiederholungs-Planer' })
    await expect(card).toBeVisible()
    await expect(card.getByLabel('Uhrzeit für wiederkehrende Aufgaben')).toHaveValue('00:30')

    await card.getByLabel('Uhrzeit für wiederkehrende Aufgaben').fill('05:45')
    const reqP = page.waitForRequest((r) => r.url().endsWith('/config/recurring') && r.method() === 'PUT')
    await card.getByRole('button', { name: 'Speichern' }).click()
    expect((await reqP).postDataJSON()).toEqual({ time: '05:45' })
    await expect(card.getByText('Gespeichert')).toBeVisible()
  })
})

test.describe('Settings — Einkaufskategorien (#411)', () => {
  async function openShopping(page: Page, mock: MockApi) {
    await openApp(page, mock)
    await page.locator('.hb-sidebar').getByRole('button', { name: 'Einstellungen' }).click()
    await page.locator('.hb-settings-nav').getByRole('button', { name: 'Einkaufskategorien' }).click()
    await expect(page.locator('.hb-settings-body').getByRole('heading', { name: 'Kategorien' })).toBeVisible()
  }

  test('renders the seeded categories and rules', async ({ page }) => {
    await openShopping(page, new MockApi())
    const catCard = page.locator('.hb-settings-body .hb-card', { hasText: 'Kategorien' }).first()
    // a couple of the 10 seeded builtins
    await expect(catCard.getByText('Obst & Gemüse')).toBeVisible()
    await expect(catCard.getByText('Sonstiges')).toBeVisible()

    const ruleCard = page.locator('.hb-settings-body .hb-card', { hasText: 'Auto-Zuordnungsregeln' })
    // the two seeded rules (milch→DAIRY, pizza→FROZEN)
    await expect(ruleCard.getByText('Milch')).toBeVisible()
    await expect(ruleCard.getByText('Pizza')).toBeVisible()
  })

  test('adds a category — POSTs label + emoji', async ({ page }) => {
    await openShopping(page, new MockApi())
    const card = page.locator('.hb-settings-body .hb-card', { hasText: 'Kategorien' }).first()

    await card.getByRole('button', { name: 'Kategorie hinzufügen' }).click()
    await card.getByLabel('Emoji', { exact: true }).fill('🍷')
    await card.getByLabel('Bezeichnung', { exact: true }).fill('Wein')

    const reqP = page.waitForRequest((r) => r.url().endsWith('/shopping/categories') && r.method() === 'POST')
    await card.getByRole('button', { name: 'Speichern' }).click()
    expect((await reqP).postDataJSON()).toMatchObject({ label: 'Wein', emoji: '🍷' })

    // the new category appears (mock assigns a key + broadcasts → refetch)
    await expect(card.getByText('Wein')).toBeVisible()
  })

  test('deletes a custom category via the confirm dialog', async ({ page }) => {
    // seed one extra custom category so there is a deletable, non-builtin row
    const mock = new MockApi().seedShoppingCategories([
      { key: 'OTHER', label: 'Sonstiges', emoji: '❓', sortOrder: 9, isBuiltin: true },
      { key: 'WINE', label: 'Wein', emoji: '🍷', sortOrder: 10, isBuiltin: false },
    ])
    await openShopping(page, mock)
    const card = page.locator('.hb-settings-body .hb-card', { hasText: 'Kategorien' }).first()
    const wineRow = card.locator('.hb-row', { hasText: 'Wein' })

    await wineRow.getByRole('button', { name: 'Löschen' }).click()
    // a custom ConfirmDialog (not window.confirm), per #125
    const dialog = page.getByRole('dialog')
    await expect(dialog.getByRole('heading', { name: 'Kategorie löschen' })).toBeVisible()

    const reqP = page.waitForRequest((r) => /\/shopping\/categories\/WINE$/.test(r.url()) && r.method() === 'DELETE')
    await dialog.getByRole('button', { name: 'Löschen' }).click()
    await reqP
    await expect(card.getByText('Wein')).toHaveCount(0)
  })

  test('the OTHER category has no delete control', async ({ page }) => {
    await openShopping(page, new MockApi())
    const card = page.locator('.hb-settings-body .hb-card', { hasText: 'Kategorien' }).first()
    const otherRow = card.locator('.hb-row', { hasText: 'Sonstiges' })
    await expect(otherRow).toBeVisible()
    await expect(otherRow.getByRole('button', { name: 'Löschen' })).toHaveCount(0)
    // a normal builtin still has its delete control
    const produceRow = card.locator('.hb-row', { hasText: 'Obst & Gemüse' })
    await expect(produceRow.getByRole('button', { name: 'Löschen' })).toHaveCount(1)
  })

  test('adds a rule — PUTs name + category + emoji', async ({ page }) => {
    await openShopping(page, new MockApi())
    const card = page.locator('.hb-settings-body .hb-card', { hasText: 'Auto-Zuordnungsregeln' })

    await card.getByRole('button', { name: 'Regel hinzufügen' }).click()
    await card.getByLabel('Artikelname', { exact: true }).fill('Apfel')
    await card.getByLabel('Kategorie', { exact: true }).selectOption('PRODUCE')
    await card.getByLabel('Emoji (optional)', { exact: true }).fill('🍎')

    const reqP = page.waitForRequest((r) => r.url().endsWith('/shopping/category-rules') && r.method() === 'PUT')
    await card.getByRole('button', { name: 'Speichern' }).click()
    expect((await reqP).postDataJSON()).toMatchObject({ displayName: 'Apfel', category: 'PRODUCE', icon: '🍎' })

    await expect(card.getByText('Apfel')).toBeVisible()
  })

  test('deletes a rule via the confirm dialog', async ({ page }) => {
    await openShopping(page, new MockApi())
    const card = page.locator('.hb-settings-body .hb-card', { hasText: 'Auto-Zuordnungsregeln' })
    const milchRow = card.locator('.hb-row', { hasText: 'Milch' })

    await milchRow.getByRole('button', { name: 'Löschen' }).click()
    const dialog = page.getByRole('dialog')
    await expect(dialog.getByRole('heading', { name: 'Regel löschen' })).toBeVisible()

    const reqP = page.waitForRequest((r) => /\/shopping\/category-rules\/Milch$/.test(r.url()) && r.method() === 'DELETE')
    await dialog.getByRole('button', { name: 'Löschen' }).click()
    await reqP
    await expect(card.getByText('Milch')).toHaveCount(0)
  })
})

test.describe('Settings — Aufgaben (#356)', () => {
  async function openTodos(page: Page, mock: MockApi) {
    await openApp(page, mock)
    await page.locator('.hb-sidebar').getByRole('button', { name: 'Einstellungen' }).click()
    await page.locator('.hb-settings-nav').getByRole('button', { name: 'Aufgaben' }).click()
    await expect(page.locator('.hb-settings-body').getByRole('heading', { name: 'Erledigt-Fenster' })).toBeVisible()
  }

  test('shows the configured done-window length and saves a change (#356)', async ({ page }) => {
    await openTodos(page, new MockApi())
    const card = page.locator('.hb-settings-body .hb-card', { hasText: 'Erledigt-Fenster' })
    // the mock defaults to 14, mirroring the backend default
    await expect(card.getByLabel('Tage', { exact: true })).toHaveValue('14')

    await card.getByLabel('Tage', { exact: true }).fill('30')
    const reqP = page.waitForRequest((r) => r.url().endsWith('/config/done-window') && r.method() === 'PUT')
    await card.getByRole('button', { name: 'Speichern' }).click()
    expect((await reqP).postDataJSON()).toEqual({ days: 30 })
    await expect(card.getByText('Gespeichert')).toBeVisible()
  })

  test('rejects a value below 1 client-side, without a request (#356)', async ({ page }) => {
    await openTodos(page, new MockApi())
    const card = page.locator('.hb-settings-body .hb-card', { hasText: 'Erledigt-Fenster' })

    let sawRequest = false
    page.on('request', (r) => { if (r.url().endsWith('/config/done-window') && r.method() === 'PUT') sawRequest = true })

    await card.getByLabel('Tage', { exact: true }).fill('0')
    // an out-of-range value disables Save (no PUT fired)
    await expect(card.getByRole('button', { name: 'Speichern' })).toBeDisabled()
    expect(sawRequest).toBe(false)
  })
})
