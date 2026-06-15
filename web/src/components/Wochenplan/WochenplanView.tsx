import { useState, useEffect, useCallback, useMemo, Fragment } from 'react'
import { useTranslation } from 'react-i18next'
import type { TFunction } from 'i18next'
import { API_BASE, errorCode, notifyTransportError, safeFetch } from '../../api'
import { errorText } from '../../i18n'
import { MealPlanEntry, MealSlot, Recipe, ShoppingList } from '../../types'
import { useWebSocket } from '../../hooks/useWebSocket'
import { Icon } from '../../ui/Icon'
import { useErrorToast } from '../../ui/ErrorToast'
import { Button, Field, IconButton, PageHead, Select, Sheet, TextInput } from '../../ui/primitives'
import { todayLabel, weekdayShort, weekLabel } from '../../ui/format'
import { CATEGORY_ICON } from '../../lib/cover'

const WS_SCHEME = window.location.protocol === 'https:' ? 'wss' : 'ws'
const wsUrl = (channel: string) => `${WS_SCHEME}://${window.location.host}/api/v1/ws/${channel}`

// Grid rows, in meal order — independent of the recipe categories (#218).
const SLOTS: MealSlot[] = ['BREAKFAST', 'LUNCH', 'DINNER']

const pad = (n: number) => String(n).padStart(2, '0')
/** Local YYYY-MM-DD (matches the app's date keying everywhere else). */
const ymd = (d: Date) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
const parseIso = (iso: string) => new Date(iso + 'T00:00:00')
const addDays = (d: Date, n: number) => new Date(d.getFullYear(), d.getMonth(), d.getDate() + n)
/** Monday-based start of the week containing `d`. */
const mondayOf = (d: Date): Date => addDays(d, -((d.getDay() + 6) % 7))

interface WochenplanViewProps {
  token: string
  onLogout: () => void
}

