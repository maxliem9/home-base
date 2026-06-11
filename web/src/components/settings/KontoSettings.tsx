// Einstellungen → Konto (#100, Phase 2). Per-user account page: change your own
// password. The current password is verified server-side (PUT /users/me/password);
// the JWT is stateless and stays valid, so no re-login is forced. Self-contained.
import { useState } from 'react'
import { API_BASE, errorCode, safeFetch } from '../../api'
import { t, errorText } from '../../i18n'
import { usernameFromToken } from '../../ui/format'
import { Icon } from '../../ui/Icon'
import { Avatar, Button, Card, Field, TextInput } from '../../ui/primitives'

const MIN_PASSWORD_LENGTH = 8

export function KontoSettings({ token, onLogout }: { token: string; onLogout: () => void }) {
  const me = usernameFromToken(token)
  const [current, setCurrent] = useState('')
  const [next, setNext] = useState('')
  const [confirm, setConfirm] = useState('')
  const [saving, setSaving] = useState(false)
  const [done, setDone] = useState(false)
  const [error, setError] = useState<string | null>(null)

  // clear the success/error hint as soon as the user edits any field again
  const onEdit = (set: (v: string) => void) => (v: string) => { set(v); setError(null); setDone(false) }

  const submit = async () => {
    setError(null)
    setDone(false)
    if (next.length < MIN_PASSWORD_LENGTH) return setError(t.settings.passwordTooShort)
    if (next !== confirm) return setError(t.settings.passwordMismatch)
    setSaving(true)
    const result = await safeFetch(token, `${API_BASE}/users/me/password`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ currentPassword: current, newPassword: next }),
    })
    setSaving(false)
    if (!result.ok) return setError(errorText(null, t.settings.passwordChangeFailed))
    if (result.res.status === 401) return onLogout()
    if (!result.res.ok) return setError(errorText(await errorCode(result.res), t.settings.passwordChangeFailed))
    setCurrent('')
    setNext('')
    setConfirm('')
    setDone(true)
    setTimeout(() => setDone(false), 3000)
  }

  const canSubmit = !saving && !!current && !!next && !!confirm

  return (
    <Card className="hb-card--pad">
      <div className="hb-cardhead">
        <div>
          <h3>{t.settings.passwordTitle}</h3>
          <p className="hb-muted" style={{ margin: '2px 0 0' }}>{t.settings.passwordHint}</p>
        </div>
        {me && (
          <span className="hb-muted" style={{ display: 'inline-flex', alignItems: 'center', gap: 8, whiteSpace: 'nowrap' }}>
            <Avatar user={me} size={24} /> {t.settings.accountSignedInAs} {me}
          </span>
        )}
      </div>
      <div className="hb-stack" style={{ gap: 12, marginTop: 14, maxWidth: 360 }}>
        <Field label={t.settings.passwordCurrent}>
          <TextInput type="password" value={current} onChange={onEdit(setCurrent)} />
        </Field>
        <Field label={t.settings.passwordNew}>
          <TextInput type="password" value={next} onChange={onEdit(setNext)} />
        </Field>
        <Field label={t.settings.passwordConfirm}>
          <TextInput
            type="password"
            value={confirm}
            onChange={onEdit(setConfirm)}
            onKeyDown={(e) => e.key === 'Enter' && canSubmit && submit()}
          />
        </Field>
        <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
          <Button icon="lock" onClick={submit} disabled={!canSubmit}>{t.settings.passwordChange}</Button>
          {done && (
            <span className="hb-muted" style={{ display: 'inline-flex', alignItems: 'center', gap: 5 }}>
              <Icon name="check" size={15} stroke={2.4} /> {t.settings.passwordChanged}
            </span>
          )}
        </div>
        {error && <p style={{ color: 'oklch(0.55 0.16 32)', fontSize: 13.5, margin: 0 }}>{error}</p>}
      </div>
    </Card>
  )
}
