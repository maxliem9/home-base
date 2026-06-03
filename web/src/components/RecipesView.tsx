import { useState, useEffect, useCallback } from 'react'
import { API_BASE, authFetch, withWsToken } from '../api'
import { Recipe, RecipeCategory } from '../types'
import { useWebSocket } from '../hooks/useWebSocket'

const WS_URL = import.meta.env.VITE_WS_URL_RECIPES ?? `ws://${window.location.host}/api/v1/ws/recipes`

const CATEGORIES: { id: RecipeCategory; label: string; icon: string }[] = [
  { id: 'BREAKFAST', label: 'Frühstück', icon: '🥐' },
  { id: 'LUNCH', label: 'Mittag', icon: '🍽️' },
  { id: 'DINNER', label: 'Abend', icon: '🍝' },
  { id: 'SNACK', label: 'Snack', icon: '🥨' },
  { id: 'DESSERT', label: 'Dessert', icon: '🍰' },
  { id: 'DRINK', label: 'Getränk', icon: '🍹' },
]

const categoryLabel = (c: RecipeCategory) => CATEGORIES.find((x) => x.id === c)?.label ?? c
const categoryIcon = (c: RecipeCategory) => CATEGORIES.find((x) => x.id === c)?.icon ?? '🍴'

const totalTime = (r: Recipe) => (r.prepTimeMinutes ?? 0) + (r.cookTimeMinutes ?? 0)

// round to at most 2 decimals; String() already drops trailing zeros
const fmtAmount = (n: number) => String(Math.round(n * 100) / 100)

