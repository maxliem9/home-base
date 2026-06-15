// HB-03 — global search / command palette (⌘K). A top-anchored overlay over the whole app:
// a search box across todos/notes/recipes/projects/shopping plus quick navigation actions,
// fully keyboard-driven (↑↓ to move, ↵ to run, esc to close). Opened with ⌘K / Ctrl-K
// (desktop) or the search icon in the mobile top bar. Reuses the global :focus-visible rings
// and manages its own focus (input on open, returns to the opener on close).
import { useEffect, useMemo, useRef, useState, type KeyboardEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { Icon } from '../ui/Icon'
import { KIND_ICON, loadSearchIndex, searchItems, type SearchItem, type SearchKind } from '../search'

export interface PaletteAction {
  id: string
  label: string
  icon: string
  hint?: string
  run: () => void
}

interface Entry {
  key: string
  icon: string
  label: string
  hint?: string
  run: () => void
}

const PER_GROUP = 5

export function CommandPalette({ token, open, onClose, actions, onOpenResult }: {
  token: string
  open: boolean
  onClose: () => void
  actions: PaletteAction[]
  onOpenResult: (item: SearchItem) => void
}) {
  const { t } = useTranslation()
  const [query, setQuery] = useState('')
  const [index, setIndex] = useState<SearchItem[]>([])
  const [loading, setLoading] = useState(false)
  const [sel, setSel] = useState(0)
  const inputRef = useRef<HTMLInputElement>(null)
  const openerRef = useRef<HTMLElement | null>(null)

  // On open: remember the opener, reset, (re)load the index, focus the input. On close:
  // return focus to the opener (e.g. the mobile search button). Refetches each open so the
  // index reflects the latest household data.
  useEffect(() => {
    if (!open) return
    openerRef.current = document.activeElement as HTMLElement | null
    setQuery('')
    setSel(0)
    setLoading(true)
    let alive = true
    loadSearchIndex(token).then((idx) => {
      if (alive) {
        setIndex(idx)
        setLoading(false)
      }
    })
    const focusId = window.setTimeout(() => inputRef.current?.focus(), 0)
    return () => {
      alive = false
      window.clearTimeout(focusId)
      const opener = openerRef.current
      if (opener && document.contains(opener)) opener.focus()
    }
  }, [open, token])

  const q = query.trim().toLowerCase()
  const matchedActions = useMemo(
    () => (q ? actions.filter((a) => a.label.toLowerCase().includes(q)) : actions),
    [actions, q],
  )
  const results = useMemo(() => searchItems(index, query), [index, query])
  const grouped = useMemo(() => {
    const g: Record<SearchKind, SearchItem[]> = { todo: [], note: [], recipe: [], project: [], shopping: [] }
    for (const it of results) if (g[it.kind].length < PER_GROUP) g[it.kind].push(it)
    return g
  }, [results])

  // Build the rendered sections AND the flat keyboard-order list together, so the running
  // `idx` baked into each row matches `sel` exactly.
  const { sections, entries } = useMemo(() => {
    const secs: { label: string; items: (Entry & { idx: number })[] }[] = []
    const flat: Entry[] = []
    const resultEntry = (it: SearchItem): Entry => ({
      key: `${it.kind}:${it.id}`,
      icon: KIND_ICON[it.kind],
      label: it.title,
      hint: it.subtitle,
      run: () => onOpenResult(it),
    })
    const add = (label: string, list: Entry[]) => {
      if (!list.length) return
      secs.push({ label, items: list.map((e) => ({ ...e, idx: flat.push(e) - 1 })) })
    }
    add(t('palette.actions'), matchedActions.map((a) => ({ key: `a:${a.id}`, icon: a.icon, label: a.label, hint: a.hint, run: a.run })))
    add(t('palette.groupTodos'), grouped.todo.map(resultEntry))
    add(t('palette.groupNotes'), grouped.note.map(resultEntry))
    add(t('palette.groupRecipes'), grouped.recipe.map(resultEntry))
    add(t('palette.groupProjects'), grouped.project.map(resultEntry))
    add(t('palette.groupShopping'), grouped.shopping.map(resultEntry))
    return { sections: secs, entries: flat }
  }, [matchedActions, grouped, onOpenResult, t])

  // Clamp the selection when the entry count shrinks.
  useEffect(() => {
    setSel((s) => (entries.length === 0 ? 0 : Math.min(s, entries.length - 1)))
  }, [entries.length])

  // Keep the highlighted row in view.
  useEffect(() => {
    if (open) document.getElementById(`hb-cmd-opt-${sel}`)?.scrollIntoView({ block: 'nearest' })
  }, [sel, open])

  if (!open) return null

  const run = (e?: Entry) => {
    if (!e) return
    e.run()
    onClose()
  }

  const onKeyDown = (e: KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'ArrowDown') { e.preventDefault(); setSel((s) => Math.min(s + 1, Math.max(0, entries.length - 1))) }
    else if (e.key === 'ArrowUp') { e.preventDefault(); setSel((s) => Math.max(s - 1, 0)) }
    else if (e.key === 'Enter') { e.preventDefault(); run(entries[sel]) }
    else if (e.key === 'Escape') { e.preventDefault(); onClose() }
  }

  const showEmpty = !loading && q.length > 0 && entries.length === 0

  return (
    <div className="hb-cmd-scrim" onClick={onClose}>
      <div className="hb-cmd" role="dialog" aria-modal="true" aria-label={t('palette.title')} onClick={(e) => e.stopPropagation()}>
        <div className="hb-cmd__search">
          <Icon name="search" size={18} stroke={2} />
          <input
            ref={inputRef}
            className="hb-cmd__input"
            type="text"
            role="combobox"
            aria-expanded={entries.length > 0}
            aria-controls="hb-cmd-list"
            aria-activedescendant={entries.length ? `hb-cmd-opt-${sel}` : undefined}
            placeholder={t('palette.placeholder')}
            value={query}
            onChange={(e) => { setQuery(e.target.value); setSel(0) }}
            onKeyDown={onKeyDown}
          />
          <kbd className="hb-cmd__esc">esc</kbd>
        </div>

        <div className="hb-cmd__list" id="hb-cmd-list" role="listbox" aria-label={t('palette.title')}>
          {loading ? (
            <div className="hb-cmd__empty">{t('common.loading')}</div>
          ) : showEmpty ? (
            <div className="hb-cmd__empty">{t('palette.noResults')}</div>
          ) : (
            sections.map((section) => (
              <div key={section.label} className="hb-cmd__group">
                <div className="hb-cmd__grouphead">{section.label}</div>
                {section.items.map((item) => (
                  <button
                    key={item.key}
                    id={`hb-cmd-opt-${item.idx}`}
                    role="option"
                    aria-selected={item.idx === sel}
                    className={`hb-cmd__opt${item.idx === sel ? ' is-active' : ''}`}
                    onMouseMove={() => setSel(item.idx)}
                    onClick={() => run(item)}
                  >
                    <Icon name={item.icon} size={17} stroke={2} />
                    <span className="hb-cmd__optlabel">{item.label}</span>
                    {item.hint && <span className="hb-cmd__opthint">{item.hint}</span>}
                  </button>
                ))}
              </div>
            ))
          )}
        </div>

        <div className="hb-cmd__foot">
          <span><kbd>↑</kbd><kbd>↓</kbd> {t('palette.footNavigate')}</span>
          <span><kbd>↵</kbd> {t('palette.footOpen')}</span>
        </div>
      </div>
    </div>
  )
}
