import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { API_BASE, errorCode, safeFetch } from '../api'
import { errorText } from '../i18n'
import { ShoppingList, ShoppingTemplate } from '../types'
import { useErrorToast } from '../ui/ErrorToast'
import { Button, Checkbox, ConfirmDialog, EmptyState, Field, IconButton, Select, Sheet, TextInput } from '../ui/primitives'
import { Icon } from '../ui/Icon'

// Named "standard/template shopping lists" (#215). Two slide-overs:
//  • TemplatesSheet — manage templates (list + an in-place create/edit form). A growing
//    item list, so a Sheet per the Modal/Sheet guideline (#29), not a centered Modal.
//  • ApplyTemplateSheet — pick which items to push onto a real list, mirroring the recipe
//    ingredient picker (#48). The actual batch-add happens in ShoppingView (onApply).
// Template CRUD lives here and re-reads via onChanged after each write; ShoppingView also
// refetches on the template WS broadcasts, so a suppressed echo never leaves stale data.

// ---- management: list + editor ---------------------------------------------

type EditMode =
  | { kind: 'list' }
  | { kind: 'edit'; template: ShoppingTemplate | null } // null = create

export function TemplatesSheet({
  token,
  templates,
  onClose,
  onChanged,
  onLogout,
  onApply,
}: {
  token: string
  templates: ShoppingTemplate[]
  onClose: () => void
  onChanged: () => void | Promise<void>
  onLogout: () => void
  onApply: (template: ShoppingTemplate) => void
}) {
  const { t } = useTranslation()
  const { flashError, errorToast } = useErrorToast()
  const [mode, setMode] = useState<EditMode>({ kind: 'list' })
  const [confirmDelete, setConfirmDelete] = useState<ShoppingTemplate | null>(null)

  const deleteTemplate = async (tpl: ShoppingTemplate) => {
    setConfirmDelete(null)
    const result = await safeFetch(token, `${API_BASE}/shopping/templates/${tpl.id}`, { method: 'DELETE' })
    if (!result.ok) return flashError(errorText(null, t('shopping.templates.deleteFailed')))
    const { res } = result
    if (res.status === 401) return onLogout()
    if (!res.ok && res.status !== 404) return flashError(errorText(await errorCode(res), t('shopping.templates.deleteFailed')))
    await onChanged()
  }

  if (mode.kind === 'edit') {
    return (
      <TemplateEditorSheet
        token={token}
        template={mode.template}
        onClose={() => setMode({ kind: 'list' })}
        onSaved={async () => { await onChanged(); setMode({ kind: 'list' }) }}
        onLogout={onLogout}
      />
    )
  }

  return (
    <Sheet
      open
      onClose={onClose}
      title={t('shopping.templates.manageTitle')}
      width={460}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>{t('common.close')}</Button>
          <Button variant="primary" icon="plus" onClick={() => setMode({ kind: 'edit', template: null })}>
            {t('shopping.templates.newTemplate')}
          </Button>
        </>
      }
    >
      <p className="hb-muted" style={{ margin: '0 0 14px', fontSize: 13, lineHeight: 1.5 }}>
        {t('shopping.templates.manageHint')}
      </p>
      {templates.length === 0 ? (
        <EmptyState icon="cart" title={t('shopping.templates.empty')} hint={t('shopping.templates.emptyHint')} />
      ) : (
        <div className="hb-list">
          {templates.map((tpl) => {
            const n = tpl.items.length
            return (
              <div key={tpl.id} className="hb-row" style={{ padding: '11px 4px' }}>
                <div className="hb-row__main">
                  <div className="hb-row__title">{tpl.name}</div>
                  <div className="hb-row__meta">
                    {n} {n === 1 ? t('shopping.templates.itemCountOne') : t('shopping.templates.itemCount')}
                  </div>
                </div>
                <div className="hb-row__right">
                  <Button variant="soft" size="sm" icon="cart" onClick={() => onApply(tpl)} disabled={n === 0}>
                    {t('shopping.templates.apply')}
                  </Button>
                  <div className="hb-row__actions">
                    <IconButton icon="edit" label={t('common.edit')} onClick={() => setMode({ kind: 'edit', template: tpl })} />
                    <IconButton icon="trash" label={t('common.delete')} danger onClick={() => setConfirmDelete(tpl)} />
                  </div>
                </div>
              </div>
            )
          })}
        </div>
      )}

      {confirmDelete && (
        <ConfirmDialog
          title={t('shopping.templates.deleteTitle')}
          message={t('shopping.templates.deleteConfirm', { name: confirmDelete.name })}
          confirmLabel={t('shopping.templates.deleteBtn')}
          danger
          onConfirm={() => deleteTemplate(confirmDelete)}
          onClose={() => setConfirmDelete(null)}
        />
      )}
      {errorToast}
    </Sheet>
  )
}

// ---- create / edit one template --------------------------------------------

