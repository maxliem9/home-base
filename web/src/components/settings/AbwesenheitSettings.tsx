// Einstellungen → Abwesenheit (#99). The calendar's configuration, relocated into the
// central hub. Uses the shared useAbsenceData hook + the AbwSettings panel, and provides
// its own year selector since the per-person settings (Kontingent/Übertrag/…) are annual.
import { useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Card } from '../../ui/primitives'
import { buildContext } from '../abwesenheit/core'
import { AbwSettings } from '../abwesenheit/AbwSettings'
import { YearStepper } from '../abwesenheit/YearStepper'
import { useAbsenceData } from '../abwesenheit/useAbsenceData'

export function AbwesenheitSettings({ token, onLogout }: { token: string; onLogout: () => void }) {
  const { t } = useTranslation()
  const { data, loading, api, errorToast } = useAbsenceData(token, onLogout)
  const [year, setYear] = useState(() => new Date().getFullYear())
  const userIds = data.users
  const ctx = useMemo(() => buildContext(data, year, userIds), [data, year, userIds])

  return (
    <div className="hb-stack" style={{ gap: 'var(--gap)' }}>
      <Card className="hb-card--pad">
        <div className="hb-cardhead">
          <div>
            <h3>{t('settings.absenceTitle')}</h3>
            <p className="hb-muted" style={{ margin: '2px 0 0' }}>{t('settings.absenceHint')}</p>
          </div>
          <YearStepper year={year} onChange={setYear} />
        </div>
        {loading ? (
          <p className="hb-muted" style={{ margin: '12px 0 0' }}>{t('common.loading')}</p>
        ) : userIds.length === 0 ? (
          <p className="hb-muted" style={{ margin: '12px 0 0' }}>{t('abwesenheit.loadError')}</p>
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
