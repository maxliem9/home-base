// Einstellungen → Einkaufskategorien (#411). Manages the household-wide grocery category
// catalog (the headers the shopping list groups by) and the auto-assignment rules that fill a
// newly added item's category + emoji from its name. Backed by:
//   GET/POST/PUT/DELETE /shopping/categories  and  GET/PUT/DELETE /shopping/category-rules
// Same per-view convention as the other settings subpages: fetch its own data, subscribe to its
// own WS channel ("shopping") and refetch on the relevant broadcast. The category key is server-
// generated from the label; the rule key is the server-normalized display name. OTHER is protected
// (delete hidden); deleting any other category reassigns its items to OTHER server-side.
import { useCallback, useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { API_BASE, errorCode, notifyTransportError, safeFetch } from '../../api'
import { errorText } from '../../i18n'
import { ShoppingCategory, ShoppingCategoryRule } from '../../types'
import { useWebSocket } from '../../hooks/useWebSocket'
import { Icon } from '../../ui/Icon'
import { Button, Card, ConfirmDialog, EmptyState, Field, IconButton, Select, TextInput } from '../../ui/primitives'
import { categoryMeta, DEFAULT_ITEM_ICON } from '../shoppingCategories'

const WS_SCHEME = window.location.protocol === 'https:' ? 'wss' : 'ws'
const WS_URL = import.meta.env.VITE_WS_URL_SHOPPING ?? `${WS_SCHEME}://${window.location.host}/api/v1/ws/shopping`

// The protected fallback bucket — never deletable, and items of a deleted category land here.
const OTHER_KEY = 'OTHER'

export function ShoppingCategoriesSettings({ token, onLogout }: { token: string; onLogout: () => void }) {
  const [categories, setCategories] = useState<ShoppingCategory[]>([])
  const [rules, setRules] = useState<ShoppingCategoryRule[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  const fetchCategories = useCallback(async () => {
    const result = await safeFetch(token, `${API_BASE}/shopping/categories`)
    if (!result.ok) return notifyTransportError()
    if (result.res.status === 401) return onLogout()
    if (result.res.ok) setCategories((await result.res.json()) as ShoppingCategory[])
  }, [onLogout, token])

  const fetchRules = useCallback(async () => {
    const result = await safeFetch(token, `${API_BASE}/shopping/category-rules`)
    if (!result.ok) return notifyTransportError()
    if (result.res.status === 401) return onLogout()
    if (result.res.ok) setRules((await result.res.json()) as ShoppingCategoryRule[])
  }, [onLogout, token])

  useEffect(() => {
    void Promise.all([fetchCategories(), fetchRules()]).finally(() => setLoading(false))
  }, [fetchCategories, fetchRules])

  // Live updates ride the shared "shopping" channel; refetch the affected list on its broadcast.
  useWebSocket({ url: WS_URL, token }, (raw) => {
    try {
      const msg = JSON.parse(raw)
      if (msg.type === 'SHOPPING_CATEGORY_CHANGED') void fetchCategories()
      else if (msg.type === 'SHOPPING_CATEGORY_RULE_CHANGED') void fetchRules()
    } catch {
      // ignore malformed frames
    }
  })

  return (
    <div style={{ display: 'grid', gap: 16 }}>
      <CategoriesCard
        token={token}
        onLogout={onLogout}
        categories={categories}
        loading={loading}
        onChanged={fetchCategories}
        onError={setError}
      />
      <RulesCard
        token={token}
        onLogout={onLogout}
        categories={categories}
        rules={rules}
        loading={loading}
        onChanged={fetchRules}
        onError={setError}
      />
      {error && (
        <div className="hb-toast hb-toast--error" role="alert">
          <Icon name="x" size={18} stroke={2.4} />
          {error}
        </div>
      )}
    </div>
  )
}

// --- Categories card -------------------------------------------------------

// Exported so the per-list category manager (#412, shopping list "eigene Kategorien") can reuse it.
// `listId` scopes CREATE to that list's own set; edit/delete/reorder go by the (globally unique) key
// and need no scope. `title`/`hint` override the settings-page copy.
export function CategoriesCard({ token, onLogout, categories, loading, onChanged, onError, listId, title, hint }: {
  token: string
  onLogout: () => void
  categories: ShoppingCategory[]
  loading: boolean
  onChanged: () => Promise<void>
  onError: (msg: string | null) => void
  listId?: string
  title?: string
  hint?: string
}) {
  const { t } = useTranslation()
  // null = no editor open; { key? } = the add (no key) or edit (with key) form.
  const [draft, setDraft] = useState<{ key?: string; label: string; emoji: string } | null>(null)
  const [confirmDelete, setConfirmDelete] = useState<ShoppingCategory | null>(null)
  const [busy, setBusy] = useState(false)

  const save = async () => {
    if (!draft || !draft.label.trim() || busy) return
    setBusy(true)
    onError(null)
    const body = JSON.stringify({ label: draft.label.trim(), emoji: draft.emoji.trim() || DEFAULT_ITEM_ICON })
    const createUrl = listId ? `${API_BASE}/shopping/categories?listId=${listId}` : `${API_BASE}/shopping/categories`
    const result = draft.key
      ? await safeFetch(token, `${API_BASE}/shopping/categories/${encodeURIComponent(draft.key)}`, {
          method: 'PUT', headers: { 'Content-Type': 'application/json' }, body,
        })
      : await safeFetch(token, createUrl, {
          method: 'POST', headers: { 'Content-Type': 'application/json' }, body,
        })
    setBusy(false)
    if (!result.ok) return onError(errorText(null, t('settings.shoppingCatSaveFailed')))
    if (result.res.status === 401) return onLogout()
    if (!result.res.ok) return onError(errorText(await errorCode(result.res), t('settings.shoppingCatSaveFailed')))
    setDraft(null)
    await onChanged()
  }

  const remove = async (cat: ShoppingCategory) => {
    setConfirmDelete(null)
    onError(null)
    const result = await safeFetch(token, `${API_BASE}/shopping/categories/${encodeURIComponent(cat.key)}`, { method: 'DELETE' })
    if (!result.ok) return onError(errorText(null, t('settings.shoppingCatDeleteFailed')))
    if (result.res.status === 401) return onLogout()
    // CATEGORY_PROTECTED (OTHER) → dedicated message; OTHER's delete is hidden, so this is a backstop.
    if (!result.res.ok) {
      const code = await errorCode(result.res)
      return onError(code === 'CATEGORY_PROTECTED' ? t('settings.shoppingCatProtected') : errorText(code, t('settings.shoppingCatDeleteFailed')))
    }
    await onChanged()
  }

  // Reorder by swapping a category's sortOrder with its neighbour's (PUT both). The WS broadcast
  // refetches the now-resorted list, so no optimistic reshuffle is needed.
  const move = async (index: number, dir: -1 | 1) => {
    const a = categories[index]
    const b = categories[index + dir]
    if (!a || !b) return
    onError(null)
    const put = (key: string, sortOrder: number) =>
      safeFetch(token, `${API_BASE}/shopping/categories/${encodeURIComponent(key)}`, {
        method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ sortOrder }),
      })
    const [ra, rb] = await Promise.all([put(a.key, b.sortOrder), put(b.key, a.sortOrder)])
    if (!ra.ok || !rb.ok) return onError(errorText(null, t('settings.shoppingCatSaveFailed')))
    if (ra.res.status === 401 || rb.res.status === 401) return onLogout()
    if (!ra.res.ok || !rb.res.ok) return onError(errorText(null, t('settings.shoppingCatSaveFailed')))
    await onChanged()
  }

  return (
    <Card className="hb-card--pad">
      <div className="hb-cardhead">
        <div>
          <h3>{title ?? t('settings.shoppingCatsTitle')}</h3>
          <p className="hb-muted" style={{ margin: '2px 0 0' }}>{hint ?? t('settings.shoppingCatsHint')}</p>
        </div>
        {!draft && (
          <Button size="sm" icon="plus" onClick={() => setDraft({ label: '', emoji: '' })}>{t('settings.shoppingCatAdd')}</Button>
        )}
      </div>

      {loading ? (
        <p className="hb-muted" style={{ marginBottom: 0 }}>{t('common.loading')}</p>
      ) : categories.length === 0 ? (
        <EmptyState icon="cart" title={t('settings.shoppingCatsEmpty')} />
      ) : (
        <div className="hb-list" style={{ marginTop: 8 }}>
          {categories.map((c, i) => (
            <div key={c.key} className="hb-row">
              <span className="hb-row__emoji" aria-hidden="true">{c.emoji}</span>
              <div className="hb-row__main">
                <div className="hb-row__title">
                  {c.label}
                  {c.isBuiltin && <span className="hb-muted"> · {t('settings.shoppingCatBuiltin')}</span>}
                </div>
              </div>
              <div className="hb-row__right">
                <IconButton icon="chevronUp" label={t('settings.shoppingCatMoveUp')} disabled={i === 0} onClick={() => move(i, -1)} />
                <IconButton icon="chevronDown" label={t('settings.shoppingCatMoveDown')} disabled={i === categories.length - 1} onClick={() => move(i, 1)} />
                <IconButton icon="edit" label={t('common.edit')} onClick={() => setDraft({ key: c.key, label: c.label, emoji: c.emoji })} />
                {c.key !== OTHER_KEY && (
                  <IconButton icon="trash" label={t('common.delete')} danger onClick={() => setConfirmDelete(c)} />
                )}
              </div>
            </div>
          ))}
        </div>
      )}

      {draft && (
        <div className="hb-stack" style={{ gap: 12, marginTop: 16 }}>
          <div className="hb-sectionlabel" style={{ margin: 0 }}>{draft.key ? t('settings.shoppingCatEdit') : t('settings.shoppingCatNew')}</div>
          <div style={{ display: 'flex', gap: 12, alignItems: 'flex-end', flexWrap: 'wrap' }}>
            <Field label={t('settings.shoppingCatEmoji')}>
              <TextInput
                value={draft.emoji}
                onChange={(v) => setDraft((d) => (d ? { ...d, emoji: v } : d))}
                placeholder={t('settings.shoppingCatEmojiPlaceholder')}
                style={{ maxWidth: 80, textAlign: 'center' }}
              />
            </Field>
            <Field label={t('settings.shoppingCatLabel')}>
              <TextInput
                value={draft.label}
                onChange={(v) => setDraft((d) => (d ? { ...d, label: v } : d))}
                placeholder={t('settings.shoppingCatLabelPlaceholder')}
                autoFocus
                onKeyDown={(e) => e.key === 'Enter' && save()}
                style={{ minWidth: 200 }}
              />
            </Field>
            <div className="hb-formactions" style={{ marginLeft: 'auto' }}>
              <Button variant="ghost" onClick={() => { setDraft(null); onError(null) }}>{t('common.cancel')}</Button>
              <Button icon="check" onClick={save} disabled={busy || !draft.label.trim()}>{t('common.save')}</Button>
            </div>
          </div>
        </div>
      )}

      {confirmDelete && (
        <ConfirmDialog
          title={t('settings.shoppingCatDeleteTitle')}
          message={t('settings.shoppingCatDeleteBody', { label: confirmDelete.label })}
          confirmLabel={t('settings.shoppingCatDeleteConfirm')}
          danger
          onConfirm={() => remove(confirmDelete)}
          onClose={() => setConfirmDelete(null)}
        />
      )}
    </Card>
  )
}