export function WochenplanView({ token, onLogout }: WochenplanViewProps) {
  const { t } = useTranslation()
  const [entries, setEntries] = useState<MealPlanEntry[]>([])
  const [recipes, setRecipes] = useState<Recipe[]>([])
  const [shoppingLists, setShoppingLists] = useState<ShoppingList[]>([])
  // The visible week, keyed by its Monday (ISO). Keeping it as a string keeps the fetch deps stable.
  const [weekStartIso, setWeekStartIso] = useState(() => ymd(mondayOf(new Date())))
  const [picking, setPicking] = useState<{ date: string; slot: MealSlot } | null>(null)
  const [addingToShopping, setAddingToShopping] = useState(false)
  const [toast, setToast] = useState<string | null>(null)
  const { flashError, errorToast } = useErrorToast()

  const weekDates = useMemo(() => {
    const start = parseIso(weekStartIso)
    return Array.from({ length: 7 }, (_, i) => addDays(start, i))
  }, [weekStartIso])
  const from = weekStartIso
  const to = ymd(weekDates[6])
  const todayIso = ymd(new Date())
  const week = weekLabel(from)

  const fetchEntries = useCallback(async () => {
    const result = await safeFetch(token, `${API_BASE}/meal-plan?from=${from}&to=${to}`)
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
    const list = (await res.json()) as MealPlanEntry[]
    setEntries(Array.isArray(list) ? list : [])
  }, [token, from, to, onLogout])

  const fetchRecipes = useCallback(async () => {
    const result = await safeFetch(token, `${API_BASE}/recipes`)
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
    // ingredients/steps are omitted when empty (encodeDefaults=false) — normalize so the
    // shopping aggregation can treat ingredients as an always-present array.
    const list = (await res.json()) as Recipe[]
    setRecipes(list.map((r) => ({ ...r, ingredients: r.ingredients ?? [], steps: r.steps ?? [] })))
  }, [token, onLogout])

  const fetchShoppingLists = useCallback(async () => {
    const result = await safeFetch(token, `${API_BASE}/shopping/lists`)
    if (!result.ok) {
      notifyTransportError()
      return
    }
    const { res } = result
    if (res.ok) setShoppingLists(await res.json())
  }, [token])

  useEffect(() => { fetchEntries() }, [fetchEntries])
  useEffect(() => { fetchRecipes() }, [fetchRecipes])
  useEffect(() => { fetchShoppingLists() }, [fetchShoppingLists])

  useWebSocket({ url: wsUrl('meal-plan'), token }, () => fetchEntries())
  // A recipe rename/delete changes what the grid shows: deleting a recipe cascades its plan
  // entries away server-side but only broadcasts on the recipes channel, so reload both here.
  useWebSocket({ url: wsUrl('recipes'), token }, () => { fetchRecipes(); fetchEntries() })

  const byKey = useMemo(() => {
    const m = new Map<string, MealPlanEntry>()
    for (const e of entries) m.set(`${e.date}-${e.slot}`, e)
    return m
  }, [entries])
  const entryFor = (date: string, slot: MealSlot) => byKey.get(`${date}-${slot}`)

  // Ingredients of every dish planned this week, in 1× recipe portions; the batch endpoint
  // sums them by name+unit. A dish planned twice contributes its ingredients twice (= 2 portions).
  const recipeById = useMemo(() => new Map(recipes.map((r) => [r.id, r])), [recipes])
  const plannedItems = useMemo(() => {
    const items: { name: string; amount?: number; unit?: string }[] = []
    for (const e of entries) {
      const r = recipeById.get(e.recipeId)
      if (!r) continue
      for (const ing of r.ingredients ?? []) {
        items.push({ name: ing.name, amount: ing.amount ?? undefined, unit: ing.unit ?? undefined })
      }
    }
    return items
  }, [entries, recipeById])

  const shiftWeek = (delta: number) => setWeekStartIso(ymd(addDays(parseIso(weekStartIso), delta * 7)))
  const goToday = () => setWeekStartIso(ymd(mondayOf(new Date())))

  const setSlot = async (date: string, slot: MealSlot, recipeId: string) => {
    setPicking(null)
    const result = await safeFetch(token, `${API_BASE}/meal-plan/${date}/${slot}`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ recipeId }),
    })
    if (!result.ok) return flashError(errorText(null, t('wochenplan.saveFailed')))
    const { res } = result
    if (res.status === 401) return onLogout()
    if (!res.ok) return flashError(errorText(await errorCode(res), t('wochenplan.saveFailed')))
    const entry = (await res.json()) as MealPlanEntry
    setEntries((prev) => [...prev.filter((e) => !(e.date === entry.date && e.slot === entry.slot)), entry])
  }

  const clearSlot = async (date: string, slot: MealSlot) => {
    setPicking(null)
    // optimistic remove; restore from the server if the delete actually failed
    setEntries((prev) => prev.filter((e) => !(e.date === date && e.slot === slot)))
    const result = await safeFetch(token, `${API_BASE}/meal-plan/${date}/${slot}`, { method: 'DELETE' })
    if (!result.ok) {
      notifyTransportError()
      fetchEntries()
      return
    }
    const { res } = result
    if (res.status === 401) return onLogout()
    if (!res.ok) {
      flashError(errorText(await errorCode(res), t('wochenplan.removeFailed')))
      fetchEntries()
    }
  }

  const addToShopping = async (listId: string) => {
    setAddingToShopping(false)
    const flash = (msg: string) => {
      setToast(msg)
      setTimeout(() => setToast(null), 2600)
    }
    const result = await safeFetch(token, `${API_BASE}/shopping/batch`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ listId, items: plannedItems }),
    })
    if (!result.ok) return flashError(errorText(null, t('wochenplan.addToShoppingFailed')))
    const { res } = result
    if (res.status === 401) return onLogout()
    if (!res.ok) return flashError(errorText(await errorCode(res), t('wochenplan.addToShoppingFailed')))
    const summary = (await res.json()) as { added: number; merged: number; skipped: number }
    const parts: string[] = []
    if (summary.added > 0) parts.push(`${summary.added} ${t('wochenplan.added')}`)
    if (summary.merged > 0) parts.push(`${summary.merged} ${t('wochenplan.merged')}`)
    flash(parts.length ? parts.join(' · ') : t('wochenplan.nothingToAdd'))
  }

  return (
    <div className="hb-page">
      <PageHead
        eyebrow={t('wochenplan.eyebrow')}
        title={t('wochenplan.title')}
        actions={
          <Button variant="soft" icon="cart" onClick={() => setAddingToShopping(true)} disabled={entries.length === 0}>
            {t('wochenplan.addToShopping')}
          </Button>
        }
      />

      <div className="hb-weeknav" role="group" aria-label={t('wochenplan.weekNav')}>
        <IconButton icon="chevronLeft" label={t('wochenplan.prevWeek')} onClick={() => shiftWeek(-1)} />
        <div className="hb-weeknav__label">
          {week.label && <span className="hb-weeknav__rel">{week.label}</span>}
          <span className="hb-weeknav__range">{week.range}</span>
        </div>
        <IconButton icon="chevronRight" label={t('wochenplan.nextWeek')} onClick={() => shiftWeek(1)} />
        <Button variant="ghost" size="sm" onClick={goToday}>{t('wochenplan.today')}</Button>
      </div>

      {/* Desktop: 7×3 matrix (days × meals). */}
      <div className="hb-mealgrid">
        <div className="hb-mealgrid__corner" aria-hidden="true" />
        {weekDates.map((d) => {
          const date = ymd(d)
          return (
            <div key={date} className={`hb-mealgrid__day${date === todayIso ? ' is-today' : ''}`}>
              <span className="hb-mealgrid__wd">{weekdayShort(d)}</span>
              <span className="hb-mealgrid__dnum">{d.getDate()}</span>
            </div>
          )
        })}
        {SLOTS.map((slot) => (
          <Fragment key={slot}>
            <div className="hb-mealgrid__slot">{t(`wochenplan.slots.${slot}`)}</div>
            {weekDates.map((d) => {
              const date = ymd(d)
              return (
                <SlotCell
                  key={`${date}-${slot}`}
                  date={date}
                  slot={slot}
                  entry={entryFor(date, slot)}
                  today={date === todayIso}
                  onPick={() => setPicking({ date, slot })}
                  onRemove={() => clearSlot(date, slot)}
                  t={t}
                />
              )
            })}
          </Fragment>
        ))}
      </div>

      {/* Mobile: vertical day list (≤860px). */}
      <div className="hb-mealdays">
        {weekDates.map((d) => {
          const date = ymd(d)
          return (
            <div key={date} className={`hb-mealday${date === todayIso ? ' is-today' : ''}`}>
              <div className="hb-mealday__head">{todayLabel(d)}</div>
              <div className="hb-mealday__slots">
                {SLOTS.map((slot) => (
                  <div key={slot} className="hb-mealday__row">
                    <span className="hb-mealday__slot">{t(`wochenplan.slots.${slot}`)}</span>
                    <SlotCell
                      date={date}
                      slot={slot}
                      entry={entryFor(date, slot)}
                      today={date === todayIso}
                      onPick={() => setPicking({ date, slot })}
                      onRemove={() => clearSlot(date, slot)}
                      t={t}
                    />
                  </div>
                ))}
              </div>
            </div>
          )
        })}
      </div>

      {picking && (
        <MealRecipePicker
          recipes={recipes}
          current={entryFor(picking.date, picking.slot)}
          dateLabel={todayLabel(parseIso(picking.date))}
          slotLabel={t(`wochenplan.slots.${picking.slot}`)}
          onClose={() => setPicking(null)}
          onPick={(recipeId) => setSlot(picking.date, picking.slot, recipeId)}
          onRemove={() => clearSlot(picking.date, picking.slot)}
        />
      )}

      {addingToShopping && (
        <AddToShoppingSheet
          lists={shoppingLists}
          itemCount={plannedItems.length}
          dishCount={entries.length}
          onClose={() => setAddingToShopping(false)}
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
    </div>
  )
}

function SlotCell({
  date,
  slot,
  entry,
  today,
  onPick,
  onRemove,
  t,
}: {
  date: string
  slot: MealSlot
  entry?: MealPlanEntry
  today: boolean
  onPick: () => void
  onRemove: () => void
  t: TFunction
}) {
  if (!entry) {
    return (
      <button
        type="button"
        data-date={date}
        data-slot={slot}
        className={`hb-mealcell hb-mealcell--empty${today ? ' is-today' : ''}`}
        onClick={onPick}
        aria-label={t('wochenplan.addMeal')}
      >
        <Icon name="plus" size={15} stroke={2} />
      </button>
    )
  }
  return (
    <div data-date={date} data-slot={slot} className={`hb-mealcell hb-mealcell--filled${today ? ' is-today' : ''}`}>
      <button type="button" className="hb-mealcell__body" onClick={onPick} title={entry.recipeTitle}>
        <Icon name={CATEGORY_ICON[entry.recipeCategory] ?? 'utensils'} size={14} stroke={2} />
        <span className="hb-mealcell__title">{entry.recipeTitle}</span>
      </button>
      <button type="button" className="hb-mealcell__remove" onClick={onRemove} aria-label={t('wochenplan.removeMeal')}>
        <Icon name="x" size={13} stroke={2} />
      </button>
    </div>
  )
}

function MealRecipePicker({
  recipes,
  current,
  dateLabel,
  slotLabel,
  onClose,
  onPick,
  onRemove,
}: {
  recipes: Recipe[]
  current?: MealPlanEntry
  dateLabel: string
  slotLabel: string
  onClose: () => void
  onPick: (recipeId: string) => void
  onRemove: () => void
}) {
  const { t } = useTranslation()
  const [q, setQ] = useState('')
  const needle = q.trim().toLowerCase()
  const filtered = needle ? recipes.filter((r) => r.title.toLowerCase().includes(needle)) : recipes

  return (
    <Sheet
      open
      onClose={onClose}
      title={`${dateLabel} · ${slotLabel}`}
      width={460}
      footer={
        current ? (
          <>
            <Button variant="ghost" onClick={onClose}>{t('common.cancel')}</Button>
            <Button variant="danger" icon="trash" onClick={onRemove}>{t('wochenplan.remove')}</Button>
          </>
        ) : (
          <Button variant="ghost" onClick={onClose}>{t('common.cancel')}</Button>
        )
      }
    >
      {recipes.length === 0 ? (
        <p className="hb-muted" style={{ margin: 0 }}>{t('wochenplan.pickEmpty')}</p>
      ) : (
        <>
          <div style={{ marginBottom: 6 }}>
            <TextInput value={q} onChange={setQ} placeholder={t('wochenplan.pickSearch')} autoFocus />
          </div>
          {filtered.length === 0 ? (
            <p className="hb-muted" style={{ margin: '8px 0 0' }}>{t('wochenplan.pickNoMatch')}</p>
          ) : (
            <div className="hb-mealpick">
              {filtered.map((r) => (
                <button
                  key={r.id}
                  type="button"
                  className={`hb-mealpick__item${current?.recipeId === r.id ? ' is-current' : ''}`}
                  onClick={() => onPick(r.id)}
                >
                  <Icon name={CATEGORY_ICON[r.category] ?? 'utensils'} size={16} stroke={2} />
                  <span className="hb-mealpick__title">{r.title}</span>
                  {current?.recipeId === r.id && <Icon name="check" size={15} stroke={2.4} />}
                </button>
              ))}
            </div>
          )}
        </>
      )}
    </Sheet>
  )
}

function AddToShoppingSheet({
  lists,
  itemCount,
  dishCount,
  onClose,
  onAdd,
}: {
  lists: ShoppingList[]
  itemCount: number
  dishCount: number
  onClose: () => void
  onAdd: (listId: string) => void
}) {
  const { t } = useTranslation()
  const [listId, setListId] = useState(lists[0]?.id ?? '')

  return (
    <Sheet
      open
      onClose={onClose}
      title={t('wochenplan.addToShoppingTitle')}
      width={440}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>{t('common.cancel')}</Button>
          <Button
            variant="primary"
            icon="cart"
            onClick={() => listId && onAdd(listId)}
            disabled={!listId || itemCount === 0}
          >
            {t('wochenplan.addConfirm')}
          </Button>
        </>
      }
    >
      {lists.length === 0 ? (
        <p className="hb-muted" style={{ margin: 0 }}>{t('wochenplan.noList')}</p>
      ) : (
        <>
          <p className="hb-muted" style={{ marginTop: 0 }}>
            {t('wochenplan.addToShoppingSummary', { items: itemCount, dishes: dishCount })}
          </p>
          {lists.length > 1 && (
            <Field label={t('wochenplan.targetList')}>
              <Select value={listId} onChange={setListId}>
                {lists.map((l) => (
                  <option key={l.id} value={l.id}>{l.name}</option>
                ))}
              </Select>
            </Field>
          )}
        </>
      )}
    </Sheet>
  )
}
