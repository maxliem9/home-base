// Einstellungen → Haushalt (#100, Phase 2). Edits the household name (the sidebar
// brand), shared by both users. Self-contained: reads GET /config and writes
// PUT /config (env default is the fallback when unset, server-side). On save it
// calls onRenamed so the live brand in the shell updates without a reload.
import { useEffect, useState } from 'react'
import { API_BASE, errorCode, safeFetch } from '../../api'
import { t, errorText } from '../../i18n'
import { Icon } from '../../ui/Icon'
import { Button, Card, Field, TextInput } from '../../ui/primitives'

export function HouseholdSettings({ token, onLogout, onRenamed }: {
  token: string
  onLogout: () => void
  onRenamed: (name: string) => void
}) {
  const [name, setName] = useState('')
  const [loaded, setLoaded] = useState(false)
  const [saving, setSaving] = useState(false)
  const [saved, setSaved] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let alive = true
    safeFetch(token, `${API_BASE}/config`).then(async (result) => {
      if (!alive) return
      if (result.ok && result.res.status === 401) return onLogout()
      if (result.ok && result.res.ok) {
        const data: { householdName?: string } = await result.res.json()
        setName(data.householdName ?? '')
      }
      // Enable editing even if the read failed — the PUT doesn't need the current
      // value, and the input stays disabled until here to avoid a late GET clobbering
      // what the user just typed.
      setLoaded(true)
    })
    return () => { alive = false }
  }, [token, onLogout])

  const save = async () => {
    const trimmed = name.trim()
    if (!trimmed) return setError(t.settings.householdNameRequired)
    setSaving(true)
    setError(null)
    setSaved(false)
    const result = await safeFetch(token, `${API_BASE}/config`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ householdName: trimmed }),
    })
    setSaving(false)
    if (!result.ok) return setError(errorText(null, t.settings.householdSaveFailed))
    if (result.res.status === 401) return onLogout()
    if (!result.res.ok) return setError(errorText(await errorCode(result.res), t.settings.householdSaveFailed))
    const data: { householdName: string } = await result.res.json()
    setName(data.householdName)
    onRenamed(data.householdName)
    setSaved(true)
    setTimeout(() => setSaved(false), 2500)
  }

  return (
    <Card className="hb-card--pad">
      <div className="hb-cardhead">
        <div>
          <h3>{t.settings.householdNameTitle}</h3>
          <p className="hb-muted" style={{ margin: '2px 0 0' }}>{t.settings.householdNameHint}</p>
        </div>
      </div>
      <div style={{ display: 'flex', gap: 12, alignItems: 'flex-end', flexWrap: 'wrap', marginTop: 12 }}>
        <Field label={t.settings.householdNameLabel}>
          <TextInput
            value={name}
            onChange={(v) => { setName(v); setSaved(false); setError(null) }}
            placeholder={t.shell.brandSub}
            onKeyDown={(e) => e.key === 'Enter' && save()}
            disabled={!loaded}
          />
        </Field>
        <Button onClick={save} disabled={saving || !loaded || !name.trim()}>{t.common.save}</Button>
        {saved && (
          <span className="hb-muted" style={{ display: 'inline-flex', alignItems: 'center', gap: 5, paddingBottom: 9 }}>
            <Icon name="check" size={15} stroke={2.4} /> {t.settings.householdSaved}
          </span>
        )}
      </div>
      {error && <p style={{ color: 'oklch(0.55 0.16 32)', fontSize: 13.5, margin: '8px 0 0' }}>{error}</p>}
    </Card>
  )
}
