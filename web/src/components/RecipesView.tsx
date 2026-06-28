import { useState, useEffect, useCallback, useRef } from 'react'
import { useTranslation } from 'react-i18next'
import type { TFunction } from 'i18next'
import { API_BASE, authFetch, downloadImage, errorCode, notifyTransportError, recipeImageUrl, safeFetch } from '../api'
import { errorText } from '../i18n'
import { Ingredient, Recipe, RecipeCategory, ShoppingList } from '../types'
import { useWebSocket } from '../hooks/useWebSocket'
import { formatNumber, parseLocaleNumber } from '../ui/format'
import { AuthedImage } from '../ui/AuthedImage'
import { Icon } from '../ui/Icon'
import { useErrorToast } from '../ui/ErrorToast'
import { Badge, Button, Card, Checkbox, EmptyState, Field, IconButton, Modal, PageHead, Select, Sheet, TextInput } from '../ui/primitives'
import {
  IngredientDraft, SectionDraft, emptyIngredient, emptySection,
  parseIngredientsText, serializeSections,
} from './recipeIngredients'
import { CATEGORY_ICON, coverHue } from '../lib/cover'

const WS_SCHEME = window.location.protocol === 'https:' ? 'wss' : 'ws'
const WS_URL = import.meta.env.VITE_WS_URL_RECIPES ?? `${WS_SCHEME}://${window.location.host}/api/v1/ws/recipes`

// Built with the reactive `t` inside each consumer so the labels follow a language
// switch. Category IDs match the backend RecipeCategory enum.
const buildCategories = (t: TFunction): { id: RecipeCategory; label: string }[] => [
  { id: 'BREAKFAST', label: t('recipes.categories.BREAKFAST') },
  { id: 'DINNER', label: t('recipes.categories.DINNER') },
  { id: 'SNACK', label: t('recipes.categories.SNACK') },
  { id: 'DESSERT', label: t('recipes.categories.DESSERT') },
  { id: 'DRINK', label: t('recipes.categories.DRINK') },
]

const categoryLabel = (t: TFunction, c: RecipeCategory) => buildCategories(t).find((x) => x.id === c)?.label ?? c
const totalTime = (r: Recipe) => (r.prepTimeMinutes ?? 0) + (r.cookTimeMinutes ?? 0)
// Scaled ingredient amount for display — locale-aware decimal separator (#238): "0,5 l" (de) /
// "0.5 l" (en). 2-decimal max with trailing zeros stripped (matching the old precision). Grouping
// is OFF on purpose: a scaled amount ≥ 1000 must stay "1500 g", not "1.500 g" (de) — the latter
// reads as 1,5 in German. Locale only changes the decimal mark, not the magnitude.
const fmtAmount = (n: number) => formatNumber(n, { maximumFractionDigits: 2, useGrouping: false })

// The backend omits empty collections from its JSON (encodeDefaults=false), so a recipe
// without ingredients or steps arrives with those keys missing. Coerce them to [] on the
// way in so the rest of the view can treat ingredients/steps as always-present arrays.
const normalizeRecipe = (r: Recipe): Recipe => ({
  ...r,
  ingredients: r.ingredients ?? [],
  steps: r.steps ?? [],
})

interface Draft {
  id?: string
  title: string
  description: string
  servings: string
  prepTimeMinutes: string
  cookTimeMinutes: string
  category: RecipeCategory
  sections: SectionDraft[]
  steps: string[]
}

// Group ingredients into consecutive runs sharing the same section label. Ingredients arrive
// ordered by sortOrder (the order they were authored), so consecutive grouping faithfully
// reconstructs the editor's sections — including two distinct sections that happen to share a
// name. A blank/absent section becomes the header-less top group (null).
const groupBySection = (items: Ingredient[]): { section: string | null; items: Ingredient[] }[] => {
  const groups: { section: string | null; items: Ingredient[] }[] = []
  for (const it of items) {
    const sec = it.section?.trim() ? it.section.trim() : null
    const last = groups[groups.length - 1]
    if (last && (last.section ?? '') === (sec ?? '')) last.items.push(it)
    else groups.push({ section: sec, items: [it] })
  }
  return groups
}

const emptyDraft = (): Draft => ({
  title: '', description: '', servings: '2', prepTimeMinutes: '', cookTimeMinutes: '',
  category: 'DINNER', sections: [emptySection()], steps: [''],
})
const draftFromRecipe = (r: Recipe): Draft => {
  const groups = groupBySection(r.ingredients)
  return {
    id: r.id,
    title: r.title,
    description: r.description ?? '',
    servings: String(r.servings),
    prepTimeMinutes: r.prepTimeMinutes != null ? String(r.prepTimeMinutes) : '',
    cookTimeMinutes: r.cookTimeMinutes != null ? String(r.cookTimeMinutes) : '',
    category: r.category,
    sections: groups.length
      ? groups.map((g) => ({
          name: g.section ?? '',
          ingredients: g.items.map((i) => ({ name: i.name, amount: i.amount != null ? String(i.amount) : '', unit: i.unit ?? '' })),
        }))
      : [emptySection()],
    steps: r.steps.length ? r.steps.map((s) => s.description) : [''],
  }
}

// Shape returned by the backend recipe-import endpoint (POST /recipes/import). A best-effort
// draft — every field may be missing (encodeDefaults=false), so the mapper below coerces.
interface ImportedRecipe {
  title: string
  description?: string
  servings?: number
  prepTimeMinutes?: number
  cookTimeMinutes?: number
  category?: RecipeCategory
  ingredients?: { name: string; amount?: number; unit?: string; section?: string }[]
  steps?: { description: string }[]
}

