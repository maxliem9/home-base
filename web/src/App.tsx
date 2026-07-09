import { useState, useEffect, useCallback, useMemo } from 'react'
import { useTranslation } from 'react-i18next'
import type { TFunction } from 'i18next'
import { API_BASE, authFetch, safeFetch, login } from './api'
import { t } from './i18n'
import { useWebSocket } from './hooks/useWebSocket'
import { useTheme } from './hooks/useTheme'
import { AvatarHuesProvider } from './hooks/useAvatarHues'
import { Icon } from './ui/Icon'
import { TransportErrorToast } from './ui/TransportErrorToast'
import { Avatar, Button, Card, Field, Modal, Sheet, TextInput } from './ui/primitives'
import { usernameFromToken } from './ui/format'
import { DashboardView } from './components/DashboardView'
import { TodosView, type TodosFocus } from './components/TodosView'
import { NotesView } from './components/NotesView'
import { ShoppingView } from './components/ShoppingView'
import { TimeView } from './components/TimeView'
import { RecipesView } from './components/RecipesView'
import { WochenplanView } from './components/Wochenplan/WochenplanView'
import { FamilienkalenderView } from './components/Familienkalender/FamilienkalenderView'
import { AbwesenheitView } from './components/abwesenheit/AbwesenheitView'
import { SettingsView, type SettingsTab } from './components/settings/SettingsView'
import { CommandPalette, type PaletteAction } from './components/CommandPalette'
import { KIND_TAB, type SearchItem } from './search'

type Tab = 'heute' | 'todos' | 'shopping' | 'notes' | 'time' | 'recipes' | 'wochenplan' | 'familienkalender' | 'abwesenheit'

// HB-09 — the mobile bottom bar shows these core areas (in this order) plus a "Mehr"
// button; everything else moves into the "Mehr" sheet so 8+ areas never overflow / clip.
// #270 — "Zeit" (time tracker) sits in the bottom bar; "Kalender" (abwesenheit) lives
// under "Mehr". (Desktop sidebar is unaffected — it lists every NAV entry.)
const CORE_TABS: Tab[] = ['heute', 'todos', 'shopping', 'time']

// Built inside Shell with the reactive `t` so the labels follow a language switch.
const buildNav = (t: TFunction): { id: Tab; label: string; shortLabel: string; icon: string }[] => [
  { id: 'heute', label: t('nav.dashboard'), shortLabel: t('nav.short.dashboard'), icon: 'home' },
  { id: 'todos', label: t('nav.todos'), shortLabel: t('nav.short.todos'), icon: 'checkCircle' },
  { id: 'shopping', label: t('nav.shopping'), shortLabel: t('nav.short.shopping'), icon: 'cart' },
  { id: 'time', label: t('nav.time'), shortLabel: t('nav.short.time'), icon: 'clock' },
  { id: 'notes', label: t('nav.notes'), shortLabel: t('nav.short.notes'), icon: 'note' },
  { id: 'recipes', label: t('nav.recipes'), shortLabel: t('nav.short.recipes'), icon: 'chef' },
  { id: 'wochenplan', label: t('nav.wochenplan'), shortLabel: t('nav.short.wochenplan'), icon: 'utensils' },
  { id: 'familienkalender', label: t('nav.familienkalender'), shortLabel: t('nav.short.familienkalender'), icon: 'calendar' },
  { id: 'abwesenheit', label: t('nav.abwesenheit'), shortLabel: t('nav.short.abwesenheit'), icon: 'sun' },
]

const WS_SCHEME = window.location.protocol === 'https:' ? 'wss' : 'ws'
const wsUrl = (channel: string) => `${WS_SCHEME}://${window.location.host}/api/v1/ws/${channel}`

interface NavBadges {
  inbox: number
  shopping: number
  timerRunning: boolean
}

