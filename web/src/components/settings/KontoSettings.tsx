// Einstellungen → Konto (#100, Phase 2). Per-user account page: pick the UI theme
// (light/dark/system, persisted as a user_pref and applied app-wide) and change your
// own password. The current password is verified server-side (PUT /users/me/password);
// the JWT is stateless and stays valid, so no re-login is forced. Self-contained.
import { useState } from 'react'
import { API_BASE, errorCode, safeFetch } from '../../api'
import { t, errorText } from '../../i18n'
import { usernameFromToken } from '../../ui/format'
import type { Theme } from '../../ui/theme'
import { Icon } from '../../ui/Icon'
import { Avatar, Button, Card, Field, SegmentedControl, TextInput } from '../../ui/primitives'

const MIN_PASSWORD_LENGTH = 8

export function KontoSettings({ token, onLogout, theme, themeLoaded, onChangeTheme }: {
  token: string
  onLogout: () => void
  theme: Theme
  themeLoaded: boolean
  onChangeTheme: (next: Theme) => Promise<boolean>
}) {
  const me = usernameFromToken(token)
  const [themeError, setThemeError] = useState(false)
  const pickTheme = async (next: Theme) => {
    if (next === theme) return
    setThemeError(false)
    // useTheme applies optimistically; only flag if the persistence failed.
    const ok = await onChangeTheme(next)
    if (!ok) setThemeError(true)
  }
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
    <div className="hb-stack" style={{ gap: 18 }}>
      <Card className="hb-card--pad">
        <div className="hb-cardhead">
          <div>
            <h3>{t.settings.themeTitle}</h3>
            <p className="hb-muted" style={{ margin: '2px 0 0' }}>{t.settings.themeHint}</p>
          </div>
        </div>
        <div style={{ marginTop: 14 }}>
          {/* A plain label span, NOT a <Field> (<label>) — wrapping a segmented
              control's buttons in a <label> leaks the label onto each button's
              accessible name. The segments are self-labeling. */}
          <div className="hb-field__label" style={{ marginBottom: 6 }}>{t.settings.themeLabel}</div>
          <SegmentedControl<Theme>
            value={theme}
            onChange={pickTheme}
            options={[
              { value: 'light', label: t.settings.themeLight },
              { value: 'dark', label: t.settings.themeDark },
              { value: 'system', label: t.settings.themeSystem },
            ]}
          />
          {!themeLoaded && <p className="hb-muted" style={{ margin: '8px 0 0', fontSize: 13 }}>{t.common.loading}</p>}
          {themeError && (
            <p style={{ color: 'oklch(0.55 0.16 32)', fontSize: 13.5, margin: '8px 0 0' }}>{t.settings.themeSaveFailed}</p>
          )}
        </div>
      </Card>

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
    </div>
  )
}