// Build an editor Draft from an imported recipe so the user lands in the normal editor pre-filled
// and reviews/edits before saving. Mirrors draftFromRecipe but the source has no id (always a new
// recipe) and the ingredient amounts arrive as numbers → stringify for the editable fields.
const draftFromImport = (r: ImportedRecipe): Draft => {
  const ingredients = r.ingredients ?? []
  const groups = groupBySection(
    ingredients.map((i, idx) => ({ id: String(idx), name: i.name, amount: i.amount, unit: i.unit, section: i.section, sortOrder: idx })),
  )
  return {
    title: r.title ?? '',
    description: r.description ?? '',
    servings: r.servings != null ? String(r.servings) : '2',
    prepTimeMinutes: r.prepTimeMinutes != null ? String(r.prepTimeMinutes) : '',
    cookTimeMinutes: r.cookTimeMinutes != null ? String(r.cookTimeMinutes) : '',
    category: r.category ?? 'DINNER',
    sections: groups.length
      ? groups.map((g) => ({
          name: g.section ?? '',
          ingredients: g.items.map((i) => ({ name: i.name, amount: i.amount != null ? String(i.amount) : '', unit: i.unit ?? '' })),
        }))
      : [emptySection()],
    steps: (r.steps ?? []).length ? (r.steps ?? []).map((s) => s.description) : [''],
  }
}

interface RecipesViewProps {
  token: string
  onLogout: () => void
}