// Editing replaces name + the full item set wholesale (PUT), mirroring how a recipe
// update handles its embedded children; creating POSTs the same shape. Blank item rows
// are dropped on save (the backend drops them too), so an empty trailing row is harmless.
function TemplateEditorSheet({
  token,
  template,
  onClose,
  onSaved,
  onLogout,
}: {
  token: string
  template: ShoppingTemplate | null
  onClose: () => void
  onSaved: () => void | Promise<void>
  onLogout: () => void
}) {
  const { t } = useTranslation()
  const [name, setName] = useState(template?.name ?? '')
  const [items, setItems] = useState<string[]>(template ? template.items.map((i) => i.name) : [])
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const setItem = (idx: number, value: string) => setItems((prev) => prev.map((v, i) => (i === idx ? value : v)))
  const addItem = () => setItems((prev) => [...prev, ''])
  const removeItem = (idx: number) => setItems((prev) => prev.filter((_, i) => i !== idx))

  const save = async () => {
    if (!name.trim() || busy) return
    setBusy(true)
    setError(null)
    try {
      const payload = {
        name: name.trim(),
        items: items.map((v) => v.trim()).filter(Boolean).map((value) => ({ name: value })),
      }
      const result = template
        ? await safeFetch(token, `${API_BASE}/shopping/templates/${template.id}`, {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(payload),
          })
        : await safeFetch(token, `${API_BASE}/shopping/templates`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(payload),
          })
      if (!result.ok) { setError(errorText(null, t('shopping.templates.saveFailed'))); return }
      const { res } = result
      if (res.status === 401) { onLogout(); return }
      if (!res.ok) { setError(errorText(await errorCode(res), t('shopping.templates.saveFailed'))); return }
      await onSaved()
    } finally {
      setBusy(false)
    }
  }

  return (
    <Sheet
      open
      onClose={onClose}
      title={template ? t('shopping.templates.editTemplate') : t('shopping.templates.newTemplate')}
      width={460}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>{t('common.cancel')}</Button>
          <Button variant="primary" icon="check" onClick={save} disabled={!name.trim() || busy}>{t('common.save')}</Button>
        </>
      }
    >
      <Field label={t('shopping.templates.nameLabel')}>
        <TextInput
          value={name}
          onChange={setName}
          placeholder={t('shopping.templates.namePlaceholder')}
          autoFocus
          onKeyDown={(e) => { if (e.key === 'Enter') save() }}
        />
      </Field>

      <div className="hb-field__label" style={{ marginTop: 14, marginBottom: 6 }}>{t('shopping.templates.items')}</div>
      {items.length === 0 ? (
        <p className="hb-muted" style={{ margin: '0 0 8px', fontSize: 13 }}>{t('shopping.templates.noItemsYet')}</p>
      ) : (
        <div className="hb-tpl-items">
          {items.map((value, i) => (
            <div key={i} className="hb-tpl-item">
              <TextInput value={value} onChange={(v) => setItem(i, v)} placeholder={t('shopping.templates.itemPlaceholder')} />
              <IconButton icon="trash" label={t('shopping.templates.removeItem')} danger onClick={() => removeItem(i)} />
            </div>
          ))}
        </div>
      )}
      <button className="hb-link" style={{ marginTop: 8 }} onClick={addItem}>
        <Icon name="plus" size={14} stroke={2.2} style={{ verticalAlign: '-2px', marginRight: 4 }} />
        {t('shopping.templates.addItem')}
      </button>

      {error && <p className="hb-modal-error">{error}</p>}
    </Sheet>
  )
}

// ---- apply: pick which items to add ----------------------------------------

// Mirrors the recipe IngredientPicker (#48): checkbox per item, all preselected, an
// all/none toggle. Confirm hands the chosen names to ShoppingView's batch-add.
export function ApplyTemplateSheet({
  template,
  lists,
  activeListId,
  onClose,
  onApply,
}: {
  template: ShoppingTemplate
  lists: ShoppingList[]
  activeListId: string | null
  onClose: () => void
  onApply: (listId: string, names: string[]) => void
}) {
  const { t } = useTranslation()
  const [sel, setSel] = useState<boolean[]>(() => template.items.map(() => true))
  // default to the currently active list, falling back to the first one
  const [listId, setListId] = useState(
    activeListId && lists.some((l) => l.id === activeListId) ? activeListId : lists[0]?.id ?? '',
  )
  const count = sel.filter(Boolean).length
  const total = template.items.length
  const allOn = count === total
  const toggle = (i: number) => setSel((s) => s.map((v, j) => (j === i ? !v : v)))

  const add = () => {
    if (!listId) return
    const names = template.items.filter((_, i) => sel[i]).map((it) => it.name)
    if (names.length) onApply(listId, names)
  }

  return (
    <Sheet
      open
      onClose={onClose}
      title={t('shopping.templates.applyTitle')}
      width={440}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>{t('common.cancel')}</Button>
          <Button variant="primary" icon="cart" onClick={add} disabled={count === 0 || !listId}>
            {count} {t('shopping.templates.applyAdd')}
          </Button>
        </>
      }
    >
      {lists.length === 0 ? (
        <p className="hb-muted" style={{ margin: 0 }}>{t('shopping.templates.applyNoList')}</p>
      ) : (
        <>
          {lists.length > 1 && (
            <Field label={t('shopping.templates.applyToList')}>
              <Select value={listId} onChange={setListId}>
                {lists.map((l) => <option key={l.id} value={l.id}>{l.name}</option>)}
              </Select>
            </Field>
          )}
          <div className="hb-picker-head">
            <span className="hb-muted">{count} / {total} {t('shopping.templates.selected')}</span>
            <button className="hb-link" onClick={() => setSel(template.items.map(() => !allOn))}>
              {allOn ? t('shopping.templates.none') : t('shopping.templates.all')}
            </button>
          </div>
          <div className="hb-picklist">
            {template.items.map((it, i) => (
              <div key={it.id} className="hb-ingpick" onClick={() => toggle(i)}>
                <Checkbox checked={sel[i]} onChange={() => toggle(i)} />
                <span className="hb-ingpick__name">{it.name}</span>
              </div>
            ))}
          </div>
        </>
      )}
    </Sheet>
  )
}
