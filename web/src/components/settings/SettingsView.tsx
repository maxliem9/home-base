// Zentrale Einstellungen (#99). A dedicated hub reached via the gear in the
// account corner (not a primary nav tab), split into subpages by domain. Only
// rarely-changed configuration lives here; workflow objects (todo lists, the
// time tracker itself) stay in their own views. Subpages so far: Haushalt (#100),
// Konto (#100) and Zeiterfassung (#99). The left sub-rail is built to grow.
import { t } from '../../i18n'
import { Icon } from '../../ui/Icon'
import { Button, PageHead } from '../../ui/primitives'
import { HouseholdSettings } from './HouseholdSettings'
import { KontoSettings } from './KontoSettings'
import { TimeSettings } from './TimeSettings'

export type SettingsTab = 'household' | 'account' | 'time'

const SUBNAV: { id: SettingsTab; label: string; icon: string }[] = [
  { id: 'household', label: t.settings.household, icon: 'home' },
  { id: 'account', label: t.settings.account, icon: 'lock' },
  { id: 'time', label: t.settings.time, icon: 'clock' },
]

export function SettingsView({ token, active, onChangeTab, onClose, onLogout, onHouseholdRenamed }: {
  token: string
  active: SettingsTab
  onChangeTab: (tab: SettingsTab) => void
  onClose: () => void
  onLogout: () => void
  onHouseholdRenamed: (name: string) => void
}) {
  const current = SUBNAV.find((s) => s.id === active) ?? SUBNAV[0]
  return (
    <div className="hb-page">
      <PageHead
        eyebrow={t.settings.title}
        title={current.label}
        actions={<Button variant="ghost" size="sm" icon="x" onClick={onClose}>{t.common.close}</Button>}
      />
      <div className="hb-settings-grid">
        <nav className="hb-settings-nav" aria-label={t.settings.title}>
          {SUBNAV.map((s) => (
            <button
              key={s.id}
              className={`hb-navitem${active === s.id ? ' is-active' : ''}`}
              onClick={() => onChangeTab(s.id)}
            >
              <Icon name={s.icon} size={20} stroke={2} />
              <span>{s.label}</span>
            </button>
          ))}
        </nav>
        <div className="hb-settings-body">
          {active === 'household' && <HouseholdSettings token={token} onLogout={onLogout} onRenamed={onHouseholdRenamed} />}
          {active === 'account' && <KontoSettings token={token} onLogout={onLogout} />}
          {active === 'time' && <TimeSettings token={token} onLogout={onLogout} />}
        </div>
      </div>
    </div>
  )
}
