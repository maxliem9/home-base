import { useState, useEffect, useCallback } from 'react'
import { API_BASE, authFetch, withWsToken } from '../api'
import { t } from '../i18n'
import { Recipe, RecipeCategory, ShoppingItem, ShoppingList } from '../types'
import { useWebSocket } from '../hooks/useWebSocket'
import { Icon } from '../ui/Icon'
import { Badge, Button, Card, Checkbox, EmptyState, Field, IconButton, Modal, PageHead, Select, TextInput } from '../ui/primitives'

const WS_SCHEME = window.location.protocol === 'https:' ? 'wss' : 'ws'
const WS_URL = import.meta.env.VITE_WS_URL_RECIPES ?? `${WS_SCHEME}://${window.location.host}/api/v1/ws/recipes`

const CATEGORIES: { id: RecipeCategory; label: string }[] = [
  { id: 'BREAKFAST', label: t.recipes.categories.BREAKFAST },
  { id: 'LUNCH', label: t.recipes.categories.LUNCH },
  { id: 'DINNER', label: t.recipes.categories.DINNER },
  { id: 'SNACK', label: t.recipes.categories.SNACK },
  { id: 'DESSERT', label: t.recipes.categories.DESSERT },
  { id: 'DRINK', label: t.recipes.categories.DRINK },
]

const categoryLabel = (c: RecipeCategory) => CATEGORIES.find((x) => x.id === c)?.label ?? c
const totalTime = (r: Recipe) => (r.prepTimeMinutes ?? 0) + (r.cookTimeMinutes ?? 0)
const fmtAmount = (n: number) => String(Math.round(n * 100) / 100)

// deterministic warm hue (≈20–80) per recipe for the photo placeholder band
const recipeHue = (id: string) => {
  let h = 0
  for (let i = 0; i < id.length; i++) h = (h * 31 + id.charCodeAt(i)) % 60
  return h + 20
}

interface IngredientDraft { name: string; amount: string; unit: string }
interface Draft {
  id?: string
  title: string
  description: string
  servings: string
  prepTimeMinutes: string
  cookTimeMinutes: string
  category: RecipeCategory
  ingredients: IngredientDraft[]
  steps: string[]
}

const emptyDraft = (): Draft => ({
  title: '', description: '', servings: '2', prepTimeMinutes: '', cookTimeMinutes: '',
  category: 'DINNER', ingredients: [{ name: '', amount: '', unit: '' }], steps: [''],
})
const draftFromRecipe = (r: Recipe): Draft => ({
  id: r.id,
  title: r.title,
  description: r.description ?? '',
  servings: String(r.servings),
  prepTimeMinutes: r.prepTimeMinutes != null ? String(r.prepTimeMinutes) : '',
  cookTimeMinutes: r.cookTimeMinutes != null ? String(r.cookTimeMinutes) : '',
  category: r.category,
  ingredients: r.ingredients.length
    ? r.ingredients.map((i) => ({ name: i.name, amount: i.amount != null ? String(i.amount) : '', unit: i.unit ?? '' }))
    : [{ name: '', amount: '', unit: '' }],
  steps: r.steps.length ? r.steps.map((s) => s.description) : [''],
})

interface RecipesViewProps {
  token: string
  onLogout: () => void
}