// --- Rules card ------------------------------------------------------------

// Exported so the per-list category manager (#501, shopping list "eigene Kategorien") can reuse it.
// `listId` scopes all three calls (upsert/rename-cleanup/delete) to that list's own dictionary; without
// it they hit the shared household dictionary. `title`/`hint` override the settings-page copy.
export function RulesCard({ token, onLogout, categories, rules, loading, onChanged, onError, listId, title, hint }: {
  token: string
  onLogout: () => void
  categories: ShoppingCategory[]
  rules: ShoppingCategoryRule[]
  loading: boolean
  onChanged: () => Promise<void>
  onError: (msg: string | null) => void
  listId?: string
  title?: string
  hint?: string
}) {
  const { t } = useTranslation()
  const scopeQuery = listId ? `?listId=${listId}` : ''
  // null = no editor open. `editingName` (when set) keeps the original display name so an edit that
  // also renames can drop the stale rule (the upsert is keyed by the normalized name).
  const [draft, setDraft] = useState<{ displayName: string; category: string; icon: string; editingName?: string } | null>(null)
  const [confirmDelete, setConfirmDelete] = useState<ShoppingCategoryRule | null>(null)
  const [busy, setBusy] = useState(false)

  // Default the category select to OTHER (or the first category) when opening the add form.
  const openAdd = () => {
    const def = categories.find((c) => c.key === OTHER_KEY)?.key ?? categories[0]?.key ?? OTHER_KEY
    setDraft({ displayName: '', category: def, icon: '' })
  }

  const save = async () => {
    if (!draft || !draft.displayName.trim() || !draft.category || busy) return
    setBusy(true)
    onError(null)
    const body: { displayName: string; category: string; icon?: string } = {
      displayName: draft.displayName.trim(),
      category: draft.category,
    }
    // Omitted icon keeps the existing one on update / defaults to 🛒 on create (backend contract).
    if (draft.icon.trim()) body.icon = draft.icon.trim()
    const result = await safeFetch(token, `${API_BASE}/shopping/category-rules${scopeQuery}`, {
      method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
    })
    if (!result.ok) { setBusy(false); return onError(errorText(null, t('settings.shoppingRuleSaveFailed'))) }
    if (result.res.status === 401) return onLogout()
    if (!result.res.ok) { setBusy(false); return onError(errorText(await errorCode(result.res), t('settings.shoppingRuleSaveFailed'))) }
    // If an edit renamed the rule, the upsert created a new keyed entry — remove the old one (same scope).
    const renamedFrom = draft.editingName
    if (renamedFrom && renamedFrom.trim().toLowerCase() !== draft.displayName.trim().toLowerCase()) {
      await safeFetch(token, `${API_BASE}/shopping/category-rules/${encodeURIComponent(renamedFrom)}${scopeQuery}`, { method: 'DELETE' })
    }
    setBusy(false)
    setDraft(null)
    await onChanged()
  }

  const remove = async (rule: ShoppingCategoryRule) => {
    setConfirmDelete(null)
    onError(null)
    const result = await safeFetch(token, `${API_BASE}/shopping/category-rules/${encodeURIComponent(rule.displayName)}${scopeQuery}`, { method: 'DELETE' })
    if (!result.ok) return onError(errorText(null, t('settings.shoppingRuleDeleteFailed')))
    if (result.res.status === 401) return onLogout()
    if (!result.res.ok) return onError(errorText(await errorCode(result.res), t('settings.shoppingRuleDeleteFailed')))
    await onChanged()
  }

  return (
    <Card className="hb-card--pad">
      <div className="hb-cardhead">
        <div>
          <h3>{title ?? t('settings.shoppingRulesTitle')}</h3>
          <p className="hb-muted" style={{ margin: '2px 0 0' }}>{hint ?? t('settings.shoppingRulesHint')}</p>
        </div>
        {!draft && (
          <Button size="sm" icon="plus" onClick={openAdd} disabled={categories.length === 0}>{t('settings.shoppingRuleAdd')}</Button>
        )}
      </div>

      {loading ? (
        <p className="hb-muted" style={{ marginBottom: 0 }}>{t('common.loading')}</p>
      ) : rules.length === 0 ? (
        <EmptyState icon="tag" title={t('settings.shoppingRulesEmpty')} />
      ) : (
        <div className="hb-list" style={{ marginTop: 8 }}>
          {rules.map((r) => {
            const meta = categoryMeta(r.category, categories)
            return (
              <div key={r.normalizedName} className="hb-row">
                <span className="hb-row__emoji" aria-hidden="true">{r.icon || DEFAULT_ITEM_ICON}</span>
                <div className="hb-row__main">
                  <div className="hb-row__title">{r.displayName}</div>
                  <div className="hb-muted" style={{ fontSize: 13 }}>{meta.emoji} {meta.label}</div>
                </div>
                <div className="hb-row__right">
                  <IconButton icon="edit" label={t('common.edit')} onClick={() => setDraft({ displayName: r.displayName, category: r.category, icon: r.icon, editingName: r.displayName })} />
                  <IconButton icon="trash" label={t('common.delete')} danger onClick={() => setConfirmDelete(r)} />
                </div>
              </div>
            )
          })}
        </div>
      )}

      {draft && (
        <div className="hb-stack" style={{ gap: 12, marginTop: 16 }}>
          <div className="hb-sectionlabel" style={{ margin: 0 }}>{draft.editingName ? t('settings.shoppingRuleEdit') : t('settings.shoppingRuleNew')}</div>
          <div style={{ display: 'flex', gap: 12, alignItems: 'flex-end', flexWrap: 'wrap' }}>
            <Field label={t('settings.shoppingRuleEmoji')}>
              <TextInput
                value={draft.icon}
                onChange={(v) => setDraft((d) => (d ? { ...d, icon: v } : d))}
                placeholder={DEFAULT_ITEM_ICON}
                style={{ maxWidth: 80, textAlign: 'center' }}
              />
            </Field>
            <Field label={t('settings.shoppingRuleName')}>
              <TextInput
                value={draft.displayName}
                onChange={(v) => setDraft((d) => (d ? { ...d, displayName: v } : d))}
                placeholder={t('settings.shoppingRuleNamePlaceholder')}
                autoFocus
                onKeyDown={(e) => e.key === 'Enter' && save()}
                style={{ minWidth: 180 }}
              />
            </Field>
            <Field label={t('settings.shoppingRuleCategory')}>
              <Select value={draft.category} onChange={(v) => setDraft((d) => (d ? { ...d, category: v } : d))}>
                {categories.map((c) => <option key={c.key} value={c.key}>{c.emoji} {c.label}</option>)}
              </Select>
            </Field>
            <div className="hb-formactions" style={{ marginLeft: 'auto' }}>
              <Button variant="ghost" onClick={() => { setDraft(null); onError(null) }}>{t('common.cancel')}</Button>
              <Button icon="check" onClick={save} disabled={busy || !draft.displayName.trim() || !draft.category}>{t('settings.shoppingRuleSave')}</Button>
            </div>
          </div>
        </div>
      )}

      {confirmDelete && (
        <ConfirmDialog
          title={t('settings.shoppingRuleDeleteTitle')}
          message={t('settings.shoppingRuleDeleteBody', { name: confirmDelete.displayName })}
          confirmLabel={t('settings.shoppingRuleDeleteConfirm')}
          danger
          onConfirm={() => remove(confirmDelete)}
          onClose={() => setConfirmDelete(null)}
        />
      )}
    </Card>
  )
}
