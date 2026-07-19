// The absence-calendar settings panel (#99): per-person Bundesland / Urlaubskontingent
// / Übertrag / Kind-krank-Cap / Teilzeit, plus household-wide Kita-Schließtage and
// eigene Feiertage. Pure content — the surrounding chrome (title, close) is provided by
// whoever renders it. Moved out of AbwesenheitView into the central Einstellungen hub;
// the household-shared edit model is intentional, so either user may change it.
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import type { AbsenceState } from '../../types'
import { Avatar, Button, Field, IconButton, Select, TextInput } from '../../ui/primitives'
import { Icon } from '../../ui/Icon'
import { parseLocaleNumber, userMeta } from '../../ui/format'
import * as C from './holidays'
import type { Ctx } from './core'
import type { Api } from './useAbsenceData'
import './abw.css'

const nameOf = (uid: string): string => userMeta(uid)?.name ?? uid

export function AbwSettings({ ctx, data, api, userIds, year }: {
  ctx: Ctx
  data: AbsenceState
  api: Api
  userIds: string[]
  year: number
}) {
  const { t } = useTranslation()
  // Accept comma or dot decimal (#299); blank/unparseable falls back. (These are type="number"
  // fields, so the DOM value is usually dot-format already, but tolerating both keeps it uniform.)
  const num = (v: string, fallback: number): number => parseLocaleNumber(String(v)) ?? fallback
  const [kDate, setKDate] = useState(`${year}-01-01`)
  const [rVon, setRVon] = useState(`${year}-07-27`)
  const [rBis, setRBis] = useState(`${year}-08-07`)
  const [rLabel, setRLabel] = useState(t('abwesenheit.kitaDefaultLabel'))
  const kita = [...(data.kitaClosures ?? [])].sort((a, b) => a.date.localeCompare(b.date))
  const wd = t('abwesenheit.weekdaysShort', { returnObjects: true })

  // Eigene Feiertage (#51): recurring by month+day. The date input's year is purely a
  // carrier and is ignored on read — only month+day are stored. Add-form state below.
  const [hDate, setHDate] = useState(`${year}-12-24`)
  const [hHalf, setHHalf] = useState(true)
  const [hLabel, setHLabel] = useState('')
  // `data` is normalised in fetchState (#54): every snapshot list is a real array here.
  const holidays = [...(data.customHolidays ?? [])].sort((a, b) => a.month - b.month || a.day - b.day)
  // MM-DD of a custom holiday → a YYYY-MM-DD value the date input understands (year = the
  // currently viewed year, just a carrier).
  const holDateValue = (h: { month: number; day: number }): string => `${year}-${C.pad(h.month)}-${C.pad(h.day)}`
  const monthDayOf = (ds: string): { month: number; day: number } => {
    const [, m, d] = ds.split('-').map(Number)
    return { month: m, day: d }
  }

  return (
    <div className="abw-set-page">
      {userIds.map((uid) => {
        const s = ctx.settings[uid]
        const rules = (data.partTime ?? []).filter((r) => r.userId === uid)
        return (
          <div key={uid} className="abw-set-person">
            <div className="abw-set-person__head"><Avatar user={uid} size={28} /><span>{nameOf(uid)}</span></div>
            <div className="abw-set-grid">
              <Field label={t('abwesenheit.bundesland')}>
                <Select value={s.state} onChange={(v) => api.updateAbsSettings(uid, year, { state: v })}>
                  {C.STATES.map((st) => <option key={st.code} value={st.code}>{st.name}</option>)}
                </Select>
              </Field>
              <Field label={t('abwesenheit.yearAllowance')}>
                <TextInput type="number" value={String(s.allowance ?? '')} onChange={(v) => api.updateAbsSettings(uid, year, { allowance: num(v, 0) })} />
              </Field>
              <Field label={t('abwesenheit.restLeave')}>
                <TextInput type="number" value={String(s.carryover ?? '')} onChange={(v) => api.updateAbsSettings(uid, year, { carryover: num(v, 0) })} />
              </Field>
              <Field label={t('abwesenheit.expiresOn')}>
                <TextInput type="date" value={s.carryoverExpires || `${year}-03-31`} onChange={(v) => api.updateAbsSettings(uid, year, { carryoverExpires: v })} />
              </Field>
              <Field label={t('abwesenheit.kindKrankCap')}>
                <TextInput type="number" value={String(s.kindKrankCap ?? '')} onChange={(v) => api.updateAbsSettings(uid, year, { kindKrankCap: Math.round(num(v, 15)) })} />
              </Field>
            </div>

            <div className="abw-set-pt">
              <div className="abw-set-pt__label">{t('abwesenheit.teilzeitTitle')}</div>
              {rules.length === 0 ? <div className="hb-muted abw-set-pt__empty">{t('abwesenheit.teilzeitEmpty')}</div> : null}
              {rules.map((r) => (
                <div key={r.id} className="abw-set-ptrow">
                  <Select value={String(r.weekday)} onChange={(v) => api.updatePartTime(r.id, { weekday: Number(v) })} style={{ width: 130 }}>
                    {[1, 2, 3, 4, 5].map((w) => <option key={w} value={w}>{wd[w - 1]}{t('abwesenheit.weekdayFree')}</option>)}
                  </Select>
                  <span className="abw-set-ptrow__lab">{t('abwesenheit.teilzeitFromLabel')}</span>
                  <TextInput type="date" value={r.start} onChange={(v) => api.updatePartTime(r.id, { start: v })} />
                  <span className="abw-set-ptrow__lab">{t('abwesenheit.teilzeitToLabel')}</span>
                  <TextInput type="date" value={r.end || ''} onChange={(v) => api.updatePartTime(r.id, { end: v || null })} />
                  <IconButton icon="trash" label={t('abwesenheit.deleteRule')} danger size={16} onClick={() => api.removePartTime(r.id)} />
                </div>
              ))}
              <button className="hb-link" style={{ marginTop: 8 }} onClick={() => api.addPartTime({ userId: uid, weekday: 1, start: `${year}-01-01`, end: null })}>
                <Icon name="plus" size={14} stroke={2.2} /> {t('abwesenheit.addFreeDay')}
              </button>
            </div>
          </div>
        )
      })}

      <div className="abw-set-kita">
        <div className="abw-set-pt__label">{t('abwesenheit.kitaSection')}</div>
        <div className="hb-muted abw-set-kita__hint">{t('abwesenheit.kitaSectionHint')}</div>
        {kita.length === 0 ? <div className="hb-muted abw-set-pt__empty">{t('abwesenheit.kitaEmpty')}</div> : null}
        <div className="abw-kita-list">
          {kita.map((k) => (
            <div key={k.id} className="abw-kita-row">
              <TextInput type="date" value={k.date} onChange={(v) => api.updateKita(k.id, { date: v })} />
              <TextInput value={k.label} onChange={(v) => api.updateKita(k.id, { label: v })} placeholder={t('abwesenheit.occasion')} />
              <IconButton icon="trash" label={t('abwesenheit.delete')} danger size={16} onClick={() => api.removeKita(k.id)} />
            </div>
          ))}
        </div>
        <div className="abw-kita-add">
          <div className="abw-kita-add__row">
            <span className="abw-kita-add__lab">{t('abwesenheit.singleDay')}</span>
            <TextInput type="date" value={kDate} onChange={setKDate} />
            <Button size="sm" variant="soft" icon="plus" onClick={() => api.addKita(kDate, t('abwesenheit.kitaDefaultLabel'))}>{t('abwesenheit.add')}</Button>
          </div>
          <div className="abw-kita-add__row">
            <span className="abw-kita-add__lab">{t('abwesenheit.period')}</span>
            <TextInput type="date" value={rVon} onChange={setRVon} />
            <span className="abw-set-ptrow__lab">{t('abwesenheit.teilzeitToLabel')}</span>
            <TextInput type="date" value={rBis} onChange={setRBis} />
            <TextInput value={rLabel} onChange={setRLabel} placeholder={t('abwesenheit.occasion')} />
            <Button size="sm" variant="soft" icon="plus" onClick={() => api.addKitaRange(rVon, rBis, rLabel)}>{t('abwesenheit.add')}</Button>
          </div>
          <div className="hb-muted abw-set-kita__hint">{t('abwesenheit.kitaRangeHint')}</div>
        </div>
      </div>

      <div className="abw-set-kita">
        <div className="abw-set-pt__label">{t('abwesenheit.holidaySection')}</div>
        <div className="hb-muted abw-set-kita__hint">{t('abwesenheit.holidaySectionHint')}</div>
        {holidays.length === 0 ? <div className="hb-muted abw-set-pt__empty">{t('abwesenheit.holidayEmpty')}</div> : null}
        <div className="abw-kita-list">
          {holidays.map((h) => (
            <div key={h.id} className="abw-kita-row">
              <TextInput type="date" value={holDateValue(h)} onChange={(v) => api.updateCustomHoliday(h.id, monthDayOf(v))} />
              <div className="abw-half">
                <button className={`abw-half__b${h.half ? '' : ' is-active'}`} onClick={() => api.updateCustomHoliday(h.id, { half: false })}>{t('abwesenheit.fullDay')}</button>
                <button className={`abw-half__b${h.half ? ' is-active' : ''}`} onClick={() => api.updateCustomHoliday(h.id, { half: true })}>{t('abwesenheit.halfDay')}</button>
              </div>
              <TextInput value={h.label} onChange={(v) => api.updateCustomHoliday(h.id, { label: v })} placeholder={t('abwesenheit.occasion')} />
              <IconButton icon="trash" label={t('abwesenheit.delete')} danger size={16} onClick={() => api.removeCustomHoliday(h.id)} />
            </div>
          ))}
        </div>
        <div className="abw-kita-add">
          <div className="abw-kita-add__row">
            <span className="abw-kita-add__lab">{t('abwesenheit.holidayDate')}</span>
            <TextInput type="date" value={hDate} onChange={setHDate} />
            <div className="abw-half">
              <button className={`abw-half__b${hHalf ? '' : ' is-active'}`} onClick={() => setHHalf(false)}>{t('abwesenheit.fullDay')}</button>
              <button className={`abw-half__b${hHalf ? ' is-active' : ''}`} onClick={() => setHHalf(true)}>{t('abwesenheit.halfDay')}</button>
            </div>
            <TextInput value={hLabel} onChange={setHLabel} placeholder={t('abwesenheit.occasion')} />
            <Button
              size="sm"
              variant="soft"
              icon="plus"
              onClick={() => api.addCustomHoliday({ ...monthDayOf(hDate), half: hHalf, label: hLabel.trim() || t('abwesenheit.holidayDefaultLabel') })}
            >{t('abwesenheit.add')}</Button>
          </div>
          <div className="hb-muted abw-set-kita__hint">{t('abwesenheit.holidayRecurHint')}</div>
        </div>
      </div>
    </div>
  )
}
