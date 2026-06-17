import {
  useState,
  useEffect,
  useCallback,
  useRef,
  useMemo,
  type ClipboardEvent as ReactClipboardEvent,
  type DragEvent as ReactDragEvent,
  type FocusEvent as ReactFocusEvent,
} from 'react'
import { useTranslation } from 'react-i18next'
import { API_BASE, authFetch, downloadImage, errorCode, noteImageUrl, notifyTransportError, safeFetch } from '../api'
import { errorText } from '../i18n'
import { Note, NoteImage, NoteVisibility } from '../types'
import { useWebSocket } from '../hooks/useWebSocket'
import { AuthedImage } from '../ui/AuthedImage'
import { Icon } from '../ui/Icon'
import { useErrorToast } from '../ui/ErrorToast'
import {
  Button,
  Card,
  EmptyState,
  Field,
  IconButton,
  PageHead,
  Sheet,
  TextInput,
  renderMarkdown,
} from '../ui/primitives'
import { relTime } from '../ui/format'

const WS_SCHEME = window.location.protocol === 'https:' ? 'wss' : 'ws'
const WS_URL = import.meta.env.VITE_WS_URL_NOTES ?? `${WS_SCHEME}://${window.location.host}/api/v1/ws/notes`

// Debounce window for auto-save after the last keystroke/field change (#309).
const AUTOSAVE_DELAY = 900

// Collapsed folder sections persist across reloads/navigation: a list of folder keys
// the user has collapsed in the grouped note list ('' = the no-folder bucket).
const COLLAPSED_FOLDERS_KEY = 'homebase_notes_collapsed_folders'

interface Draft {
  id?: string
  title: string
  content: string
  tags: string
  folder: string
  visibility: NoteVisibility
}

type SaveState = 'idle' | 'saving' | 'saved' | 'error'

const emptyDraft = (): Draft => ({ title: '', content: '', tags: '', folder: '', visibility: 'SHARED' })
const draftFromNote = (n: Note): Draft => ({
  id: n.id,
  title: n.title,
  content: n.content,
  tags: n.tags.join(', '),
  folder: n.folder ?? '',
  visibility: n.visibility,
})
const parseTags = (raw: string): string[] => raw.split(',').map((x) => x.trim()).filter(Boolean)

// Canonical JSON of the persisted fields — drives both the PUT/POST body and the
// dirty check (compare against the last-saved snapshot so we never fire a redundant
// PUT right after loading a note, #309).
const serializeDraft = (d: Draft): string =>
  JSON.stringify({
    title: d.title.trim(),
    content: d.content,
    tags: parseTags(d.tags),
    // always send a (possibly empty) string: the backend trims it and maps blank ⇒
    // null, so this both sets a folder and clears one when the field is emptied.
    folder: d.folder.trim(),
    visibility: d.visibility,
  })

interface NotesViewProps {
  token: string
  onLogout: () => void
}

