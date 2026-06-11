// Einstellungen → Benachrichtigungen (#100, Phase 2). Sets the Telegram digest time,
// stored in app_settings and re-read by the scheduler each cycle (a change applies from
// the next scheduled run). The digest only actually sends when Telegram is configured
// server-side (`enabled`); the time stays editable regardless. Self-contained.
import { useEffect, useState } from 'react'
import { API_BASE, errorCode, safeFetch } from '../../api'
import { t, errorText } from '../../i18n'
import { Icon } from '../../ui/Icon'
import { Button, Card, Field, TextInput } from '../../ui/primitives'

export function NotificationsSettings({ token, onLogout }: { token: string; onLogout: () => void }) {
  const [time, setTime] = useState('')
  const [enabled, setEnabled] = useState(true)
  const [loaded, setLoaded] = useState(false)
  const [saving, setSaving] = useState(false)
  const [saved, setSaved] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let alive = true
    safeFetch(token, `${API_BASE}/config/digest`).then(async (result) => {
      if (!alive) return
      if (result.ok && result.res.status === 401) return onLogout()
      if (result.ok && result.res.ok) {
        const data: { time?: string; enabled?: boolean } = await result.res.json()
        setTime(data.time ?? '')
        setEnabled(data.enabled ?? false)
      }
      // enable editing even if the read failed; the input stays disabled until here so a
      // late GET can't clobber a freshly-typed value (same pattern as the household name).
      setLoaded(true)
    })
    return () => { alive = false }
  }, [token, onLogout])

  const save = async () => {
    if (!time) return
    setSaving(true)
    setError(null)
    setSaved(false)
    const result = await safeFetch(token, `${API_BASE}/config/digest`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ time }),
    })
    setSaving(false)
    if (!result.ok) return setError(errorText(null, t.settings.digestSaveFailed))
    if (result.res.status === 401) return onLogout()
    if (!result.res.ok) return setError(errorText(await errorCode(result.res), t.settings.digestSaveFailed))
    const data: { time: string } = await result.res.json()
    setTime(data.time)
    setSaved(true)
    setTimeout(() => setSaved(false), 2500)
  }

  return (
    <Card className="hb-card--pad">
      <div className="hb-cardhead">
        <div>
          <h3>{t.settings.digestTitle}</h3>
          <p className="hb-muted" style={{ margin: '2px 0 0' }}>{t.settings.digestHint}</p>
        </div>
      </div>
      {loaded && !enabled && (
        <p className="hb-muted" style={{ margin: '12px 0 0' }}>{t.settings.digestDisabled}</p>
      )}
      <div style={{ display: 'flex', gap: 12, alignItems: 'flex-end', flexWrap: 'wrap', marginTop: 14 }}>
        <Field label={t.settings.digestTimeLabel}>
          <TextInput type="time" value={time} onChange={(v) => { setTime(v); setError(null); setSaved(false) }} disabled={!loaded} />
        </Field>
        <Button onClick={save} disabled={saving || !loaded || !time}>{t.common.save}</Button>
        {saved && (
          <span className="hb-muted" style={{ display: 'inline-flex', alignItems: 'center', gap: 5, paddingBottom: 9 }}>
            <Icon name="check" size={15} stroke={2.4} /> {t.settings.digestSaved}
          </span>
        )}
      </div>
      <p className="hb-muted" style={{ margin: '10px 0 0', fontSize: 13 }}>{t.settings.digestApplies}</p>
      {error && <p style={{ color: 'oklch(0.55 0.16 32)', fontSize: 13.5, margin: '8px 0 0' }}>{error}</p>}
    </Card>
  )
}