export function RecipesView({ token, onLogout }: RecipesViewProps) {
  const [recipes, setRecipes] = useState<Recipe[]>([])
  const [shoppingLists, setShoppingLists] = useState<ShoppingList[]>([])
  const [loading, setLoading] = useState(true)
  const [filter, setFilter] = useState<RecipeCategory | 'ALL'>('ALL')
  const [selected, setSelected] = useState<Recipe | null>(null)
  const [draft, setDraft] = useState<Draft | null>(null)
  const [picking, setPicking] = useState<Recipe | null>(null)
  const [saving, setSaving] = useState(false)
  const [toast, setToast] = useState<string | null>(null)

  const fetchRecipes = useCallback(async () => {
    try {
      const res = await authFetch(token, `${API_BASE}/recipes`)
      if (res.status === 401) {
        onLogout()
        return
      }
      if (!res.ok) return
      setRecipes(await res.json())
    } finally {
      setLoading(false)
    }
  }, [onLogout, token])

  const fetchShoppingLists = useCallback(async () => {
    const res = await authFetch(token, `${API_BASE}/shopping/lists`)
    if (res.ok) setShoppingLists(await res.json())
  }, [token])

  useEffect(() => { fetchRecipes() }, [fetchRecipes])
  useEffect(() => { fetchShoppingLists() }, [fetchShoppingLists])

  useWebSocket(withWsToken(WS_URL, token), (raw) => {
    try {
      const msg = JSON.parse(raw)
      if (!msg.payload) return
      if (msg.type === 'RECIPE_CREATED') {
        setRecipes((prev) => (prev.some((r) => r.id === msg.payload.id) ? prev : [msg.payload, ...prev]))
      } else if (msg.type === 'RECIPE_UPDATED') {
        setRecipes((prev) =>
          prev.some((r) => r.id === msg.payload.id) ? prev.map((r) => (r.id === msg.payload.id ? msg.payload : r)) : [msg.payload, ...prev],
        )
        setSelected((cur) => (cur && cur.id === msg.payload.id ? msg.payload : cur))
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
    try {
      const body = JSON.stringify({
        title: draft.title.trim(),
        description: draft.description.trim() || undefined,
        servings: parseInt(draft.servings, 10) || 1,
        prepTimeMinutes: draft.prepTimeMinutes ? parseInt(draft.prepTimeMinutes, 10) : undefined,
        cookTimeMinutes: draft.cookTimeMinutes ? parseInt(draft.cookTimeMinutes, 10) : undefined,
        category: draft.category,
        ingredients: draft.ingredients
          .filter((i) => i.name.trim())
          .map((i) => ({
            name: i.name.trim(),
            amount: i.amount.trim() ? parseFloat(i.amount.replace(',', '.')) : undefined,
            unit: i.unit.trim() || undefined,
          })),
        steps: draft.steps.filter((s) => s.trim()).map((s) => ({ description: s.trim() })),
      })
      const url = draft.id ? `${API_BASE}/recipes/${draft.id}` : `${API_BASE}/recipes`
      const res = await authFetch(token, url, {
        method: draft.id ? 'PUT' : 'POST',
        headers: { 'Content-Type': 'application/json' },
        body,
      })
      if (res.ok) {
        const saved: Recipe = await res.json()
        setRecipes((prev) => (prev.some((r) => r.id === saved.id) ? prev.map((r) => (r.id === saved.id ? saved : r)) : [saved, ...prev]))
        setDraft(null)
        setSelected(saved)
      }
    } finally {
      setSaving(false)
    }
  }

  const handleDelete = async (id: string) => {
    setRecipes((prev) => prev.filter((r) => r.id !== id))
    setDraft(null)
    setSelected(null)
    await authFetch(token, `${API_BASE}/recipes/${id}`, { method: 'DELETE' })
  }

  // add the chosen ingredients to a shopping list (deduped against that list)
  const addToShopping = async (listId: string, chosen: Recipe['ingredients']) => {
    const res = await authFetch(token, `${API_BASE}/shopping`)
    const existing: Set<string> = res.ok
      ? new Set((await res.json() as ShoppingItem[]).filter((i) => i.listId === listId).map((i) => i.name.toLowerCase()))
      : new Set()
    const toAdd = chosen.filter((i) => i.name.trim() && !existing.has(i.name.toLowerCase()))
    await Promise.all(
      toAdd.map((i) =>
        authFetch(token, `${API_BASE}/shopping`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ name: i.name, listId }),
        }),
      ),
    )
    setPicking(null)
    const n = toAdd.length
    setToast(n ? `${n} ${n === 1 ? t.recipes.addedOne : t.recipes.addedToList}` : t.recipes.nothingToAdd)
    setTimeout(() => setToast(null), 2600)
  }

  // keep the open recipe in sync with the store (e.g. after WS edits)
  const current = selected ? recipes.find((r) => r.id === selected.id) ?? selected : null
  const visible = filter === 'ALL' ? recipes : recipes.filter((r) => r.category === filter)

  // ---- detail page (full page, not a modal) ----
  if (current && !draft) {
    return (
      <>
        <RecipeDetail
          recipe={current}
          onBack={() => setSelected(null)}
          onEdit={() => setDraft(draftFromRecipe(current))}
          onDelete={() => handleDelete(current.id)}
          onAddToShopping={() => setPicking(current)}
        />
        {picking && (
          <IngredientPicker
            recipe={picking}
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
      </>
    )
  }

  return (
    <div className="hb-page">
      <PageHead
        eyebrow={`${recipes.length} ${t.recipes.count}`}
        title={t.recipes.title}
        actions={<Button icon="plus" onClick={() => setDraft(emptyDraft())}>{t.recipes.newRecipe}</Button>}
      />

      <div className="hb-pickrow" style={{ marginBottom: 24 }}>
        <button className={`hb-pick${filter === 'ALL' ? ' is-active' : ''}`} onClick={() => setFilter('ALL')}>{t.recipes.filterAll}</button>
        {CATEGORIES.map((c) => (
          <button key={c.id} className={`hb-pick${filter === c.id ? ' is-active' : ''}`} onClick={() => setFilter(c.id)}>{c.label}</button>
        ))}
      </div>

      {loading ? (
        <p className="hb-muted" style={{ textAlign: 'center', padding: 24 }}>{t.common.loading}</p>
      ) : visible.length === 0 ? (
        <Card className="hb-card--pad">
          <EmptyState icon="chef" title={filter === 'ALL' ? t.recipes.emptyAll : t.recipes.emptyCategory} hint={filter === 'ALL' ? t.recipes.emptyHint : undefined} />
        </Card>
      ) : (
        <div className="hb-recipe-grid">
          {visible.map((recipe) => (
            <Card key={recipe.id} className="hb-recipecard hb-card--hover" onClick={() => setSelected(recipe)}>
              <div className="hb-recipecard__img" style={{ ['--rh' as string]: recipeHue(recipe.id) }}>
                <Icon name="chef" size={26} stroke={1.6} />
                <span className="hb-recipecard__ph">{t.recipes.photoSoon}</span>
                <Badge tone="clay">{categoryLabel(recipe.category)}</Badge>
              </div>
              <div className="hb-recipecard__body">
                <div className="hb-recipecard__title">{recipe.title}</div>
                {recipe.description && <p className="hb-recipecard__desc">{recipe.description}</p>}
                <div className="hb-recipecard__meta">
                  {totalTime(recipe) > 0 && (
                    <span style={{ display: 'inline-flex', alignItems: 'center', gap: 5 }}>
                      <Icon name="clock" size={15} stroke={2} /> {totalTime(recipe)} {t.recipes.minutesAbbr}
                    </span>
                  )}
                  <span style={{ display: 'inline-flex', alignItems: 'center', gap: 5 }}>
                    <Icon name="users" size={15} stroke={2} /> {recipe.servings} {t.recipes.servingsAbbr}
                  </span>
                </div>
              </div>
            </Card>
          ))}
        </div>
      )}

      {draft && <RecipeEditor draft={draft} setDraft={setDraft} saving={saving} onSave={handleSave} onCancel={() => setDraft(null)} />}

      {toast && (
        <div className="hb-toast">
          <Icon name="check" size={18} stroke={2.4} style={{ color: 'var(--accent)' }} />
          {toast}
        </div>
      )}
    </div>
  )
}

function RecipeDetail({ recipe, onBack, onEdit, onDelete, onAddToShopping }: {
  recipe: Recipe
  onBack: () => void
  onEdit: () => void
  onDelete: () => void
  onAddToShopping: () => void
}) {
  const [servings, setServings] = useState(recipe.servings)
  const factor = recipe.servings > 0 ? servings / recipe.servings : 1
  const total = (recipe.prepTimeMinutes ?? 0) + (recipe.cookTimeMinutes ?? 0)

  return (
    <div className="hb-page">
      <button className="hb-backlink" onClick={onBack}>
        <Icon name="chevronLeft" size={17} stroke={2.2} />{t.recipes.backToRecipes}
      </button>

      <div className="hb-pagehead">
        <div>
          <div className="hb-pagehead__eyebrow">{categoryLabel(recipe.category)}</div>
          <h1>{recipe.title}</h1>
        </div>
        <div className="hb-pagehead__actions">
          <Button variant="danger" icon="trash" onClick={onDelete}>{t.common.delete}</Button>
          <Button variant="ghost" icon="edit" onClick={onEdit}>{t.recipes.edit}</Button>
          <Button variant="soft" icon="cart" onClick={onAddToShopping}>{t.recipes.addToList}</Button>
        </div>
      </div>

      {recipe.description && (
        <p className="hb-muted" style={{ margin: '0 0 18px', fontSize: 16, maxWidth: 640 }}>{recipe.description}</p>
      )}

      <div className="hb-recipe-facts" style={{ maxWidth: 560, marginBottom: 26 }}>
        <div className="hb-servings-step hb-fact" style={{ flexDirection: 'row', alignItems: 'center' }}>
          <div style={{ flex: 1 }}>
            <div className="hb-fact__v">{servings}</div>
            <div className="hb-fact__l">{t.recipes.servings}</div>
          </div>
          <div style={{ display: 'flex', gap: 4 }}>
            <IconButton icon="minus" label={t.recipes.lessServings} onClick={() => setServings((s) => Math.max(1, s - 1))} />
            <IconButton icon="plus" label={t.recipes.moreServings} onClick={() => setServings((s) => s + 1)} />
          </div>
        </div>
        {recipe.prepTimeMinutes != null && (
          <div className="hb-fact"><div className="hb-fact__v">{recipe.prepTimeMinutes}</div><div className="hb-fact__l">{t.recipes.prep} ({t.recipes.minutesAbbr})</div></div>
        )}
        {recipe.cookTimeMinutes != null && (
          <div className="hb-fact"><div className="hb-fact__v">{recipe.cookTimeMinutes}</div><div className="hb-fact__l">{t.recipes.cook} ({t.recipes.minutesAbbr})</div></div>
        )}
        {total > 0 && (
          <div className="hb-fact"><div className="hb-fact__v">{total}</div><div className="hb-fact__l">{t.recipes.totalTime} ({t.recipes.minutesAbbr})</div></div>
        )}
      </div>

      <div className="hb-recipe-body">
        {recipe.ingredients.length > 0 && (
          <div>
            <div className="hb-sectionlabel">{t.recipes.ingredients}</div>
            <div className="hb-ingredients">
              {recipe.ingredients.map((ing) => (
                <div key={ing.id} className="hb-ing">
                  <span className="hb-ing__amt">{ing.amount != null ? `${fmtAmount(ing.amount * factor)} ${ing.unit ?? ''}`.trim() : ''}</span>
                  <span>{ing.name}</span>
                </div>
              ))}
            </div>
          </div>
        )}
        {recipe.steps.length > 0 && (
          <div>
            <div className="hb-sectionlabel">{t.recipes.preparation}</div>
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

function IngredientPicker({ recipe, lists, onClose, onAdd }: {
  recipe: Recipe
  lists: ShoppingList[]
  onClose: () => void
  onAdd: (listId: string, chosen: Recipe['ingredients']) => void
}) {
  const [sel, setSel] = useState<boolean[]>(() => recipe.ingredients.map(() => true))
  const [listId, setListId] = useState(lists[0]?.id ?? '')
  const toggle = (i: number) => setSel((s) => s.map((v, j) => (j === i ? !v : v)))
  const count = sel.filter(Boolean).length
  const allOn = count === recipe.ingredients.length

  const add = () => {
    if (!listId) return
    const chosen = recipe.ingredients.filter((_, i) => sel[i])
    if (chosen.length) onAdd(listId, chosen)
  }

  return (
    <Modal
      open
      onClose={onClose}
      title={t.recipes.pickerTitle}
      width={440}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>{t.common.cancel}</Button>
          <Button variant="primary" icon="cart" onClick={add} disabled={count === 0 || !listId}>{count} {t.recipes.pickerAdd}</Button>
        </>
      }
    >
      {lists.length === 0 ? (
        <p className="hb-muted" style={{ margin: 0 }}>{t.recipes.pickerNoList}</p>
      ) : (
        <>
          {lists.length > 1 && (
            <Field label={t.recipes.pickerTargetList}>
              <Select value={listId} onChange={setListId}>
                {lists.map((l) => <option key={l.id} value={l.id}>{l.name}</option>)}
              </Select>
            </Field>
          )}
          <div className="hb-picker-head">
            <span className="hb-muted">{count} von {recipe.ingredients.length} {t.recipes.pickerSelected}</span>
            <button className="hb-link" onClick={() => setSel(recipe.ingredients.map(() => !allOn))}>
              {allOn ? t.recipes.pickerNone : t.recipes.pickerAll}
            </button>
          </div>
          <div className="hb-picklist">
            {recipe.ingredients.map((ing, i) => (
              <div key={ing.id} className="hb-ingpick" onClick={() => toggle(i)}>
                <Checkbox checked={sel[i]} onChange={() => toggle(i)} />
                <span className="hb-ing__amt">{[ing.amount != null ? fmtAmount(ing.amount) : null, ing.unit].filter(Boolean).join(' ') || '·'}</span>
                <span className="hb-ingpick__name">{ing.name}</span>
              </div>
            ))}
          </div>
        </>
      )}
    </Modal>
  )
}

function RecipeEditor({ draft, setDraft, saving, onSave, onCancel }: {
  draft: Draft
  setDraft: (d: Draft) => void
  saving: boolean
  onSave: () => void
  onCancel: () => void
}) {
  const setIngredient = (idx: number, patch: Partial<IngredientDraft>) =>
    setDraft({ ...draft, ingredients: draft.ingredients.map((ing, i) => (i === idx ? { ...ing, ...patch } : ing)) })
  const addIngredient = () => setDraft({ ...draft, ingredients: [...draft.ingredients, { name: '', amount: '', unit: '' }] })
  const removeIngredient = (idx: number) => setDraft({ ...draft, ingredients: draft.ingredients.filter((_, i) => i !== idx) })

  const setStep = (idx: number, value: string) => setDraft({ ...draft, steps: draft.steps.map((s, i) => (i === idx ? value : s)) })
  const addStep = () => setDraft({ ...draft, steps: [...draft.steps, ''] })
  const removeStep = (idx: number) => setDraft({ ...draft, steps: draft.steps.filter((_, i) => i !== idx) })

  return (
    <Modal
      open
      onClose={onCancel}
      title={draft.id ? t.recipes.editRecipe : t.recipes.newRecipe}
      width={620}
      footer={
        <>
          <Button variant="ghost" onClick={onCancel}>{t.common.cancel}</Button>
          <Button onClick={onSave} disabled={saving || !draft.title.trim()}>{t.common.save}</Button>
        </>
      }
    >
      <Field label={t.common.titlePlaceholder}>
        <TextInput autoFocus value={draft.title} onChange={(v) => setDraft({ ...draft, title: v })} placeholder={t.common.titlePlaceholder} />
      </Field>
      <Field label={t.common.descriptionOptional}>
        <textarea className="hb-input" rows={2} value={draft.description} placeholder={t.common.descriptionOptional} onChange={(e) => setDraft({ ...draft, description: e.target.value })} />
      </Field>

      <div className="hb-formgrid">
        <Field label={t.recipes.category}>
          <Select value={draft.category} onChange={(v) => setDraft({ ...draft, category: v as RecipeCategory })}>
            {CATEGORIES.map((c) => <option key={c.id} value={c.id}>{c.label}</option>)}
          </Select>
        </Field>
        <Field label={t.recipes.servings}>
          <TextInput type="number" value={draft.servings} onChange={(v) => setDraft({ ...draft, servings: v })} />
        </Field>
        <Field label={t.recipes.prepLabel}>
          <TextInput type="number" value={draft.prepTimeMinutes} onChange={(v) => setDraft({ ...draft, prepTimeMinutes: v })} />
        </Field>
        <Field label={t.recipes.cookLabel}>
          <TextInput type="number" value={draft.cookTimeMinutes} onChange={(v) => setDraft({ ...draft, cookTimeMinutes: v })} />
        </Field>
      </div>

      <div>
        <div className="hb-cardhead">
          <h3 style={{ fontSize: 15 }}>{t.recipes.ingredients}</h3>
          <button className="hb-link" onClick={addIngredient}>{t.recipes.addIngredient}</button>
        </div>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
          {draft.ingredients.map((ing, idx) => (
            <div key={idx} className="hb-editrow">
              <input className="hb-input" placeholder={t.recipes.ingredientName} value={ing.name} onChange={(e) => setIngredient(idx, { name: e.target.value })} />
              <input className="hb-input hb-input--amt" placeholder={t.recipes.amount} value={ing.amount} onChange={(e) => setIngredient(idx, { amount: e.target.value })} />
              <input className="hb-input hb-input--unit" placeholder={t.recipes.unitAbbr} value={ing.unit} onChange={(e) => setIngredient(idx, { unit: e.target.value })} />
              <IconButton icon="x" label={t.recipes.removeIngredient} onClick={() => removeIngredient(idx)} />
            </div>
          ))}
        </div>
      </div>

      <div>
        <div className="hb-cardhead">
          <h3 style={{ fontSize: 15 }}>{t.recipes.preparation}</h3>
          <button className="hb-link" onClick={addStep}>{t.recipes.addStep}</button>
        </div>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
          {draft.steps.map((step, idx) => (
            <div key={idx} className="hb-editrow" style={{ alignItems: 'flex-start' }}>
              <span className="hb-step__n" style={{ marginTop: 8 }}>{idx + 1}</span>
              <textarea className="hb-input" rows={2} placeholder={t.recipes.stepPlaceholder} value={step} onChange={(e) => setStep(idx, e.target.value)} />
              <IconButton icon="x" label={t.recipes.removeStep} onClick={() => removeStep(idx)} />
            </div>
          ))}
        </div>
      </div>
    </Modal>
  )
}
