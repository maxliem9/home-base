// Einstellungen → Benachrichtigungen (#100, Phase 2; extended #182). Sets the household-wide
// scheduler config, all stored in app_settings and re-read by their scheduler each cycle (a change
// applies from the next scheduled run, no restart):
//  - Morning briefing — the "Guten Morgen" overview (due today, overdue, inbox, absences, kita).
//  - Evening digest — the daily recap (done today, new inbox, due tomorrow + a preview of who's
//    absent / whether the kita is closed tomorrow).
//    Each Telegram digest has an in-app on/off toggle, an editable time, and a per-section checkbox
//    group (#182); it only actually sends when enabled AND Telegram is configured server-side
//    (`telegramConfigured`) — the controls stay editable regardless, with an inactive note when not.
//  - Recurring-todo safety-net time — always-on, so no toggle/sections.
// Self-contained.
import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { API_BASE, errorCode, safeFetch } from '../../api'
import { errorText } from '../../i18n'
import { Icon } from '../../ui/Icon'
import { Button, Card, Checkbox, Field, TextInput } from '../../ui/primitives'

export function NotificationsSettings({ token, onLogout }: { token: string; onLogout: () => void }) {
  const { t } = useTranslation()
  return (
    <div style={{ display: 'grid', gap: 16 }}>
      {/* Morning first (chronological), then the evening recap, then the recurring safety-net. */}
      <DigestCard
        token={token}
        onLogout={onLogout}
        endpoint="/config/morning-digest"
        title={t('settings.morningDigestTitle')}
        hint={t('settings.morningDigestHint')}
      />
      <DigestCard
        token={token}
        onLogout={onLogout}
        endpoint="/config/digest"
        title={t('settings.digestTitle')}
        hint={t('settings.digestHint')}
      />
      <RemindersCard token={token} onLogout={onLogout} />
      <RecurringCard token={token} onLogout={onLogout} />
    </div>
  )
}

interface DigestConfig {
  time: string
  enabled: boolean
  telegramConfigured: boolean
  sections: string[]
  availableSections: string[]
}

