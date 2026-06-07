import { useState, useEffect, useCallback, useRef, useMemo } from 'react'
import { API_BASE, authFetch, errorCode, noteImageUrl, safeFetch, withWsToken } from '../api'
import { t, errorText } from '../i18n'
import { Note, NoteVisibility } from '../types'
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
  visibility: NoteVisibility
}

const emptyDraft = (): Draft => ({ title: '', content: '', tags: '', visibility: 'SHARED' })
const draftFromNote = (n: Note): Draft => ({
  id: n.id,
  title: n.title,
  content: n.content,
  tags: n.tags.join(', '),
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
  const [uploadingImage, setUploadingImage] = useState(false)
  const [imageError, setImageError] = useState<string | null>(null)
  const [lightbox, setLightbox] = useState<string | null>(null)
  const fileInputRef = useRef<HTMLInputElement>(null)
  const { flashError, errorToast } = useErrorToast()

  const fetchNotes = useCallback(async (q: string) => {
    try {
      const url = q.trim() ? `${API_BASE}/notes?q=${encodeURIComponent(q.trim())}` : `${API_BASE}/notes`
      const res = await authFetch(token, url)
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

  useWebSocket(withWsToken(WS_URL, token), (raw) => {
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
        visibility: draft.visibility,
      })
      const res = draft.id
        ? await authFetch(token, `${API_BASE}/notes/${draft.id}`, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body })
        : await authFetch(token, `${API_BASE}/notes`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body })
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

  const listed = tagFilter ? notes.filter((n) => n.tags.includes(tagFilter)) : notes
  const selected = notes.find((n) => n.id === selectedId) ?? null

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
    const res = await authFetch(token, `${API_BASE}/notes/${selected.id}/images/${imageId}`, { method: 'DELETE' })
    if (res.status === 401) return onLogout()
    if (res.ok) {
      const updated: Note = await res.json()
      setNotes((prev) => prev.map((n) => (n.id === updated.id ? updated : n)))
    } else {
      setImageError(errorText(await errorCode(res), t.notes.imageDeleteFailed))
    }
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
                  className="hb-input hb-mono-area"
                  rows={12}
                  value={draft.content}
                  placeholder={t.notes.contentPlaceholder}
                  onChange={(e) => setDraft({ ...draft, content: e.target.value })}
                />
              </Field>
              <Field label={t.notes.tagsPlaceholder}>
                <TextInput value={draft.tags} onChange={(v) => setDraft({ ...draft, tags: v })} placeholder={t.notes.tagsPlaceholder} />
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
                    {selected.tags.map((tag) => <span key={tag} className="hb-tagchip is-static">#{tag}</span>)}
                  </div>
                </div>
                <div style={{ display: 'flex', gap: 2 }}>
                  <IconButton icon="edit" label={t.common.edit} onClick={() => setDraft(draftFromNote(selected))} />
                  <IconButton icon="trash" label={t.common.delete} danger onClick={() => handleDelete(selected.id)} />
                </div>
              </div>
              <div className="hb-md">{renderMarkdown(selected.content)}</div>

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
                        <img
                          src={noteImageUrl(selected.id, img.id, token)}
                          alt={img.originalName}
                          loading="lazy"
                          onClick={() => setLightbox(noteImageUrl(selected.id, img.id, token))}
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
          <img src={lightbox} alt="" onClick={(e) => e.stopPropagation()} />
        </div>
      )}

      {errorToast}
    </div>
  )
}