export function NotesView({ token, onLogout }: NotesViewProps) {
  const { t } = useTranslation()
  const [notes, setNotes] = useState<Note[]>([])
  const [loading, setLoading] = useState(true)
  const [query, setQuery] = useState('')
  const [draft, setDraft] = useState<Draft | null>(null)
  const [saveState, setSaveState] = useState<SaveState>('idle')
  const [saveError, setSaveError] = useState<string | null>(null)
  const [selectedId, setSelectedId] = useState<string | null>(null)
  // editor sub-mode: false = Markdown source textarea, true = rendered preview (#310)
  const [previewMode, setPreviewMode] = useState(false)
  // mobile (≤860px) note-switcher slide-over (#313)
  const [switcherOpen, setSwitcherOpen] = useState(false)
  const [tagFilter, setTagFilter] = useState<string | null>(null)
  // null = all folders; '' = the "no folder" bucket; otherwise a specific folder name
  const [folderFilter, setFolderFilter] = useState<string | null>(null)
  // Collapsed folder sections in the grouped list — persisted so a tidied list stays tidy
  // across reloads. Keys are folder names; '' is the no-folder bucket. Absent = expanded.
  const [collapsedFolders, setCollapsedFolders] = useState<Set<string>>(() => {
    try {
      const raw = localStorage.getItem(COLLAPSED_FOLDERS_KEY)
      // guard the parse: only an array seeds the set — a bare string would otherwise
      // iterate into per-character "folders" (new Set("ab") → {'a','b'}).
      if (raw) {
        const parsed = JSON.parse(raw)
        if (Array.isArray(parsed)) return new Set<string>(parsed)
      }
    } catch { /* corrupt/unavailable storage → start all-expanded */ }
    return new Set<string>()
  })
  const [uploadingImage, setUploadingImage] = useState(false)
  // progress of a multi-file gallery upload (null = no batch in flight); drives the button label
  const [uploadProgress, setUploadProgress] = useState<{ done: number; total: number } | null>(null)
  const [imageError, setImageError] = useState<string | null>(null)
  const [lightbox, setLightbox] = useState<{ noteId: string; imageId: string; originalName: string } | null>(null)
  const fileInputRef = useRef<HTMLInputElement>(null)
  const contentRef = useRef<HTMLTextAreaElement>(null)
  // live mirror of the open draft's id — read after an awaited upload to detect that the
  // user switched/closed the editor in the meantime (the captured `draft` would be stale).
  const draftIdRef = useRef<string | undefined>(undefined)
  draftIdRef.current = draft?.id

  // ---- Auto-save plumbing (#309) ----
  // Live mirror of the draft so async callbacks (debounce, blur, commit-on-switch)
  // read the CURRENT editor model rather than a stale render closure.
  const draftRef = useRef<Draft | null>(null)
  draftRef.current = draft
  // Monotonic editor-session token. Bumped every time an editor session begins (open
  // existing note or start a new one). A save captures the session it was issued for;
  // the id-capture after a create only applies if we're STILL in that same session —
  // so switching notes / opening a different new note mid-create can't get the wrong id.
  const sessionRef = useRef(0)
  // Serialized last-saved payload for the current session; the debounce only saves when
  // the live draft differs from this, so a freshly-opened note is never re-PUT.
  const savedSnapshotRef = useRef<string | null>(null)
  // In-flight guards: savingRef serializes saves; creatingRef specifically blocks a
  // second POST while the first create (no id yet) is still in flight (double-POST race).
  const savingRef = useRef(false)
  const creatingRef = useRef(false)
  const autosaveTimer = useRef<ReturnType<typeof setTimeout>>()
  const { flashError, errorToast } = useErrorToast()

  const fetchNotes = useCallback(async (q: string) => {
    try {
      const url = q.trim() ? `${API_BASE}/notes?q=${encodeURIComponent(q.trim())}` : `${API_BASE}/notes`
      const result = await safeFetch(token, url)
      // transport reject → fire the global toast once, keep existing data
      if (!result.ok) {
        notifyTransportError()
        return
      }
      const { res } = result
      if (res.status === 401) {
        onLogout()
        return
      }
      if (!res.ok) return
      setNotes(await res.json())
    } finally {
      setLoading(false)
    }
  }, [onLogout, token])

  const debounce = useRef<ReturnType<typeof setTimeout>>()
  useEffect(() => {
    clearTimeout(debounce.current)
    debounce.current = setTimeout(() => fetchNotes(query), 200)
    return () => clearTimeout(debounce.current)
  }, [query, fetchNotes])

  useWebSocket({ url: WS_URL, token }, (raw) => {
    try {
      const msg = JSON.parse(raw)
      if (!msg.payload) return
      // WS-echo safety (#309): every save broadcasts NOTE_UPDATED, and we merge it into
      // the `notes` list only. The editor binds to the separate `draft` state, so the
      // echo never clobbers the in-progress text/caret — we deliberately do NOT feed the
      // echoed payload back into `draft`.
      if (msg.type === 'NOTE_CREATED') {
        setNotes((prev) => (prev.some((n) => n.id === msg.payload.id) ? prev : [msg.payload, ...prev]))
      } else if (msg.type === 'NOTE_UPDATED') {
        setNotes((prev) =>
          prev.some((n) => n.id === msg.payload.id)
            ? prev.map((n) => (n.id === msg.payload.id ? msg.payload : n))
            : [msg.payload, ...prev],
        )
      } else if (msg.type === 'NOTE_DELETED') {
        setNotes((prev) => prev.filter((n) => n.id !== msg.payload.id))
        setSelectedId((cur) => (cur === msg.payload.id ? null : cur))
      }
    } catch {
      // ignore malformed frames
    }
  })

  // Persist the given draft (POST for a new note, PUT for an existing one). Reads from an
  // explicit `d` snapshot so it can run against the OUTGOING note when the user is already
  // switching away. `session` is the editor session this save belongs to.
  const saveDraft = useCallback(
    async (d: Draft, session: number) => {
      const title = d.title.trim()
      // Never write an empty title (the backend requires one for both POST and PUT);
      // for a new note this means we simply don't create until the user types a title.
      if (!title) return
      // Skip when nothing changed since the last save (e.g. a stray blur right after load).
      const body = serializeDraft(d)
      if (body === savedSnapshotRef.current) return
      // Don't start a second create while the first is still in flight (#309 double-POST).
      if (!d.id && creatingRef.current) return
      if (savingRef.current) return
      savingRef.current = true
      if (!d.id) creatingRef.current = true
      setSaveState('saving')
      setSaveError(null)
      // Did this attempt succeed (HTTP ok)? Gates the trailing-edit re-fire in `finally`:
      // we re-fire ONLY after a successful save. On a transport/HTTP failure the live draft
      // stays dirty vs the snapshot, so a blanket re-fire would hot-loop and hammer the
      // network — leave the inline error up and wait for the next edit/debounce instead.
      let saveOk = false
      // The id captured from a successful create, so a trailing-edit re-fire PUTs the same
      // note instead of POSTing a second one. We can't read it back off `draft`: setDraft is
      // async and may not have flushed by the time the re-fire reads draftRef.
      let savedId: string | undefined = d.id
      try {
        const result = d.id
          ? await safeFetch(token, `${API_BASE}/notes/${d.id}`, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body })
          : await safeFetch(token, `${API_BASE}/notes`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body })
        // transport reject → surface the inline error so the user notices (edits are NOT lost)
        if (!result.ok) {
          setSaveError(errorText(null, t('notes.saveFailed')))
          setSaveState('error')
          return
        }
        const { res } = result
        if (res.status === 401) return onLogout()
        if (res.ok) {
          saveOk = true
          const saved: Note = await res.json()
          savedId = saved.id
          setNotes((prev) => (prev.some((n) => n.id === saved.id) ? prev.map((n) => (n.id === saved.id ? saved : n)) : [saved, ...prev]))
          // Remember exactly what we persisted so the next change is detected as dirty.
          savedSnapshotRef.current = body
          // On the FIRST create, capture the new id INTO the draft so subsequent autosaves
          // PUT it — but only if we're still editing the same (still-unsaved) session, so a
          // switch/new-note mid-create can't get stamped with the wrong id (#309).
          if (!d.id && sessionRef.current === session) {
            setSelectedId(saved.id)
            setDraft((prev) => (prev && !prev.id ? { ...prev, id: saved.id } : prev))
          }
          // Truthful status (#309): a keystroke that arrived WHILE this save was in flight is
          // still unsaved (its debounce tick early-returned because savingRef was set, and
          // nothing re-schedules it). If the live draft of THIS session still differs from
          // what we just wrote, KEEP the status on "saving" — don't flash "Gespeichert" while
          // a newer edit is pending; the `finally` re-fire below will persist it and only then
          // does the status reach "saved".
          const live = draftRef.current
          const trailingDirty = sessionRef.current === session && !!live && serializeDraft(live) !== savedSnapshotRef.current
          setSaveState(trailingDirty ? 'saving' : 'saved')
        } else {
          setSaveError(errorText(await errorCode(res), t('notes.saveFailed')))
          setSaveState('error')
        }
      } finally {
        savingRef.current = false
        creatingRef.current = false
        // Trailing-edit re-fire (#309): persist a change typed during the in-flight window.
        // Must run AFTER clearing savingRef/creatingRef, or the re-fire would hit the in-flight
        // early-return. Re-reads the freshest draft + same session and re-checks dirtiness, so
        // it is a no-op when nothing changed. Reusing the now-captured id keeps a mid-create
        // trailing edit a PUT (not a second POST). This also rescues a commitPending()
        // (blur/leave/switch/UNMOUNT) that early-returned during the save: its edit lives in
        // draftRef and gets persisted by this tail. Guarded by saveOk — never on failure.
        if (saveOk) {
          const live = draftRef.current
          if (sessionRef.current === session && live && serializeDraft(live) !== savedSnapshotRef.current) {
            void saveDraft(savedId ? { ...live, id: savedId } : live, session)
          }
        }
      }
    },
    [onLogout, t, token],
  )

  // Cancel any pending debounce and flush an immediate save of the CURRENT draft. Called
  // when leaving the editor (switch note / close / blur / unmount) so nothing is dropped.
  const commitPending = useCallback(() => {
    clearTimeout(autosaveTimer.current)
    const d = draftRef.current
    if (d) void saveDraft(d, sessionRef.current)
  }, [saveDraft])

  // Open the editor on a note (or a brand-new draft). Bumps the session, seeds the
  // dirty-baseline so an unedited note is never re-saved, and resets the editor UI.
  const openEditor = useCallback((note: Note | null) => {
    // re-clicking the note already open in the editor is a no-op — don't reset the draft
    // (which would discard in-flight, not-yet-debounced edits and flicker the content).
    if (note && draftRef.current?.id === note.id) return
    // flush whatever was being edited before swapping the draft out (#309)
    commitPending()
    sessionRef.current++
    const next = note ? draftFromNote(note) : emptyDraft()
    savedSnapshotRef.current = note ? serializeDraft(next) : null
    setDraft(next)
    setSelectedId(note ? note.id : null)
    setPreviewMode(false)
    setSaveState('idle')
    setSaveError(null)
  }, [commitPending])

  // Close the editor back to the empty state (mobile "back", desktop Cancel/Close). Edits
  // are already auto-saved; commitPending covers any change still inside the debounce window.
  const closeEditor = useCallback(() => {
    commitPending()
    setDraft(null)
    setSelectedId(null)
    setSwitcherOpen(false)
  }, [commitPending])

  // Debounced auto-save: whenever the draft changes and differs from the last save, schedule
  // a save AUTOSAVE_DELAY after the last change. Keyed on the serialized payload so only the
  // persisted fields (title/content/tags/folder/visibility) restart the timer.
  const draftKey = draft ? serializeDraft(draft) : null
  useEffect(() => {
    if (draftKey == null) return
    if (draftKey === savedSnapshotRef.current) return
    const session = sessionRef.current
    clearTimeout(autosaveTimer.current)
    autosaveTimer.current = setTimeout(() => {
      const d = draftRef.current
      if (d) void saveDraft(d, session)
    }, AUTOSAVE_DELAY)
    return () => clearTimeout(autosaveTimer.current)
  }, [draftKey, saveDraft])

  // Flush a pending save if the whole NotesView unmounts mid-edit (view switch).
  useEffect(() => () => { commitPending() }, [commitPending])

  // Commit when focus leaves the editor entirely (blur to outside the card, #309).
  const handleEditorBlur = (e: ReactFocusEvent<HTMLDivElement>) => {
    if (!e.currentTarget.contains(e.relatedTarget as Node | null)) commitPending()
  }

  const handleDelete = async (id: string) => {
    // a delete supersedes any pending auto-save of this note
    clearTimeout(autosaveTimer.current)
    setNotes((prev) => prev.filter((n) => n.id !== id))
    setDraft(null)
    setSelectedId(null)
    setSwitcherOpen(false)
    const result = await safeFetch(token, `${API_BASE}/notes/${id}`, { method: 'DELETE' })
    // On failure refetch to resync rather than restoring a captured snapshot,
    // which could clobber a concurrent WS update.
    if (!result.ok) {
      await fetchNotes(query)
      return flashError(errorText(null, t('notes.deleteFailed')))
    }
    const { res } = result
    if (res.status === 401) return onLogout()
    if (!res.ok) {
      await fetchNotes(query)
      flashError(errorText(await errorCode(res), t('notes.deleteFailed')))
    }
  }

  const allTags = useMemo(() => {
    const s = new Set<string>()
    notes.forEach((n) => n.tags.forEach((tag) => s.add(tag)))
    return [...s].sort()
  }, [notes])

  // folders are derived client-side from the loaded notes, just like tags — there is no
  // separate folder entity. Blank/absent folders are not their own named folder.
  const allFolders = useMemo(() => {
    const s = new Set<string>()
    notes.forEach((n) => { if (n.folder) s.add(n.folder) })
    return [...s].sort((a, b) => a.localeCompare(b, 'de'))
  }, [notes])

  const listed = useMemo(
    () =>
      notes.filter((n) => {
        if (tagFilter && !n.tags.includes(tagFilter)) return false
        if (folderFilter !== null) {
          // '' selects notes without a folder; otherwise an exact folder match
          if (folderFilter === '') { if (n.folder) return false }
          else if (n.folder !== folderFilter) return false
        }
        return true
      }),
    [notes, tagFilter, folderFilter],
  )

  // Folder-grouped sections (#311): named folders first (alphabetical, de), then the
  // "no folder" bucket last; within each group newest-first by updatedAt. Applied to the
  // already-filtered subset, so an active folder/tag filter just narrows what's grouped.
  const groups = useMemo(() => {
    const byFolder = new Map<string, Note[]>()
    for (const n of listed) {
      const key = n.folder || ''
      const arr = byFolder.get(key)
      if (arr) arr.push(n)
      else byFolder.set(key, [n])
    }
    const named = [...byFolder.keys()].filter((k) => k !== '').sort((a, b) => a.localeCompare(b, 'de'))
    const keys = byFolder.has('') ? [...named, ''] : named
    return keys.map((key) => ({
      folder: key, // '' = the no-folder bucket
      notes: byFolder.get(key)!.slice().sort((a, b) => b.updatedAt.localeCompare(a.updatedAt)),
    }))
  }, [listed])

  // Collapse/expand a folder section and persist the new set (best-effort).
  const toggleFolder = useCallback((key: string) => {
    setCollapsedFolders((prev) => {
      const next = new Set(prev)
      if (next.has(key)) next.delete(key)
      else next.add(key)
      try {
        localStorage.setItem(COLLAPSED_FOLDERS_KEY, JSON.stringify([...next]))
      } catch { /* ignore quota/availability errors — collapse still works this session */ }
      return next
    })
  }, [])

  // the note currently being edited (live, from `notes`) — source for the image gallery +
  // caret-insertion. Only existing (saved) notes have images; a brand-new draft has none.
  const editNote = draft?.id ? notes.find((n) => n.id === draft.id) ?? null : null
  const editImages = editNote?.images ?? []

  // clear any stale image upload error when the editor opens/closes/switches notes —
  // paste/drop errors must not leak across views (#146)
  useEffect(() => { setImageError(null) }, [draft?.id, draft === null])

  // Post a single image file to a saved note and refresh that note in state. Returns the
  // newly-attached NoteImage on success, or an HTTP-status-tagged failure — the callers
  // own how to surface it (inline message vs. aggregated batch count). Does NOT touch
  // imageError/uploadingImage itself so it composes cleanly into the multi-file loop.
  // The backend appends each image (correct sort_order); N sequential calls keep order.
  const uploadOneImage = async (
    noteId: string,
    file: File,
  ): Promise<{ ok: true; image: NoteImage | null } | { ok: false; status: number | null; code: string | null }> => {
    try {
      const fd = new FormData()
      fd.append('file', file)
      const res = await authFetch(token, `${API_BASE}/notes/${noteId}/images`, { method: 'POST', body: fd })
      if (res.ok) {
        const updated: Note = await res.json()
        setNotes((prev) => prev.map((n) => (n.id === updated.id ? updated : n)))
        // the upload returns the whole note; the newest attachment is the last image
        return { ok: true, image: updated.images[updated.images.length - 1] ?? null }
      }
      return { ok: false, status: res.status, code: res.status >= 400 && res.status < 500 ? await errorCode(res) : null }
    } catch {
      return { ok: false, status: null, code: null }
    }
  }

  // Map a single-file upload failure to its inline German message (413 too large, 415
  // unsupported type, else the generic write-error fallback, #146).
  const imageFailureText = (status: number | null, code: string | null): string => {
    if (status === 413) return t('notes.imageTooLarge')
    if (status === 415) return t('notes.imageBadType')
    return errorText(code, t('notes.imageUploadFailed'))
  }

  // Upload one image to a saved note and refresh that note in state. Returns the
  // newly-attached NoteImage on success (so the editor can insert a ref to it at the
  // caret), or null on any failure — in which case the inline imageError is already set.
  // Used by the editor paste/drop flow (one image at a time).
  const uploadImageToNote = async (noteId: string, file: File): Promise<NoteImage | null> => {
    setImageError(null)
    setUploadingImage(true)
    try {
      const result = await uploadOneImage(noteId, file)
      if (result.ok) return result.image
      if (result.status === 401) { onLogout(); return null }
      setImageError(imageFailureText(result.status, result.code))
      return null
    } finally {
      setUploadingImage(false)
    }
  }

  // Gallery upload of one or more selected files (#266): upload sequentially so each lands
  // with the right sort_order, show {done}/{total} progress, and report any failures as a
  // single aggregated count rather than flashing one error per file. A 401 anywhere logs out.
  const handleUploadImages = async (files: File[]) => {
    if (!editNote || files.length === 0) return
    const noteId = editNote.id
    setImageError(null)
    setUploadingImage(true)
    setUploadProgress({ done: 0, total: files.length })
    let failures = 0
    try {
      for (let i = 0; i < files.length; i++) {
        const result = await uploadOneImage(noteId, files[i])
        if (!result.ok && result.status === 401) { onLogout(); return }
        if (!result.ok) failures++
        setUploadProgress({ done: i + 1, total: files.length })
      }
      if (failures === 1) setImageError(t('notes.imageUploadFailed'))
      else if (failures > 1) setImageError(t('notes.imagesSomeFailed', { count: failures }))
    } finally {
      setUploadingImage(false)
      setUploadProgress(null)
    }
  }

  // Paste/drop an image directly into the editor: upload it to the (saved) note, then
  // insert an inline `![name](image:<id>)` ref at the caret — the "GitHub feel" (#146).
  // Guarded by draft.id: a brand-new, unsaved draft has no note to attach to, so we
  // hint to save first rather than dropping the file silently.
  const uploadAndInsert = async (file: File) => {
    if (!draft) return
    if (!draft.id) { setImageError(t('notes.imageSaveFirst')); return }
    // remember which note this upload belongs to; the await below lets the user switch
    // (or close) the editor meanwhile. The image is still saved to the right note
    // server-side — we just must not paste its ref into a now-different note's content.
    const targetNoteId = draft.id
    const img = await uploadImageToNote(targetNoteId, file)
    if (img && draftIdRef.current === targetNoteId) insertAtCaret(img)
  }

  // first image among dropped/pasted items, JPEG/PNG/WebP/GIF only (backend-allowed set)
  const ALLOWED_IMAGE_TYPES = ['image/jpeg', 'image/png', 'image/webp', 'image/gif']
  // Some browsers/paths (certain Safari paste variants, some drag sources) hand us a
  // File with an EMPTY `type`; matching only on MIME would silently drop those (#154).
  // Fall back to the file extension in that case so we still recognise an allowed image —
  // without weakening the MIME allow-list for files that DO report a type (a pasted
  // empty-type .txt stays a non-image).
  const ALLOWED_IMAGE_EXTENSIONS = ['.jpg', '.jpeg', '.png', '.webp', '.gif']
  const isAllowedImage = (f: File): boolean => {
    if (f.type) return ALLOWED_IMAGE_TYPES.includes(f.type)
    const name = f.name.toLowerCase()
    return ALLOWED_IMAGE_EXTENSIONS.some((ext) => name.endsWith(ext))
  }
  const firstImageFile = (files: readonly File[]): File | null =>
    files.find(isAllowedImage) ?? null

  const handleEditorPaste = (e: ReactClipboardEvent<HTMLTextAreaElement>) => {
    // pull image files out of the clipboard items; ignore plain-text pastes entirely
    const files = Array.from(e.clipboardData.items)
      .filter((it) => it.kind === 'file')
      .map((it) => it.getAsFile())
      .filter((f): f is File => f != null)
    const img = firstImageFile(files)
    if (!img) return // let the browser handle non-image pastes (text etc.)
    e.preventDefault()
    void uploadAndInsert(img)
  }

  const handleEditorDrop = (e: ReactDragEvent<HTMLTextAreaElement>) => {
    const img = firstImageFile(Array.from(e.dataTransfer.files))
    if (!img) return // non-image drop → leave default behaviour
    e.preventDefault()
    void uploadAndInsert(img)
  }

  const handleEditorDragOver = (e: ReactDragEvent<HTMLTextAreaElement>) => {
    // only intercept when an actual file is being dragged in, so text drag/drop within
    // the textarea keeps working; signal a copy cursor for the drop.
    if (!Array.from(e.dataTransfer.items).some((it) => it.kind === 'file')) return
    e.preventDefault()
    e.dataTransfer.dropEffect = 'copy'
  }

  const handleDeleteImage = async (imageId: string) => {
    if (!editNote) return
    const noteId = editNote.id
    setImageError(null)
    const result = await safeFetch(token, `${API_BASE}/notes/${noteId}/images/${imageId}`, { method: 'DELETE' })
    // transport reject → no Response; surface the inline image error
    if (!result.ok) {
      setImageError(errorText(null, t('notes.imageDeleteFailed')))
      return
    }
    const { res } = result
    if (res.status === 401) return onLogout()
    if (res.ok) {
      const updated: Note = await res.json()
      setNotes((prev) => prev.map((n) => (n.id === updated.id ? updated : n)))
    } else {
      setImageError(errorText(await errorCode(res), t('notes.imageDeleteFailed')))
    }
  }

  // Download an attachment under its original upload name (the lightbox renders a blob URL, so
  // the browser's "Save image as…" loses the server's filename — see downloadImage in api.ts).
  const handleDownloadImage = async (noteId: string, imageId: string, originalName: string) => {
    const outcome = await downloadImage(token, noteImageUrl(noteId, imageId), originalName)
    if (outcome === 'unauthorized') onLogout()
    else if (outcome === 'error') flashError(errorText(null, t('notes.imageDownloadFailed')))
  }

  // Insert an inline reference to an already-uploaded attachment at the editor caret.
  // The snippet `![name](image:id)` replaces the current selection / lands at the cursor;
  // renderMarkdown resolves it to the authed image on the preview side. Caret is restored
  // after React re-renders the controlled textarea.
  //
  // Both the thumbnail-click and the async paste/drop upload (#146) funnel here. The
  // paste/drop path `await`s the upload first, so the user may have typed (or edited
  // other fields) in the meantime — therefore read the CURRENT content + selection from
  // the live DOM (el.value/selectionStart), not this render's captured `draft`, and merge
  // with a functional setState. Mixing a stale `text` with the live caret index used to
  // drop in-flight edits and misplace the snippet.
  const insertAtCaret = (img: NoteImage) => {
    const el = contentRef.current
    // Sanitize the alt text: `]`, `(`, `)` and newlines would break the inline-image
    // syntax `![alt](image:id)` — a `]` in a common download name like "report].png"
    // closes the alt early and the whole snippet renders as literal text. Replace them
    // with a space so such names still render as an image (not XSS-relevant; the
    // markdown renderer builds React elements, so the alt is always plain text).
    const alt = img.originalName.replace(/[\]()\r\n]/g, ' ').trim()
    const snippet = `![${alt}](image:${img.id})`
    const text = el ? el.value : (draft?.content ?? '')
    // insert at the caret / replace the selection; with no textarea fall back to the end.
    // (Edge: a textarea the user never focused reports caret 0, so a blind insert lands at
    // the start — acceptable; the normal flow is click-in-text-then-insert.)
    const start = el ? el.selectionStart : text.length
    const end = el ? el.selectionEnd : text.length
    const next = text.slice(0, start) + snippet + text.slice(end)
    setDraft((prev) => (prev ? { ...prev, content: next } : prev))
    const caret = start + snippet.length
    requestAnimationFrame(() => {
      const e2 = contentRef.current
      if (e2) {
        e2.focus()
        e2.setSelectionRange(caret, caret)
      }
    })
  }

  // The grouped, clickable note list — rendered in the left column AND inside the mobile
  // switcher Sheet. `onPick` lets the Sheet close itself after a jump.
  const renderNoteGroups = (onPick?: () => void) => (
    <div className="hb-notes-groups">
      {groups.map((g) => {
        const collapsed = collapsedFolders.has(g.folder)
        return (
          // the no-folder bucket has folder==='' → prefix keys so a real folder can't collide
          <div key={g.folder ? `f:${g.folder}` : 'nofolder'} className="hb-notes-group">
            {/* the header toggles its section; chevron + aria-expanded convey the state */}
            <button
              type="button"
              className="hb-notes-group__head"
              aria-expanded={!collapsed}
              onClick={() => toggleFolder(g.folder)}
            >
              <Icon name={collapsed ? 'chevronRight' : 'chevronDown'} size={14} stroke={2.4} className="hb-notes-group__chevron" />
              <Icon name={g.folder ? 'folder' : 'inbox'} size={14} stroke={2} />
              <span className="hb-notes-group__name">{g.folder || t('notes.noFolder')}</span>
              <span className="hb-notes-group__count">{g.notes.length}</span>
            </button>
            {!collapsed && (
              <div className="hb-notes-items">
                {g.notes.map((n) => (
                  <button
                    key={n.id}
                    className={`hb-noteitem${selectedId === n.id ? ' is-active' : ''}`}
                    onClick={() => { openEditor(n); onPick?.() }}
                  >
                    <div className="hb-noteitem__top">
                      <Icon name={n.visibility === 'PRIVATE' ? 'lock' : 'users'} size={14} stroke={2} style={{ color: 'var(--ink-3)' }} />
                      <span className="hb-noteitem__title">{n.title}</span>
                    </div>
                    {n.content && <div className="hb-noteitem__preview">{n.content.replace(/[#*`>_-]/g, '').trim()}</div>}
                    <div className="hb-noteitem__meta">
                      {relTime(n.updatedAt)}
                      {n.images.length > 0 && (
                        <span className="hb-noteitem__imgcount">
                          <Icon name="image" size={13} stroke={2} /> {n.images.length}
                        </span>
                      )}
                    </div>
                  </button>
                ))}
              </div>
            )}
          </div>
        )
      })}
    </div>
  )

  return (
    <div className="hb-page">
      <PageHead
        eyebrow={`${notes.length} ${t('notes.count')}`}
        title={t('notes.title')}
        actions={<Button icon="plus" onClick={() => openEditor(null)}>{t('notes.newNote')}</Button>}
      />

      <div className={`hb-notes-layout${draft ? ' is-editing' : ''}`}>
        <div className="hb-notes-list">
          <div className="hb-quickadd hb-search" style={{ marginBottom: 14 }}>
            <Icon name="search" size={18} stroke={2} style={{ color: 'var(--ink-3)' }} />
            <input value={query} placeholder={t('notes.searchPlaceholder')} onChange={(e) => setQuery(e.target.value)} />
          </div>

          {allFolders.length > 0 && (
            <div className="hb-tagrow">
              <button className={`hb-tagchip${folderFilter === null ? ' is-active' : ''}`} onClick={() => setFolderFilter(null)}>
                {t('notes.allFolders')}
              </button>
              {allFolders.map((folder) => (
                <button
                  key={folder}
                  className={`hb-tagchip${folderFilter === folder ? ' is-active' : ''}`}
                  onClick={() => setFolderFilter(folder)}
                >
                  <Icon name="folder" size={13} stroke={2} /> {folder}
                </button>
              ))}
              <button className={`hb-tagchip${folderFilter === '' ? ' is-active' : ''}`} onClick={() => setFolderFilter('')}>
                {t('notes.noFolder')}
              </button>
            </div>
          )}

          {allTags.length > 0 && (
            <div className="hb-tagrow">
              <button className={`hb-tagchip${tagFilter === null ? ' is-active' : ''}`} onClick={() => setTagFilter(null)}>
                {t('notes.allTags')}
              </button>
              {allTags.map((tag) => (
                <button key={tag} className={`hb-tagchip${tagFilter === tag ? ' is-active' : ''}`} onClick={() => setTagFilter(tag)}>
                  #{tag}
                </button>
              ))}
            </div>
          )}

          {loading ? (
            <p className="hb-muted" style={{ padding: 8 }}>{t('common.loading')}</p>
          ) : listed.length === 0 ? (
            <Card className="hb-card--pad">
              <EmptyState
                icon="note"
                title={query.trim() ? t('notes.noResults') : t('notes.empty')}
                hint={query.trim() ? undefined : t('notes.emptyHint')}
                action={query.trim() ? undefined : <Button size="sm" icon="plus" onClick={() => openEditor(null)}>{t('notes.newNote')}</Button>}
              />
            </Card>
          ) : (
            renderNoteGroups()
          )}
        </div>

        <div className="hb-notes-detail" onBlur={handleEditorBlur}>
          {draft ? (
            <Card className="hb-card--pad">
              {/* Mobile-only bar: back to the list + open the note switcher (#313) */}
              <div className="hb-note-editor__mobilebar">
                <button type="button" className="hb-note-back" onClick={closeEditor}>
                  <Icon name="chevronLeft" size={18} stroke={2.2} /> {t('notes.backToList')}
                </button>
                <IconButton icon="more" label={t('notes.switchNote')} onClick={() => setSwitcherOpen(true)} />
              </div>

              {/* Header: Edit/Preview toggle on the left, auto-save status + delete on the right */}
              <div className="hb-note-editor__bar">
                <div className="hb-seg" role="tablist">
                  <button
                    role="tab"
                    aria-selected={!previewMode}
                    className={`hb-seg__item${!previewMode ? ' is-active' : ''}`}
                    onClick={() => setPreviewMode(false)}
                  >
                    {t('notes.editSource')}
                  </button>
                  <button
                    role="tab"
                    aria-selected={previewMode}
                    className={`hb-seg__item${previewMode ? ' is-active' : ''}`}
                    onClick={() => setPreviewMode(true)}
                  >
                    {t('notes.preview')}
                  </button>
                </div>
                <div className="hb-note-editor__baractions">
                  <SaveStatus
                    state={saveState}
                    savingLabel={t('notes.saving')}
                    savedLabel={t('notes.saved')}
                    errorLabel={t('notes.saveFailed')}
                  />
                  {draft.id && (
                    <IconButton icon="trash" label={t('common.delete')} danger onClick={() => handleDelete(draft.id!)} />
                  )}
                </div>
              </div>

              <Field label={t('common.titlePlaceholder')}>
                <TextInput autoFocus value={draft.title} onChange={(v) => setDraft({ ...draft, title: v })} placeholder={t('common.titlePlaceholder')} />
              </Field>

              {previewMode ? (
                <div className="hb-md hb-note-preview">
                  {draft.content.trim()
                    ? renderMarkdown(draft.content, {
                        // inline `![](image:<id>)` refs resolve to the same authed loader as the gallery
                        resolveImage: (imageId, alt) =>
                          draft.id ? (
                            <AuthedImage url={noteImageUrl(draft.id, imageId)} token={token} alt={alt} className="hb-md-img" />
                          ) : (
                            alt || null
                          ),
                      })
                    : <p className="hb-muted" style={{ margin: 0 }}>{t('notes.contentPlaceholder')}</p>}
                </div>
              ) : (
                <Field label={t('notes.contentPlaceholder')}>
                  <textarea
                    ref={contentRef}
                    className="hb-input hb-mono-area"
                    rows={12}
                    value={draft.content}
                    placeholder={t('notes.contentPlaceholder')}
                    onChange={(e) => setDraft({ ...draft, content: e.target.value })}
                    // paste/drag an image straight into the editor → upload + inline ref (#146)
                    onPaste={handleEditorPaste}
                    onDrop={handleEditorDrop}
                    onDragOver={handleEditorDragOver}
                  />
                  {/* subtle inline feedback for the paste/drop upload flow */}
                  {uploadingImage && (
                    <p className="hb-note-editor__uploading">
                      <Icon name="image" size={14} stroke={2} /> {t('notes.imageUploadingInline')}
                    </p>
                  )}
                  {imageError && <p className="hb-note-images__error">{imageError}</p>}
                </Field>
              )}

              {!previewMode && editImages.length > 0 && (
                <Field label={t('notes.insertImageLabel')}>
                  <div className="hb-note-insert-strip">
                    {editImages.map((img) => (
                      <button
                        key={img.id}
                        type="button"
                        className="hb-note-insert-thumb"
                        title={`${t('notes.insertImage')}: ${img.originalName}`}
                        aria-label={`${t('notes.insertImage')}: ${img.originalName}`}
                        onClick={() => insertAtCaret(img)}
                      >
                        <AuthedImage url={noteImageUrl(draft.id!, img.id)} token={token} alt={img.originalName} />
                      </button>
                    ))}
                  </div>
                </Field>
              )}

              <Field label={t('notes.tagsPlaceholder')}>
                <TextInput value={draft.tags} onChange={(v) => setDraft({ ...draft, tags: v })} placeholder={t('notes.tagsPlaceholder')} />
              </Field>
              <Field label={t('notes.folderLabel')}>
                <input
                  className="hb-input"
                  list="hb-note-folders"
                  value={draft.folder}
                  placeholder={t('notes.folderPlaceholder')}
                  onChange={(e) => setDraft({ ...draft, folder: e.target.value })}
                />
                {/* autocomplete from folders already in use, derived like tags */}
                <datalist id="hb-note-folders">
                  {allFolders.map((folder) => <option key={folder} value={folder} />)}
                </datalist>
              </Field>
              <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                <span className="hb-field__label">{t('notes.visibility')}</span>
                <Button
                  variant="secondary"
                  size="sm"
                  icon={draft.visibility === 'PRIVATE' ? 'lock' : 'users'}
                  onClick={() => setDraft({ ...draft, visibility: draft.visibility === 'SHARED' ? 'PRIVATE' : 'SHARED' })}
                >
                  {draft.visibility === 'PRIVATE' ? t('notes.private') : t('notes.shared')}
                </Button>
              </div>

              {saveError && <p className="hb-modal-error" style={{ marginTop: 8 }}>{saveError}</p>}

              {/* Image gallery (upload / manage) — available while editing a SAVED note (#310). */}
              {editNote && (
                <div className="hb-note-images">
                  <div className="hb-note-images__head">
                    <span className="hb-field__label">
                      {t('notes.images')}{editNote.images.length > 0 ? ` (${editNote.images.length})` : ''}
                    </span>
                    <Button
                      variant="secondary"
                      size="sm"
                      icon="plus"
                      onClick={() => fileInputRef.current?.click()}
                      disabled={uploadingImage}
                    >
                      {uploadProgress
                        ? t('notes.uploadingMany', { done: uploadProgress.done, total: uploadProgress.total })
                        : uploadingImage
                          ? t('notes.uploading')
                          : t('notes.addImage')}
                    </Button>
                  </div>
                  {/* show the inline image error here too when in preview mode (the editor field is hidden) */}
                  {previewMode && imageError && <p className="hb-note-images__error">{imageError}</p>}
                  {editNote.images.length > 0 && (
                    <div className="hb-note-images__grid">
                      {editNote.images.map((img) => (
                        <div key={img.id} className="hb-note-thumb">
                          <AuthedImage
                            url={noteImageUrl(editNote.id, img.id)}
                            token={token}
                            alt={img.originalName}
                            onClick={() => setLightbox({ noteId: editNote.id, imageId: img.id, originalName: img.originalName })}
                          />
                          <button
                            type="button"
                            className="hb-note-thumb__del"
                            title={t('notes.removeImage')}
                            aria-label={t('notes.removeImage')}
                            onClick={() => handleDeleteImage(img.id)}
                          >
                            <Icon name="x" size={14} stroke={2.4} />
                          </button>
                        </div>
                      ))}
                    </div>
                  )}
                  <input
                    ref={fileInputRef}
                    type="file"
                    multiple
                    accept="image/jpeg,image/png,image/webp,image/gif"
                    style={{ display: 'none' }}
                    onChange={(e) => {
                      const files = Array.from(e.target.files ?? [])
                      if (files.length > 0) handleUploadImages(files)
                      e.target.value = '' // allow re-selecting the same file(s)
                    }}
                  />
                </div>
              )}

              {/* Close affordance: edits are already auto-saved, so this just deselects. */}
              <div style={{ display: 'flex', justifyContent: 'flex-end', marginTop: 14 }}>
                <Button variant="ghost" onClick={closeEditor}>{t('common.close')}</Button>
              </div>
            </Card>
          ) : (
            <Card className="hb-card--pad"><EmptyState icon="note" title={t('notes.title')} hint={t('notes.selectHint')} /></Card>
          )}
        </div>
      </div>

      {/* Mobile note switcher (#313): jump to another note without going back first. */}
      <Sheet open={switcherOpen} onClose={() => setSwitcherOpen(false)} title={t('notes.switchNote')}>
        {listed.length === 0
          ? <EmptyState icon="note" title={t('notes.empty')} />
          : renderNoteGroups(() => setSwitcherOpen(false))}
      </Sheet>

      {lightbox && (
        <div className="hb-lightbox" onClick={() => setLightbox(null)}>
          <button
            type="button"
            className="hb-lightbox__download"
            title={t('notes.downloadImage')}
            aria-label={t('notes.downloadImage')}
            onClick={(e) => {
              e.stopPropagation()
              handleDownloadImage(lightbox.noteId, lightbox.imageId, lightbox.originalName)
            }}
          >
            <Icon name="download" size={18} stroke={2.2} />
          </button>
          <AuthedImage url={noteImageUrl(lightbox.noteId, lightbox.imageId)} token={token} alt="" onClick={(e) => e.stopPropagation()} />
        </div>
      )}

      {errorToast}
    </div>
  )
}

// Auto-save status pill (#309): idle shows nothing; "Speichert…" pulses, "Gespeichert" gets a
// check, the error state is a red tag reusing notes.saveFailed. Replaces the manual Save button.
// Labels are passed pre-resolved so this stays a dumb presentational component.
function SaveStatus({ state, savingLabel, savedLabel, errorLabel }: {
  state: SaveState
  savingLabel: string
  savedLabel: string
  errorLabel: string
}) {
  if (state === 'idle') return null
  if (state === 'saving') {
    return <span className="hb-savestatus is-saving">{savingLabel}</span>
  }
  if (state === 'saved') {
    return (
      <span className="hb-savestatus is-saved">
        <Icon name="check" size={14} stroke={2.4} /> {savedLabel}
      </span>
    )
  }
  return <span className="hb-savestatus is-error">{errorLabel}</span>
}