export function RecipesView({ token, onLogout }: RecipesViewProps) {
  const { t } = useTranslation()
  const [recipes, setRecipes] = useState<Recipe[]>([])
  const [shoppingLists, setShoppingLists] = useState<ShoppingList[]>([])
  const [loading, setLoading] = useState(true)
  const [filter, setFilter] = useState<RecipeCategory | 'ALL'>('ALL')
  const [selected, setSelected] = useState<Recipe | null>(null)
  const [draft, setDraft] = useState<Draft | null>(null)
  const [picking, setPicking] = useState<Recipe | null>(null)
  const [pickServings, setPickServings] = useState(0)
  const [saving, setSaving] = useState(false)
  const [saveError, setSaveError] = useState<string | null>(null)
  const [toast, setToast] = useState<string | null>(null)
  const [importOpen, setImportOpen] = useState(false)
  const { flashError, errorToast } = useErrorToast()

  const fetchRecipes = useCallback(async () => {
    try {
      const result = await safeFetch(token, `${API_BASE}/recipes`)
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
      const list = (await res.json()) as Recipe[]
      setRecipes(list.map(normalizeRecipe))
    } finally {
      setLoading(false)
    }
  }, [onLogout, token])

  const fetchShoppingLists = useCallback(async () => {
    const result = await safeFetch(token, `${API_BASE}/shopping/lists`)
    // transport reject → fire the global toast once, keep existing data
    if (!result.ok) {
      notifyTransportError()
      return
    }
    const { res } = result
    if (res.ok) setShoppingLists(await res.json())
  }, [token])

  useEffect(() => { fetchRecipes() }, [fetchRecipes])
  useEffect(() => { fetchShoppingLists() }, [fetchShoppingLists])

  useWebSocket({ url: WS_URL, token }, (raw) => {
    try {
      const msg = JSON.parse(raw)
      if (!msg.payload) return
      if (msg.type === 'RECIPE_CREATED') {
        const incoming = normalizeRecipe(msg.payload)
        setRecipes((prev) => (prev.some((r) => r.id === incoming.id) ? prev : [incoming, ...prev]))
      } else if (msg.type === 'RECIPE_UPDATED') {
        const incoming = normalizeRecipe(msg.payload)
        setRecipes((prev) =>
          prev.some((r) => r.id === incoming.id) ? prev.map((r) => (r.id === incoming.id ? incoming : r)) : [incoming, ...prev],
        )
        setSelected((cur) => (cur && cur.id === incoming.id ? incoming : cur))
      } else if (msg.type === 'RECIPE_DELETED') {
        setRecipes((prev) => prev.filter((r) => r.id !== msg.payload.id))
        setSelected((cur) => (cur && cur.id === msg.payload.id ? null : cur))
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
        description: draft.description.trim() || undefined,
        servings: parseInt(draft.servings, 10) || 1,
        prepTimeMinutes: draft.prepTimeMinutes ? parseInt(draft.prepTimeMinutes, 10) : undefined,
        cookTimeMinutes: draft.cookTimeMinutes ? parseInt(draft.cookTimeMinutes, 10) : undefined,
        category: draft.category,
        // flatten sections back to a flat ingredient list; each row carries its section label
        // (blank → undefined). List order = section order, so sortOrder reflects the grouping.
        ingredients: draft.sections.flatMap((sec) =>
          sec.ingredients
            .filter((i) => i.name.trim())
            .map((i) => ({
              name: i.name.trim(),
              // accept comma or dot decimal (#299); blank/unparseable → no amount
              amount: i.amount.trim() ? (parseLocaleNumber(i.amount) ?? undefined) : undefined,
              unit: i.unit.trim() || undefined,
              section: sec.name.trim() || undefined,
            })),
        ),
        steps: draft.steps.filter((s) => s.trim()).map((s) => ({ description: s.trim() })),
      })
      const url = draft.id ? `${API_BASE}/recipes/${draft.id}` : `${API_BASE}/recipes`
      const result = await safeFetch(token, url, {
        method: draft.id ? 'PUT' : 'POST',
        headers: { 'Content-Type': 'application/json' },
        body,
      })
      // transport reject → keep the editor modal open and show the inline error so the user can retry
      if (!result.ok) {
        setSaveError(errorText(null, t('recipes.saveFailed')))
        return
      }
      const { res } = result
      if (res.status === 401) return onLogout()
      if (res.ok) {
        const saved = normalizeRecipe(await res.json())
        setRecipes((prev) => (prev.some((r) => r.id === saved.id) ? prev.map((r) => (r.id === saved.id ? saved : r)) : [saved, ...prev]))
        setDraft(null)
        setSelected(saved)
      } else {
        // keep the editor modal open and show the reason inline so the user can retry
        setSaveError(errorText(await errorCode(res), t('recipes.saveFailed')))
      }
    } finally {
      setSaving(false)
    }
  }

  const handleDelete = async (id: string) => {
    setRecipes((prev) => prev.filter((r) => r.id !== id))
    setDraft(null)
    setSelected(null)
    const result = await safeFetch(token, `${API_BASE}/recipes/${id}`, { method: 'DELETE' })
    // On failure refetch to resync rather than restoring a captured snapshot,
    // which could clobber a concurrent WS update.
    if (!result.ok) {
      await fetchRecipes()
      return flashError(errorText(null, t('recipes.deleteFailed')))
    }
    const { res } = result
    if (res.status === 401) return onLogout()
    if (!res.ok) {
      await fetchRecipes()
      flashError(errorText(await errorCode(res), t('recipes.deleteFailed')))
    }
  }

  // Upsert a recipe returned by a cover-image mutation (upload / delete) so the list and the
  // open detail refresh immediately, independent of the WS echo.
  const applyRecipe = (saved: Recipe) => {
    const next = normalizeRecipe(saved)
    setRecipes((prev) => (prev.some((r) => r.id === next.id) ? prev.map((r) => (r.id === next.id ? next : r)) : [next, ...prev]))
  }

  // hand the chosen (already serving-scaled) ingredients to the batch endpoint, which formats
  // each as a "200 g Mehl" label and merges quantities into matching items already on the list
  const addToShopping = async (listId: string, items: { name: string; amount?: number; unit?: string }[]) => {
    setPicking(null)
    const flash = (msg: string) => {
      setToast(msg)
      setTimeout(() => setToast(null), 2600)
    }
    const result = await safeFetch(token, `${API_BASE}/shopping/batch`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ listId, items }),
    })
    // transport reject → no Response; surface the generic German fallback
    if (!result.ok) return flashError(errorText(null, t('recipes.addToListFailed')))
    const { res } = result
    if (res.status === 401) return onLogout()
    // a genuine write failure routes through the error toast (was wrongly shown
    // as the success-styled "nothing to add" message before — issue #96)
    if (!res.ok) return flashError(errorText(await errorCode(res), t('recipes.addToListFailed')))
    const summary = (await res.json()) as { added: number; merged: number; skipped: number }
    const parts: string[] = []
    if (summary.added > 0) parts.push(`${summary.added} ${t('recipes.added')}`)
    if (summary.merged > 0) parts.push(`${summary.merged} ${t('recipes.merged')}`)
    // success/empty case keeps the genuine "nothing to add" confirmation
    flash(parts.length ? parts.join(' · ') : t('recipes.nothingToAdd'))
  }

  // keep the open recipe in sync with the store (e.g. after WS edits)
  const current = selected ? recipes.find((r) => r.id === selected.id) ?? selected : null
  const visible = filter === 'ALL' ? recipes : recipes.filter((r) => r.category === filter)

  // ---- editor page (full page, not a modal) — issue #123 ----
  // Takes priority over the detail branch: editing from the detail page sets both `selected`
  // and `draft`, so cancelling returns to the detail; creating new (no `selected`) returns to
  // the list. Save success clears the draft and selects the saved recipe → detail page.
  if (draft) {
    return (
      <RecipeEditor
        draft={draft}
        setDraft={setDraft}
        saving={saving}
        error={saveError}
        onSave={handleSave}
        onCancel={() => { setDraft(null); setSaveError(null) }}
      />
    )
  }

  // ---- detail page (full page, not a modal) ----
  if (current) {
    return (
      <>
        <RecipeDetail
          recipe={current}
          token={token}
          onLogout={onLogout}
          onUpdated={applyRecipe}
          onBack={() => setSelected(null)}
          onEdit={() => setDraft(draftFromRecipe(current))}
          onDelete={() => handleDelete(current.id)}
          onExportError={() => flashError(errorText(null, t('recipes.exportFailed')))}
          onAddToShopping={(servings) => { setPickServings(servings); setPicking(current) }}
        />
        {picking && (
          <IngredientPicker
            recipe={picking}
            servings={pickServings}
            lists={shoppingLists}
            onClose={() => setPicking(null)}
            onAdd={addToShopping}
          />
        )}
        {toast && (
          <div className="hb-toast">
            <Icon name="check" size={18} stroke={2.4} style={{ color: 'var(--accent)' }} />
            {toast}
          </div>
        )}
        {errorToast}
      </>
    )
  }

  return (
    <div className="hb-page">
      <PageHead
        eyebrow={`${recipes.length} ${t('recipes.count')}`}
        title={t('recipes.title')}
        actions={
          <>
            <Button variant="soft" icon="download" onClick={() => setImportOpen(true)}>{t('recipes.importFromUrl')}</Button>
            <Button icon="plus" onClick={() => setDraft(emptyDraft())}>{t('recipes.newRecipe')}</Button>
          </>
        }
      />

      {importOpen && (
        <ImportFromUrlModal
          token={token}
          onLogout={onLogout}
          onClose={() => setImportOpen(false)}
          onImported={(imported) => { setImportOpen(false); setSelected(null); setDraft(draftFromImport(imported)) }}
        />
      )}

      <div className="hb-pickrow" style={{ marginBottom: 24 }}>
        <button className={`hb-pick${filter === 'ALL' ? ' is-active' : ''}`} onClick={() => setFilter('ALL')}>{t('recipes.filterAll')}</button>
        {buildCategories(t).map((c) => (
          <button key={c.id} className={`hb-pick${filter === c.id ? ' is-active' : ''}`} onClick={() => setFilter(c.id)}>{c.label}</button>
        ))}
      </div>

      {loading ? (
        <p className="hb-muted" style={{ textAlign: 'center', padding: 24 }}>{t('common.loading')}</p>
      ) : visible.length === 0 ? (
        <Card className="hb-card--pad">
          <EmptyState
            icon="chef"
            title={filter === 'ALL' ? t('recipes.emptyAll') : t('recipes.emptyCategory')}
            hint={filter === 'ALL' ? t('recipes.emptyHint') : undefined}
            action={filter === 'ALL' ? <Button size="sm" icon="plus" onClick={() => setDraft(emptyDraft())}>{t('recipes.newRecipe')}</Button> : undefined}
          />
        </Card>
      ) : (
        <div className="hb-recipe-grid">
          {visible.map((recipe) => (
            <Card key={recipe.id} className="hb-recipecard hb-card--hover" onClick={() => setSelected(recipe)}>
              <div className="hb-recipecard__img" style={{ ['--rh' as string]: coverHue(recipe.title) }}>
                {recipe.image ? (
                  <>
                    <AuthedImage
                      url={recipeImageUrl(recipe.id, recipe.image.id)}
                      token={token}
                      alt={recipe.title}
                      className="hb-recipecard__photo"
                    />
                    <Badge tone="clay">{categoryLabel(t, recipe.category)}</Badge>
                  </>
                ) : (
                  // HB-05 — generated cover (warm title-derived gradient + category glyph + label)
                  <div className="hb-recipecard__cover">
                    <Icon name={CATEGORY_ICON[recipe.category]} size={30} stroke={1.7} />
                    <span className="hb-recipecard__covlabel">{categoryLabel(t, recipe.category)}</span>
                  </div>
                )}
              </div>
              <div className="hb-recipecard__body">
                <div className="hb-recipecard__title">{recipe.title}</div>
                {recipe.description && <p className="hb-recipecard__desc">{recipe.description}</p>}
                <div className="hb-recipecard__meta">
                  {totalTime(recipe) > 0 && (
                    <span style={{ display: 'inline-flex', alignItems: 'center', gap: 5 }}>
                      <Icon name="clock" size={15} stroke={2} /> {totalTime(recipe)} {t('recipes.minutesAbbr')}
                    </span>
                  )}
                  <span style={{ display: 'inline-flex', alignItems: 'center', gap: 5 }}>
                    <Icon name="users" size={15} stroke={2} /> {recipe.servings} {t('recipes.servingsAbbr')}
                  </span>
                </div>
              </div>
            </Card>
          ))}
        </div>
      )}

      {toast && (
        <div className="hb-toast">
          <Icon name="check" size={18} stroke={2.4} style={{ color: 'var(--accent)' }} />
          {toast}
        </div>
      )}

      {errorToast}
    </div>
  )
}