// Offline-seed the nav badges (#520) from the views' own durable read-caches, so a cold start with no
// connection shows the last-known counts instead of 0 until the (failing) background poll returns. The
// keys mirror TodosView/ShoppingView's caches (homebase_todos_cache / homebase_shopping_cache); the
// count logic matches refreshTodos/refreshShopping below. The timer dot has no cache yet (the Zeit view
// isn't cached), so it stays off offline. Best-effort — any parse issue just yields 0.
function readCachedBadges(): { inbox: number; shopping: number } {
  const today = new Date().toISOString().slice(0, 10)
  let inbox = 0
  let shopping = 0
  try {
    const raw = localStorage.getItem('homebase_todos_cache')
    if (raw) {
      const { todos = [] } = JSON.parse(raw) as { todos?: { status: string; dueDate?: string }[] }
      inbox = todos.filter((x) => x.status !== 'DONE' && x.dueDate && x.dueDate <= today).length
    }
  } catch {
    /* corrupt / private mode → leave 0 */
  }
  try {
    const raw = localStorage.getItem('homebase_shopping_cache')
    if (raw) {
      const { items = [] } = JSON.parse(raw) as { items?: { checked: boolean }[] }
      shopping = items.filter((x) => !x.checked).length
    }
  } catch {
    /* corrupt / private mode → leave 0 */
  }
  return { inbox, shopping }
}

function useNavBadges(token: string): NavBadges {
  const [badges, setBadges] = useState<NavBadges>(() => ({ ...readCachedBadges(), timerRunning: false }))

  const refreshTodos = useCallback(async () => {
    // Badges are a non-critical background poll (initial effect + WS callback); on a
    // transport reject (offline/DNS) bail silently rather than throwing an unhandled
    // rejection. No toast — these fire often and the view-level reads already surface
    // connectivity issues via the global transport toast. (#114)
    const result = await safeFetch(token, `${API_BASE}/todos`)
    if (!result.ok || !result.res.ok) return
    const todos: { status: string; dueDate?: string }[] = await result.res.json()
    const today = new Date().toISOString().slice(0, 10)
    // badge counts open todos that are due today or overdue
    const due = todos.filter((x) => x.status !== 'DONE' && x.dueDate && x.dueDate <= today).length
    setBadges((b) => ({ ...b, inbox: due }))
  }, [token])

  const refreshShopping = useCallback(async () => {
    const result = await safeFetch(token, `${API_BASE}/shopping`)
    if (!result.ok || !result.res.ok) return
    const items: { checked: boolean }[] = await result.res.json()
    setBadges((b) => ({ ...b, shopping: items.filter((x) => !x.checked).length }))
  }, [token])

  const refreshRunning = useCallback(async () => {
    const result = await safeFetch(token, `${API_BASE}/time/running/all`)
    if (!result.ok || !result.res.ok) return
    const running: { userId?: string }[] = await result.res.json().catch(() => [])
    // the badge dot marks the *current* user's own running timer
    const me = usernameFromToken(token)
    setBadges((b) => ({ ...b, timerRunning: Array.isArray(running) && running.some((e) => e.userId === me) }))
  }, [token])

  useEffect(() => {
    refreshTodos()
    refreshShopping()
    refreshRunning()
  }, [refreshTodos, refreshShopping, refreshRunning])

  useWebSocket({ url: wsUrl('todos'), token }, refreshTodos)
  useWebSocket({ url: wsUrl('shopping'), token }, refreshShopping)
  useWebSocket({ url: wsUrl('time'), token }, refreshRunning)

  return badges
}

// Fetch the household name from the backend config endpoint.
// Falls back to the i18n default if the request fails (e.g. during local dev
// without a backend running).
async function fetchHouseholdName(token: string): Promise<string> {
  try {
    const res = await authFetch(token, `${API_BASE}/config`)
    if (!res.ok) return t('shell.brandSub')
    const data: { householdName: string } = await res.json()
    return data.householdName || t('shell.brandSub')
  } catch {
    return t('shell.brandSub')
  }
}

export default function App() {
  const [token, setToken] = useState(() => localStorage.getItem('homebase_token') ?? '')
  const [tab, setTab] = useState<Tab>('heute')

  if (!token) {
    return (
      <LoginView
        onLogin={(nextToken) => {
          localStorage.setItem('homebase_token', nextToken)
          setToken(nextToken)
        }}
      />
    )
  }

  const logout = () => {
    localStorage.removeItem('homebase_token')
    setToken('')
  }

  return <Shell token={token} tab={tab} setTab={setTab} onLogout={logout} />
}

