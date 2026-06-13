// Einstellungen → Haushalt (#100). Edits the household name (the sidebar
// brand), shared by both users, and shows a read-only overview of the
// household's members (avatar + display name). On save it calls onRenamed
// so the live brand in the shell updates without a reload.
import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { API_BASE, errorCode, safeFetch } from '../../api'
import { errorText } from '../../i18n'
import { useHouseholdUsers } from '../../hooks/useHouseholdUsers'
import { userMeta } from '../../ui/format'
import { Icon } from '../../ui/Icon'
import { Avatar, Button, Card, Field, TextInput } from '../../ui/primitives'

export function HouseholdSettings({ token, onLogout, onRenamed }: {
  token: string
  onLogout: () => void
  onRenamed: (name: string) => void
}) {
  const { t } = useTranslation()
  const [name, setName] = useState('')
  const [loaded, setLoaded] = useState(false)
  const [saving, setSaving] = useState(false)
  const [saved, setSaved] = useState(false)
  const [error, setError] = useState<string | null>(null)

  // Roster for the members overview — reuses the same hook that drives assignee
  // chips elsewhere, so the list stays consistent with the rest of the app.
  const members = useHouseholdUsers(token)

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
    if (!trimmed) return setError(t('settings.householdNameRequired'))
    setSaving(true)
    setError(null)
    setSaved(false)
    const result = await safeFetch(token, `${API_BASE}/config`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ householdName: trimmed }),
    })
    setSaving(false)
    if (!result.ok) return setError(errorText(null, t('settings.householdSaveFailed')))
    if (result.res.status === 401) return onLogout()
    if (!result.res.ok) return setError(errorText(await errorCode(result.res), t('settings.householdSaveFailed')))
    const data: { householdName: string } = await result.res.json()
    setName(data.householdName)
    onRenamed(data.householdName)
    setSaved(true)
    setTimeout(() => setSaved(false), 2500)
  }

  return (
    <div className="hb-stack" style={{ gap: 18 }}>
      <Card className="hb-card--pad">
        <div className="hb-cardhead">
          <div>
            <h3>{t('settings.householdNameTitle')}</h3>
            <p className="hb-muted" style={{ margin: '2px 0 0' }}>{t('settings.householdNameHint')}</p>
          </div>
        </div>
        <div style={{ display: 'flex', gap: 12, alignItems: 'flex-end', flexWrap: 'wrap', marginTop: 12 }}>
          <Field label={t('settings.householdNameLabel')}>
            <TextInput
              value={name}
              onChange={(v) => { setName(v); setSaved(false); setError(null) }}
              placeholder={t('shell.brandSub')}
              onKeyDown={(e) => e.key === 'Enter' && save()}
              disabled={!loaded}
              maxLength={60}
            />
          </Field>
          <Button onClick={save} disabled={saving || !loaded || !name.trim()}>{t('common.save')}</Button>
          {saved && (
            <span className="hb-muted" style={{ display: 'inline-flex', alignItems: 'center', gap: 5, paddingBottom: 9 }}>
              <Icon name="check" size={15} stroke={2.4} /> {t('settings.householdSaved')}
            </span>
          )}
        </div>
        {error && <p style={{ color: 'oklch(0.55 0.16 32)', fontSize: 13.5, margin: '8px 0 0' }}>{error}</p>}
      </Card>

      <Card className="hb-card--pad hb-members-card">
        <div className="hb-cardhead">
          <div>
            <h3>{t('settings.householdMembersTitle')}</h3>
            <p className="hb-muted" style={{ margin: '2px 0 0' }}>{t('settings.householdMembersHint')}</p>
          </div>
        </div>
        <ul style={{ listStyle: 'none', margin: '12px 0 0', padding: 0, display: 'flex', flexDirection: 'column', gap: 10 }}>
          {members.map((username) => {
            const meta = userMeta(username)
            return (
              <li key={username} style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                <Avatar user={username} size={32} />
                <span style={{ fontSize: 15, fontWeight: 500 }}>{meta?.name ?? username}</span>
              </li>
            )
          })}
        </ul>
      </Card>
    </div>
  )
}