interface IngredientDraft {
  name: string
  amount: string
  unit: string
}

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
  title: '',
  description: '',
  servings: '2',
  prepTimeMinutes: '',
  cookTimeMinutes: '',
  category: 'DINNER',
  ingredients: [{ name: '', amount: '', unit: '' }],
  steps: [''],
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
  const [loading, setLoading] = useState(true)
  const [filter, setFilter] = useState<RecipeCategory | 'ALL'>('ALL')
  const [selected, setSelected] = useState<Recipe | null>(null)
  const [draft, setDraft] = useState<Draft | null>(null)
  const [saving, setSaving] = useState(false)

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

  useEffect(() => { fetchRecipes() }, [fetchRecipes])

  useWebSocket(withWsToken(WS_URL, token), (raw) => {
    try {
      const msg = JSON.parse(raw)
      if (!msg.payload) return
      if (msg.type === 'RECIPE_CREATED') {
        setRecipes((prev) => (prev.some((r) => r.id === msg.payload.id) ? prev : [msg.payload, ...prev]))
      } else if (msg.type === 'RECIPE_UPDATED') {
        setRecipes((prev) =>
          prev.some((r) => r.id === msg.payload.id)
            ? prev.map((r) => (r.id === msg.payload.id ? msg.payload : r))
            : [msg.payload, ...prev],
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
        setRecipes((prev) =>
          prev.some((r) => r.id === saved.id) ? prev.map((r) => (r.id === saved.id ? saved : r)) : [saved, ...prev],
        )
        setDraft(null)
        setSelected((cur) => (cur && cur.id === saved.id ? saved : cur))
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

  const visible = filter === 'ALL' ? recipes : recipes.filter((r) => r.category === filter)

  return (
    <div className="min-h-screen bg-gray-50 flex flex-col">
      <header className="bg-white shadow-sm px-4 py-3">
        <div className="flex items-center justify-between gap-3">
          <h1 className="text-xl font-semibold text-gray-800 truncate">HomeBase — Rezepte</h1>
          <button onClick={onLogout} className="text-sm text-gray-500 hover:text-gray-800">
            Abmelden
          </button>
        </div>
        <div className="mt-2 flex gap-2 overflow-x-auto pb-1">
          <FilterChip active={filter === 'ALL'} onClick={() => setFilter('ALL')} label="Alle" />
          {CATEGORIES.map((c) => (
            <FilterChip
              key={c.id}
              active={filter === c.id}
              onClick={() => setFilter(c.id)}
              label={`${c.icon} ${c.label}`}
            />
          ))}
        </div>
      </header>

      <main className="flex-1 px-4 py-4 max-w-2xl mx-auto w-full">
        {loading ? (
          <p className="text-gray-400 text-center mt-10">Lädt…</p>
        ) : visible.length === 0 ? (
          <div className="text-center mt-20">
            <p className="text-gray-400 text-lg">{filter === 'ALL' ? 'Noch keine Rezepte' : 'Keine Rezepte in dieser Kategorie'}</p>
            {filter === 'ALL' && <p className="text-gray-300 text-sm mt-1">Erstelle ein Rezept mit +</p>}
          </div>
        ) : (
          <ul className="grid grid-cols-1 sm:grid-cols-2 gap-3">
            {visible.map((recipe) => (
              <li
                key={recipe.id}
                onClick={() => setSelected(recipe)}
                className="bg-white rounded-lg shadow-sm px-4 py-3 cursor-pointer hover:shadow transition"
              >
                <div className="flex items-start gap-2">
                  <span className="text-2xl shrink-0">{categoryIcon(recipe.category)}</span>
                  <div className="min-w-0 flex-1">
                    <h2 className="font-medium text-gray-800 truncate">{recipe.title}</h2>
                    <p className="text-xs text-gray-400 mt-0.5">{categoryLabel(recipe.category)}</p>
                  </div>
                </div>
                <div className="flex items-center gap-3 mt-2 text-xs text-gray-500">
                  {totalTime(recipe) > 0 && <span>⏱️ {totalTime(recipe)} Min</span>}
                  <span>🍽️ {recipe.servings} Port.</span>
                </div>
              </li>
            ))}
          </ul>
        )}
      </main>

      {/* FAB */}
      <button
        onClick={() => setDraft(emptyDraft())}
        className="fixed bottom-20 right-6 w-14 h-14 rounded-full bg-indigo-600 text-white text-3xl shadow-lg hover:bg-indigo-700 active:scale-95 transition flex items-center justify-center"
        aria-label="Neues Rezept"
      >
        +
      </button>

      {selected && !draft && (
        <RecipeDetail
          recipe={selected}
          onClose={() => setSelected(null)}
          onEdit={() => setDraft(draftFromRecipe(selected))}
          onDelete={() => handleDelete(selected.id)}
        />
      )}

      {draft && (
        <RecipeEditor
          draft={draft}
          setDraft={setDraft}
          saving={saving}
          onSave={handleSave}
          onCancel={() => setDraft(null)}
        />
      )}
    </div>
  )
}

function FilterChip({ active, onClick, label }: { active: boolean; onClick: () => void; label: string }) {
  return (
    <button
      onClick={onClick}
      className={`shrink-0 text-sm px-3 py-1 rounded-full border transition ${
        active ? 'bg-indigo-600 text-white border-indigo-600' : 'bg-white text-gray-600 border-gray-300 hover:bg-gray-50'
      }`}
    >
      {label}
    </button>
  )
}

function RecipeDetail({
  recipe,
  onClose,
  onEdit,
  onDelete,
}: {
  recipe: Recipe
  onClose: () => void
  onEdit: () => void
  onDelete: () => void
}) {
  const [servings, setServings] = useState(recipe.servings)
  const factor = recipe.servings > 0 ? servings / recipe.servings : 1

  return (
    <div className="fixed inset-0 bg-black/40 flex items-end sm:items-center justify-center p-4 z-50">
      <div className="bg-white rounded-2xl w-full max-w-md p-5 shadow-xl max-h-[88vh] overflow-y-auto">
        <div className="flex items-start gap-2">
          <span className="text-3xl">{categoryIcon(recipe.category)}</span>
          <div className="flex-1 min-w-0">
            <h2 className="text-lg font-semibold text-gray-800">{recipe.title}</h2>
            <p className="text-xs text-gray-400">{categoryLabel(recipe.category)}</p>
          </div>
          <button onClick={onClose} className="text-gray-400 hover:text-gray-700 text-xl leading-none">✕</button>
        </div>

        {recipe.description && <p className="text-sm text-gray-600 mt-3 whitespace-pre-wrap">{recipe.description}</p>}

        <div className="flex flex-wrap gap-3 mt-3 text-xs text-gray-500">
          {recipe.prepTimeMinutes != null && <span>Vorbereitung: {recipe.prepTimeMinutes} Min</span>}
          {recipe.cookTimeMinutes != null && <span>Kochzeit: {recipe.cookTimeMinutes} Min</span>}
        </div>

        {/* Servings stepper — scales the amounts live */}
        <div className="flex items-center gap-3 mt-4 bg-gray-50 rounded-lg px-3 py-2">
          <span className="text-sm text-gray-600 flex-1">Portionen</span>
          <button
            onClick={() => setServings((s) => Math.max(1, s - 1))}
            className="w-8 h-8 rounded-full bg-white border border-gray-300 text-lg leading-none hover:bg-gray-100"
            aria-label="Weniger Portionen"
          >
            −
          </button>
          <span className="w-8 text-center font-medium text-gray-800">{servings}</span>
          <button
            onClick={() => setServings((s) => s + 1)}
            className="w-8 h-8 rounded-full bg-white border border-gray-300 text-lg leading-none hover:bg-gray-100"
            aria-label="Mehr Portionen"
          >
            +
          </button>
        </div>

        {recipe.ingredients.length > 0 && (
          <div className="mt-4">
            <h3 className="text-sm font-semibold text-gray-700 mb-2">Zutaten</h3>
            <ul className="space-y-1">
              {recipe.ingredients.map((ing) => (
                <li key={ing.id} className="text-sm text-gray-700 flex justify-between gap-2">
                  <span>{ing.name}</span>
                  {ing.amount != null && (
                    <span className="text-gray-500 shrink-0">
                      {fmtAmount(ing.amount * factor)} {ing.unit ?? ''}
                    </span>
                  )}
                </li>
              ))}
            </ul>
          </div>
        )}

        {recipe.steps.length > 0 && (
          <div className="mt-4">
            <h3 className="text-sm font-semibold text-gray-700 mb-2">Zubereitung</h3>
            <ol className="space-y-2">
              {recipe.steps.map((step) => (
                <li key={step.id} className="text-sm text-gray-700 flex gap-2">
                  <span className="shrink-0 w-6 h-6 rounded-full bg-indigo-100 text-indigo-600 text-xs flex items-center justify-center font-medium">
                    {step.stepNumber}
                  </span>
                  <span className="whitespace-pre-wrap">{step.description}</span>
                </li>
              ))}
            </ol>
          </div>
        )}

        <div className="flex justify-between items-center mt-6">
          <button onClick={onDelete} className="px-3 py-2 rounded-lg text-red-500 hover:bg-red-50">
            Löschen
          </button>
          <button onClick={onEdit} className="px-4 py-2 rounded-lg bg-indigo-600 text-white hover:bg-indigo-700">
            Bearbeiten
          </button>
        </div>
      </div>
    </div>
  )
}

function RecipeEditor({
  draft,
  setDraft,
  saving,
  onSave,
  onCancel,
}: {
  draft: Draft
  setDraft: (d: Draft) => void
  saving: boolean
  onSave: () => void
  onCancel: () => void
}) {
  const inputCls = 'w-full border border-gray-300 rounded-lg px-3 py-2 text-gray-800 focus:outline-none focus:ring-2 focus:ring-indigo-500'

  const setIngredient = (idx: number, patch: Partial<IngredientDraft>) =>
    setDraft({ ...draft, ingredients: draft.ingredients.map((ing, i) => (i === idx ? { ...ing, ...patch } : ing)) })
  const addIngredient = () => setDraft({ ...draft, ingredients: [...draft.ingredients, { name: '', amount: '', unit: '' }] })
  const removeIngredient = (idx: number) =>
    setDraft({ ...draft, ingredients: draft.ingredients.filter((_, i) => i !== idx) })

  const setStep = (idx: number, value: string) =>
    setDraft({ ...draft, steps: draft.steps.map((s, i) => (i === idx ? value : s)) })
  const addStep = () => setDraft({ ...draft, steps: [...draft.steps, ''] })
  const removeStep = (idx: number) => setDraft({ ...draft, steps: draft.steps.filter((_, i) => i !== idx) })

  return (
    <div className="fixed inset-0 bg-black/40 flex items-end sm:items-center justify-center p-4 z-50">
      <div className="bg-white rounded-2xl w-full max-w-md p-5 shadow-xl max-h-[88vh] overflow-y-auto">
        <h2 className="text-lg font-semibold text-gray-800 mb-3">{draft.id ? 'Rezept bearbeiten' : 'Neues Rezept'}</h2>

        <input
          autoFocus
          type="text"
          placeholder="Titel…"
          value={draft.title}
          onChange={(e) => setDraft({ ...draft, title: e.target.value })}
          className={inputCls}
        />
        <textarea
          placeholder="Beschreibung (optional)…"
          value={draft.description}
          onChange={(e) => setDraft({ ...draft, description: e.target.value })}
          rows={2}
          className={`${inputCls} mt-2`}
        />

        <div className="grid grid-cols-2 gap-2 mt-2">
          <label className="text-sm text-gray-600">
            Kategorie
            <select
              value={draft.category}
              onChange={(e) => setDraft({ ...draft, category: e.target.value as RecipeCategory })}
              className={`${inputCls} mt-1`}
            >
              {CATEGORIES.map((c) => (
                <option key={c.id} value={c.id}>{c.label}</option>
              ))}
            </select>
          </label>
          <label className="text-sm text-gray-600">
            Portionen
            <input
              type="number"
              min={1}
              value={draft.servings}
              onChange={(e) => setDraft({ ...draft, servings: e.target.value })}
              className={`${inputCls} mt-1`}
            />
          </label>
          <label className="text-sm text-gray-600">
            Vorbereitung (Min)
            <input
              type="number"
              min={0}
              value={draft.prepTimeMinutes}
              onChange={(e) => setDraft({ ...draft, prepTimeMinutes: e.target.value })}
              className={`${inputCls} mt-1`}
            />
          </label>
          <label className="text-sm text-gray-600">
            Kochzeit (Min)
            <input
              type="number"
              min={0}
              value={draft.cookTimeMinutes}
              onChange={(e) => setDraft({ ...draft, cookTimeMinutes: e.target.value })}
              className={`${inputCls} mt-1`}
            />
          </label>
        </div>

        {/* Ingredients */}
        <div className="mt-4">
          <div className="flex items-center justify-between mb-1">
            <h3 className="text-sm font-semibold text-gray-700">Zutaten</h3>
            <button onClick={addIngredient} className="text-sm text-indigo-600 hover:text-indigo-800">+ Zutat</button>
          </div>
          <div className="space-y-2">
            {draft.ingredients.map((ing, idx) => (
              <div key={idx} className="flex gap-2">
                <input
                  type="text"
                  placeholder="Zutat"
                  value={ing.name}
                  onChange={(e) => setIngredient(idx, { name: e.target.value })}
                  className="flex-1 min-w-0 border border-gray-300 rounded-lg px-2 py-1.5 text-sm text-gray-800 focus:outline-none focus:ring-2 focus:ring-indigo-500"
                />
                <input
                  type="text"
                  placeholder="Menge"
                  value={ing.amount}
                  onChange={(e) => setIngredient(idx, { amount: e.target.value })}
                  className="w-16 border border-gray-300 rounded-lg px-2 py-1.5 text-sm text-gray-800 focus:outline-none focus:ring-2 focus:ring-indigo-500"
                />
                <input
                  type="text"
                  placeholder="Einh."
                  value={ing.unit}
                  onChange={(e) => setIngredient(idx, { unit: e.target.value })}
                  className="w-16 border border-gray-300 rounded-lg px-2 py-1.5 text-sm text-gray-800 focus:outline-none focus:ring-2 focus:ring-indigo-500"
                />
                <button
                  onClick={() => removeIngredient(idx)}
                  className="text-gray-300 hover:text-red-500 px-1"
                  aria-label="Zutat entfernen"
                >
                  ✕
                </button>
              </div>
            ))}
          </div>
        </div>

        {/* Steps */}
        <div className="mt-4">
          <div className="flex items-center justify-between mb-1">
            <h3 className="text-sm font-semibold text-gray-700">Zubereitung</h3>
            <button onClick={addStep} className="text-sm text-indigo-600 hover:text-indigo-800">+ Schritt</button>
          </div>
          <div className="space-y-2">
            {draft.steps.map((step, idx) => (
              <div key={idx} className="flex gap-2 items-start">
                <span className="shrink-0 w-6 h-6 mt-1 rounded-full bg-indigo-100 text-indigo-600 text-xs flex items-center justify-center font-medium">
                  {idx + 1}
                </span>
                <textarea
                  placeholder="Schritt beschreiben…"
                  value={step}
                  onChange={(e) => setStep(idx, e.target.value)}
                  rows={2}
                  className="flex-1 min-w-0 border border-gray-300 rounded-lg px-2 py-1.5 text-sm text-gray-800 focus:outline-none focus:ring-2 focus:ring-indigo-500"
                />
                <button
                  onClick={() => removeStep(idx)}
                  className="text-gray-300 hover:text-red-500 px-1 mt-1"
                  aria-label="Schritt entfernen"
                >
                  ✕
                </button>
              </div>
            ))}
          </div>
        </div>

        <div className="flex justify-end gap-2 mt-5">
          <button onClick={onCancel} className="px-4 py-2 rounded-lg text-gray-600 hover:bg-gray-100">
            Abbrechen
          </button>
          <button
            onClick={onSave}
            disabled={saving || !draft.title.trim()}
            className="px-4 py-2 rounded-lg bg-indigo-600 text-white hover:bg-indigo-700 disabled:opacity-50"
          >
            Speichern
          </button>
        </div>
      </div>
    </div>
  )
}