function Shell({ token, tab, setTab, onLogout }: { token: string; tab: Tab; setTab: (t: Tab) => void; onLogout: () => void }) {
  const { t } = useTranslation()
  const NAV = useMemo(() => buildNav(t), [t])
  // HB-09 — split the mobile bottom bar into core areas + a "Mehr" overflow sheet.
  const coreNav = useMemo(() => NAV.filter((n) => CORE_TABS.includes(n.id)), [NAV])
  const moreNav = useMemo(() => NAV.filter((n) => !CORE_TABS.includes(n.id)), [NAV])
  const badges = useNavBadges(token)
  // Per-user theme (#100): load + apply on mount, follow the OS live while 'system'.
  // Lifted here (always mounted when logged in) so it applies app-wide; the selector
  // in Konto-Einstellungen drives the same state.
  const themeCtl = useTheme(token)
  const me = usernameFromToken(token)
  const count: Partial<Record<Tab, number>> = { todos: badges.inbox, shopping: badges.shopping }
  const [household, setHousehold] = useState(t('shell.brandSub'))
  const [confirmLogout, setConfirmLogout] = useState(false)
  // Central settings hub (#99): a separate surface reached via the gear in the
  // account corner — not a primary nav tab. null = closed; opening a nav tab closes it.
  const [settings, setSettings] = useState<SettingsTab | null>(null)
  // The gear opens the general Haushalt subpage; a view's deep-link can target its own
  // subpage (e.g. the tracker's Wochensoll card → 'time').
  const openSettings = (tab: SettingsTab = 'household') => setSettings(tab)

  // ⌘K / Ctrl-K command palette (HB-03) — global search + quick navigation.
  const [paletteOpen, setPaletteOpen] = useState(false)
  const [moreOpen, setMoreOpen] = useState(false)
  // Deep-link focus the todos view should open on (dashboard stat tiles → #255/#256).
  // Plain navigation clears it (lands on the default tab); the tiles set it via goTodos.
  const [todosFocus, setTodosFocus] = useState<TodosFocus | null>(null)
  const go = useCallback((next: Tab) => { setSettings(null); setMoreOpen(false); setTodosFocus(null); setTab(next) }, [setTab])
  const goTodos = useCallback((focus: TodosFocus) => { setSettings(null); setMoreOpen(false); setTodosFocus(focus); setTab('todos') }, [setTab])
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && (e.key === 'k' || e.key === 'K')) {
        e.preventDefault()
        setPaletteOpen((o) => !o)
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [])
  const paletteActions: PaletteAction[] = useMemo(
    () => [
      ...NAV.map((n) => ({ id: `nav:${n.id}`, label: n.label, icon: n.icon, run: () => go(n.id) })),
      { id: 'settings', label: t('nav.settings'), icon: 'settings', run: () => openSettings() },
    ],
    [NAV, go, t],
  )
  const onOpenResult = useCallback((item: SearchItem) => go(KIND_TAB[item.kind]), [go])

  useEffect(() => {
    fetchHouseholdName(token).then(setHousehold)
  }, [token])

  return (
    <AvatarHuesProvider token={token}>
    <div className="hb-app">
      <aside className="hb-sidebar">
        <div className="hb-brand">
          <div className="hb-brand__mark"><Icon name="home" size={21} stroke={2.2} /></div>
          <div>
            <div className="hb-brand__name">HomeBase</div>
            <div className="hb-brand__sub">{household}</div>
          </div>
        </div>

        <nav className="hb-nav">
          {NAV.map((n) => (
            <button key={n.id} className={`hb-navitem${!settings && tab === n.id ? ' is-active' : ''}`} onClick={() => go(n.id)}>
              <Icon name={n.icon} size={20} stroke={2} />
              <span>{n.label}</span>
              {n.id === 'time' && badges.timerRunning && (
                <span className="hb-syncdot" style={{ animation: 'none', background: 'var(--clay)' }} title={t('shell.timerRunning')} />
              )}
              {count[n.id] ? <span className="hb-navitem__badge">{count[n.id]}</span> : null}
            </button>
          ))}
        </nav>

        <div className="hb-side-foot">
          <button className={`hb-navitem hb-side-foot__settings${settings ? ' is-active' : ''}`} onClick={() => openSettings()}>
            <Icon name="settings" size={20} stroke={2} />
            <span>{t('nav.settings')}</span>
          </button>
          <button className="hb-userchip" onClick={() => setConfirmLogout(true)} title={t('common.logout')}>
            <Avatar user={me} size={34} />
            <div>
              <div className="hb-userchip__name">{me ?? 'HomeBase'}</div>
              <div className="hb-userchip__sub">{t('shell.syncActive')}</div>
            </div>
            <span className="hb-syncdot" title={t('shell.syncActive')} />
          </button>
        </div>
      </aside>

      <main className="hb-main">
        {/* Mobile top bar — only visible ≤860px (CSS), where the sidebar is hidden.
            Surfaces the brand + logout, since the sidebar foot chip is gone. */}
        <header className="hb-topbar">
          <div className="hb-brand">
            <div className="hb-brand__mark"><Icon name="home" size={19} stroke={2.2} /></div>
            <div>
              <div className="hb-brand__name">HomeBase</div>
              <div className="hb-brand__sub">{household}</div>
            </div>
          </div>
          <div className="hb-topbar__actions">
            <button
              className="hb-iconbtn hb-topbar__search"
              onClick={() => setPaletteOpen(true)}
              aria-label={t('palette.open')}
              title={t('palette.open')}
            >
              <Icon name="search" size={20} stroke={2} />
            </button>
            <button
              className={`hb-iconbtn hb-topbar__gear${settings ? ' is-active' : ''}`}
              onClick={() => openSettings()}
              aria-label={t('nav.settings')}
              title={t('nav.settings')}
            >
              <Icon name="settings" size={20} stroke={2} />
            </button>
            <button className="hb-userchip hb-topbar__user" onClick={() => setConfirmLogout(true)} title={t('common.logout')} aria-label={t('common.logout')}>
              <Avatar user={me} size={32} />
              <div className="hb-userchip__text">
                <div className="hb-userchip__name">{me ?? 'HomeBase'}</div>
                <div className="hb-userchip__sub">{t('shell.syncActive')}</div>
              </div>
              <span className="hb-syncdot" title={t('shell.syncActive')} />
            </button>
          </div>
        </header>

        {settings ? (
          <SettingsView
            token={token}
            active={settings}
            onChangeTab={setSettings}
            onClose={() => setSettings(null)}
            onLogout={onLogout}
            onHouseholdRenamed={setHousehold}
            theme={themeCtl.theme}
            themeLoaded={themeCtl.loaded}
            onChangeTheme={themeCtl.setTheme}
          />
        ) : (
          <>
            {tab === 'heute' && <DashboardView token={token} onLogout={onLogout} onNavigate={go} onOpenTodos={goTodos} />}
            {tab === 'todos' && <TodosView token={token} onLogout={onLogout} initialFocus={todosFocus} />}
            {tab === 'shopping' && <ShoppingView token={token} onLogout={onLogout} />}
            {tab === 'notes' && <NotesView token={token} onLogout={onLogout} />}
            {tab === 'time' && <TimeView token={token} onLogout={onLogout} onOpenSettings={() => openSettings('time')} />}
            {tab === 'recipes' && <RecipesView token={token} onLogout={onLogout} />}
            {tab === 'wochenplan' && <WochenplanView token={token} onLogout={onLogout} />}
            {tab === 'familienkalender' && <FamilienkalenderView token={token} onLogout={onLogout} />}
            {tab === 'abwesenheit' && <AbwesenheitView token={token} onLogout={onLogout} />}
          </>
        )}
      </main>

      {/* Mobile bottom tab bar — only visible ≤860px (CSS), the sidebar's replacement
          navigation. Same tab state, badges and running-timer dot as the sidebar. */}
      <nav className="hb-tabbar" aria-label={t('nav.main')}>
        {coreNav.map((n) => (
          <button
            key={n.id}
            className={`hb-tabbar__item${!settings && tab === n.id ? ' is-active' : ''}`}
            onClick={() => go(n.id)}
            aria-current={!settings && tab === n.id ? 'page' : undefined}
          >
            <span className="hb-tabbar__icon">
              <Icon name={n.icon} size={22} stroke={2} />
              {n.id === 'time' && badges.timerRunning && (
                <span className="hb-tabbar__dot" title={t('shell.timerRunning')} />
              )}
              {count[n.id] ? <span className="hb-tabbar__badge">{count[n.id]}</span> : null}
            </span>
            <span className="hb-tabbar__label">{n.shortLabel}</span>
          </button>
        ))}
        {/* HB-09 — overflow areas live behind "Mehr"; it highlights while one of them is active. */}
        <button
          className={`hb-tabbar__item${!settings && moreNav.some((n) => n.id === tab) ? ' is-active' : ''}`}
          onClick={() => setMoreOpen(true)}
          aria-haspopup="dialog"
          aria-expanded={moreOpen}
        >
          <span className="hb-tabbar__icon">
            <Icon name="more" size={22} stroke={2} />
            {/* #270 — Zeit is now a core tab carrying its own running-timer dot, so
                "Mehr" no longer needs one (its overflow areas have no timer). */}
          </span>
          <span className="hb-tabbar__label">{t('nav.short.more')}</span>
        </button>
      </nav>

      {/* HB-09 — "Mehr" sheet listing the overflow nav areas. */}
      {moreOpen && (
        <Sheet open onClose={() => setMoreOpen(false)} title={t('nav.more')}>
          <div className="hb-morenav">
            {moreNav.map((n) => (
              <button
                key={n.id}
                className={`hb-morenav__item${!settings && tab === n.id ? ' is-active' : ''}`}
                onClick={() => go(n.id)}
                aria-current={!settings && tab === n.id ? 'page' : undefined}
              >
                <Icon name={n.icon} size={20} stroke={2} />
                <span className="hb-morenav__label">{n.label}</span>
                {n.id === 'time' && badges.timerRunning && (
                  <span className="hb-syncdot" style={{ animation: 'none', background: 'var(--clay)' }} title={t('shell.timerRunning')} />
                )}
                {count[n.id] ? <span className="hb-navitem__badge">{count[n.id]}</span> : null}
              </button>
            ))}
          </div>
        </Sheet>
      )}

      {/* Global ⌘K command palette (HB-03). */}
      <CommandPalette
        token={token}
        open={paletteOpen}
        onClose={() => setPaletteOpen(false)}
        actions={paletteActions}
        onOpenResult={onOpenResult}
      />

      {/* Single global toast for background GET/read transport failures (issue #93). */}
      <TransportErrorToast />

      {/* Confirm before ending the session — guards against an accidental tap on
          the user chip (sidebar on desktop, top bar on mobile). */}
      <Modal
        open={confirmLogout}
        onClose={() => setConfirmLogout(false)}
        title={t('shell.logoutTitle')}
        width={400}
        footer={
          <>
            <Button variant="ghost" onClick={() => setConfirmLogout(false)}>{t('common.cancel')}</Button>
            <Button variant="primary" icon="logout" onClick={onLogout}>{t('common.logout')}</Button>
          </>
        }
      >
        <p style={{ margin: 0 }}>{t('shell.logoutBody')}</p>
      </Modal>
    </div>
    </AvatarHuesProvider>
  )
}

