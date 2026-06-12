// Einstellungen → Abwesenheit (#99). The calendar's configuration, relocated into the
// central hub. Uses the shared useAbsenceData hook + the AbwSettings panel, and provides
// its own year selector since the per-person settings (Kontingent/Übertrag/…) are annual.
import { useMemo, useState } from 'react'
import { t } from '../../i18n'
import { Card } from '../../ui/primitives'
import { Icon } from '../../ui/Icon'
import { buildContext } from '../abwesenheit/core'
import { AbwSettings } from '../abwesenheit/AbwSettings'
import { useAbsenceData } from '../abwesenheit/useAbsenceData'

// Same window the backend accepts for settings, so the year stepper can never
// produce a year the settings PUT would reject.
const YEAR_MIN = 2000
const YEAR_MAX = 2200
const clampYear = (y: number): number => Math.min(YEAR_MAX, Math.max(YEAR_MIN, y))

export function AbwesenheitSettings({ token, onLogout }: { token: string; onLogout: () => void }) {
  const { data, loading, api, errorToast } = useAbsenceData(token, onLogout)
  const [year, setYear] = useState(() => new Date().getFullYear())
  const userIds = data.users
  const ctx = useMemo(() => buildContext(data, year, userIds), [data, year, userIds])

  return (
    <div className="hb-stack" style={{ gap: 'var(--gap)' }}>
      <Card className="hb-card--pad">
        <div className="hb-cardhead">
          <div>
            <h3>{t.settings.absenceTitle}</h3>
            <p className="hb-muted" style={{ margin: '2px 0 0' }}>{t.settings.absenceHint}</p>
          </div>
          <div className="abw-yearnav">
            <button className="hb-iconbtn" onClick={() => setYear((y) => clampYear(y - 1))} aria-label={t.abwesenheit.prevYear}>
              <Icon name="chevronLeft" size={17} stroke={2.2} />
            </button>
            <span className="abw-yearnav__y hb-mono">{year}</span>
            <button className="hb-iconbtn" onClick={() => setYear((y) => clampYear(y + 1))} aria-label={t.abwesenheit.nextYear}>
              <Icon name="chevronRight" size={17} stroke={2.2} />
            </button>
          </div>
        </div>
        {loading ? (
          <p className="hb-muted" style={{ margin: '12px 0 0' }}>{t.common.loading}</p>
        ) : userIds.length === 0 ? (
          <p className="hb-muted" style={{ margin: '12px 0 0' }}>{t.abwesenheit.loadError}</p>
        ) : (
          <div style={{ marginTop: 14 }}>
            <AbwSettings ctx={ctx} data={data} api={api} userIds={userIds} year={year} />
          </div>
        )}
      </Card>
      {errorToast}
    </div>
  )
}
