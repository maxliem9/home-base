// Einstellungen → Aufgaben (#356, follows #340). Household-wide todo display config, stored in
// app_settings and re-read by both clients each load (a change applies on the next load, no
// restart):
//  - "Erledigt"-Fenster — how many calendar days the Erledigt tab / done-section spans before
//    it's capped. The per-device "Alle anzeigen" toggle (#340) still overrides this to reveal the
//    full history; the badge/tile COUNTS deliberately stay on "today" and are unaffected.
// Self-contained; mirrors the RecurringCard plumbing in NotificationsSettings.
import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { API_BASE, errorCode, safeFetch } from '../../api'
import { errorText } from '../../i18n'
import { Icon } from '../../ui/Icon'
import { Button, Card, Field, TextInput } from '../../ui/primitives'

// Keep in sync with the backend default (ConfigRoutes.DONE_WINDOW_DEFAULT_DAYS) and the clients'
// historical constant. Used as the fallback when the GET fails or the field arrives absent
// (encodeDefaults=false). Bounds mirror the backend's validation so the UI rejects the same range.
const DONE_WINDOW_DEFAULT = 14
const DONE_WINDOW_MIN = 1
const DONE_WINDOW_MAX = 3650

export function TodosSettings({ token, onLogout }: { token: string; onLogout: () => void }) {
  return (
    <div style={{ display: 'grid', gap: 16 }}>
      <DoneWindowCard token={token} onLogout={onLogout} />
    </div>
  )
}

// "Erledigt"-history window length in days. Same control/validation/persistence shape as the
// recurring-time card (NotificationsSettings), just a number field instead of a time.
function DoneWindowCard({ token, onLogout }: { token: string; onLogout: () => void }) {
  const { t } = useTranslation()
  // Kept as a string so the number input can be cleared mid-edit; parsed + validated on save.
  const [days, setDays] = useState('')
  const [loaded, setLoaded] = useState(false)
  const [saving, setSaving] = useState(false)
  const [saved, setSaved] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let alive = true
    safeFetch(token, `${API_BASE}/config/done-window`).then(async (result) => {
      if (!alive) return
      if (result.ok && result.res.status === 401) return onLogout()
      if (result.ok && result.res.ok) {
        const data: { days?: number } = await result.res.json()
        // encodeDefaults=false (CLAUDE.md): tolerate the field being absent → fall back to default.
        setDays(String(data.days ?? DONE_WINDOW_DEFAULT))
      } else {
        setDays(String(DONE_WINDOW_DEFAULT))
      }
      // enable editing even if the read failed; the input stays disabled until here so a late GET
      // can't clobber a freshly-typed value (same pattern as the recurring time).
      setLoaded(true)
    })
    return () => { alive = false }
  }, [token, onLogout])

  const parsed = Number(days)
  const valid = Number.isInteger(parsed) && parsed >= DONE_WINDOW_MIN && parsed <= DONE_WINDOW_MAX

  const save = async () => {
    if (!valid) return setError(t('settings.doneWindowInvalid', { min: DONE_WINDOW_MIN, max: DONE_WINDOW_MAX }))
    setSaving(true)
    setError(null)
    setSaved(false)
    const result = await safeFetch(token, `${API_BASE}/config/done-window`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ days: parsed }),
    })
    setSaving(false)
    if (!result.ok) return setError(errorText(null, t('settings.doneWindowSaveFailed')))
    if (result.res.status === 401) return onLogout()
    if (!result.res.ok) return setError(errorText(await errorCode(result.res), t('settings.doneWindowSaveFailed')))
    const data: { days: number } = await result.res.json()
    setDays(String(data.days))
    setSaved(true)
    setTimeout(() => setSaved(false), 2500)
  }

  return (
    <Card className="hb-card--pad">
      <div className="hb-cardhead">
        <div>
          <h3>{t('settings.doneWindowTitle')}</h3>
          <p className="hb-muted" style={{ margin: '2px 0 0' }}>{t('settings.doneWindowHint')}</p>
        </div>
      </div>
      <div style={{ display: 'flex', gap: 12, alignItems: 'flex-end', flexWrap: 'wrap', marginTop: 14 }}>
        <Field label={t('settings.doneWindowLabel')}>
          <TextInput
            type="number"
            value={days}
            onChange={(v) => { setDays(v); setError(null); setSaved(false) }}
            onKeyDown={(e) => e.key === 'Enter' && save()}
            disabled={!loaded}
            style={{ maxWidth: 120 }}
          />
        </Field>
        <Button onClick={save} disabled={saving || !loaded || !valid}>{t('common.save')}</Button>
        {saved && (
          <span className="hb-muted" style={{ display: 'inline-flex', alignItems: 'center', gap: 5, paddingBottom: 9 }}>
            <Icon name="check" size={15} stroke={2.4} /> {t('settings.doneWindowSaved')}
          </span>
        )}
      </div>
      <p className="hb-muted" style={{ margin: '10px 0 0', fontSize: 13 }}>{t('settings.doneWindowApplies')}</p>
      {error && <p style={{ color: 'oklch(0.55 0.16 32)', fontSize: 13.5, margin: '8px 0 0' }}>{error}</p>}
    </Card>
  )
}
