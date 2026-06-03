import { useState, useEffect, useCallback, useRef } from 'react'
import { API_BASE, authFetch, withWsToken } from '../api'
import { t } from '../i18n'
import { Note, NoteVisibility } from '../types'
import { useWebSocket } from '../hooks/useWebSocket'

const WS_URL = import.meta.env.VITE_WS_URL_NOTES ?? `ws://${window.location.host}/api/v1/ws/notes`

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

const parseTags = (raw: string): string[] =>
  raw.split(',').map((t) => t.trim()).filter((t) => t.length > 0)

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

  // debounce search input
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
        // upsert: a note flipped private→shared arrives as an update the client hasn't seen yet
        setNotes((prev) =>
          prev.some((n) => n.id === msg.payload.id)
            ? prev.map((n) => (n.id === msg.payload.id ? msg.payload : n))
            : [msg.payload, ...prev],
        )
      } else if (msg.type === 'NOTE_DELETED') {
        setNotes((prev) => prev.filter((n) => n.id !== msg.payload.id))
      }
    } catch {
      // ignore malformed frames
    }
  })

  const handleSave = async () => {
    if (!draft || !draft.title.trim()) return
    setSaving(true)
    try {
      const body = JSON.stringify({
        title: draft.title.trim(),
        content: draft.content,
        tags: parseTags(draft.tags),
        visibility: draft.visibility,
      })
      if (draft.id) {
        const res = await authFetch(token, `${API_BASE}/notes/${draft.id}`, {
          method: 'PUT',
          headers: { 'Content-Type': 'application/json' },
          body,
        })
        if (res.ok) {
          const updated: Note = await res.json()
          setNotes((prev) => prev.map((n) => (n.id === updated.id ? updated : n)))
        }
      } else {
        const res = await authFetch(token, `${API_BASE}/notes`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body,
        })
        if (res.ok) {
          const created: Note = await res.json()
          setNotes((prev) => [created, ...prev])
        }
      }
      setDraft(null)
    } finally {
      setSaving(false)
    }
  }

  const handleDelete = async (id: string) => {
    setNotes((prev) => prev.filter((n) => n.id !== id))
    setDraft(null)
    await authFetch(token, `${API_BASE}/notes/${id}`, { method: 'DELETE' })
  }

  return (
    <div className="min-h-screen bg-gray-50 flex flex-col">
      <header className="bg-white shadow-sm px-4 py-3">
        <div className="flex items-center justify-between gap-3">
          <h1 className="text-xl font-semibold text-gray-800 truncate">{t.notes.headerTitle}</h1>
          <button onClick={onLogout} className="text-sm text-gray-500 hover:text-gray-800">
            {t.common.logout}
          </button>
        </div>
        <input
          type="search"
          placeholder={t.notes.searchPlaceholder}
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          className="mt-2 w-full border border-gray-300 rounded-lg px-3 py-2 text-sm text-gray-800 focus:outline-none focus:ring-2 focus:ring-indigo-500"
        />
      </header>

      <main className="flex-1 px-4 py-4 max-w-xl mx-auto w-full">
        {loading ? (
          <p className="text-gray-400 text-center mt-10">{t.common.loading}</p>
        ) : notes.length === 0 ? (
          <div className="text-center mt-20">
            <p className="text-gray-400 text-lg">{query.trim() ? t.notes.noResults : t.notes.empty}</p>
            {!query.trim() && <p className="text-gray-300 text-sm mt-1">{t.notes.emptyHint}</p>}
          </div>
        ) : (
          <ul className="space-y-3">
            {notes.map((note) => (
              <li
                key={note.id}
                onClick={() => setDraft(draftFromNote(note))}
                className="bg-white rounded-lg shadow-sm px-4 py-3 cursor-pointer hover:shadow transition"
              >
                <div className="flex items-start gap-2">
                  <h2 className="flex-1 min-w-0 font-medium text-gray-800 truncate">{note.title}</h2>
                  <span className="shrink-0 text-xs" title={note.visibility === 'PRIVATE' ? t.notes.private : t.notes.shared}>
                    {note.visibility === 'PRIVATE' ? '🔒' : '👥'}
                  </span>
                </div>
                {note.content && (
                  <p className="text-sm text-gray-500 mt-1 line-clamp-2 whitespace-pre-wrap">{note.content}</p>
                )}
                {note.tags.length > 0 && (
                  <div className="flex flex-wrap gap-1 mt-2">
                    {note.tags.map((tag) => (
                      <span key={tag} className="text-xs bg-indigo-50 text-indigo-600 rounded-full px-2 py-0.5">
                        #{tag}
                      </span>
                    ))}
                  </div>
                )}
              </li>
            ))}
          </ul>
        )}
      </main>

      {/* FAB */}
      <button
        onClick={() => setDraft(emptyDraft())}
        className="fixed bottom-20 right-6 w-14 h-14 rounded-full bg-indigo-600 text-white text-3xl shadow-lg hover:bg-indigo-700 active:scale-95 transition flex items-center justify-center"
        aria-label={t.notes.newNote}
      >
        +
      </button>

      {/* Editor modal */}
      {draft && (
        <div className="fixed inset-0 bg-black/40 flex items-end sm:items-center justify-center p-4 z-50">
          <div className="bg-white rounded-2xl w-full max-w-md p-5 shadow-xl max-h-[85vh] overflow-y-auto">
            <h2 className="text-lg font-semibold text-gray-800 mb-3">
              {draft.id ? t.notes.editNote : t.notes.newNote}
            </h2>
            <input
              autoFocus
              type="text"
              placeholder={t.common.titlePlaceholder}
              value={draft.title}
              onChange={(e) => setDraft({ ...draft, title: e.target.value })}
              className="w-full border border-gray-300 rounded-lg px-3 py-2 text-gray-800 focus:outline-none focus:ring-2 focus:ring-indigo-500"
            />
            <textarea
              placeholder={t.notes.contentPlaceholder}
              value={draft.content}
              onChange={(e) => setDraft({ ...draft, content: e.target.value })}
              rows={8}
              className="w-full border border-gray-300 rounded-lg px-3 py-2 text-gray-800 mt-2 font-mono text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500"
            />
            <input
              type="text"
              placeholder={t.notes.tagsPlaceholder}
              value={draft.tags}
              onChange={(e) => setDraft({ ...draft, tags: e.target.value })}
              className="w-full border border-gray-300 rounded-lg px-3 py-2 text-gray-800 mt-2 focus:outline-none focus:ring-2 focus:ring-indigo-500"
            />
            <div className="flex items-center gap-2 mt-3">
              <span className="text-sm text-gray-600">{t.notes.visibility}</span>
              <button
                onClick={() =>
                  setDraft({ ...draft, visibility: draft.visibility === 'SHARED' ? 'PRIVATE' : 'SHARED' })
                }
                className="text-sm px-3 py-1 rounded-full border border-gray-300 hover:bg-gray-50"
              >
                {draft.visibility === 'PRIVATE' ? `🔒 ${t.notes.private}` : `👥 ${t.notes.shared}`}
              </button>
            </div>
            <div className="flex justify-between items-center mt-4">
              {draft.id ? (
                <button
                  onClick={() => handleDelete(draft.id!)}
                  className="px-3 py-2 rounded-lg text-red-500 hover:bg-red-50"
                >
                  {t.common.delete}
                </button>
              ) : (
                <span />
              )}
              <div className="flex gap-2">
                <button
                  onClick={() => setDraft(null)}
                  className="px-4 py-2 rounded-lg text-gray-600 hover:bg-gray-100"
                >
                  {t.common.cancel}
                </button>
                <button
                  onClick={handleSave}
                  disabled={saving || !draft.title.trim()}
                  className="px-4 py-2 rounded-lg bg-indigo-600 text-white hover:bg-indigo-700 disabled:opacity-50"
                >
                  {t.common.save}
                </button>
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