function RecipeDetail({ recipe, token, onBack, onEdit, onDelete, onExportError, onLogout, onUpdated, onAddToShopping }: {
  recipe: Recipe
  token: string
  onBack: () => void
  onEdit: () => void
  onDelete: () => void
  onExportError: () => void
  onLogout: () => void
  onUpdated: (recipe: Recipe) => void
  onAddToShopping: (servings: number) => void
}) {
  const { t } = useTranslation()
  const [servings, setServings] = useState(recipe.servings)
  const [showExport, setShowExport] = useState(false)
  const [exporting, setExporting] = useState(false)
  const factor = recipe.servings > 0 ? servings / recipe.servings : 1
  const total = (recipe.prepTimeMinutes ?? 0) + (recipe.cookTimeMinutes ?? 0)

  // Download the recipe via the backend export endpoint. The current servings count is
  // sent so the file matches what's on screen; the JWT stays in the Authorization header
  // (never the URL), and the filename comes from the server's Content-Disposition.
  const exportRecipe = async (format: 'md' | 'pdf') => {
    setExporting(true)
    try {
      const params = new URLSearchParams({ format })
      if (servings !== recipe.servings) params.set('servings', String(servings))
      const result = await safeFetch(token, `${API_BASE}/recipes/${recipe.id}/export?${params}`)
      if (!result.ok) return onExportError()
      const { res } = result
      if (res.status === 401) return onLogout()
      if (!res.ok) return onExportError()
      const blob = await res.blob()
      const filename = res.headers.get('Content-Disposition')?.match(/filename="?([^"]+)"?/)?.[1] ?? `rezept.${format}`
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = filename
      document.body.appendChild(a)
      a.click()
      a.remove()
      URL.revokeObjectURL(url)
      setShowExport(false)
    } finally {
      setExporting(false)
    }
  }

  return (
    <div className="hb-page">
      <button className="hb-backlink" onClick={onBack}>
        <Icon name="chevronLeft" size={17} stroke={2.2} />{t('recipes.backToRecipes')}
      </button>

      <div className="hb-pagehead">
        <div>
          <div className="hb-pagehead__eyebrow">{categoryLabel(t, recipe.category)}</div>
          <h1>{recipe.title}</h1>
        </div>
        <div className="hb-pagehead__actions">
          <Button variant="danger" icon="trash" onClick={onDelete}>{t('common.delete')}</Button>
          <Button variant="ghost" icon="edit" onClick={onEdit}>{t('recipes.edit')}</Button>
          <Button variant="ghost" icon="download" onClick={() => setShowExport(true)}>{t('recipes.export')}</Button>
          <Button variant="soft" icon="cart" onClick={() => onAddToShopping(servings)}>{t('recipes.addToList')}</Button>
        </div>
      </div>

      {showExport && (
        <Modal
          open
          onClose={() => setShowExport(false)}
          title={t('recipes.exportTitle')}
          footer={<Button variant="ghost" onClick={() => setShowExport(false)}>{t('common.cancel')}</Button>}
        >
          <p className="hb-muted" style={{ marginTop: 0 }}>{t('recipes.exportHint')}</p>
          <div style={{ display: 'flex', gap: 10 }}>
            <Button icon="download" disabled={exporting} onClick={() => exportRecipe('md')}>{t('recipes.exportMarkdown')}</Button>
            <Button variant="soft" icon="download" disabled={exporting} onClick={() => exportRecipe('pdf')}>{t('recipes.exportPdf')}</Button>
          </div>
        </Modal>
      )}

      {recipe.description && (
        <p className="hb-muted" style={{ margin: '0 0 18px', fontSize: 16, maxWidth: 640 }}>{recipe.description}</p>
      )}

      <RecipeImages recipe={recipe} token={token} onLogout={onLogout} onUpdated={onUpdated} />

      <div className="hb-recipe-facts" style={{ maxWidth: 560, marginBottom: 26 }}>
        <div className="hb-servings-step hb-fact" style={{ flexDirection: 'row', alignItems: 'center' }}>
          <div style={{ flex: 1 }}>
            <div className="hb-fact__v">{servings}</div>
            <div className="hb-fact__l">{t('recipes.servings')}</div>
          </div>
          <div style={{ display: 'flex', gap: 4 }}>
            <IconButton icon="minus" label={t('recipes.lessServings')} onClick={() => setServings((s) => Math.max(1, s - 1))} />
            <IconButton icon="plus" label={t('recipes.moreServings')} onClick={() => setServings((s) => s + 1)} />
          </div>
        </div>
        {recipe.prepTimeMinutes != null && (
          <div className="hb-fact"><div className="hb-fact__v">{recipe.prepTimeMinutes}</div><div className="hb-fact__l">{t('recipes.prep')} ({t('recipes.minutesAbbr')})</div></div>
        )}
        {recipe.cookTimeMinutes != null && (
          <div className="hb-fact"><div className="hb-fact__v">{recipe.cookTimeMinutes}</div><div className="hb-fact__l">{t('recipes.cook')} ({t('recipes.minutesAbbr')})</div></div>
        )}
        {total > 0 && (
          <div className="hb-fact"><div className="hb-fact__v">{total}</div><div className="hb-fact__l">{t('recipes.totalTime')} ({t('recipes.minutesAbbr')})</div></div>
        )}
      </div>

      <div className="hb-recipe-body">
        {recipe.ingredients.length > 0 && (
          <div>
            <div className="hb-sectionlabel">{t('recipes.ingredients')}</div>
            {groupBySection(recipe.ingredients).map((group, gi) => (
              <div key={gi} className="hb-inggroup">
                {group.section && <div className="hb-ingsubhead">{group.section}</div>}
                <div className="hb-ingredients">
                  {group.items.map((ing) => (
                    <div key={ing.id} className="hb-ing">
                      <span className="hb-ing__amt">{ing.amount != null ? `${fmtAmount(ing.amount * factor)} ${ing.unit ?? ''}`.trim() : ''}</span>
                      <span>{ing.name}</span>
                    </div>
                  ))}
                </div>
              </div>
            ))}
          </div>
        )}
        {recipe.steps.length > 0 && (
          <div>
            <div className="hb-sectionlabel">{t('recipes.preparation')}</div>
            <ol className="hb-steps">
              {recipe.steps.map((step) => (
                <li key={step.id} className="hb-step">
                  <span className="hb-step__n">{step.stepNumber}</span>
                  <span>{step.description}</span>
                </li>
              ))}
            </ol>
          </div>
        )}
      </div>
    </div>
  )
}