// One Telegram-digest card, shared by the morning briefing and the evening recap — they differ
// only in endpoint + heading/hint (identical {time, enabled, sections} contract and flow, #182).
// `telegramConfigured` reports whether Telegram is wired up; when not, an inactive note shows but
// every control stays editable (ready for when it is). Save sends time + enabled + sections in one
// PUT.
function DigestCard({
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
  const { t } = useTranslation()
  const [time, setTime] = useState('')
  const [enabled, setEnabled] = useState(true)
  const [telegramConfigured, setTelegramConfigured] = useState(true)
  const [available, setAvailable] = useState<string[]>([])
  // Selected section ids as a Set for cheap membership toggles; serialized back to an array on save.
  const [selected, setSelected] = useState<Set<string>>(new Set())
  const [loaded, setLoaded] = useState(false)
  const [saving, setSaving] = useState(false)
  const [saved, setSaved] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const dirty = () => { setError(null); setSaved(false) }

  useEffect(() => {
    let alive = true
    safeFetch(token, `${API_BASE}${endpoint}`).then(async (result) => {
      if (!alive) return
      if (result.ok && result.res.status === 401) return onLogout()
      if (result.ok && result.res.ok) {
        const data: Partial<DigestConfig> = await result.res.json()
        setTime(data.time ?? '')
        setEnabled(data.enabled ?? false)
        setTelegramConfigured(data.telegramConfigured ?? false)
        // `encodeDefaults=false` (CLAUDE.md) means an empty selection arrives as a missing key.
        setAvailable(data.availableSections ?? [])
        setSelected(new Set(data.sections ?? []))
      }
      // enable editing even if the read failed; inputs stay disabled until here so a late GET
      // can't clobber freshly-typed values (same pattern as the household name).
      setLoaded(true)
    })
    return () => { alive = false }
  }, [token, onLogout, endpoint])

  const toggleSection = (id: string) => {
    setSelected((prev) => {
      const next = new Set(prev)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })
    dirty()
  }

  const save = async () => {
    if (!time) return
    setSaving(true)
    setError(null)
    setSaved(false)
    // Persist in the backend's display order so the stored value is stable + readable.
    const sections = available.filter((id) => selected.has(id))
    const result = await safeFetch(token, `${API_BASE}${endpoint}`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ time, enabled, sections }),
    })
    setSaving(false)
    if (!result.ok) return setError(errorText(null, t('settings.digestSaveFailed')))
    if (result.res.status === 401) return onLogout()
    if (!result.res.ok) return setError(errorText(await errorCode(result.res), t('settings.digestSaveFailed')))
    const data: Partial<DigestConfig> = await result.res.json()
    setTime(data.time ?? time)
    setEnabled(data.enabled ?? enabled)
    setSelected(new Set(data.sections ?? []))
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
      {loaded && !telegramConfigured && (
        <p className="hb-muted" style={{ margin: '12px 0 0' }}>{t('settings.digestDisabled')}</p>
      )}

      {/* On/off toggle (#182): a deselected digest skips entirely; the rest stays editable. */}
      <label style={{ display: 'flex', alignItems: 'center', gap: 10, marginTop: 14, cursor: loaded ? 'pointer' : 'default' }}>
        <Checkbox checked={enabled} onChange={(v) => { if (loaded) { setEnabled(v); dirty() } }} />
        <span>{t('settings.digestEnabledLabel')}</span>
      </label>

      <div style={{ display: 'flex', gap: 12, alignItems: 'flex-end', flexWrap: 'wrap', marginTop: 14 }}>
        <Field label={t('settings.digestTimeLabel')}>
          <TextInput type="time" value={time} onChange={(v) => { setTime(v); dirty() }} disabled={!loaded} />
        </Field>
      </div>

      {/* Per-section checkbox group (#182): which content blocks this digest renders. Deliberately
          NOT wrapped in <Field> — that renders a <label>, and nesting the per-row <label>s inside it
          makes the outer label swallow every row into one giant accessible name. */}
      {available.length > 0 && (
        <div style={{ marginTop: 16 }}>
          <div className="hb-field__label">{t('settings.digestSectionsLabel')}</div>
          <p className="hb-muted" style={{ margin: '2px 0 8px', fontSize: 13 }}>{t('settings.digestSectionsHint')}</p>
          <div style={{ display: 'grid', gap: 8 }}>
            {available.map((id) => (
              <label key={id} style={{ display: 'flex', alignItems: 'center', gap: 10, cursor: loaded ? 'pointer' : 'default' }}>
                <Checkbox checked={selected.has(id)} onChange={() => { if (loaded) toggleSection(id) }} />
                <span>{t(`settings.digestSections.${id}`, { defaultValue: id })}</span>
              </label>
            ))}
          </div>
        </div>
      )}

      <div style={{ display: 'flex', gap: 12, alignItems: 'center', flexWrap: 'wrap', marginTop: 16 }}>
        <Button onClick={save} disabled={saving || !loaded || !time}>{t('common.save')}</Button>
        {saved && (
          <span className="hb-muted" style={{ display: 'inline-flex', alignItems: 'center', gap: 5 }}>
            <Icon name="check" size={15} stroke={2.4} /> {t('settings.digestSaved')}
          </span>
        )}
      </div>
      <p className="hb-muted" style={{ margin: '10px 0 0', fontSize: 13 }}>{t('settings.digestApplies')}</p>
      {error && <p style={{ color: 'oklch(0.55 0.16 32)', fontSize: 13.5, margin: '8px 0 0' }}>{error}</p>}
    </Card>
  )
}

