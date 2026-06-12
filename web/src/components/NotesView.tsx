import { useState, useEffect, useCallback, useRef, useMemo, type ImgHTMLAttributes } from 'react'
import { API_BASE, authFetch, errorCode, noteImageUrl, notifyTransportError, safeFetch } from '../api'
import { t, errorText } from '../i18n'
import { Note, NoteImage, NoteVisibility } from '../types'
import { useWebSocket } from '../hooks/useWebSocket'
import { Icon } from '../ui/Icon'
import { useErrorToast } from '../ui/ErrorToast'
import {
  Avatar,
  Badge,
  Button,
  Card,
  EmptyState,
  Field,
  IconButton,
  PageHead,
  TextInput,
  renderMarkdown,
} from '../ui/primitives'
import { relTime } from '../ui/format'

const WS_SCHEME = window.location.protocol === 'https:' ? 'wss' : 'ws'
const WS_URL = import.meta.env.VITE_WS_URL_NOTES ?? `${WS_SCHEME}://${window.location.host}/api/v1/ws/notes`

interface Draft {
  id?: string
  title: string
  content: string
  tags: string
  folder: string
  visibility: NoteVisibility
}

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

interface NotesViewProps {
  token: string
  onLogout: () => void
}

export function NotesView({ token, onLogout }: NotesViewProps) {
  const [notes, setNotes] = useState<Note[]>([])
  const [loading, setLoading] = useState(true)
  const [query, setQuery] = useState('')
  const [draft, setDraft] = useState<Draft | null>(null)
  const [saving, setSaving] = useState(false)
  const [saveError, setSaveError] = useState<string | null>(null)
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [tagFilter, setTagFilter] = useState<string | null>(null)
  // null = all folders; '' = the "no folder" bucket; otherwise a specific folder name
  const [folderFilter, setFolderFilter] = useState<string | null>(null)
  const [uploadingImage, setUploadingImage] = useState(false)
  const [imageError, setImageError] = useState<string | null>(null)
  const [lightbox, setLightbox] = useState<{ noteId: string; imageId: string } | null>(null)
  const fileInputRef = useRef<HTMLInputElement>(null)
  const contentRef = useRef<HTMLTextAreaElement>(null)
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

  const handleSave = async () => {
    if (!draft || !draft.title.trim()) return
    setSaving(true)
    setSaveError(null)
    try {
      const body = JSON.stringify({
        title: draft.title.trim(),
        content: draft.content,
        tags: parseTags(draft.tags),
        // always send a (possibly empty) string: the backend trims it and maps blank ⇒
        // null, so this both sets a folder and clears one when the field is emptied.
        folder: draft.folder.trim(),
        visibility: draft.visibility,
      })
      const result = draft.id
        ? await safeFetch(token, `${API_BASE}/notes/${draft.id}`, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body })
        : await safeFetch(token, `${API_BASE}/notes`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body })
      // transport reject → keep the editor open and show the inline error so the user can retry
      if (!result.ok) {
        setSaveError(errorText(null, t.notes.saveFailed))
        return
      }
      const { res } = result
      if (res.status === 401) return onLogout()
      if (res.ok) {
        const saved: Note = await res.json()
        setNotes((prev) => (prev.some((n) => n.id === saved.id) ? prev.map((n) => (n.id === saved.id ? saved : n)) : [saved, ...prev]))
        setSelectedId(saved.id)
        setDraft(null)
      } else {
        // keep the editor open and show the reason inline so the user can retry
        setSaveError(errorText(await errorCode(res), t.notes.saveFailed))
      }
    } finally {
      setSaving(false)
    }
  }

  const handleDelete = async (id: string) => {
    setNotes((prev) => prev.filter((n) => n.id !== id))
    setDraft(null)
    setSelectedId(null)
    const result = await safeFetch(token, `${API_BASE}/notes/${id}`, { method: 'DELETE' })
    // On failure refetch to resync rather than restoring a captured snapshot,
    // which could clobber a concurrent WS update.
    if (!result.ok) {
      await fetchNotes(query)
      return flashError(errorText(null, t.notes.deleteFailed))
    }
    const { res } = result
    if (res.status === 401) return onLogout()
    if (!res.ok) {
      await fetchNotes(query)
      flashError(errorText(await errorCode(res), t.notes.deleteFailed))
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

  const listed = notes.filter((n) => {
    if (tagFilter && !n.tags.includes(tagFilter)) return false
    if (folderFilter !== null) {
      // '' selects notes without a folder; otherwise an exact folder match
      if (folderFilter === '') { if (n.folder) return false }
      else if (n.folder !== folderFilter) return false
    }
    return true
  })
  const selected = notes.find((n) => n.id === selectedId) ?? null
  // attachments of the note currently being edited — the source for caret-insertion.
  // Only existing (saved) notes have images; a brand-new draft has none yet.
  const editImages = draft?.id ? (notes.find((n) => n.id === draft.id)?.images ?? []) : []

  // images are managed from the read view; clear any stale upload error on selection change
  useEffect(() => { setImageError(null) }, [selectedId])

  // clear a stale save error whenever the editor opens on a different note (or closes)
  useEffect(() => { setSaveError(null) }, [draft?.id, draft === null])

  const handleUploadImage = async (file: File) => {
    if (!selected) return
    setImageError(null)
    setUploadingImage(true)
    try {
      const fd = new FormData()
      fd.append('file', file)
      const res = await authFetch(token, `${API_BASE}/notes/${selected.id}/images`, { method: 'POST', body: fd })
      if (res.status === 401) return onLogout()
      if (res.ok) {
        const updated: Note = await res.json()
        setNotes((prev) => prev.map((n) => (n.id === updated.id ? updated : n)))
      } else if (res.status === 413) {
        setImageError(t.notes.imageTooLarge)
      } else if (res.status === 415) {
        setImageError(t.notes.imageBadType)
      } else {
        setImageError(errorText(await errorCode(res), t.notes.imageUploadFailed))
      }
    } catch {
      setImageError(t.notes.imageUploadFailed)
    } finally {
      setUploadingImage(false)
    }
  }

  const handleDeleteImage = async (imageId: string) => {
    if (!selected) return
    setImageError(null)
    const result = await safeFetch(token, `${API_BASE}/notes/${selected.id}/images/${imageId}`, { method: 'DELETE' })
    // transport reject → no Response; surface the inline image error
    if (!result.ok) {
      setImageError(errorText(null, t.notes.imageDeleteFailed))
      return
    }
    const { res } = result
    if (res.status === 401) return onLogout()
    if (res.ok) {
      const updated: Note = await res.json()
      setNotes((prev) => prev.map((n) => (n.id === updated.id ? updated : n)))
    } else {
      setImageError(errorText(await errorCode(res), t.notes.imageDeleteFailed))
    }
  }

  // Insert an inline reference to an already-uploaded attachment at the editor caret
  // (issue follow-up tracks paste/drag-to-upload). The snippet `![name](image:id)`
  // replaces the current selection / lands at the cursor; renderMarkdown resolves it
  // to the authed image on the read side. Caret is restored after React re-renders the
  // controlled textarea. Only offered while editing an existing note (images need an id).
  const insertAtCaret = (img: NoteImage) => {
    if (!draft) return
    const snippet = `![${img.originalName}](image:${img.id})`
    const el = contentRef.current
    const text = draft.content
    // insert at the caret / replace the selection; with no textarea fall back to the end.
    // (Edge: a textarea the user never focused reports caret 0, so a blind insert lands at
    // the start — acceptable; the normal flow is click-in-text-then-insert.)
    const start = el ? el.selectionStart : text.length
    const end = el ? el.selectionEnd : text.length
    const next = text.slice(0, start) + snippet + text.slice(end)
    setDraft({ ...draft, content: next })
    const caret = start + snippet.length
    requestAnimationFrame(() => {
      const e2 = contentRef.current
      if (e2) {
        e2.focus()
        e2.setSelectionRange(caret, caret)
      }
    })
  }

  return (
    <div className="hb-page">
      <PageHead
        eyebrow={`${notes.length} ${t.notes.count}`}
        title={t.notes.title}
        actions={<Button icon="plus" onClick={() => { setDraft(emptyDraft()); setSelectedId(null) }}>{t.notes.newNote}</Button>}
      />

      <div className="hb-notes-layout">
        <div>
          <div className="hb-quickadd hb-search" style={{ marginBottom: 14 }}>
            <Icon name="search" size={18} stroke={2} style={{ color: 'var(--ink-3)' }} />
            <input value={query} placeholder={t.notes.searchPlaceholder} onChange={(e) => setQuery(e.target.value)} />
          </div>

          {allFolders.length > 0 && (
            <div className="hb-tagrow">
              <button className={`hb-tagchip${folderFilter === null ? ' is-active' : ''}`} onClick={() => setFolderFilter(null)}>
                {t.notes.allFolders}
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
                {t.notes.noFolder}
              </button>
            </div>
          )}

          {allTags.length > 0 && (
            <div className="hb-tagrow">
              <button className={`hb-tagchip${tagFilter === null ? ' is-active' : ''}`} onClick={() => setTagFilter(null)}>
                {t.notes.allTags}
              </button>
              {allTags.map((tag) => (
                <button key={tag} className={`hb-tagchip${tagFilter === tag ? ' is-active' : ''}`} onClick={() => setTagFilter(tag)}>
                  #{tag}
                </button>
              ))}
            </div>
          )}

          {loading ? (
            <p className="hb-muted" style={{ padding: 8 }}>{t.common.loading}</p>
          ) : listed.length === 0 ? (
            <Card className="hb-card--pad">
              <EmptyState icon="note" title={query.trim() ? t.notes.noResults : t.notes.empty} hint={query.trim() ? undefined : t.notes.emptyHint} />
            </Card>
          ) : (
            <div className="hb-notes-items">
              {listed.map((note) => (
                <button
                  key={note.id}
                  className={`hb-noteitem${selectedId === note.id ? ' is-active' : ''}`}
                  onClick={() => { setSelectedId(note.id); setDraft(null) }}
                >
                  <div className="hb-noteitem__top">
                    <Icon name={note.visibility === 'PRIVATE' ? 'lock' : 'users'} size={14} stroke={2} style={{ color: 'var(--ink-3)' }} />
                    <span className="hb-noteitem__title">{note.title}</span>
                  </div>
                  {note.content && <div className="hb-noteitem__preview">{note.content.replace(/[#*`>_-]/g, '').trim()}</div>}
                  <div className="hb-noteitem__meta">
                    {relTime(note.updatedAt)}
                    {note.folder && (
                      <span className="hb-noteitem__imgcount">
                        <Icon name="folder" size={13} stroke={2} /> {note.folder}
                      </span>
                    )}
                    {note.images.length > 0 && (
                      <span className="hb-noteitem__imgcount">
                        <Icon name="image" size={13} stroke={2} /> {note.images.length}
                      </span>
                    )}
                  </div>
                </button>
              ))}
            </div>
          )}
        </div>

        <div>
          {draft ? (
            <Card className="hb-card--pad">
              <Field label={t.common.titlePlaceholder}>
                <TextInput autoFocus value={draft.title} onChange={(v) => setDraft({ ...draft, title: v })} placeholder={t.common.titlePlaceholder} />
              </Field>
              <Field label={t.notes.contentPlaceholder}>
                <textarea
                  ref={contentRef}
                  className="hb-input hb-mono-area"
                  rows={12}
                  value={draft.content}
                  placeholder={t.notes.contentPlaceholder}
                  onChange={(e) => setDraft({ ...draft, content: e.target.value })}
                />
              </Field>
              {editImages.length > 0 && (
                <Field label={t.notes.insertImageLabel}>
                  <div className="hb-note-insert-strip">
                    {editImages.map((img) => (
                      <button
                        key={img.id}
                        type="button"
                        className="hb-note-insert-thumb"
                        title={`${t.notes.insertImage}: ${img.originalName}`}
                        aria-label={`${t.notes.insertImage}: ${img.originalName}`}
                        onClick={() => insertAtCaret(img)}
                      >
                        <AuthedImage noteId={draft.id!} imageId={img.id} token={token} alt={img.originalName} />
                      </button>
                    ))}
                  </div>
                </Field>
              )}
              <Field label={t.notes.tagsPlaceholder}>
                <TextInput value={draft.tags} onChange={(v) => setDraft({ ...draft, tags: v })} placeholder={t.notes.tagsPlaceholder} />
              </Field>
              <Field label={t.notes.folderLabel}>
                <input
                  className="hb-input"
                  list="hb-note-folders"
                  value={draft.folder}
                  placeholder={t.notes.folderPlaceholder}
                  onChange={(e) => setDraft({ ...draft, folder: e.target.value })}
                />
                {/* autocomplete from folders already in use, derived like tags */}
                <datalist id="hb-note-folders">
                  {allFolders.map((folder) => <option key={folder} value={folder} />)}
                </datalist>
              </Field>
              <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                <span className="hb-field__label">{t.notes.visibility}</span>
                <Button
                  variant="secondary"
                  size="sm"
                  icon={draft.visibility === 'PRIVATE' ? 'lock' : 'users'}
                  onClick={() => setDraft({ ...draft, visibility: draft.visibility === 'SHARED' ? 'PRIVATE' : 'SHARED' })}
                >
                  {draft.visibility === 'PRIVATE' ? t.notes.private : t.notes.shared}
                </Button>
              </div>
              {saveError && <p className="hb-modal-error" style={{ marginTop: 8 }}>{saveError}</p>}
              <div style={{ display: 'flex', justifyContent: 'space-between', marginTop: 6 }}>
                {draft.id ? (
                  <Button variant="danger" icon="trash" onClick={() => handleDelete(draft.id!)}>{t.common.delete}</Button>
                ) : <span />}
                <div style={{ display: 'flex', gap: 10 }}>
                  <Button variant="ghost" onClick={() => setDraft(null)}>{t.common.cancel}</Button>
                  <Button onClick={handleSave} disabled={saving || !draft.title.trim()}>{t.common.save}</Button>
                </div>
              </div>
            </Card>
          ) : selected ? (
            <Card className="hb-card--pad">
              <div className="hb-note-doc__head">
                <div style={{ minWidth: 0 }}>
                  <div className="hb-note-doc__title">{selected.title}</div>
                  <div className="hb-note-doc__meta">
                    <Avatar user={selected.createdBy} size={22} />
                    <Badge tone={selected.visibility === 'PRIVATE' ? 'neutral' : 'accent'}>
                      {selected.visibility === 'PRIVATE' ? t.notes.private : t.notes.shared}
                    </Badge>
                    <span className="hb-muted" style={{ fontSize: 13 }}>{relTime(selected.updatedAt)}</span>
                    {selected.folder && (
                      <span className="hb-tagchip is-static">
                        <Icon name="folder" size={12} stroke={2} /> {selected.folder}
                      </span>
                    )}
                    {selected.tags.map((tag) => <span key={tag} className="hb-tagchip is-static">#{tag}</span>)}
                  </div>
                </div>
                <div style={{ display: 'flex', gap: 2 }}>
                  <IconButton icon="edit" label={t.common.edit} onClick={() => setDraft(draftFromNote(selected))} />
                  <IconButton icon="trash" label={t.common.delete} danger onClick={() => handleDelete(selected.id)} />
                </div>
              </div>
              <div className="hb-md">
                {renderMarkdown(selected.content, {
                  // inline `![](image:<id>)` refs resolve to the same authed loader as the gallery
                  resolveImage: (imageId, alt) => (
                    <AuthedImage noteId={selected.id} imageId={imageId} token={token} alt={alt} className="hb-md-img" />
                  ),
                })}
              </div>

              <div className="hb-note-images">
                <div className="hb-note-images__head">
                  <span className="hb-field__label">
                    {t.notes.images}{selected.images.length > 0 ? ` (${selected.images.length})` : ''}
                  </span>
                  <Button
                    variant="secondary"
                    size="sm"
                    icon="plus"
                    onClick={() => fileInputRef.current?.click()}
                    disabled={uploadingImage}
                  >
                    {uploadingImage ? t.notes.uploading : t.notes.addImage}
                  </Button>
                </div>
                {imageError && <p className="hb-note-images__error">{imageError}</p>}
                {selected.images.length > 0 && (
                  <div className="hb-note-images__grid">
                    {selected.images.map((img) => (
                      <div key={img.id} className="hb-note-thumb">
                        <AuthedImage
                          noteId={selected.id}
                          imageId={img.id}
                          token={token}
                          alt={img.originalName}
                          onClick={() => setLightbox({ noteId: selected.id, imageId: img.id })}
                        />
                        <button
                          type="button"
                          className="hb-note-thumb__del"
                          title={t.notes.removeImage}
                          aria-label={t.notes.removeImage}
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
                  accept="image/jpeg,image/png,image/webp,image/gif"
                  style={{ display: 'none' }}
                  onChange={(e) => {
                    const f = e.target.files?.[0]
                    if (f) handleUploadImage(f)
                    e.target.value = '' // allow re-selecting the same file
                  }}
                />
              </div>
            </Card>
          ) : (
            <Card className="hb-card--pad"><EmptyState icon="note" title={t.notes.title} hint={t.notes.selectHint} /></Card>
          )}
        </div>
      </div>

      {lightbox && (
        <div className="hb-lightbox" onClick={() => setLightbox(null)}>
          <AuthedImage noteId={lightbox.noteId} imageId={lightbox.imageId} token={token} alt="" onClick={(e) => e.stopPropagation()} />
        </div>
      )}

      {errorToast}
    </div>
  )
}

// Loads a note image through authFetch (Authorization header) into a blob URL, so the JWT never
// rides in the image URL. The object URL is revoked on unmount / when the target changes.
function AuthedImage({ noteId, imageId, token, ...imgProps }: {
  noteId: string
  imageId: string
  token: string
} & ImgHTMLAttributes<HTMLImageElement>) {
  const [src, setSrc] = useState<string | null>(null)
  useEffect(() => {
    let active = true
    let objectUrl: string | null = null
    // Clear any previous blob before loading a new target, so a prop change in place
    // never renders the just-revoked object URL for a frame.
    setSrc(null)
    authFetch(token, noteImageUrl(noteId, imageId))
      .then((res) => (res.ok ? res.blob() : Promise.reject(new Error(String(res.status)))))
      .then((blob) => {
        if (!active) return
        objectUrl = URL.createObjectURL(blob)
        setSrc(objectUrl)
      })
      .catch(() => { /* broken/forbidden image → render nothing */ })
    return () => {
      active = false
      if (objectUrl) URL.revokeObjectURL(objectUrl)
    }
  }, [noteId, imageId, token])
  return src ? <img src={src} {...imgProps} /> : null
}