// Single cover image for a recipe's detail page: a large preview (click to zoom) plus add /
// replace / remove controls. A recipe has at most one image; uploading replaces the current one.
// Mutations return the updated recipe via onUpdated.
function RecipeImages({ recipe, token, onLogout, onUpdated }: {
  recipe: Recipe
  token: string
  onLogout: () => void
  onUpdated: (recipe: Recipe) => void
}) {
  const { t } = useTranslation()
  const { flashError, errorToast } = useErrorToast()
  const [uploading, setUploading] = useState(false)
  const [imageError, setImageError] = useState<string | null>(null)
  const [lightbox, setLightbox] = useState(false)
  const fileInputRef = useRef<HTMLInputElement>(null)
  const image = recipe.image

  const handleUpload = async (file: File) => {
    setImageError(null)
    setUploading(true)
    try {
      const fd = new FormData()
      fd.append('file', file)
      const res = await authFetch(token, `${API_BASE}/recipes/${recipe.id}/images`, { method: 'POST', body: fd })
      if (res.status === 401) return onLogout()
      if (res.ok) onUpdated(await res.json())
      else if (res.status === 413) setImageError(t('recipes.imageTooLarge'))
      else if (res.status === 415) setImageError(t('recipes.imageBadType'))
      else setImageError(errorText(await errorCode(res), t('recipes.imageUploadFailed')))
    } catch {
      setImageError(t('recipes.imageUploadFailed'))
    } finally {
      setUploading(false)
    }
  }

  const handleDelete = async () => {
    if (!image) return
    setImageError(null)
    try {
      const res = await authFetch(token, `${API_BASE}/recipes/${recipe.id}/images/${image.id}`, { method: 'DELETE' })
      if (res.status === 401) return onLogout()
      if (res.ok) onUpdated(await res.json())
      else setImageError(errorText(await errorCode(res), t('recipes.imageDeleteFailed')))
    } catch {
      setImageError(t('recipes.imageDeleteFailed'))
    }
  }

  // Download the cover image under its original upload name (the lightbox renders a blob URL, so
  // the browser's "Save image as…" loses the server's filename — see downloadImage in api.ts).
  // The download is triggered from inside the lightbox, so a failure goes to a toast (the inline
  // imageError below would be hidden behind the overlay).
  const handleDownload = async () => {
    if (!image) return
    const outcome = await downloadImage(token, recipeImageUrl(recipe.id, image.id), image.originalName)
    if (outcome === 'unauthorized') onLogout()
    else if (outcome === 'error') flashError(t('recipes.imageDownloadFailed'))
  }

  return (
    <div className="hb-recipe-photos">
      {image && (
        <button type="button" className="hb-recipe-hero" onClick={() => setLightbox(true)} aria-label={t('recipes.openImage')}>
          <AuthedImage url={recipeImageUrl(recipe.id, image.id)} token={token} alt={recipe.title} />
        </button>
      )}

      <div className="hb-recipe-photos__head">
        <span className="hb-field__label">{t('recipes.image')}</span>
        <div style={{ display: 'flex', gap: 8 }}>
          {image && (
            <Button variant="ghost" size="sm" icon="trash" onClick={handleDelete}>{t('recipes.removeImage')}</Button>
          )}
          <Button variant="secondary" size="sm" icon="plus" disabled={uploading} onClick={() => fileInputRef.current?.click()}>
            {uploading ? t('recipes.uploading') : image ? t('recipes.changeImage') : t('recipes.addImage')}
          </Button>
        </div>
      </div>
      {imageError && <p className="hb-note-images__error">{imageError}</p>}

      <input
        ref={fileInputRef}
        type="file"
        accept="image/jpeg,image/png,image/webp,image/gif"
        style={{ display: 'none' }}
        onChange={(e) => {
          const f = e.target.files?.[0]
          if (f) handleUpload(f)
          e.target.value = '' // allow re-selecting the same file
        }}
      />

      {lightbox && image && (
        <div className="hb-lightbox" onClick={() => setLightbox(false)}>
          <button
            type="button"
            className="hb-lightbox__download"
            title={t('recipes.downloadImage')}
            aria-label={t('recipes.downloadImage')}
            onClick={(e) => {
              e.stopPropagation()
              handleDownload()
            }}
          >
            <Icon name="download" size={18} stroke={2.2} />
          </button>
          <AuthedImage url={recipeImageUrl(recipe.id, image.id)} token={token} alt="" onClick={(e) => e.stopPropagation()} />
        </div>
      )}
      {errorToast}
    </div>
  )
}

