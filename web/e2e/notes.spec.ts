import { test, expect, type Page } from '@playwright/test'
import { MockApi, note, noteImage, TOKEN } from './helpers/mockApi'

// `Buffer` is a Node global present in the Playwright runtime; the e2e tsconfig has no
// @types/node, so declare just the call we use for synthetic upload file bytes (#266).
declare const Buffer: { from(input: number[]): Uint8Array }

/** Logs in, installs the mock backend, and navigates to the notes view. */
async function openNotes(page: Page, mock: MockApi) {
  await mock.install(page)
  await page.addInitScript((t) => localStorage.setItem('homebase_token', t), TOKEN)
  await page.goto('/')
  await page.getByRole('button', { name: 'Notizen' }).click()
  await expect(page.getByRole('heading', { name: 'Notizen' })).toBeVisible()
}

// HB-13: clicking a note rests it in the rendered PREVIEW (read state) — the document title is a
// heading, not an input. Clicking the body morphs the document into the inline editor in place
// (the Markdown source textarea), so the image/paste tests open it via `editNote`.
async function openNote(page: Page, titleRe: RegExp) {
  await page.getByRole('button', { name: titleRe }).click()
  await expect(page.locator('.hb-note-doc__title')).toBeVisible()
}
async function editNote(page: Page, titleRe: RegExp) {
  await openNote(page, titleRe)
  await page.locator('.hb-note-doc__body').click()
  await expect(page.getByPlaceholder('Inhalt (Markdown)…')).toBeVisible()
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

  // HB-13: a selected note rests in the rendered preview (no textarea); clicking the body opens
  // the inline editor in place (accent ring), pre-filled with the Markdown source.
  test('clicking a note shows the preview; clicking the body opens the inline editor', async ({ page }) => {
    await openNotes(page, new MockApi().seedNotes([WLAN]))

    await page.getByRole('button', { name: /WLAN Passwort/ }).click()
    // preview: rendered markdown, the title is a heading, no source textarea yet
    await expect(page.locator('.hb-note-doc__title')).toHaveText('WLAN Passwort')
    await expect(page.locator('.hb-note-doc__body')).toContainText('Router: abc123')
    await expect(page.getByPlaceholder('Inhalt (Markdown)…')).toHaveCount(0)

    // clicking the body switches THIS document into edit mode in place
    await page.locator('.hb-note-doc__body').click()
    await expect(page.getByPlaceholder('Inhalt (Markdown)…')).toHaveValue('Router: **abc123**')
    await expect(page.locator('.hb-note-doc.is-editing')).toBeVisible()
  })

  // A brand-new note skips the preview and opens straight in the editor, focused in the title.
  test('a new note opens straight in the editor focused in the title', async ({ page }) => {
    await openNotes(page, new MockApi())
    await page.locator('.hb-pagehead').getByRole('button', { name: 'Neue Notiz' }).click()
    await expect(page.locator('.hb-note-doc.is-editing')).toBeVisible()
    await expect(page.getByPlaceholder('Titel…')).toBeFocused()
  })

  // An empty note's preview is a clickable placeholder that opens the editor.
  test('empty note shows a clickable placeholder that opens the editor', async ({ page }) => {
    await openNotes(page, new MockApi().seedNotes([note({ id: 'n1', title: 'Leer', content: '' })]))
    await page.getByRole('button', { name: /Leer/ }).click()
    await expect(page.locator('.hb-note-doc__empty')).toHaveText('Leere Notiz — klicke, um zu schreiben')
    await page.locator('.hb-note-doc__empty').click()
    await expect(page.getByPlaceholder('Inhalt (Markdown)…')).toBeVisible()
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

  test('creates a note via auto-save (no Save button), capturing the new id', async ({ page }) => {
    await openNotes(page, new MockApi())

    // a brand-new draft has no id, so the first auto-save must be a POST (not PUT). Track both;
    // ignore the image sub-routes (not exercised here).
    const posts: string[] = []
    const puts: string[] = []
    page.on('request', (r) => {
      const p = new URL(r.url()).pathname
      if (p.includes('/images')) return
      if (p.endsWith('/notes') && r.method() === 'POST') posts.push(p)
      else if (/\/notes\/[^/]+$/.test(p) && r.method() === 'PUT') puts.push(p)
    })

    // a new note opens directly in the editor (HB-13) — title + source textarea are right there
    await page.locator('.hb-pagehead').getByRole('button', { name: 'Neue Notiz' }).click()
    await page.getByPlaceholder('Titel…').fill('Einkaufsidee')
    await page.getByPlaceholder('Inhalt (Markdown)…').fill('Test Inhalt')

    // auto-save fires after the debounce → POST, status flips to "Gespeichert", note in the list
    await expect(page.locator('.hb-savestatus.is-saved')).toHaveText('Gespeichert')
    await expect(page.getByRole('button', { name: /Einkaufsidee/ })).toBeVisible()
    expect(posts.length).toBe(1) // exactly one create, no double-POST

    // id was captured into the draft → editing again auto-saves via PUT (not a second POST)
    const put = page.waitForRequest(
      (r) => /\/notes\/[^/]+$/.test(new URL(r.url()).pathname) && r.method() === 'PUT',
    )
    await page.getByPlaceholder('Inhalt (Markdown)…').fill('Test Inhalt — mehr')
    await put
    await expect(page.locator('.hb-savestatus.is-saved')).toHaveText('Gespeichert')
    expect(posts.length).toBe(1) // still just the one create
    expect(puts.length).toBeGreaterThanOrEqual(1)
  })

  test('editing a note auto-saves via PUT after the debounce, showing "Gespeichert"', async ({ page }) => {
    await openNotes(page, new MockApi().seedNotes([WLAN]))
    // open → preview, then click the body to edit in place (#310/HB-13)
    await editNote(page, /WLAN Passwort/)
    await expect(page.getByPlaceholder('Titel…')).toHaveValue('WLAN Passwort')

    // #309: editing the content fires exactly one auto-save PUT carrying the new content,
    // and the status indicator flips to "Gespeichert" (no manual Save button exists).
    const put = page.waitForRequest(
      (r) => /\/notes\/n1$/.test(new URL(r.url()).pathname) && r.method() === 'PUT',
    )
    await page.getByPlaceholder('Inhalt (Markdown)…').fill('Router: **xyz789**')
    const req = await put
    expect(JSON.parse(req.postData() ?? '{}').content).toBe('Router: **xyz789**')
    await expect(page.locator('.hb-savestatus.is-saved')).toHaveText('Gespeichert')

    // the list reflects the merged REST response (preview text updated)
    await expect(page.locator('.hb-noteitem.is-active .hb-noteitem__preview')).toContainText('Router: xyz789')
  })

  // HB-13: Esc saves the in-flight edit and returns the document to the rendered preview.
  test('Esc saves the edit and returns to the preview', async ({ page }) => {
    await openNotes(page, new MockApi().seedNotes([WLAN]))
    await editNote(page, /WLAN Passwort/)

    const put = page.waitForRequest((r) => /\/notes\/n1$/.test(new URL(r.url()).pathname) && r.method() === 'PUT')
    await page.getByPlaceholder('Inhalt (Markdown)…').fill('Router: **changed**')
    await put
    await page.keyboard.press('Escape')

    // back to preview: the textarea is gone and the rendered body shows the saved content
    await expect(page.getByPlaceholder('Inhalt (Markdown)…')).toHaveCount(0)
    await expect(page.locator('.hb-note-doc__body')).toContainText('Router: changed')
    await expect(page.locator('.hb-note-doc.is-editing')).toHaveCount(0)
  })

  // HB-13: a click outside the document saves and returns to the preview.
  test('clicking outside the document saves and returns to the preview', async ({ page }) => {
    await openNotes(page, new MockApi().seedNotes([WLAN]))
    await editNote(page, /WLAN Passwort/)

    const put = page.waitForRequest((r) => /\/notes\/n1$/.test(new URL(r.url()).pathname) && r.method() === 'PUT')
    await page.getByPlaceholder('Inhalt (Markdown)…').fill('Router: **outside**')
    // click the search box (outside the note document) → save + back to preview
    await page.getByPlaceholder('Suchen …').click()
    await put

    await expect(page.getByPlaceholder('Inhalt (Markdown)…')).toHaveCount(0)
    await expect(page.locator('.hb-note-doc__body')).toContainText('Router: outside')
  })

  // #309 regression: a keystroke typed WHILE a save is in flight must still be persisted, and
  // the status must not lie. The first auto-save PUT is artificially delayed; during that window
  // a SECOND edit is typed. The in-flight save's tail must re-fire so the FINAL PUT carries the
  // second change, and "Gespeichert" must appear only AFTER that latest content was sent (not on
  // the first PUT, whose body is already stale).
  test('persists an edit typed while a save is in flight, and "Gespeichert" only after it lands', async ({ page }) => {
    await openNotes(page, new MockApi().seedNotes([WLAN]))
    await editNote(page, /WLAN Passwort/)

    // Delay every PUT to n1 by ~1.5s, then let the mock handle it. This holds the first save
    // open long enough to type a second change before it resolves.
    await page.route('**/api/v1/notes/n1', async (route) => {
      if (route.request().method() !== 'PUT') return route.fallback()
      await new Promise((r) => setTimeout(r, 1500))
      return route.fallback()
    })

    // record every PUT body in order so we can assert what the FINAL save actually sent
    const putBodies: string[] = []
    page.on('request', (r) => {
      if (/\/notes\/n1$/.test(new URL(r.url()).pathname) && r.method() === 'PUT') {
        putBodies.push(JSON.parse(r.postData() ?? '{}').content)
      }
    })

    const ta = page.getByPlaceholder('Inhalt (Markdown)…')

    // first edit → after the debounce the (delayed) first PUT starts and stays in flight
    const firstPut = page.waitForRequest((r) => /\/notes\/n1$/.test(new URL(r.url()).pathname) && r.method() === 'PUT')
    await ta.fill('FIRST change')
    await firstPut

    // while that PUT is held open the status shows "Speichert…", NOT "Gespeichert"
    await expect(page.locator('.hb-savestatus.is-saving')).toBeVisible()
    await expect(page.locator('.hb-savestatus.is-saved')).toHaveCount(0)

    // type the SECOND change during the in-flight window — the bug dropped exactly this edit
    await ta.fill('SECOND change')
    // status must stay truthful: still saving, never flashing "Gespeichert" with stale content
    await expect(page.locator('.hb-savestatus.is-saved')).toHaveCount(0)

    // once everything settles the status reaches "Gespeichert"…
    await expect(page.locator('.hb-savestatus.is-saved')).toHaveText('Gespeichert')
    // …and the FINAL PUT carried the second change (the trailing edit was persisted, not lost)
    expect(putBodies[putBodies.length - 1]).toBe('SECOND change')
    // the editor still holds the latest text (no clobber by the in-flight save's response)
    await expect(ta).toHaveValue('SECOND change')
    // the merged list preview reflects the last-saved content, proving "Gespeichert" is truthful
    await expect(page.locator('.hb-noteitem.is-active .hb-noteitem__preview')).toContainText('SECOND change')
  })

  test('does not auto-save when nothing changed after opening a note', async ({ page }) => {
    await openNotes(page, new MockApi().seedNotes([WLAN]))
    let puts = 0
    page.on('request', (r) => {
      const p = new URL(r.url()).pathname
      if (/\/notes\/n1$/.test(p) && r.method() === 'PUT') puts++
    })
    // open (preview) → enter edit → leave via Esc without changing a field → no PUT (#309 dirty flag)
    await page.getByRole('button', { name: /WLAN Passwort/ }).click()
    await page.locator('.hb-note-doc__body').click()
    await expect(page.getByPlaceholder('Inhalt (Markdown)…')).toBeVisible()
    await page.keyboard.press('Escape')
    // give the debounce window a chance to (wrongly) fire
    await page.waitForTimeout(1200)
    expect(puts).toBe(0)
  })

  // #323: a failed auto-save must NOT be silently lost. When the PUT fails (offline/flaky), the edit
  // is persisted to a durable localStorage queue (homebase_notes_pending), the note shows a "not
  // synced" marker, and it is retried on the next connectivity signal until it lands — then the
  // marker clears and the content is saved. Mirrors the shopping check-off queue (#170/#179).
  test('a failed auto-save is queued (not-synced marker + persisted) and retried on the next signal', async ({ page }) => {
    await openNotes(page, new MockApi().seedNotes([WLAN]))
    await editNote(page, /WLAN Passwort/)

    // Fail every PUT to n1 with a transport-level reject while `offline` is set; once cleared the
    // route falls through to the MockApi which persists it (a restored-connectivity retry).
    let offline = true
    await page.route('**/api/v1/notes/n1', async (route) => {
      if (route.request().method() !== 'PUT') return route.fallback()
      if (offline) return route.abort('failed')
      return route.fallback()
    })

    // edit the body → the debounced save fires, the PUT rejects → the edit is queued, not lost.
    // Plain text (no markdown chars) so the stripped list-preview is a clean, assertable substring.
    await page.getByPlaceholder('Inhalt (Markdown)…').fill('Router OFFLINEEDIT')

    // the editor shows the "not synced" marker (German), and the collective banner appears
    await expect(page.locator('.hb-savestatus.is-pending')).toHaveText(/Noch nicht synchronisiert/)
    await expect(page.locator('.hb-syncbar')).toContainText('wird nachgeholt')

    // the body was persisted to the durable queue keyed by the note id (survives a reload)
    const queued = await page.evaluate(() => {
      const raw = localStorage.getItem('homebase_notes_pending')
      return raw ? JSON.parse(raw) : null
    })
    expect(queued).not.toBeNull()
    expect(queued.n1).toBeTruthy()
    expect(JSON.parse(queued.n1.body).content).toBe('Router OFFLINEEDIT')

    // restore connectivity and fire a flush trigger (the manual "Jetzt versuchen" retry); the PUT
    // now lands, the marker + banner clear, and the queue entry is gone.
    const put = page.waitForRequest((r) => /\/notes\/n1$/.test(new URL(r.url()).pathname) && r.method() === 'PUT')
    offline = false
    await page.locator('.hb-syncbar').getByRole('button', { name: 'Jetzt versuchen' }).click()
    const req = await put
    expect(JSON.parse(req.postData() ?? '{}').content).toBe('Router OFFLINEEDIT')

    await expect(page.locator('.hb-savestatus.is-pending')).toHaveCount(0)
    await expect(page.locator('.hb-syncbar')).toHaveCount(0)
    const after = await page.evaluate(() => localStorage.getItem('homebase_notes_pending'))
    expect(after).toBeNull()
    // the list preview reflects the now-saved content, proving the queued write actually landed
    await expect(page.locator('.hb-noteitem.is-active .hb-noteitem__preview')).toContainText('OFFLINEEDIT')
  })

  // #323: a brand-new note whose CREATE fails offline parks under a sentinel key (no id yet). Each
  // edit while offline retries the create (so several POST *attempts* are fine — only one can ever
  // land, since the in-flight create guard blocks concurrent ones), and once one succeeds it captures
  // the returned id, so the note is NOT double-created and later edits PUT it.
  test('a failed create is queued under the sentinel and captures its id on a successful retry', async ({ page }) => {
    await openNotes(page, new MockApi())

    // Reject the create POST while offline; fall through once restored. Only the successful POST
    // creates a note in the mock, so the list size is the real "no duplicate" invariant.
    let offline = true
    await page.route('**/api/v1/notes', async (route) => {
      if (route.request().method() !== 'POST') return route.fallback()
      if (offline) return route.abort('failed')
      return route.fallback()
    })

    // a new note opens in the editor; type a title+body → the create POST fires and rejects
    await page.locator('.hb-pagehead').getByRole('button', { name: 'Neue Notiz' }).click()
    await page.getByPlaceholder('Titel…').fill('Offline Notiz')
    await page.getByPlaceholder('Inhalt (Markdown)…').fill('entworfen offline')

    // marker shows; the queue holds the create under the sentinel key (no id yet)
    await expect(page.locator('.hb-savestatus.is-pending')).toBeVisible()
    const queued = await page.evaluate(() => JSON.parse(localStorage.getItem('homebase_notes_pending') ?? '{}'))
    expect(queued.__new__).toBeTruthy()
    expect(queued.__new__.id).toBeUndefined()

    // restore + retry → the create lands, marker clears, queue empties, the note appears exactly once
    offline = false
    await page.locator('.hb-savestatus.is-pending').click()
    await expect(page.locator('.hb-savestatus.is-pending')).toHaveCount(0)
    await expect(page.getByRole('button', { name: /Offline Notiz/ })).toHaveCount(1) // no double-create
    const after = await page.evaluate(() => localStorage.getItem('homebase_notes_pending'))
    expect(after).toBeNull()

    // the id was captured into the draft → a follow-up edit auto-saves via PUT (not a new POST), and
    // it lands (no marker), proving the sentinel→id migration produced a real, editable note
    const put = page.waitForRequest((r) => /\/notes\/[^/]+$/.test(new URL(r.url()).pathname) && r.method() === 'PUT')
    await page.getByPlaceholder('Inhalt (Markdown)…').fill('entworfen offline — mehr')
    await put
    await expect(page.locator('.hb-savestatus.is-saved')).toBeVisible()
    await expect(page.getByRole('button', { name: /Offline Notiz/ })).toHaveCount(1)
  })

  test('deletes a note from the document header', async ({ page }) => {
    await openNotes(page, new MockApi().seedNotes([WLAN]))
    // the trash action sits in the document header in BOTH preview and edit (no separate view)
    await openNote(page, /WLAN Passwort/)
    await page.getByRole('button', { name: 'Löschen' }).click()

    await expect(page.getByText('Noch keine Notizen')).toBeVisible()
  })

  // The note-image gallery loads each thumbnail through authFetch (Authorization
  // header) → res.blob() → URL.createObjectURL(), so the JWT never rides in the
  // image URL. These cover that <AuthedImage> path end-to-end (issue #10). The gallery
  // lives in edit mode (HB-13), so each opens the document and enters the editor first.
  const PHOTOS = note({
    id: 'n1',
    title: 'Urlaubsfotos',
    images: [noteImage({ id: 'img1', noteId: 'n1', originalName: 'strand.png' })],
  })

  test('renders a note image thumbnail via authFetch→blob, JWT in header not URL', async ({ page }) => {
    await openNotes(page, new MockApi().seedNotes([PHOTOS]))

    const imageRequest = page.waitForRequest((r) => r.url().includes('/notes/n1/images/img1'))
    await editNote(page, /Urlaubsfotos/)

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
    await editNote(page, /Urlaubsfotos/)

    const thumb = page.locator('.hb-note-thumb img')
    await expect(thumb).toHaveAttribute('src', /^blob:/)
    await thumb.click()

    // the lightbox overlay opens with its own blob-loaded image
    const lightbox = page.locator('.hb-lightbox')
    await expect(lightbox).toBeVisible()
    await expect(lightbox.locator('img')).toHaveAttribute('src', /^blob:/)

    // clicking the backdrop (not the centered image) closes it — and does NOT kick us out of edit
    await lightbox.click({ position: { x: 5, y: 5 } })
    await expect(lightbox).toHaveCount(0)
    await expect(page.getByPlaceholder('Inhalt (Markdown)…')).toBeVisible()
  })

  // ---- Gallery: multi-select upload (#266) ----
  // The "Bild hinzufügen" button opens a hidden <input multiple>; selecting several files
  // uploads them one after another (one POST each) and the gallery shows all thumbnails.
  test('uploads multiple selected files at once and shows every thumbnail', async ({ page }) => {
    await openNotes(page, new MockApi().seedNotes([WLAN]))
    await editNote(page, /WLAN Passwort/)

    const uploads: string[] = []
    page.on('request', (r) => {
      if (r.url().includes('/notes/n1/images') && r.method() === 'POST') uploads.push(r.url())
    })

    // set three files on the hidden multi-file input directly (no native picker in CI)
    await page.locator('input[type="file"]').setInputFiles([
      { name: 'a.png', mimeType: 'image/png', buffer: Buffer.from([1, 2, 3]) },
      { name: 'b.png', mimeType: 'image/png', buffer: Buffer.from([4, 5, 6]) },
      { name: 'c.png', mimeType: 'image/png', buffer: Buffer.from([7, 8, 9]) },
    ])

    // all three thumbnails render (each via the authed blob loader)
    await expect(page.locator('.hb-note-thumb img')).toHaveCount(3)
    expect(uploads.length).toBe(3) // one request per file (approach A)
    // no error banner on the all-success path
    await expect(page.locator('.hb-note-images__error')).toHaveCount(0)
  })

  test('reports an aggregated error when some of several uploads fail', async ({ page }) => {
    // first upload fails (415), the remaining two succeed → "1 Bild(er) …" is NOT used
    // for a single failure; here exactly one fails so the generic single-fail text shows.
    await openNotes(page, new MockApi().seedNotes([WLAN]).failNextImageUpload(415))
    await editNote(page, /WLAN Passwort/)

    await page.locator('input[type="file"]').setInputFiles([
      { name: 'bad.tiff', mimeType: 'image/tiff', buffer: Buffer.from([1, 2, 3]) },
      { name: 'ok1.png', mimeType: 'image/png', buffer: Buffer.from([4, 5, 6]) },
      { name: 'ok2.png', mimeType: 'image/png', buffer: Buffer.from([7, 8, 9]) },
    ])

    // the two good files still landed
    await expect(page.locator('.hb-note-thumb img')).toHaveCount(2)
    // one failure → generic upload-failed text
    await expect(page.locator('.hb-note-images__error')).toHaveText('Upload fehlgeschlagen.')
  })

  // ---- Editor image upload: paste / drag&drop straight into the textarea (#146) ----
  // Fires a real `paste`/`drop` event carrying an image File at the caret, then asserts
  // the upload happened (POST /notes/{id}/images) and an inline `![name](image:<id>)`
  // ref was inserted. Both go through the same uploadImageToNote → insertAtCaret flow.

  // Dispatch a clipboard/drag event carrying a synthetic image File onto the editor
  // textarea, at the given caret offset. `mime` defaults to image/png; pass '' to
  // simulate the empty-MIME File some Safari paste/drag paths deliver (#154), where
  // detection must fall back to the filename extension. Whether the event was
  // cancelled by preventDefault is NOT reported here — use waitForRequest to assert uploads.
  const fireEditorImageEvent = (
    page: Page,
    kind: 'paste' | 'drop',
    filename: string,
    caret = 0,
    mime = 'image/png',
  ) =>
    page.evaluate(
      async ({ kind, filename, caret, mime }) => {
        const ta = document.querySelector('textarea.hb-mono-area') as HTMLTextAreaElement
        // entering edit focuses the textarea with the caret at the end via rAF (HB-13). Let that
        // settle first so the explicit test caret below is the last write, not clobbered by it.
        await new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r)))
        ta.focus()
        ta.setSelectionRange(caret, caret)
        const dt = new DataTransfer()
        dt.items.add(new File([new Uint8Array([1, 2, 3])], filename, mime ? { type: mime } : {}))
        const ev =
          kind === 'paste'
            ? new ClipboardEvent('paste', { clipboardData: dt, bubbles: true, cancelable: true })
            : new DragEvent('drop', { dataTransfer: dt, bubbles: true, cancelable: true })
        ta.dispatchEvent(ev)
      },
      { kind, filename, caret, mime },
    )

  // Dispatch a paste/drop carrying a NON-image payload (plain text, or a non-image file
  // for drops) onto the editor textarea, and report whether the handler called
  // preventDefault. `dispatchEvent` returns false iff the event was cancelled — so a
  // truthy result proves the browser's default (text paste / native drop) still runs.
  const fireEditorNonImageEvent = (page: Page, kind: 'paste' | 'drop', payload: string): Promise<boolean> =>
    page.evaluate(
      ({ kind, payload }) => {
        const ta = document.querySelector('textarea.hb-mono-area') as HTMLTextAreaElement
        ta.focus()
        const dt = new DataTransfer()
        if (kind === 'paste') dt.setData('text/plain', payload)
        else dt.items.add(new File([payload], 'notes.txt', { type: 'text/plain' }))
        const ev =
          kind === 'paste'
            ? new ClipboardEvent('paste', { clipboardData: dt, bubbles: true, cancelable: true })
            : new DragEvent('drop', { dataTransfer: dt, bubbles: true, cancelable: true })
        return ta.dispatchEvent(ev) // false ⇒ preventDefault was called
      },
      { kind, payload },
    )

  test('pastes an image into the editor → uploads and inserts a markdown ref', async ({ page }) => {
    await openNotes(page, new MockApi().seedNotes([WLAN]))
    await editNote(page, /WLAN Passwort/)

    const upload = page.waitForRequest((r) => r.url().includes('/notes/n1/images') && r.method() === 'POST')
    await fireEditorImageEvent(page, 'paste', 'pasted.png', 0)
    await upload

    // the upload response (note + new image) drives an inline ref at the caret
    const ta = page.getByPlaceholder('Inhalt (Markdown)…')
    await expect(ta).toHaveValue(/^!\[pasted\.png\]\(image:noteimg-\d+\)Router: \*\*abc123\*\*$/)
  })

  test('drops an image onto the editor → uploads and inserts a markdown ref', async ({ page }) => {
    await openNotes(page, new MockApi().seedNotes([WLAN]))
    await editNote(page, /WLAN Passwort/)

    const upload = page.waitForRequest((r) => r.url().includes('/notes/n1/images') && r.method() === 'POST')
    // drop at the end of the existing content
    await fireEditorImageEvent(page, 'drop', 'dropped.png', 'Router: **abc123**'.length)
    await upload

    const ta = page.getByPlaceholder('Inhalt (Markdown)…')
    await expect(ta).toHaveValue(/^Router: \*\*abc123\*\*!\[dropped\.png\]\(image:noteimg-\d+\)$/)
  })

  test('surfaces a 415 upload error in the editor (German text, no insert)', async ({ page }) => {
    await openNotes(page, new MockApi().seedNotes([WLAN]).failNextImageUpload(415))
    await editNote(page, /WLAN Passwort/)

    await fireEditorImageEvent(page, 'paste', 'weird.tiff', 0)

    await expect(page.locator('.hb-note-images__error')).toHaveText('Nur JPEG, PNG, WebP oder GIF erlaubt.')
    // nothing was inserted — content is unchanged
    await expect(page.getByPlaceholder('Inhalt (Markdown)…')).toHaveValue('Router: **abc123**')
  })

  // #154: some browsers/paths (certain Safari paste variants, some drag sources) hand us
  // an image File with an EMPTY `type`. MIME-only matching dropped those silently (no
  // upload, no error). Detection now falls back to the filename extension — so an
  // empty-type .png still uploads and inserts, on both the paste and the drop path.
  test('empty-MIME image (extension fallback) still uploads + inserts on paste', async ({ page }) => {
    await openNotes(page, new MockApi().seedNotes([WLAN]))
    await editNote(page, /WLAN Passwort/)

    const upload = page.waitForRequest((r) => r.url().includes('/notes/n1/images') && r.method() === 'POST')
    await fireEditorImageEvent(page, 'paste', 'safari-screenshot.png', 0, '') // empty type
    await upload

    const ta = page.getByPlaceholder('Inhalt (Markdown)…')
    await expect(ta).toHaveValue(/^!\[safari-screenshot\.png\]\(image:noteimg-\d+\)Router: \*\*abc123\*\*$/)
  })

  test('empty-MIME image (extension fallback) still uploads + inserts on drop', async ({ page }) => {
    await openNotes(page, new MockApi().seedNotes([WLAN]))
    await editNote(page, /WLAN Passwort/)

    const upload = page.waitForRequest((r) => r.url().includes('/notes/n1/images') && r.method() === 'POST')
    await fireEditorImageEvent(page, 'drop', 'dragged.gif', 'Router: **abc123**'.length, '') // empty type
    await upload

    const ta = page.getByPlaceholder('Inhalt (Markdown)…')
    await expect(ta).toHaveValue(/^Router: \*\*abc123\*\*!\[dragged\.gif\]\(image:noteimg-\d+\)$/)
  })

  // The extension fallback must NOT swallow non-image files that also lack a MIME type:
  // an empty-type .txt is left to the browser (no preventDefault, no upload).
  test('empty-MIME non-image file (.txt) is NOT treated as an image on drop', async ({ page }) => {
    await openNotes(page, new MockApi().seedNotes([WLAN]))
    await editNote(page, /WLAN Passwort/)

    let uploaded = false
    page.on('request', (r) => { if (r.url().includes('/images') && r.method() === 'POST') uploaded = true })

    const droppedPrevented = await page.evaluate(() => {
      const ta = document.querySelector('textarea.hb-mono-area') as HTMLTextAreaElement
      ta.focus()
      const dt = new DataTransfer()
      dt.items.add(new File(['hello'], 'notes.txt', {})) // empty type, non-image extension
      return ta.dispatchEvent(new DragEvent('drop', { dataTransfer: dt, bubbles: true, cancelable: true }))
    })
    expect(droppedPrevented).toBe(true) // dispatchEvent === true ⇒ default not cancelled
    expect(uploaded).toBe(false)
  })

  test('keeps edits typed WHILE the upload is in flight (no stale-draft clobber)', async ({ page }) => {
    const mock = new MockApi().seedNotes([WLAN]).holdNextImageUpload()
    await openNotes(page, mock)
    await editNote(page, /WLAN Passwort/)

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
    // With no notes, the empty state also renders a "Neue Notiz" action (#228); scope the click
    // to the page header so the locator stays unambiguous (the empty-state button does the same).
    // A new note opens directly in the editor (HB-13), so the source textarea is right there.
    await page.locator('.hb-pagehead').getByRole('button', { name: 'Neue Notiz' }).click()
    await expect(page.getByPlaceholder('Inhalt (Markdown)…')).toBeVisible()

    let uploaded = false
    page.on('request', (r) => { if (r.url().includes('/images') && r.method() === 'POST') uploaded = true })
    await fireEditorImageEvent(page, 'paste', 'early.png', 0)

    await expect(page.locator('.hb-note-images__error')).toHaveText('Notiz zuerst speichern, dann Bilder einfügen.')
    expect(uploaded).toBe(false)
  })

  // Plain-text paste / non-image drop must pass straight through: no upload request and
  // crucially NO preventDefault — otherwise typing/pasting text or dragging text into the
  // textarea would break. Guards the "preventDefault only when we actually take an image".
  test('plain-text paste passes through (no upload, not preventDefault\'d)', async ({ page }) => {
    await openNotes(page, new MockApi().seedNotes([WLAN]))
    await editNote(page, /WLAN Passwort/)

    let uploaded = false
    page.on('request', (r) => { if (r.url().includes('/images') && r.method() === 'POST') uploaded = true })

    const prevented = await fireEditorNonImageEvent(page, 'paste', 'einfach Text')
    expect(prevented).toBe(true) // dispatchEvent === true ⇒ default not cancelled
    expect(uploaded).toBe(false)
    // and a non-image FILE drop is ignored just the same (left to the browser)
    const droppedPrevented = await fireEditorNonImageEvent(page, 'drop', 'irgendein Text')
    expect(droppedPrevented).toBe(true)
    expect(uploaded).toBe(false)
  })

  // The thumbnail-click insert and the paste/drop insert share insertAtCaret, which
  // sanitizes the alt text. A name with `]`/`(`/`)` (e.g. "Screenshot (1)].png", a very
  // common download name) would otherwise break the inline-image ref — those chars must
  // be replaced with spaces so it still renders as an image.
  test('sanitizes ] ( ) in the image name when inserting an inline ref', async ({ page }) => {
    const note1 = note({
      id: 'n1',
      title: 'Fotos',
      content: '',
      images: [noteImage({ id: 'img1', noteId: 'n1', originalName: 'Screenshot (1)].png' })],
    })
    await openNotes(page, new MockApi().seedNotes([note1]))
    await editNote(page, /Fotos/)

    // click the "insert at cursor" thumbnail in the editor strip
    await page.locator('.hb-note-insert-thumb').first().click()
    // ()] each stripped to a space → the ref stays well-formed; id is untouched
    await expect(page.getByPlaceholder('Inhalt (Markdown)…')).toHaveValue('![Screenshot  1  .png](image:img1)')
  })

  // Cross-note guard: if the user switches to editing another note WHILE an upload is in
  // flight, the resolved ref must NOT land in the now-open note (the image itself is still
  // saved to the original note server-side). Only the wrong text insertion is skipped.
  test('does not insert the ref into a different note opened during the upload', async ({ page }) => {
    const A = note({ id: 'n1', title: 'Notiz A', content: 'AAA' })
    const B = note({ id: 'n2', title: 'Notiz B', content: 'BBB' })
    const mock = new MockApi().seedNotes([A, B]).holdNextImageUpload()
    await openNotes(page, mock)
    await editNote(page, /Notiz A/)

    const upload = page.waitForRequest((r) => r.url().includes('/notes/n1/images') && r.method() === 'POST')
    await fireEditorImageEvent(page, 'paste', 'switch.png', 0)
    await upload // pending in the mock

    // switch to editing note B while A's upload is still open
    await editNote(page, /Notiz B/)
    await expect(page.getByPlaceholder('Inhalt (Markdown)…')).toHaveValue('BBB')

    // release A's upload → the guard skips the insert; B stays untouched
    await mock.releaseImageUpload()
    // give the resolved promise a tick; B's content must remain exactly "BBB"
    await expect(page.getByPlaceholder('Inhalt (Markdown)…')).toHaveValue('BBB')

    // and A really did receive the image (ref insert skipped, upload succeeded). Switching
    // back to A and entering the editor shows the uploaded attachment in the gallery.
    await editNote(page, /Notiz A/)
    await expect(page.getByPlaceholder('Inhalt (Markdown)…')).toHaveValue('AAA')
    await expect(page.locator('.hb-note-thumb img')).toHaveCount(1)
  })

  // ---- Folder grouping (#311) ----
  // The flat list becomes folder-grouped sections: a header (folder name + count) with its
  // notes indented beneath; notes without a folder fall into the "Ohne Ordner" group.
  test('groups notes into folder sections with headers + counts', async ({ page }) => {
    await openNotes(page, new MockApi().seedNotes([
      note({ id: 'n1', title: 'Steuer 2025', folder: 'Finanzen' }),
      note({ id: 'n2', title: 'Versicherung', folder: 'Finanzen' }),
      note({ id: 'n3', title: 'Geschenkideen' }), // no folder
    ]))

    const groups = page.locator('.hb-notes-group')
    // two groups: the named "Finanzen" folder, then the "Ohne Ordner" bucket
    await expect(groups).toHaveCount(2)

    const finanzen = groups.filter({ hasText: 'Finanzen' })
    await expect(finanzen.locator('.hb-notes-group__count')).toHaveText('2')
    await expect(finanzen.getByRole('button', { name: /Steuer 2025/ })).toBeVisible()
    await expect(finanzen.getByRole('button', { name: /Versicherung/ })).toBeVisible()

    const ohne = groups.filter({ hasText: 'Ohne Ordner' })
    await expect(ohne.locator('.hb-notes-group__count')).toHaveText('1')
    await expect(ohne.getByRole('button', { name: /Geschenkideen/ })).toBeVisible()
  })

  test('folder filter chip still narrows the grouped list to that folder', async ({ page }) => {
    await openNotes(page, new MockApi().seedNotes([
      note({ id: 'n1', title: 'Steuer 2025', folder: 'Finanzen' }),
      note({ id: 'n2', title: 'Rezept-Notiz', folder: 'Küche' }),
    ]))
    // both folder groups present initially
    await expect(page.locator('.hb-notes-group')).toHaveCount(2)

    // activating the "Finanzen" filter chip narrows the grouping to just that folder
    await page.locator('.hb-tagchip', { hasText: 'Finanzen' }).click()
    await expect(page.locator('.hb-notes-group')).toHaveCount(1)
    await expect(page.locator('.hb-notes-group__name')).toHaveText('Finanzen')
    await expect(page.getByRole('button', { name: /Rezept-Notiz/ })).toHaveCount(0)
  })

  // A folder header collapses/expands its section; the collapsed set is persisted in
  // localStorage so it survives a reload.
  test('collapses a folder section via its header and persists the state across reload', async ({ page }) => {
    await openNotes(page, new MockApi().seedNotes([
      note({ id: 'n1', title: 'Steuer 2025', folder: 'Finanzen' }),
      note({ id: 'n2', title: 'Versicherung', folder: 'Finanzen' }),
      note({ id: 'n3', title: 'Geschenkideen' }), // no folder → control group, stays expanded
    ]))

    const finanzenHead = page.locator('.hb-notes-group__head', { hasText: 'Finanzen' })
    // expanded by default: the folder's notes are visible
    await expect(finanzenHead).toHaveAttribute('aria-expanded', 'true')
    await expect(page.getByRole('button', { name: /Steuer 2025/ })).toBeVisible()

    // collapsing hides only this folder's notes (the no-folder note stays put)
    await finanzenHead.click()
    await expect(finanzenHead).toHaveAttribute('aria-expanded', 'false')
    await expect(page.getByRole('button', { name: /Steuer 2025/ })).toHaveCount(0)
    await expect(page.getByRole('button', { name: /Versicherung/ })).toHaveCount(0)
    await expect(page.getByRole('button', { name: /Geschenkideen/ })).toBeVisible()

    // clicking again expands it back
    await finanzenHead.click()
    await expect(finanzenHead).toHaveAttribute('aria-expanded', 'true')
    await expect(page.getByRole('button', { name: /Steuer 2025/ })).toBeVisible()

    // collapse, then reload: the section comes back collapsed (read from localStorage)
    await finanzenHead.click()
    await expect(page.getByRole('button', { name: /Steuer 2025/ })).toHaveCount(0)
    await page.reload()
    await page.getByRole('button', { name: 'Notizen' }).click()
    const finanzenAfter = page.locator('.hb-notes-group__head', { hasText: 'Finanzen' })
    await expect(finanzenAfter).toHaveAttribute('aria-expanded', 'false')
    await expect(page.getByRole('button', { name: /Steuer 2025/ })).toHaveCount(0)
  })

  // The "Alle ein-/ausklappen" toolbar control (#345) collapses/expands every visible folder
  // group at once and persists via the same localStorage key as the per-folder toggle.
  test('collapse-all / expand-all toggles every folder group and persists', async ({ page }) => {
    await openNotes(page, new MockApi().seedNotes([
      note({ id: 'n1', title: 'Steuer 2025', folder: 'Finanzen' }),
      note({ id: 'n2', title: 'Rezept-Notiz', folder: 'Küche' }),
      note({ id: 'n3', title: 'Geschenkideen' }), // no folder → the '' bucket
    ]))

    const control = page.locator('.hb-notes-collapseall')
    // all expanded by default → the control offers "collapse all"
    await expect(control).toHaveText(/Alle einklappen/)
    await expect(page.getByRole('button', { name: /Steuer 2025/ })).toBeVisible()
    await expect(page.getByRole('button', { name: /Rezept-Notiz/ })).toBeVisible()
    await expect(page.getByRole('button', { name: /Geschenkideen/ })).toBeVisible()

    // collapse all → every group's notes hide (incl. the no-folder bucket) and the label flips
    await control.click()
    await expect(control).toHaveText(/Alle ausklappen/)
    await expect(page.getByRole('button', { name: /Steuer 2025/ })).toHaveCount(0)
    await expect(page.getByRole('button', { name: /Rezept-Notiz/ })).toHaveCount(0)
    await expect(page.getByRole('button', { name: /Geschenkideen/ })).toHaveCount(0)
    // every header now reads collapsed
    for (const head of await page.locator('.hb-notes-group__head').all()) {
      await expect(head).toHaveAttribute('aria-expanded', 'false')
    }

    // persisted: a reload keeps everything collapsed and the label on "expand all"
    await page.reload()
    await page.getByRole('button', { name: 'Notizen' }).click()
    await expect(page.locator('.hb-notes-collapseall')).toHaveText(/Alle ausklappen/)
    await expect(page.getByRole('button', { name: /Steuer 2025/ })).toHaveCount(0)

    // expand all → the set is cleared and every group's notes return
    await page.locator('.hb-notes-collapseall').click()
    await expect(page.locator('.hb-notes-collapseall')).toHaveText(/Alle einklappen/)
    await expect(page.getByRole('button', { name: /Steuer 2025/ })).toBeVisible()
    await expect(page.getByRole('button', { name: /Geschenkideen/ })).toBeVisible()
  })

  // ---- Mobile collapse + back control (#313) ----
  // At ≤860px the list and document are one-pane-at-a-time: browsing shows the list, opening a
  // note collapses the list to show the full-width document, and a back control returns to it.
  test('mobile: opening a note collapses the list; back restores it', async ({ page }) => {
    // navigate via the desktop nav first, then shrink to the mobile breakpoint
    await openNotes(page, new MockApi().seedNotes([WLAN]))
    await page.setViewportSize({ width: 600, height: 900 })

    const list = page.locator('.hb-notes-list')
    await expect(list).toBeVisible()

    // open the note → list collapses, full-width document (preview) + mobile back bar appear
    await page.getByRole('button', { name: /WLAN Passwort/ }).click()
    await expect(page.locator('.hb-note-doc__title')).toHaveText('WLAN Passwort')
    await expect(list).toBeHidden()
    // the mobile back control (scoped to the editor bar to avoid the nav's "Notizen" tab)
    const back = page.locator('.hb-note-back')
    await expect(back).toBeVisible()
    await expect(back).toHaveText('Notizen')

    // back → document closes, list returns
    await back.click()
    await expect(list).toBeVisible()
    await expect(page.locator('.hb-note-doc')).toHaveCount(0)
  })

  test('mobile: the note switcher slide-over jumps to another note', async ({ page }) => {
    await openNotes(page, new MockApi().seedNotes([
      note({ id: 'n1', title: 'Erste Notiz', content: 'AAA' }),
      note({ id: 'n2', title: 'Zweite Notiz', content: 'BBB' }),
    ]))
    await page.setViewportSize({ width: 600, height: 900 })

    await page.getByRole('button', { name: /Erste Notiz/ }).click()
    await expect(page.locator('.hb-note-doc__body')).toContainText('AAA')

    // open the switcher slide-over and pick the other note (no "back" needed)
    await page.getByRole('button', { name: 'Notiz wechseln' }).click()
    const sheet = page.locator('.hb-sheet')
    await expect(sheet).toBeVisible()
    await sheet.getByRole('button', { name: /Zweite Notiz/ }).click()

    // the document now shows the second note; the sheet has closed
    await expect(page.locator('.hb-note-doc__body')).toContainText('BBB')
    await expect(sheet).toHaveCount(0)
  })
})
