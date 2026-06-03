import { useState } from 'react'
import { login } from './api'
import { t } from './i18n'
import { TodosView } from './components/TodosView'
import { NotesView } from './components/NotesView'
import { ShoppingView } from './components/ShoppingView'
import { TimeView } from './components/TimeView'
import { RecipesView } from './components/RecipesView'

type Tab = 'todos' | 'shopping' | 'notes' | 'time' | 'recipes'

const TABS: { id: Tab; label: string }[] = [
  { id: 'todos', label: t.nav.todos },
  { id: 'shopping', label: t.nav.shopping },
  { id: 'notes', label: t.nav.notes },
  { id: 'time', label: t.nav.time },
  { id: 'recipes', label: t.nav.recipes },
]

export default function App() {
  const [token, setToken] = useState(() => localStorage.getItem('homebase_token') ?? '')
  const [tab, setTab] = useState<Tab>('todos')

  if (!token) {
    return <LoginView onLogin={(nextToken) => {
      localStorage.setItem('homebase_token', nextToken)
      setToken(nextToken)
    }} />
  }

  const logout = () => {
    localStorage.removeItem('homebase_token')
    setToken('')
  }

  return (
    <div className="pb-16">
      {tab === 'todos' && <TodosView token={token} onLogout={logout} />}
      {tab === 'shopping' && <ShoppingView token={token} onLogout={logout} />}
      {tab === 'notes' && <NotesView token={token} onLogout={logout} />}
      {tab === 'time' && <TimeView token={token} onLogout={logout} />}
      {tab === 'recipes' && <RecipesView token={token} onLogout={logout} />}

      <nav className="fixed bottom-0 inset-x-0 bg-white border-t border-gray-200 flex z-40">
        {TABS.map(({ id, label }) => (
          <button
            key={id}
            onClick={() => setTab(id)}
            className={`flex-1 py-3 text-sm font-medium transition ${
              tab === id ? 'text-indigo-600' : 'text-gray-400 hover:text-gray-600'
            }`}
          >
            {label}
          </button>
        ))}
      </nav>
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
    <div className="min-h-screen bg-gray-50 flex items-center justify-center px-4">
      <div className="bg-white rounded-lg shadow-sm border border-gray-200 w-full max-w-sm p-5">
        <h1 className="text-xl font-semibold text-gray-800">{t.login.title}</h1>
        <div className="mt-4 space-y-3">
          <input
            autoFocus
            type="text"
            placeholder={t.login.username}
            value={username}
            onChange={(e) => setUsername(e.target.value)}
            onKeyDown={(e) => e.key === 'Enter' && submit()}
            className="w-full border border-gray-300 rounded-lg px-3 py-2 text-gray-800 focus:outline-none focus:ring-2 focus:ring-indigo-500"
          />
          <input
            type="password"
            placeholder={t.login.password}
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            onKeyDown={(e) => e.key === 'Enter' && submit()}
            className="w-full border border-gray-300 rounded-lg px-3 py-2 text-gray-800 focus:outline-none focus:ring-2 focus:ring-indigo-500"
          />
          {error && <p className="text-sm text-red-600">{error}</p>}
          <button
            onClick={submit}
            disabled={submitting || !username.trim() || !password}
            className="w-full rounded-lg bg-indigo-600 text-white py-2 font-medium hover:bg-indigo-700 disabled:opacity-50"
          >
            {t.login.submit}
          </button>
        </div>
      </div>
    </div>
  )
}