function IngredientPicker({ recipe, servings, lists, onClose, onAdd }: {
  recipe: Recipe
  servings: number
  lists: ShoppingList[]
  onClose: () => void
  onAdd: (listId: string, items: { name: string; amount?: number; unit?: string }[]) => void
}) {
  const { t } = useTranslation()
  const [sel, setSel] = useState<boolean[]>(() => recipe.ingredients.map(() => true))
  const [listId, setListId] = useState(lists[0]?.id ?? '')
  const effServings = servings > 0 ? servings : recipe.servings
  const factor = recipe.servings > 0 ? effServings / recipe.servings : 1
  const scale = (a: number) => Math.round(a * factor * 1000) / 1000
  const toggle = (i: number) => setSel((s) => s.map((v, j) => (j === i ? !v : v)))
  const count = sel.filter(Boolean).length
  const allOn = count === recipe.ingredients.length

  const add = () => {
    if (!listId) return
    const items = recipe.ingredients
      .filter((_, i) => sel[i])
      .map((ing) => ({
        name: ing.name,
        amount: ing.amount != null ? scale(ing.amount) : undefined,
        unit: ing.unit ?? undefined,
      }))
    if (items.length) onAdd(listId, items)
  }

  return (
    <Sheet
      open
      onClose={onClose}
      title={t('recipes.pickerTitle')}
      width={440}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>{t('common.cancel')}</Button>
          <Button variant="primary" icon="cart" onClick={add} disabled={count === 0 || !listId}>{count} {t('recipes.pickerAdd')}</Button>
        </>
      }
    >
      {lists.length === 0 ? (
        <p className="hb-muted" style={{ margin: 0 }}>{t('recipes.pickerNoList')}</p>
      ) : (
        <>
          {lists.length > 1 && (
            <Field label={t('recipes.pickerTargetList')}>
              <Select value={listId} onChange={setListId}>
                {lists.map((l) => <option key={l.id} value={l.id}>{l.name}</option>)}
              </Select>
            </Field>
          )}
          {factor !== 1 && (
            <p className="hb-muted" style={{ margin: '0 0 4px', fontSize: 13 }}>
              {t('recipes.pickerScaledTo', { n: String(effServings) })}
            </p>
          )}
          <div className="hb-picker-head">
            <span className="hb-muted">{t('recipes.pickerSelected', { n: String(count), total: String(recipe.ingredients.length) })}</span>
            <button className="hb-link" onClick={() => setSel(recipe.ingredients.map(() => !allOn))}>
              {allOn ? t('recipes.pickerNone') : t('recipes.pickerAll')}
            </button>
          </div>
          <div className="hb-picklist">
            {recipe.ingredients.map((ing, i) => (
              <div key={ing.id} className="hb-ingpick" onClick={() => toggle(i)}>
                <Checkbox checked={sel[i]} onChange={() => toggle(i)} />
                <span className="hb-ing__amt">{[ing.amount != null ? fmtAmount(scale(ing.amount)) : null, ing.unit].filter(Boolean).join(' ') || '·'}</span>
                <span className="hb-ingpick__name">{ing.name}</span>
              </div>
            ))}
          </div>
        </>
      )}
    </Sheet>
  )
}