function LoginView({ onLogin }: { onLogin: (token: string) => void }) {
  const { t } = useTranslation()
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const submit = async () => {
    if (!username.trim() || !password) return
    setSubmitting(true)
    setError(null)
    try {
      const response = await login(username.trim(), password)
      onLogin(response.token)
    } catch (e) {
      setError(e instanceof Error ? e.message : t('login.failed'))
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div className="hb-login">
      <Card className="hb-card--pad hb-login__card">
        <div className="hb-login__brand">
          <div className="hb-brand__mark"><Icon name="home" size={21} stroke={2.2} /></div>
          <div>
            <div className="hb-brand__name">{t('login.title')}</div>
            <div className="hb-brand__sub">{t('login.subtitle')}</div>
          </div>
        </div>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
          <Field label={t('login.username')}>
            <TextInput
              autoFocus
              value={username}
              onChange={setUsername}
              placeholder={t('login.username')}
              onKeyDown={(e) => e.key === 'Enter' && submit()}
            />
          </Field>
          <Field label={t('login.password')}>
            <TextInput
              type="password"
              value={password}
              onChange={setPassword}
              placeholder={t('login.password')}
              onKeyDown={(e) => e.key === 'Enter' && submit()}
            />
          </Field>
          {error && <p style={{ color: 'oklch(0.55 0.16 32)', fontSize: 13.5, margin: 0 }}>{error}</p>}
          <Button onClick={submit} disabled={submitting || !username.trim() || !password} style={{ width: '100%' }}>
            {t('login.submit')}
          </Button>
        </div>
      </Card>
    </div>
  )
}
