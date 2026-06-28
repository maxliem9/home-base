import { test, expect, type Page } from '@playwright/test'
import { MockApi, shoppingList, shoppingItem, shoppingTemplate, TOKEN } from './helpers/mockApi'

/** Logs in, installs the mock backend, and navigates to the shopping view. */
async function openShopping(page: Page, mock: MockApi) {
  await mock.install(page)
  await page.addInitScript((t) => localStorage.setItem('homebase_token', t), TOKEN)
  // Pin list view: the default is now tiles (#440); the row-based assertions below test the list.
  // The tile view + toggle have their own test ('tile view: …').
  await page.addInitScript(() => localStorage.setItem('homebase_shopping_viewmode', 'list'))
  await page.goto('/')
  await page.getByRole('button', { name: 'Einkaufsliste' }).click()
  await expect(page.getByRole('heading', { name: 'Einkaufslisten' })).toBeVisible()
}

const WOCHE = shoppingList({ id: 'sl1', name: 'Wocheneinkauf' })

test.describe('Shopping lists', () => {
  test('shows the no-list empty state when there are no lists', async ({ page }) => {
    await openShopping(page, new MockApi([], [], []))
    await expect(page.getByText('Noch keine Liste')).toBeVisible()
  })

  test('renders items from the active list', async ({ page }) => {
    const mock = new MockApi([], [], [WOCHE], [
      shoppingItem({ id: 'i1', name: 'Äpfel', listId: 'sl1' }),
      shoppingItem({ id: 'i2', name: 'Milch', listId: 'sl1' }),
    ])
    await openShopping(page, mock)

    await expect(page.getByText('Äpfel')).toBeVisible()
    await expect(page.getByText('Milch')).toBeVisible()
  })

  test('tile view: groups items into tiles, taps to check off, toggles back to list', async ({ page }) => {
    const mock = new MockApi([], [], [WOCHE], [
      shoppingItem({ id: 'i1', name: 'Äpfel', listId: 'sl1' }),
      shoppingItem({ id: 'i2', name: 'Milch', listId: 'sl1' }),
    ])
    await openShopping(page, mock)

    await page.getByRole('button', { name: 'Kachelansicht' }).click()
    await expect(page.locator('.hb-tile', { hasText: 'Äpfel' })).toBeVisible()
    await expect(page.locator('.hb-row')).toHaveCount(0)

    // tapping a tile checks the item off into "Im Wagen"
    await page.locator('.hb-tile', { hasText: 'Äpfel' }).click()
    await expect(page.locator('.hb-tile--done', { hasText: 'Äpfel' })).toBeVisible()

    // toggling back to list restores the row view (and persists)
    await page.getByRole('button', { name: 'Listenansicht' }).click()
    await expect(page.locator('.hb-row', { hasText: 'Milch' })).toBeVisible()
    await expect(page.locator('.hb-tile')).toHaveCount(0)
  })

  test('adds an item to the active list', async ({ page }) => {
    await openShopping(page, new MockApi([], [], [WOCHE], []))

    await page.getByPlaceholder('Was fehlt in „Wocheneinkauf"? …').fill('Brot')
    await page.getByRole('button', { name: 'Hinzufügen' }).click()

    await expect(page.getByText('Brot')).toBeVisible()
  })

  // #377: hitting Enter, then immediately typing + Enter for a second item (before the
  // first POST resolves) must yield two items — not one merged "BananenMilch". The field
  // has to clear on submit, not only after the await. We hold the first POST open and type
  // the second item character-by-character so it would *append* to an uncleared field.
  test('adds two items typed in quick succession without merging them', async ({ page }) => {
    await openShopping(page, new MockApi([], [], [WOCHE], []))

    // hold the first POST open so the second add starts while it's still in flight
    let release: () => void = () => {}
    const gate = new Promise<void>((r) => { release = r })
    let first = true
    await page.route('**/api/v1/shopping', async (route) => {
      if (route.request().method() === 'POST' && first) {
        first = false
        await gate
      }
      return route.fallback()
    })

    const input = page.getByPlaceholder('Was fehlt in „Wocheneinkauf"? …')
    await input.pressSequentially('Bananen')
    await input.press('Enter')          // first POST is now pending behind the gate
    // the field must already be empty (cleared on submit, not after the await) — otherwise
    // these keystrokes append and the next Enter posts the merged "BananenMilch"
    await expect(input).toHaveValue('')
    await input.pressSequentially('Milch')
    await input.press('Enter')
    release()                           // let the first POST complete

    await expect(page.locator('.hb-row', { hasText: 'Bananen' })).toBeVisible()
    await expect(page.locator('.hb-row', { hasText: 'Milch' })).toBeVisible()
    // crucially: no merged item, and "Bananen" stands alone (not "BananenMilch").
    // Scope to the row title — designed SVG icons leave no text between adjacent rows, so a
    // loose getByText would match the concatenated "Bananen"+"Milch" of two separate rows.
    await expect(page.locator('.hb-row__title', { hasText: 'BananenMilch' })).toHaveCount(0)
    await expect(page.locator('.hb-row')).toHaveCount(2)
  })

  test('checking an item moves it into the cart section', async ({ page }) => {
    const mock = new MockApi([], [], [WOCHE], [shoppingItem({ id: 'i1', name: 'Butter', listId: 'sl1' })])
    await openShopping(page, mock)

    await page.locator('.hb-row', { hasText: 'Butter' }).getByRole('checkbox').click()

    await expect(page.getByText(/Im Wagen/)).toBeVisible()
    await expect(page.locator('.hb-row--done', { hasText: 'Butter' })).toBeVisible()
  })

  test('switching tabs shows only that list\'s items', async ({ page }) => {
    const mock = new MockApi([], [], [
      shoppingList({ id: 'sl1', name: 'Wocheneinkauf' }),
      shoppingList({ id: 'sl2', name: 'Drogerie' }),
    ], [
      shoppingItem({ id: 'i1', name: 'Äpfel', listId: 'sl1' }),
      shoppingItem({ id: 'i2', name: 'Shampoo', listId: 'sl2' }),
    ])
    await openShopping(page, mock)

    await expect(page.getByText('Äpfel')).toBeVisible()
    await expect(page.getByText('Shampoo')).toHaveCount(0)

    await page.getByRole('tab', { name: 'Drogerie' }).click()
    await expect(page.getByText('Shampoo')).toBeVisible()
    await expect(page.getByText('Äpfel')).toHaveCount(0)
  })

  test('creates a new list via the modal', async ({ page }) => {
    await openShopping(page, new MockApi([], [], [WOCHE], []))

    await page.locator('.hb-tabs').getByRole('button', { name: 'Neue Liste' }).click()
    const modal = page.locator('.hb-modal')
    await modal.getByPlaceholder('z. B. Wocheneinkauf').fill('Baumarkt')
    await modal.getByRole('button', { name: 'Erstellen' }).click()

    const tab = page.locator('.hb-tabs').getByRole('tab', { name: 'Baumarkt' })
    await expect(tab).toBeVisible()
    await expect(tab).toHaveClass(/is-active/)
  })

  test('deletes the active list and its items', async ({ page }) => {
    const mock = new MockApi([], [], [
      shoppingList({ id: 'sl1', name: 'Wocheneinkauf' }),
      shoppingList({ id: 'sl2', name: 'Drogerie' }),
    ], [shoppingItem({ id: 'i2', name: 'Shampoo', listId: 'sl2' })])
    await openShopping(page, mock)

    await page.getByRole('tab', { name: 'Drogerie' }).click()
    await expect(page.getByText('Shampoo')).toBeVisible()

    await page.getByRole('button', { name: /Liste „Drogerie" löschen/ }).click()
    // a confirmation modal must appear first — no immediate delete
    await expect(page.getByRole('heading', { name: 'Liste löschen?' })).toBeVisible()
    await page.locator('.hb-modal').getByRole('button', { name: 'Endgültig löschen' }).click()

    await expect(page.locator('.hb-tabs').getByRole('tab', { name: 'Drogerie' })).toHaveCount(0)
    await expect(page.getByText('Shampoo')).toHaveCount(0)
  })

  test('puts the most recently checked item on top of the cart', async ({ page }) => {
    const mock = new MockApi([], [], [WOCHE], [
      // already in the cart, checked a while ago
      shoppingItem({ id: 'i1', name: 'Alt', listId: 'sl1', checked: true, checkedAt: '2026-06-10T08:00:00Z' }),
      shoppingItem({ id: 'i2', name: 'Neu', listId: 'sl1' }),
    ])
    await openShopping(page, mock)

    // check "Neu" now → its checkedAt is newer than "Alt"s
    await page.locator('.hb-row', { hasText: 'Neu' }).getByRole('checkbox').click()

    const cart = page.locator('.hb-row--done')
    await expect(cart).toHaveCount(2)
    await expect(cart.first()).toContainText('Neu') // last checked sits on top
    await expect(cart.last()).toContainText('Alt')
  })

  // Issue: ticking items in a store with no wifi must not silently lose them.
  test('remembers a check made offline and syncs it when back online', async ({ page }) => {
    const mock = new MockApi([], [], [WOCHE], [shoppingItem({ id: 'i1', name: 'Milch', listId: 'sl1' })])
    await openShopping(page, mock)

    // simulate no wifi: every shopping-item write is dropped before it reaches the backend
    await page.route('**/api/v1/shopping/**', (route) => {
      if (route.request().method() === 'PUT') return route.abort('internetdisconnected')
      return route.fallback()
    })

    await page.locator('.hb-row', { hasText: 'Milch' }).getByRole('checkbox').click()

    // optimistic: it moves into the cart and is NOT reverted…
    await expect(page.locator('.hb-row--done', { hasText: 'Milch' })).toBeVisible()
    // …but it's clearly flagged as not-yet-synced (item badge + banner), never silently dropped
    await expect(page.locator('.hb-syncbar')).toContainText('nachgeholt')
    await expect(page.locator('.hb-row--done', { hasText: 'Milch' }).locator('.hb-syncbadge')).toBeVisible()
    // and it's durably queued in localStorage, so it survives a reload / app close
    await expect
      .poll(() => page.evaluate(() => localStorage.getItem('homebase_shopping_pending')))
      .toContain('i1')

    // back online → "Jetzt versuchen" drains the queue
    await page.unroute('**/api/v1/shopping/**')
    const putPromise = page.waitForRequest((r) => r.url().includes('/shopping/i1') && r.method() === 'PUT')
    await page.locator('.hb-syncbar').getByRole('button', { name: 'Jetzt versuchen' }).click()
    await putPromise

    // marker + banner clear, the item stays checked, and the queue is empty
    await expect(page.locator('.hb-syncbar')).toHaveCount(0)
    await expect(page.locator('.hb-syncbadge')).toHaveCount(0)
    await expect(page.locator('.hb-row--done', { hasText: 'Milch' })).toBeVisible()
    await expect
      .poll(() => page.evaluate(() => localStorage.getItem('homebase_shopping_pending')))
      .toBeNull()
  })

  test('auto-retries the offline queue without manual action', async ({ page }) => {
    const mock = new MockApi([], [], [WOCHE], [shoppingItem({ id: 'i1', name: 'Eier', listId: 'sl1' })])
    await openShopping(page, mock)

    // first PUT is dropped (offline); once we go back online the periodic/online retry lands it
    await page.route('**/api/v1/shopping/**', (route) => {
      if (route.request().method() === 'PUT') return route.abort('internetdisconnected')
      return route.fallback()
    })
    await page.locator('.hb-row', { hasText: 'Eier' }).getByRole('checkbox').click()
    await expect(page.locator('.hb-syncbar')).toBeVisible()

    // restore connectivity and fire the OS "online" event — the queue drains on its own
    await page.unroute('**/api/v1/shopping/**')
    const putPromise = page.waitForRequest((r) => r.url().includes('/shopping/i1') && r.method() === 'PUT')
    await page.evaluate(() => window.dispatchEvent(new Event('online')))
    await putPromise

    await expect(page.locator('.hb-syncbar')).toHaveCount(0)
  })

  // A transient 5xx (backend restart / proxy hiccup, wifi fine) must be retried,
  // not silently dropped — same lost-check-off failure mode as being offline.
  test('keeps a check queued through a transient 5xx and syncs on recovery', async ({ page }) => {
    const mock = new MockApi([], [], [WOCHE], [shoppingItem({ id: 'i1', name: 'Mehl', listId: 'sl1' })])
    await openShopping(page, mock)

    let fail = true
    await page.route('**/api/v1/shopping/**', (route) => {
      if (route.request().method() === 'PUT' && fail) {
        return route.fulfill({ status: 503, contentType: 'application/json', body: '{"code":"UNAVAILABLE"}' })
      }
      return route.fallback()
    })

    await page.locator('.hb-row', { hasText: 'Mehl' }).getByRole('checkbox').click()

    // the 503 must NOT drop the entry — it stays checked, flagged, and queued
    await expect(page.locator('.hb-row--done', { hasText: 'Mehl' })).toBeVisible()
    await expect(page.locator('.hb-syncbar')).toBeVisible()
    await expect
      .poll(() => page.evaluate(() => localStorage.getItem('homebase_shopping_pending')))
      .toContain('i1')

    // backend recovers → retry lands it and clears the queue
    fail = false
    const putPromise = page.waitForRequest((r) => r.url().includes('/shopping/i1') && r.method() === 'PUT')
    await page.locator('.hb-syncbar').getByRole('button', { name: 'Jetzt versuchen' }).click()
    await putPromise

    await expect(page.locator('.hb-syncbar')).toHaveCount(0)
  })
})

// Named "standard/template lists" (#215): save several named templates (item names)
// and re-add a chosen subset to a real list via the existing batch-add.
test.describe('Shopping templates', () => {
  test('creates a template, then adds selected items to the active list', async ({ page }) => {
    await openShopping(page, new MockApi([], [], [WOCHE], []))

    // open the template management slide-over and create a new template
    await page.locator('.hb-tabs').getByRole('button', { name: 'Vorlagen' }).click()
    const manage = page.locator('.hb-sheet')
    await expect(manage).toBeVisible()
    await expect(manage.getByText('Noch keine Vorlagen')).toBeVisible()
    await manage.getByRole('button', { name: 'Neue Vorlage' }).click()

    // editor (in-place): name + two item rows
    const editor = page.locator('.hb-sheet')
    await editor.getByPlaceholder('z. B. Wocheneinkauf').fill('Wocheneinkauf')
    await editor.getByRole('button', { name: '+ Produkt' }).click()
    await editor.getByRole('button', { name: '+ Produkt' }).click()
    const itemInputs = editor.locator('.hb-tpl-item .hb-input')
    await itemInputs.nth(0).fill('Milch')
    await itemInputs.nth(1).fill('Brot')
    await editor.getByRole('button', { name: 'Speichern' }).click()

    // back on the list, the saved template shows with its item count
    await expect(manage.getByText('Wocheneinkauf')).toBeVisible()
    await expect(manage.getByText('2 Produkte')).toBeVisible()

    // "Zur Liste hinzufügen" opens the selection sheet (both items preselected)
    await manage.getByRole('button', { name: 'Zur Liste hinzufügen' }).click()
    const pick = page.locator('.hb-sheet')
    await expect(pick.getByText('2 / 2 ausgewählt')).toBeVisible()
    await pick.getByRole('button', { name: /hinzufügen/ }).click()

    // toast confirms, and both items appear on the active list
    await expect(page.getByText(/hinzugefügt/)).toBeVisible()
    await expect(page.locator('.hb-row', { hasText: 'Milch' })).toBeVisible()
    await expect(page.locator('.hb-row', { hasText: 'Brot' })).toBeVisible()
  })

  test('adds only the selected subset of a template', async ({ page }) => {
    const mock = new MockApi([], [], [WOCHE], []).seedShoppingTemplates([
      shoppingTemplate({ id: 'tpl1', name: 'Grundausstattung', items: ['Mehl', 'Zucker', 'Salz'] }),
    ])
    await openShopping(page, mock)

    await page.locator('.hb-tabs').getByRole('button', { name: 'Vorlagen' }).click()
    await page.locator('.hb-sheet').getByRole('button', { name: 'Zur Liste hinzufügen' }).click()

    const pick = page.locator('.hb-sheet')
    // deselect everything, then pick only "Zucker"
    await pick.getByRole('button', { name: 'Keine' }).click()
    await expect(pick.getByText('0 / 3 ausgewählt')).toBeVisible()
    await pick.locator('.hb-ingpick', { hasText: 'Zucker' }).click()
    await pick.getByRole('button', { name: '1 hinzufügen' }).click()

    // only Zucker lands; the unticked items stay off the list
    await expect(page.locator('.hb-row', { hasText: 'Zucker' })).toBeVisible()
    await expect(page.getByText('Mehl')).toHaveCount(0)
    await expect(page.getByText('Salz')).toHaveCount(0)
  })

  test('deletes a template via the confirm dialog', async ({ page }) => {
    const mock = new MockApi([], [], [WOCHE], []).seedShoppingTemplates([
      shoppingTemplate({ id: 'tpl1', name: 'Altlast', items: ['X'] }),
    ])
    await openShopping(page, mock)

    await page.locator('.hb-tabs').getByRole('button', { name: 'Vorlagen' }).click()
    const manage = page.locator('.hb-sheet')
    await expect(manage.getByText('Altlast')).toBeVisible()

    // trash → a custom confirm dialog must appear before the delete (no window.confirm)
    await manage.getByRole('button', { name: 'Löschen' }).click()
    await expect(page.getByRole('heading', { name: 'Vorlage löschen?' })).toBeVisible()
    await page.locator('.hb-modal').getByRole('button', { name: 'Endgültig löschen' }).click()

    await expect(page.getByText('Altlast')).toHaveCount(0)
    await expect(page.getByText('Noch keine Vorlagen')).toBeVisible()
  })
})

// Category grouping, per-item emoji, "most used" autocomplete, and the category-move
// override menu (#389).
test.describe('Shopping categories & suggestions', () => {
  test('groups open items into category sections in route order, with emoji', async ({ page }) => {
    const mock = new MockApi([], [], [WOCHE], [
      shoppingItem({ id: 'i1', name: 'Milch', listId: 'sl1', category: 'DAIRY', icon: '🥛' }),
      shoppingItem({ id: 'i2', name: 'Tomaten', listId: 'sl1', category: 'PRODUCE', icon: '🍅' }),
    ])
    await openShopping(page, mock)

    const heads = page.locator('.hb-cathead')
    await expect(heads).toHaveCount(2)
    // fixed shopping-route order: Obst & Gemüse (PRODUCE) before Milchprodukte (DAIRY)
    await expect(heads.nth(0)).toContainText('Obst & Gemüse')
    await expect(heads.nth(1)).toContainText('Milchprodukte & Eier')
    // the item renders its designed SVG icon (Tomaten → tomatoes.svg), not the emoji
    await expect(
      page.locator('.hb-row', { hasText: 'Tomaten' }).locator('.hb-row__emoji img'),
    ).toHaveAttribute('src', /tomatoes\.svg/)
  })

  test('an item with no category falls into Sonstiges', async ({ page }) => {
    const mock = new MockApi([], [], [WOCHE], [shoppingItem({ id: 'i1', name: 'Wunderdings', listId: 'sl1' })])
    await openShopping(page, mock)
    await expect(page.locator('.hb-cathead', { hasText: 'Sonstiges' })).toBeVisible()
    await expect(page.locator('.hb-row', { hasText: 'Wunderdings' })).toBeVisible()
  })

  test('autocomplete ranks suggestions by frequency and adds the picked one', async ({ page }) => {
    const mock = new MockApi([], [], [WOCHE], []).seedShoppingSuggestions([
      { name: 'Milch', category: 'DAIRY', icon: '🥛', count: 31 },
      { name: 'Tomaten', category: 'PRODUCE', icon: '🍅', count: 24 },
      { name: 'Toastbrot', category: 'BAKERY', icon: '🍞', count: 11 },
      { name: 'Tofu', category: 'PANTRY', icon: '🥡', count: 6 },
    ])
    await openShopping(page, mock)

    await page.getByPlaceholder('Was fehlt in „Wocheneinkauf"? …').fill('to')
    const ac = page.locator('.hb-ac')
    await expect(ac).toBeVisible()
    // only the "to…" matches, ranked by count desc (Tomaten 24 > Toastbrot 11 > Tofu 6); not Milch
    const items = ac.locator('.hb-ac__item')
    await expect(items).toHaveCount(3)
    await expect(items.nth(0)).toContainText('Tomaten')
    await expect(items.nth(2)).toContainText('Tofu')
    await expect(ac.getByText('Milch')).toHaveCount(0)

    await items.filter({ hasText: 'Toastbrot' }).click()
    await expect(page.locator('.hb-row', { hasText: 'Toastbrot' })).toBeVisible()
  })

  test('Enter adds the highlighted suggestion', async ({ page }) => {
    const mock = new MockApi([], [], [WOCHE], []).seedShoppingSuggestions([
      { name: 'Bananen', category: 'PRODUCE', icon: '🍌', count: 12 },
    ])
    await openShopping(page, mock)

    const input = page.getByPlaceholder('Was fehlt in „Wocheneinkauf"? …')
    await input.fill('ban')
    await expect(page.locator('.hb-ac__item', { hasText: 'Bananen' })).toBeVisible()
    await input.press('Enter')
    await expect(page.locator('.hb-row', { hasText: 'Bananen' })).toBeVisible()
  })

  // #398: the quick-add input must announce its autocomplete to screen readers — combobox
  // role, expanded state, the listbox it controls, and which option is highlighted.
  test('quick-add input exposes combobox semantics for screen readers', async ({ page }) => {
    const mock = new MockApi([], [], [WOCHE], []).seedShoppingSuggestions([
      { name: 'Tomaten', category: 'PRODUCE', icon: '🍅', count: 24 },
      { name: 'Tofu', category: 'PANTRY', icon: '🥡', count: 6 },
    ])
    await openShopping(page, mock)

    const input = page.getByPlaceholder('Was fehlt in „Wocheneinkauf"? …')
    await expect(input).toHaveAttribute('role', 'combobox')
    await expect(input).toHaveAttribute('aria-autocomplete', 'list')
    await expect(input).toHaveAttribute('aria-expanded', 'false') // collapsed while empty

    await input.fill('to')
    await expect(input).toHaveAttribute('aria-expanded', 'true')
    // aria-controls points at the now-rendered listbox
    const listId = await page.locator('.hb-ac').getAttribute('id')
    expect(listId).toBeTruthy()
    await expect(input).toHaveAttribute('aria-controls', listId!)
    // the highlighted option is announced via aria-activedescendant (default: top match "Tomaten")
    const active1 = await input.getAttribute('aria-activedescendant')
    await expect(page.locator(`[id="${active1}"]`)).toContainText('Tomaten')

    // ArrowDown advances the active option, and aria-activedescendant follows it
    await input.press('ArrowDown')
    const active2 = await input.getAttribute('aria-activedescendant')
    expect(active2).not.toBe(active1)
    await expect(page.locator(`[id="${active2}"]`)).toContainText('Tofu')
  })

  test('moves an item to another category via the override menu', async ({ page }) => {
    const mock = new MockApi([], [], [WOCHE], [
      shoppingItem({ id: 'i1', name: 'Pizza', listId: 'sl1', category: 'OTHER', icon: '🍕' }),
    ])
    await openShopping(page, mock)
    await expect(page.locator('.hb-cathead', { hasText: 'Sonstiges' })).toBeVisible()

    const row = page.locator('.hb-row', { hasText: 'Pizza' })
    await row.hover() // reveal the row actions
    await row.getByRole('button', { name: 'In Kategorie verschieben' }).click()
    const menu = page.locator('.hb-catmenu')
    await expect(menu).toBeVisible()

    const putPromise = page.waitForRequest((r) => r.url().includes('/shopping/i1') && r.method() === 'PUT')
    await menu.getByRole('menuitemradio', { name: /Tiefkühl/ }).click()
    const put = await putPromise
    expect(JSON.parse(put.postData() || '{}').category).toBe('FROZEN')

    // the item jumps to the Tiefkühl section; the now-empty Sonstiges section is gone
    await expect(page.locator('.hb-cathead', { hasText: 'Tiefkühl' })).toBeVisible()
    await expect(page.locator('.hb-cathead', { hasText: 'Sonstiges' })).toHaveCount(0)
    // #398: focus follows the moved item — it returns to the (remounted) trigger in the new section
    await expect(page.locator('.hb-row', { hasText: 'Pizza' }).getByRole('button', { name: 'In Kategorie verschieben' })).toBeFocused()
  })

  // #398: the override menu must be operable by keyboard — focus the current item on open,
  // move focus with the arrow keys, and on Escape close and hand focus back to the trigger.
  test('category override menu manages focus for keyboard users', async ({ page }) => {
    const mock = new MockApi([], [], [WOCHE], [
      shoppingItem({ id: 'i1', name: 'Pizza', listId: 'sl1', category: 'OTHER', icon: '🍕' }),
    ])
    await openShopping(page, mock)

    const row = page.locator('.hb-row', { hasText: 'Pizza' })
    await row.hover() // reveal the row actions
    const trigger = row.getByRole('button', { name: 'In Kategorie verschieben' })
    await trigger.click()
    const menu = page.locator('.hb-catmenu')
    await expect(menu).toBeVisible()

    // opens with focus on the current category (OTHER → "Sonstiges", last in the list)
    const current = menu.getByRole('menuitemradio', { name: /Sonstiges/ })
    await expect(current).toBeFocused()

    // arrow keys move focus among the items, staying inside the menu (roving tabindex)
    await page.keyboard.press('ArrowUp')
    await expect(current).not.toBeFocused()
    await expect(menu.locator(':focus')).toHaveAttribute('role', 'menuitemradio')

    // Escape closes the menu and returns focus to the trigger
    await page.keyboard.press('Escape')
    await expect(menu).toHaveCount(0)
    await expect(trigger).toBeFocused()
  })
})