// "Aus URL importieren" — a short, focused dialog (one URL field), so a centered <Modal> per the
// Modal-vs-Sheet guideline. Posts the URL to the backend, which fetches the page server-side and
// extracts the schema.org/Recipe JSON-LD; on success the editor opens pre-filled (the user reviews
// and saves via the normal flow — nothing is persisted by the import itself).
function ImportFromUrlModal({ token, onClose, onLogout, onImported }: {
  token: string
  onClose: () => void
  onLogout: () => void
  onImported: (r: ImportedRecipe) => void
}) {
  const { t } = useTranslation()
  const [url, setUrl] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const submit = async () => {
    const trimmed = url.trim()
    if (!trimmed || busy) return
    setBusy(true)
    setError(null)
    const result = await safeFetch(token, `${API_BASE}/recipes/import`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ url: trimmed }),
    })
    if (!result.ok) {
      setBusy(false)
      setError(t('recipes.importFailed'))
      return
    }
    const { res } = result
    if (res.status === 401) return onLogout()
    if (res.ok) {
      const imported = (await res.json()) as ImportedRecipe
      onImported(imported)
      return
    }
    setBusy(false)
    // 422 = page had no recipe data; everything else = generic failure
    setError(res.status === 422 ? t('recipes.importNoData') : t('recipes.importFailed'))
  }

  return (
    <Modal
      open
      onClose={onClose}
      title={t('recipes.importTitle')}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>{t('common.cancel')}</Button>
          <Button onClick={submit} disabled={busy || !url.trim()}>
            {busy ? t('recipes.importing') : t('recipes.importAction')}
          </Button>
        </>
      }
    >
      <p className="hb-muted" style={{ marginTop: 0 }}>{t('recipes.importHint')}</p>
      <Field label={t('recipes.importUrlLabel')}>
        <TextInput
          autoFocus
          type="url"
          value={url}
          onChange={setUrl}
          onKeyDown={(e) => { if (e.key === 'Enter') submit() }}
          placeholder="https://…"
        />
      </Field>
      {error && <p className="hb-modal-error" role="alert" style={{ marginBottom: 0 }}>{error}</p>}
    </Modal>
  )
}

