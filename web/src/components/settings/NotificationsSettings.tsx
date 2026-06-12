// Einstellungen → Benachrichtigungen (#100, Phase 2). Sets the household-wide scheduler
// times, all stored in app_settings and re-read by their scheduler each cycle (a change
// applies from the next scheduled run, no restart):
//  - Morning briefing time — the "Guten Morgen" overview (due today, overdue, inbox,
//    absences, kita closures).
//  - Evening digest time — the daily recap (done today, new inbox, due tomorrow).
//    Both Telegram digests only actually send when Telegram is configured server-side
//    (`enabled`); the times stay editable regardless.
//  - Recurring-todo safety-net time — always-on, so no enabled flag.
// Self-contained.
import { useEffect, useState } from 'react'
import { API_BASE, errorCode, safeFetch } from '../../api'
import { t, errorText } from '../../i18n'
import { Icon } from '../../ui/Icon'
import { Button, Card, Field, TextInput } from '../../ui/primitives'

export function NotificationsSettings({ token, onLogout }: { token: string; onLogout: () => void }) {
  return (
    <div style={{ display: 'grid', gap: 16 }}>
      {/* Morning first (chronological), then the evening recap, then the recurring safety-net. */}
      <DigestTimeCard
        token={token}
        onLogout={onLogout}
        endpoint="/config/morning-digest"
        title={t.settings.morningDigestTitle}
        hint={t.settings.morningDigestHint}
      />
      <DigestTimeCard
        token={token}
        onLogout={onLogout}
        endpoint="/config/digest"
        title={t.settings.digestTitle}
        hint={t.settings.digestHint}
      />
      <RecurringCard token={token} onLogout={onLogout} />
    </div>
  )
}

// One Telegram-digest time card, shared by the morning briefing and the evening recap — they
// differ only in endpoint + heading/hint (identical {time, enabled} contract and flow).
// `enabled` reports whether Telegram is configured at all; when not, an inactive note shows but
// the time stays editable (ready for when it is).
function DigestTimeCard({
  token,
  onLogout,
  endpoint,
  title,
  hint,
}: {
  token: string
  onLogout: () => void
  endpoint: string
  title: string
  hint: string
}) {
  const [time, setTime] = useState('')
  const [enabled, setEnabled] = useState(true)
  const [loaded, setLoaded] = useState(false)
  const [saving, setSaving] = useState(false)
  const [saved, setSaved] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let alive = true
    safeFetch(token, `${API_BASE}${endpoint}`).then(async (result) => {
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
  }, [token, onLogout, endpoint])

  const save = async () => {
    if (!time) return
    setSaving(true)
    setError(null)
    setSaved(false)
    const result = await safeFetch(token, `${API_BASE}${endpoint}`, {
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
          <h3>{title}</h3>
          <p className="hb-muted" style={{ margin: '2px 0 0' }}>{hint}</p>
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

// Recurring-todo safety-net run time. Always-on scheduler, so no enabled flag — otherwise the
// same control/validation/persistence as the digest time.
function RecurringCard({ token, onLogout }: { token: string; onLogout: () => void }) {
  const [time, setTime] = useState('')
  const [loaded, setLoaded] = useState(false)
  const [saving, setSaving] = useState(false)
  const [saved, setSaved] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let alive = true
    safeFetch(token, `${API_BASE}/config/recurring`).then(async (result) => {
      if (!alive) return
      if (result.ok && result.res.status === 401) return onLogout()
      if (result.ok && result.res.ok) {
        const data: { time?: string } = await result.res.json()
        setTime(data.time ?? '')
      }
      // enable editing even if the read failed; the input stays disabled until here so a
      // late GET can't clobber a freshly-typed value (same pattern as the digest time).
      setLoaded(true)
    })
    return () => { alive = false }
  }, [token, onLogout])

  const save = async () => {
    if (!time) return
    setSaving(true)
    setError(null)
    setSaved(false)
    const result = await safeFetch(token, `${API_BASE}/config/recurring`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ time }),
    })
    setSaving(false)
    if (!result.ok) return setError(errorText(null, t.settings.recurringSaveFailed))
    if (result.res.status === 401) return onLogout()
    if (!result.res.ok) return setError(errorText(await errorCode(result.res), t.settings.recurringSaveFailed))
    const data: { time: string } = await result.res.json()
    setTime(data.time)
    setSaved(true)
    setTimeout(() => setSaved(false), 2500)
  }

  return (
    <Card className="hb-card--pad">
      <div className="hb-cardhead">
        <div>
          <h3>{t.settings.recurringTitle}</h3>
          <p className="hb-muted" style={{ margin: '2px 0 0' }}>{t.settings.recurringHint}</p>
        </div>
      </div>
      <div style={{ display: 'flex', gap: 12, alignItems: 'flex-end', flexWrap: 'wrap', marginTop: 14 }}>
        <Field label={t.settings.recurringTimeLabel}>
          <TextInput type="time" value={time} onChange={(v) => { setTime(v); setError(null); setSaved(false) }} disabled={!loaded} />
        </Field>
        <Button onClick={save} disabled={saving || !loaded || !time}>{t.common.save}</Button>
        {saved && (
          <span className="hb-muted" style={{ display: 'inline-flex', alignItems: 'center', gap: 5, paddingBottom: 9 }}>
            <Icon name="check" size={15} stroke={2.4} /> {t.settings.recurringSaved}
          </span>
        )}
      </div>
      <p className="hb-muted" style={{ margin: '10px 0 0', fontSize: 13 }}>{t.settings.recurringApplies}</p>
      {error && <p style={{ color: 'oklch(0.55 0.16 32)', fontSize: 13.5, margin: '8px 0 0' }}>{error}</p>}
    </Card>
  )
}
