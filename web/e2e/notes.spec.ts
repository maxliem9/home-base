import { test, expect, type Page } from '@playwright/test'
import { MockApi, note, noteImage, TOKEN } from './helpers/mockApi'

/** Logs in, installs the mock backend, and navigates to the notes view. */
async function openNotes(page: Page, mock: MockApi) {
  await mock.install(page)
  await page.addInitScript((t) => localStorage.setItem('homebase_token', t), TOKEN)
  await page.goto('/')
  await page.getByRole('button', { name: 'Notizen' }).click()
  await expect(page.getByRole('heading', { name: 'Notizen' })).toBeVisible()
}

const WLAN = note({
  id: 'n1',
  title: 'WLAN Passwort',
  content: 'Router: **abc123**',
  tags: ['technik'],
  visibility: 'SHARED',
})

test.describe('Notes', () => {
  test('shows the empty state with no notes', async ({ page }) => {
    await openNotes(page, new MockApi())
    await expect(page.getByText('Noch keine Notizen')).toBeVisible()
  })

  test('lists notes and opens one to read it', async ({ page }) => {
    await openNotes(page, new MockApi().seedNotes([WLAN]))

    await page.getByRole('button', { name: /WLAN Passwort/ }).click()
    // the reading pane renders the note's markdown content
    await expect(page.locator('.hb-md')).toContainText('Router: abc123')
  })

  test('searches notes by query', async ({ page }) => {
    await openNotes(page, new MockApi().seedNotes([
      note({ id: 'n1', title: 'WLAN Passwort' }),
      note({ id: 'n2', title: 'Geburtstage' }),
    ]))

    await page.getByPlaceholder('Suchen …').fill('WLAN')
    await expect(page.getByRole('button', { name: /WLAN Passwort/ })).toBeVisible()
    await expect(page.getByRole('button', { name: /Geburtstage/ })).toHaveCount(0)
  })

  test('filters notes by tag', async ({ page }) => {
    await openNotes(page, new MockApi().seedNotes([
      note({ id: 'n1', title: 'WLAN', tags: ['technik'] }),
      note({ id: 'n2', title: 'Geburtstage', tags: ['familie'] }),
    ]))
    await expect(page.getByRole('button', { name: /WLAN/ })).toBeVisible()
    await expect(page.getByRole('button', { name: /Geburtstage/ })).toBeVisible()

    await page.getByRole('button', { name: '#familie' }).click()
    await expect(page.getByRole('button', { name: /Geburtstage/ })).toBeVisible()
    await expect(page.getByRole('button', { name: /^WLAN/ })).toHaveCount(0)
  })

  test('creates a note', async ({ page }) => {
    await openNotes(page, new MockApi())

    await page.getByRole('button', { name: 'Neue Notiz' }).click()
    await page.getByPlaceholder('Titel…').fill('Einkaufsidee')
    await page.getByPlaceholder('Inhalt (Markdown)…').fill('Test Inhalt')
    await page.getByRole('button', { name: 'Speichern' }).click()

    // the new note is selected and shown in the reading pane + list
    await expect(page.locator('.hb-note-doc__title')).toHaveText('Einkaufsidee')
    await expect(page.getByRole('button', { name: /Einkaufsidee/ })).toBeVisible()
  })

  test('edits a note', async ({ page }) => {
    await openNotes(page, new MockApi().seedNotes([WLAN]))
    await page.getByRole('button', { name: /WLAN Passwort/ }).click()

    await page.getByRole('button', { name: 'Bearbeiten' }).click()
    await page.getByPlaceholder('Titel…').fill('WLAN Zugang')
    await page.getByRole('button', { name: 'Speichern' }).click()

    await expect(page.locator('.hb-note-doc__title')).toHaveText('WLAN Zugang')
  })

  test('deletes a note', async ({ page }) => {
    await openNotes(page, new MockApi().seedNotes([WLAN]))
    await page.getByRole('button', { name: /WLAN Passwort/ }).click()

    await page.getByRole('button', { name: 'Löschen' }).click()

    await expect(page.getByText('Noch keine Notizen')).toBeVisible()
  })

  // The note-image gallery loads each thumbnail through authFetch (Authorization
  // header) → res.blob() → URL.createObjectURL(), so the JWT never rides in the
  // image URL. These cover that <AuthedImage> path end-to-end (issue #10).
  const PHOTOS = note({
    id: 'n1',
    title: 'Urlaubsfotos',
    images: [noteImage({ id: 'img1', noteId: 'n1', originalName: 'strand.png' })],
  })

  test('renders a note image thumbnail via authFetch→blob, JWT in header not URL', async ({ page }) => {
    await openNotes(page, new MockApi().seedNotes([PHOTOS]))

    const imageRequest = page.waitForRequest((r) => r.url().includes('/notes/n1/images/img1'))
    await page.getByRole('button', { name: /Urlaubsfotos/ }).click()

    // AuthedImage resolves the blob and renders <img src="blob:…">
    await expect(page.locator('.hb-note-thumb img')).toHaveAttribute('src', /^blob:/)

    const req = await imageRequest
    expect(req.headers()['authorization']).toBe(`Bearer ${TOKEN}`)
    // the token must not leak into the URL (no ?token=, no bare JWT)
    expect(req.url()).not.toContain(TOKEN)
    expect(new URL(req.url()).searchParams.has('token')).toBe(false)
  })

  test('opens the lightbox when a note image is clicked', async ({ page }) => {
    await openNotes(page, new MockApi().seedNotes([PHOTOS]))
    await page.getByRole('button', { name: /Urlaubsfotos/ }).click()

    const thumb = page.locator('.hb-note-thumb img')
    await expect(thumb).toHaveAttribute('src', /^blob:/)
    await thumb.click()

    // the lightbox overlay opens with its own blob-loaded image
    const lightbox = page.locator('.hb-lightbox')
    await expect(lightbox).toBeVisible()
    await expect(lightbox.locator('img')).toHaveAttribute('src', /^blob:/)

    // clicking the backdrop (not the centered image) closes it
    await lightbox.click({ position: { x: 5, y: 5 } })
    await expect(lightbox).toHaveCount(0)
  })
})