function RecipeEditor({ draft, setDraft, saving, error, onSave, onCancel }: {
  draft: Draft
  setDraft: (d: Draft) => void
  saving: boolean
  error: string | null
  onSave: () => void
  onCancel: () => void
}) {
  const { t } = useTranslation()
  // Whether the optional section-name fields are shown. Sticky for the editor's lifetime: once
  // sections are in play (an existing recipe already has a named section, or the user clicked
  // "+ Abschnitt"), the name field stays put — clearing a name must not make it vanish mid-edit.
  const [sectionsShown, setSectionsShown] = useState(
    draft.sections.length > 1 || draft.sections.some((s) => s.name.trim() !== ''),
  )

  // Free-text bulk editor for ingredients (paste a list, one per line; "# Name" opens a section).
  // The structured rows stay the source of truth: while in text mode every change is parsed back
  // into draft.sections live, so save + toggling back to the list need no extra reconciliation.
  const [pasteMode, setPasteMode] = useState(false)
  const [ingredientsText, setIngredientsText] = useState('')
  const enterPasteMode = () => { setIngredientsText(serializeSections(draft.sections)); setPasteMode(true) }
  const exitPasteMode = () => {
    // a section the user typed in text mode must reveal its name field back in list mode
    setSectionsShown((shown) => shown || draft.sections.length > 1 || draft.sections.some((s) => s.name.trim() !== ''))
    setPasteMode(false)
  }
  const onIngredientsTextChange = (v: string) => {
    setIngredientsText(v)
    setDraft({ ...draft, sections: parseIngredientsText(v) })
  }

  // Sections own their ingredient rows; mutations are addressed by (section, row) index.
  const setSection = (si: number, patch: Partial<SectionDraft>) =>
    setDraft({ ...draft, sections: draft.sections.map((s, i) => (i === si ? { ...s, ...patch } : s)) })
  const addSection = () => {
    setSectionsShown(true)
    setDraft({ ...draft, sections: [...draft.sections, emptySection()] })
  }
  const removeSection = (si: number) => setDraft({ ...draft, sections: draft.sections.filter((_, i) => i !== si) })

  const setIngredient = (si: number, ii: number, patch: Partial<IngredientDraft>) =>
    setSection(si, { ingredients: draft.sections[si].ingredients.map((ing, i) => (i === ii ? { ...ing, ...patch } : ing)) })
  const addIngredient = (si: number) => setSection(si, { ingredients: [...draft.sections[si].ingredients, emptyIngredient()] })
  const removeIngredient = (si: number, ii: number) =>
    setSection(si, { ingredients: draft.sections[si].ingredients.filter((_, i) => i !== ii) })

  const setStep = (idx: number, value: string) => setDraft({ ...draft, steps: draft.steps.map((s, i) => (i === idx ? value : s)) })
  const addStep = () => setDraft({ ...draft, steps: [...draft.steps, ''] })
  const removeStep = (idx: number) => setDraft({ ...draft, steps: draft.steps.filter((_, i) => i !== idx) })

  // remove control only appears once there is more than one section (can't remove the last)
  const multiSection = draft.sections.length > 1
  const actions = (
    <>
      <Button variant="ghost" onClick={onCancel}>{t('common.cancel')}</Button>
      <Button onClick={onSave} disabled={saving || !draft.title.trim()}>{t('common.save')}</Button>
    </>
  )

  return (
    <div className="hb-page">
      <button className="hb-backlink" onClick={onCancel}>
        <Icon name="chevronLeft" size={17} stroke={2.2} />{t('recipes.backToRecipes')}
      </button>

      <div className="hb-pagehead">
        <div>
          <div className="hb-pagehead__eyebrow">{t('recipes.newRecipeEyebrow')}</div>
          <h1>{draft.id ? t('recipes.editRecipe') : t('recipes.newRecipe')}</h1>
        </div>
        <div className="hb-pagehead__actions">{actions}</div>
      </div>

      <div className="hb-recipe-form">
        <Field label={t('common.titlePlaceholder')}>
          <TextInput autoFocus value={draft.title} onChange={(v) => setDraft({ ...draft, title: v })} placeholder={t('common.titlePlaceholder')} />
        </Field>
        <Field label={t('common.descriptionOptional')}>
          <textarea className="hb-input" rows={2} value={draft.description} placeholder={t('common.descriptionOptional')} onChange={(e) => setDraft({ ...draft, description: e.target.value })} />
        </Field>

        <div className="hb-formgrid">
          <Field label={t('recipes.category')}>
            <Select value={draft.category} onChange={(v) => setDraft({ ...draft, category: v as RecipeCategory })}>
              {buildCategories(t).map((c) => <option key={c.id} value={c.id}>{c.label}</option>)}
            </Select>
          </Field>
          <Field label={t('recipes.servings')}>
            <TextInput type="number" value={draft.servings} onChange={(v) => setDraft({ ...draft, servings: v })} />
          </Field>
          <Field label={t('recipes.prepLabel')}>
            <TextInput type="number" value={draft.prepTimeMinutes} onChange={(v) => setDraft({ ...draft, prepTimeMinutes: v })} />
          </Field>
          <Field label={t('recipes.cookLabel')}>
            <TextInput type="number" value={draft.cookTimeMinutes} onChange={(v) => setDraft({ ...draft, cookTimeMinutes: v })} />
          </Field>
        </div>

        <div>
          <div className="hb-cardhead" style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
            <h3 style={{ fontSize: 15 }}>{t('recipes.ingredients')}</h3>
            <button type="button" className="hb-link" onClick={pasteMode ? exitPasteMode : enterPasteMode}>
              {pasteMode ? t('recipes.editAsList') : t('recipes.editAsText')}
            </button>
          </div>
          {pasteMode ? (
            <>
              <textarea
                className="hb-input hb-mono-area"
                rows={8}
                value={ingredientsText}
                placeholder={t('recipes.ingredientsTextPlaceholder')}
                onChange={(e) => onIngredientsTextChange(e.target.value)}
              />
              <p className="hb-muted" style={{ fontSize: 13, marginTop: 6 }}>{t('recipes.ingredientsTextHint')}</p>
            </>
          ) : (
            <>
              <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
                {draft.sections.map((section, si) => (
                  <div key={si} className={multiSection ? 'hb-ingsec' : undefined}>
                    {/* Section name only appears once sections are in play — a brand-new single
                        section stays a plain flat list; once shown it sticks (see sectionsShown). */}
                    {sectionsShown && (
                      <div className="hb-ingsec__head">
                        <input
                          className="hb-input hb-ingsec__name"
                          placeholder={t('recipes.sectionName')}
                          value={section.name}
                          onChange={(e) => setSection(si, { name: e.target.value })}
                        />
                        {multiSection && <IconButton icon="x" label={t('recipes.removeSection')} onClick={() => removeSection(si)} />}
                      </div>
                    )}
                    <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
                      {section.ingredients.map((ing, ii) => (
                        <div key={ii} className="hb-editrow">
                          <input className="hb-input" placeholder={t('recipes.ingredientName')} value={ing.name} onChange={(e) => setIngredient(si, ii, { name: e.target.value })} />
                          <input className="hb-input hb-input--amt" placeholder={t('recipes.amount')} value={ing.amount} onChange={(e) => setIngredient(si, ii, { amount: e.target.value })} />
                          <input className="hb-input hb-input--unit" placeholder={t('recipes.unitAbbr')} value={ing.unit} onChange={(e) => setIngredient(si, ii, { unit: e.target.value })} />
                          <IconButton icon="x" label={t('recipes.removeIngredient')} onClick={() => removeIngredient(si, ii)} />
                        </div>
                      ))}
                    </div>
                    {/* add-row below the list so it stays reachable as rows grow (issue #123 part 2) */}
                    <button className="hb-link hb-addrow" onClick={() => addIngredient(si)}>{t('recipes.addIngredient')}</button>
                  </div>
                ))}
              </div>
              <button className="hb-link hb-addrow" onClick={addSection}>{t('recipes.addSection')}</button>
            </>
          )}
        </div>

        <div>
          <div className="hb-cardhead">
            <h3 style={{ fontSize: 15 }}>{t('recipes.preparation')}</h3>
          </div>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
            {draft.steps.map((step, idx) => (
              <div key={idx} className="hb-editrow" style={{ alignItems: 'flex-start' }}>
                <span className="hb-step__n" style={{ marginTop: 8 }}>{idx + 1}</span>
                <textarea className="hb-input" rows={2} placeholder={t('recipes.stepPlaceholder')} value={step} onChange={(e) => setStep(idx, e.target.value)} />
                <IconButton icon="x" label={t('recipes.removeStep')} onClick={() => removeStep(idx)} />
              </div>
            ))}
          </div>
          <button className="hb-link hb-addrow" onClick={addStep}>{t('recipes.addStep')}</button>
        </div>

        {error && <p className="hb-modal-error">{error}</p>}

        {/* repeat the actions at the bottom so a long form doesn't force a scroll back up to save */}
        <div className="hb-pagehead__actions hb-recipe-form__foot">{actions}</div>
      </div>
    </div>
  )
}