// Recurring-todo safety-net run time. Always-on scheduler, so no enabled flag — otherwise the
// same control/validation/persistence as the digest time.
// Todo reminders (#429 Phase 2a): an on/off toggle + an optional quiet-hours window, delivered via
// the same Telegram bot as the digests. A todo opts in by carrying a due *time*; the reminder fires
// at that time (minus an optional lead). Quiet hours must be set as a pair (both or neither).
function RemindersCard({ token, onLogout }: { token: string; onLogout: () => void }) {
  const { t } = useTranslation()
  const [enabled, setEnabled] = useState(true)
  const [quietStart, setQuietStart] = useState('')
  const [quietEnd, setQuietEnd] = useState('')
  const [loaded, setLoaded] = useState(false)
  const [saving, setSaving] = useState(false)
  const [saved, setSaved] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const dirty = () => { setError(null); setSaved(false) }

  useEffect(() => {
    let alive = true
    safeFetch(token, `${API_BASE}/config/reminders`).then(async (result) => {
      if (!alive) return
      if (result.ok && result.res.status === 401) return onLogout()
      if (result.ok && result.res.ok) {
        const data: { enabled?: boolean; quietStart?: string; quietEnd?: string } = await result.res.json()
        setEnabled(data.enabled ?? true)
        setQuietStart(data.quietStart ?? '')
        setQuietEnd(data.quietEnd ?? '')
      }
      setLoaded(true)
    })
    return () => { alive = false }
  }, [token, onLogout])

  // quiet hours are all-or-nothing: a single bound is invalid (the backend rejects it too)
  const quietIncomplete = !!quietStart !== !!quietEnd

  const save = async () => {
    if (quietIncomplete) return
    setSaving(true)
    setError(null)
    setSaved(false)
    const result = await safeFetch(token, `${API_BASE}/config/reminders`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ enabled, quietStart, quietEnd }),
    })
    setSaving(false)
    if (!result.ok) return setError(errorText(null, t('settings.remindersSaveFailed')))
    if (result.res.status === 401) return onLogout()
    if (!result.res.ok) return setError(errorText(await errorCode(result.res), t('settings.remindersSaveFailed')))
    const data: { enabled: boolean; quietStart?: string; quietEnd?: string } = await result.res.json()
    setEnabled(data.enabled)
    setQuietStart(data.quietStart ?? '')
    setQuietEnd(data.quietEnd ?? '')
    setSaved(true)
    setTimeout(() => setSaved(false), 2500)
  }

  return (
    <Card className="hb-card--pad">
      <div className="hb-cardhead">
        <div>
          <h3>{t('settings.remindersTitle')}</h3>
          <p className="hb-muted" style={{ margin: '2px 0 0' }}>{t('settings.remindersHint')}</p>
        </div>
      </div>
      <label style={{ display: 'flex', alignItems: 'center', gap: 10, marginTop: 14, cursor: loaded ? 'pointer' : 'default' }}>
        <Checkbox checked={enabled} onChange={(v) => { if (loaded) { setEnabled(v); dirty() } }} />
        <span>{t('settings.remindersEnabled')}</span>
      </label>
      <div style={{ display: 'flex', gap: 12, alignItems: 'flex-end', flexWrap: 'wrap', marginTop: 14 }}>
        <Field label={t('settings.remindersQuietStart')}>
          <TextInput type="time" value={quietStart} onChange={(v) => { setQuietStart(v); dirty() }} disabled={!loaded} />
        </Field>
        <Field label={t('settings.remindersQuietEnd')}>
          <TextInput type="time" value={quietEnd} onChange={(v) => { setQuietEnd(v); dirty() }} disabled={!loaded} />
        </Field>
        <Button onClick={save} disabled={saving || !loaded || quietIncomplete}>{t('common.save')}</Button>
        {saved && (
          <span className="hb-muted" style={{ display: 'inline-flex', alignItems: 'center', gap: 5, paddingBottom: 9 }}>
            <Icon name="check" size={15} stroke={2.4} /> {t('settings.remindersSaved')}
          </span>
        )}
      </div>
      <p className="hb-muted" style={{ margin: '10px 0 0', fontSize: 13 }}>{t('settings.remindersQuietHint')}</p>
      {quietIncomplete && <p style={{ color: 'oklch(0.55 0.16 32)', fontSize: 13.5, margin: '8px 0 0' }}>{t('settings.remindersQuietIncomplete')}</p>}
      {error && <p style={{ color: 'oklch(0.55 0.16 32)', fontSize: 13.5, margin: '8px 0 0' }}>{error}</p>}
    </Card>
  )
}

function RecurringCard({ token, onLogout }: { token: string; onLogout: () => void }) {
  const { t } = useTranslation()
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
    if (!result.ok) return setError(errorText(null, t('settings.recurringSaveFailed')))
    if (result.res.status === 401) return onLogout()
    if (!result.res.ok) return setError(errorText(await errorCode(result.res), t('settings.recurringSaveFailed')))
    const data: { time: string } = await result.res.json()
    setTime(data.time)
    setSaved(true)
    setTimeout(() => setSaved(false), 2500)
  }

  return (
    <Card className="hb-card--pad">
      <div className="hb-cardhead">
        <div>
          <h3>{t('settings.recurringTitle')}</h3>
          <p className="hb-muted" style={{ margin: '2px 0 0' }}>{t('settings.recurringHint')}</p>
        </div>
      </div>
      <div style={{ display: 'flex', gap: 12, alignItems: 'flex-end', flexWrap: 'wrap', marginTop: 14 }}>
        <Field label={t('settings.recurringTimeLabel')}>
          <TextInput type="time" value={time} onChange={(v) => { setTime(v); setError(null); setSaved(false) }} disabled={!loaded} />
        </Field>
        <Button onClick={save} disabled={saving || !loaded || !time}>{t('common.save')}</Button>
        {saved && (
          <span className="hb-muted" style={{ display: 'inline-flex', alignItems: 'center', gap: 5, paddingBottom: 9 }}>
            <Icon name="check" size={15} stroke={2.4} /> {t('settings.recurringSaved')}
          </span>
        )}
      </div>
      <p className="hb-muted" style={{ margin: '10px 0 0', fontSize: 13 }}>{t('settings.recurringApplies')}</p>
      {error && <p style={{ color: 'oklch(0.55 0.16 32)', fontSize: 13.5, margin: '8px 0 0' }}>{error}</p>}
    </Card>
  )
}
