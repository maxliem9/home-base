import { useState, useEffect, useCallback } from 'react'
import { API_BASE, authFetch, login, withWsToken } from './api'
import { t } from './i18n'
import { useWebSocket } from './hooks/useWebSocket'
import { Icon } from './ui/Icon'
import { TransportErrorToast } from './ui/TransportErrorToast'
import { Avatar, Button, Card, Field, TextInput } from './ui/primitives'
import { usernameFromToken } from './ui/format'
import { TodosView } from './components/TodosView'
import { NotesView } from './components/NotesView'
import { ShoppingView } from './components/ShoppingView'
import { TimeView } from './components/TimeView'
import { RecipesView } from './components/RecipesView'
import { AbwesenheitView } from './components/abwesenheit/AbwesenheitView'

type Tab = 'todos' | 'shopping' | 'notes' | 'time' | 'recipes' | 'abwesenheit'

const NAV: { id: Tab; label: string; icon: string }[] = [
  { id: 'todos', label: t.nav.todos, icon: 'checkCircle' },
  { id: 'shopping', label: t.nav.shopping, icon: 'cart' },
  { id: 'notes', label: t.nav.notes, icon: 'note' },
  { id: 'time', label: t.nav.time, icon: 'clock' },
  { id: 'recipes', label: t.nav.recipes, icon: 'chef' },
  { id: 'abwesenheit', label: t.nav.abwesenheit, icon: 'calendar' },
]

const WS_SCHEME = window.location.protocol === 'https:' ? 'wss' : 'ws'
const wsUrl = (channel: string) => `${WS_SCHEME}://${window.location.host}/api/v1/ws/${channel}`

interface NavBadges {
  inbox: number
  shopping: number
  timerRunning: boolean
}

function useNavBadges(token: string): NavBadges {
  const [badges, setBadges] = useState<NavBadges>({ inbox: 0, shopping: 0, timerRunning: false })

  const refreshTodos = useCallback(async () => {
    const res = await authFetch(token, `${API_BASE}/todos`)
    if (!res.ok) return
    const todos: { status: string; dueDate?: string }[] = await res.json()
    const today = new Date().toISOString().slice(0, 10)
    // badge counts open todos that are due today or overdue
    const due = todos.filter((x) => x.status !== 'DONE' && x.dueDate && x.dueDate <= today).length
    setBadges((b) => ({ ...b, inbox: due }))
  }, [token])

  const refreshShopping = useCallback(async () => {
    const res = await authFetch(token, `${API_BASE}/shopping`)
    if (!res.ok) return
    const items: { checked: boolean }[] = await res.json()
    setBadges((b) => ({ ...b, shopping: items.filter((x) => !x.checked).length }))
  }, [token])

  const refreshRunning = useCallback(async () => {
    const res = await authFetch(token, `${API_BASE}/time/running`)
    if (!res.ok) return
    const running = await res.json().catch(() => null)
    setBadges((b) => ({ ...b, timerRunning: running != null && !!running.id }))
  }, [token])

  useEffect(() => {
    refreshTodos()
    refreshShopping()
    refreshRunning()
  }, [refreshTodos, refreshShopping, refreshRunning])

  useWebSocket(withWsToken(wsUrl('todos'), token), refreshTodos)
  useWebSocket(withWsToken(wsUrl('shopping'), token), refreshShopping)
  useWebSocket(withWsToken(wsUrl('time'), token), refreshRunning)

  return badges
}

// Fetch the household name from the backend config endpoint.
// Falls back to the i18n default if the request fails (e.g. during local dev
// without a backend running).
async function fetchHouseholdName(token: string): Promise<string> {
  try {
    const res = await authFetch(token, `${API_BASE}/config`)
    if (!res.ok) return t.shell.brandSub
    const data: { householdName: string } = await res.json()
    return data.householdName || t.shell.brandSub
  } catch {
    return t.shell.brandSub
  }
}

export default function App() {
  const [token, setToken] = useState(() => localStorage.getItem('homebase_token') ?? '')
  const [tab, setTab] = useState<Tab>('todos')

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
  const badges = useNavBadges(token)
  const me = usernameFromToken(token)
  const count: Partial<Record<Tab, number>> = { todos: badges.inbox, shopping: badges.shopping }
  const [household, setHousehold] = useState(t.shell.brandSub)

  useEffect(() => {
    fetchHouseholdName(token).then(setHousehold)
  }, [token])

  return (
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
            <button key={n.id} className={`hb-navitem${tab === n.id ? ' is-active' : ''}`} onClick={() => setTab(n.id)}>
              <Icon name={n.icon} size={20} stroke={2} />
              <span>{n.label}</span>
              {n.id === 'time' && badges.timerRunning && (
                <span className="hb-syncdot" style={{ animation: 'none', background: 'var(--clay)' }} title={t.shell.timerRunning} />
              )}
              {count[n.id] ? <span className="hb-navitem__badge">{count[n.id]}</span> : null}
            </button>
          ))}
        </nav>

        <div className="hb-side-foot">
          <button className="hb-userchip" onClick={onLogout} title={t.common.logout}>
            <Avatar user={me} size={34} />
            <div>
              <div className="hb-userchip__name">{me ?? 'HomeBase'}</div>
              <div className="hb-userchip__sub">{t.shell.syncActive}</div>
            </div>
            <span className="hb-syncdot" title={t.shell.syncActive} />
          </button>
        </div>
      </aside>

      <main className="hb-main">
        {tab === 'todos' && <TodosView token={token} onLogout={onLogout} />}
        {tab === 'shopping' && <ShoppingView token={token} onLogout={onLogout} />}
        {tab === 'notes' && <NotesView token={token} onLogout={onLogout} />}
        {tab === 'time' && <TimeView token={token} onLogout={onLogout} />}
        {tab === 'recipes' && <RecipesView token={token} onLogout={onLogout} />}
        {tab === 'abwesenheit' && <AbwesenheitView token={token} onLogout={onLogout} />}
      </main>

      {/* Single global toast for background GET/read transport failures (issue #93). */}
      <TransportErrorToast />
    </div>
  )
}

function LoginView({ onLogin }: { onLogin: (token: string) => void }) {
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
      setError(e instanceof Error ? e.message : t.login.failed)
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
            <div className="hb-brand__name">{t.login.title}</div>
            <div className="hb-brand__sub">{t.login.subtitle}</div>
          </div>
        </div>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
          <Field label={t.login.username}>
            <TextInput
              autoFocus
              value={username}
              onChange={setUsername}
              placeholder={t.login.username}
              onKeyDown={(e) => e.key === 'Enter' && submit()}
            />
          </Field>
          <Field label={t.login.password}>
            <TextInput
              type="password"
              value={password}
              onChange={setPassword}
              placeholder={t.login.password}
              onKeyDown={(e) => e.key === 'Enter' && submit()}
            />
          </Field>
          {error && <p style={{ color: 'oklch(0.55 0.16 32)', fontSize: 13.5, margin: 0 }}>{error}</p>}
          <Button onClick={submit} disabled={submitting || !username.trim() || !password} style={{ width: '100%' }}>
            {t.login.submit}
          </Button>
        </div>
      </Card>
    </div>
  )
}
