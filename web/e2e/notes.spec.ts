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

  // ---- Editor image upload: paste / drag&drop straight into the textarea (#146) ----
  // Fires a real `paste`/`drop` event carrying an image File at the caret, then asserts
  // the upload happened (POST /notes/{id}/images) and an inline `![name](image:<id>)`
  // ref was inserted. Both go through the same uploadImageToNote → insertAtCaret flow.

  // Dispatch a clipboard/drag event carrying a synthetic PNG File onto the editor
  // textarea, at the given caret offset. Returns nothing; the app reacts to the event.
  const fireEditorImageEvent = (page: Page, kind: 'paste' | 'drop', filename: string, caret = 0) =>
    page.evaluate(
      ({ kind, filename, caret }) => {
        const ta = document.querySelector('textarea.hb-mono-area') as HTMLTextAreaElement
        ta.focus()
        ta.setSelectionRange(caret, caret)
        const dt = new DataTransfer()
        dt.items.add(new File([new Uint8Array([1, 2, 3])], filename, { type: 'image/png' }))
        const ev =
          kind === 'paste'
            ? new ClipboardEvent('paste', { clipboardData: dt, bubbles: true, cancelable: true })
            : new DragEvent('drop', { dataTransfer: dt, bubbles: true, cancelable: true })
        ta.dispatchEvent(ev)
      },
      { kind, filename, caret },
    )

  const openEditorFor = async (page: Page, titleRe: RegExp) => {
    await page.getByRole('button', { name: titleRe }).click()
    await page.getByRole('button', { name: 'Bearbeiten' }).click()
    await expect(page.getByPlaceholder('Inhalt (Markdown)…')).toBeVisible()
  }

  test('pastes an image into the editor → uploads and inserts a markdown ref', async ({ page }) => {
    await openNotes(page, new MockApi().seedNotes([WLAN]))
    await openEditorFor(page, /WLAN Passwort/)

    const upload = page.waitForRequest((r) => r.url().includes('/notes/n1/images') && r.method() === 'POST')
    await fireEditorImageEvent(page, 'paste', 'pasted.png', 0)
    await upload

    // the upload response (note + new image) drives an inline ref at the caret
    const ta = page.getByPlaceholder('Inhalt (Markdown)…')
    await expect(ta).toHaveValue(/^!\[pasted\.png\]\(image:noteimg-\d+\)Router: \*\*abc123\*\*$/)
  })

  test('drops an image onto the editor → uploads and inserts a markdown ref', async ({ page }) => {
    await openNotes(page, new MockApi().seedNotes([WLAN]))
    await openEditorFor(page, /WLAN Passwort/)

    const upload = page.waitForRequest((r) => r.url().includes('/notes/n1/images') && r.method() === 'POST')
    // drop at the end of the existing content
    await fireEditorImageEvent(page, 'drop', 'dropped.png', 'Router: **abc123**'.length)
    await upload

    const ta = page.getByPlaceholder('Inhalt (Markdown)…')
    await expect(ta).toHaveValue(/^Router: \*\*abc123\*\*!\[dropped\.png\]\(image:noteimg-\d+\)$/)
  })

  test('surfaces a 415 upload error in the editor (German text, no insert)', async ({ page }) => {
    await openNotes(page, new MockApi().seedNotes([WLAN]).failNextImageUpload(415))
    await openEditorFor(page, /WLAN Passwort/)

    await fireEditorImageEvent(page, 'paste', 'weird.tiff', 0)

    await expect(page.locator('.hb-note-images__error')).toHaveText('Nur JPEG, PNG, WebP oder GIF erlaubt.')
    // nothing was inserted — content is unchanged
    await expect(page.getByPlaceholder('Inhalt (Markdown)…')).toHaveValue('Router: **abc123**')
  })

  test('keeps edits typed WHILE the upload is in flight (no stale-draft clobber)', async ({ page }) => {
    const mock = new MockApi().seedNotes([WLAN]).holdNextImageUpload()
    await openNotes(page, mock)
    await openEditorFor(page, /WLAN Passwort/)

    const ta = page.getByPlaceholder('Inhalt (Markdown)…')
    // paste an image at the very start; the upload is held open by the mock
    const upload = page.waitForRequest((r) => r.url().includes('/notes/n1/images') && r.method() === 'POST')
    await fireEditorImageEvent(page, 'paste', 'inflight.png', 0)
    await upload // request is now pending in the mock

    // the user keeps typing at the end of the content while the upload hasn't returned
    await ta.focus()
    await page.evaluate(() => {
      const el = document.querySelector('textarea.hb-mono-area') as HTMLTextAreaElement
      el.setSelectionRange(el.value.length, el.value.length)
    })
    await ta.pressSequentially(' EDIT')
    await expect(ta).toHaveValue('Router: **abc123** EDIT')

    // now let the upload resolve → the insert must NOT wipe the typed " EDIT", and the
    // snippet lands at the LIVE caret (end), not at the stale paste position.
    await mock.releaseImageUpload()
    await expect(ta).toHaveValue(/^Router: \*\*abc123\*\* EDIT!\[inflight\.png\]\(image:noteimg-\d+\)$/)
  })

  test('a brand-new unsaved draft hints to save first instead of uploading', async ({ page }) => {
    await openNotes(page, new MockApi())
    await page.getByRole('button', { name: 'Neue Notiz' }).click()
    await expect(page.getByPlaceholder('Inhalt (Markdown)…')).toBeVisible()

    let uploaded = false
    page.on('request', (r) => { if (r.url().includes('/images') && r.method() === 'POST') uploaded = true })
    await fireEditorImageEvent(page, 'paste', 'early.png', 0)

    await expect(page.locator('.hb-note-images__error')).toHaveText('Notiz zuerst speichern, dann Bilder einfügen.')
    expect(uploaded).toBe(false)
  })
})
