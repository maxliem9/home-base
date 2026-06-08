import { useState, useEffect, useCallback } from 'react'
import { API_BASE, errorCode, notifyTransportError, safeFetch } from '../api'
import { t, errorText } from '../i18n'
import { Project, ShoppingItem, TimeEntry, Todo } from '../types'
import { useWebSocket } from '../hooks/useWebSocket'
import { useErrorToast } from '../ui/ErrorToast'
import { Icon } from '../ui/Icon'
import { Avatar, Badge, Button, Card, Checkbox, EmptyState, IconButton, PageHead, PriorityDot } from '../ui/primitives'
import { dueLabel, fmtClock, todayLabel, userMeta, usernameFromToken } from '../ui/format'

const WS_SCHEME = window.location.protocol === 'https:' ? 'wss' : 'ws'
const wsUrl = (channel: string) => `${WS_SCHEME}://${window.location.host}/api/v1/ws/${channel}`

// Tabs the dashboard tiles/cards can jump to. A subset of App's Tab union — the
// `onNavigate` prop is satisfied by App's `setTab` (it accepts the wider type).
type NavTarget = 'todos' | 'shopping' | 'time'

interface DashboardViewProps {
  token: string
  onLogout: () => void
  onNavigate: (tab: NavTarget) => void
}

// time-of-day greeting — thresholds mirror the mock (docs/web/src/views_heute.jsx)
function greeting(hour = new Date().getHours()): string {
  if (hour < 5) return t.dashboard.greetingNight
  if (hour < 11) return t.dashboard.greetingMorning
  if (hour < 17) return t.dashboard.greetingDay
  if (hour < 22) return t.dashboard.greetingEvening
  return t.dashboard.greetingNight
}

const pad2 = (n: number) => String(n).padStart(2, '0')
// local YYYY-MM-DD for a Date — todos carry local calendar dates, and "done
// today" must be judged in the user's timezone, not UTC.
function localIso(d: Date): string {
  return `${d.getFullYear()}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())}`
}

function StatTile({ value, label, icon, onClick }: { value: number; label: string; icon: string; onClick: () => void }) {
  return (
    <button className="hb-stat" onClick={onClick}>
      <div className="hb-stat__icon"><Icon name={icon} size={19} stroke={2} /></div>
      <div className="hb-stat__value hb-mono">{value}</div>
      <div className="hb-stat__label">{label}</div>
    </button>
  )
}

export function DashboardView({ token, onLogout, onNavigate }: DashboardViewProps) {
  const me = usernameFromToken(token)
  const meName = userMeta(me)?.name ?? me ?? 'HomeBase'
  const [todos, setTodos] = useState<Todo[]>([])
  const [shopping, setShopping] = useState<ShoppingItem[]>([])
  const [running, setRunning] = useState<TimeEntry | null>(null)
  const [projects, setProjects] = useState<Project[]>([])
  const [loading, setLoading] = useState(true)
  const [quick, setQuick] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [nowMs, setNowMs] = useState(() => Date.now())
  const { flashError, errorToast } = useErrorToast()

  // Each read is independent; a transport reject fires the global toast once and
  // keeps the existing data (the dashboard stays usable on a flaky connection).
  const fetchTodos = useCallback(async () => {
    const result = await safeFetch(token, `${API_BASE}/todos`)
    if (!result.ok) return notifyTransportError()
    if (result.res.status === 401) return onLogout()
    if (result.res.ok) setTodos(await result.res.json())
  }, [onLogout, token])

  const fetchShopping = useCallback(async () => {
    const result = await safeFetch(token, `${API_BASE}/shopping`)
    if (!result.ok) return notifyTransportError()
    if (result.res.status === 401) return onLogout()
    if (result.res.ok) setShopping(await result.res.json())
  }, [onLogout, token])

  const fetchRunning = useCallback(async () => {
    const result = await safeFetch(token, `${API_BASE}/time/running`)
    if (!result.ok) return notifyTransportError()
    if (result.res.status === 401) return onLogout()
    // 404 = NO_RUNNING_TIMER → no timer is running for this user
    setRunning(result.res.ok ? await result.res.json() : null)
  }, [onLogout, token])

  const fetchProjects = useCallback(async () => {
    const result = await safeFetch(token, `${API_BASE}/time/projects`)
    if (!result.ok) return notifyTransportError()
    if (result.res.status === 401) return onLogout()
    if (result.res.ok) setProjects(await result.res.json())
  }, [onLogout, token])

  // Hold the data-dependent body behind `loading` until the first reads resolve,
  // so the landing page doesn't flash misleading zeros / empty states on every load.
  useEffect(() => {
    Promise.all([fetchTodos(), fetchShopping(), fetchRunning(), fetchProjects()]).finally(() => setLoading(false))
  }, [fetchTodos, fetchShopping, fetchRunning, fetchProjects])

  // Live updates — the dashboard is read-only aggregation, so just refetch the
  // affected resource on any frame rather than fine-grained patching.
  useWebSocket({ url: wsUrl('todos'), token }, fetchTodos)
  useWebSocket({ url: wsUrl('shopping'), token }, fetchShopping)
  useWebSocket({ url: wsUrl('time'), token }, () => {
    fetchRunning()
    fetchProjects() // a project rename/color change should re-style the running widget
  })

  // tick the live clock once a second while a timer runs
  useEffect(() => {
    if (!running) return
    const id = setInterval(() => setNowMs(Date.now()), 1000)
    return () => clearInterval(id)
  }, [running?.id])

  const submitQuick = async () => {
    const title = quick.trim()
    if (!title || submitting) return
    setSubmitting(true)
    try {
      // no listId → the backend creates an INBOX todo (TodoRoutes always sets INBOX)
      const result = await safeFetch(token, `${API_BASE}/todos`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ title }),
      })
      if (!result.ok) return flashError(errorText(null, t.dashboard.addFailed))
      if (result.res.status === 401) return onLogout()
      if (result.res.ok) {
        const created: Todo = await result.res.json()
        setTodos((prev) => (prev.some((x) => x.id === created.id) ? prev : [created, ...prev]))
        setQuick('')
      } else {
        flashError(errorText(await errorCode(result.res), t.dashboard.addFailed))
      }
    } finally {
      setSubmitting(false)
    }
  }

  const markDone = async (todo: Todo) => {
    // optimistic — drop it out of "Heute dran" immediately
    setTodos((prev) => prev.map((x) => (x.id === todo.id ? { ...x, status: 'DONE' } : x)))
    const result = await safeFetch(token, `${API_BASE}/todos/${todo.id}`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ status: 'DONE' }),
    })
    // On failure refetch to resync (a recurring todo also spawns a successor —
    // the WS frame reconciles that too).
    if (!result.ok) {
      await fetchTodos()
      return flashError(errorText(null, t.dashboard.saveFailed))
    }
    if (result.res.status === 401) return onLogout()
    if (result.res.ok) {
      const updated: Todo = await result.res.json()
      setTodos((prev) => prev.map((x) => (x.id === updated.id ? updated : x)))
    } else {
      await fetchTodos()
      flashError(errorText(await errorCode(result.res), t.dashboard.saveFailed))
    }
  }

  const checkItem = async (item: ShoppingItem) => {
    // peek only lists open items, so a tap always checks one off
    setShopping((prev) => prev.map((i) => (i.id === item.id ? { ...i, checked: true } : i)))
    const result = await safeFetch(token, `${API_BASE}/shopping/${item.id}`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ checked: true }),
    })
    if (!result.ok) {
      await fetchShopping()
      return flashError(errorText(null, t.dashboard.saveFailed))
    }
    if (result.res.status === 401) return onLogout()
    if (!result.res.ok) {
      await fetchShopping()
      flashError(errorText(await errorCode(result.res), t.dashboard.saveFailed))
    }
  }

  const stopTimer = async () => {
    setRunning(null) // optimistic; the time WS frame reconciles
    const result = await safeFetch(token, `${API_BASE}/time/entries/stop`, { method: 'POST' })
    if (!result.ok) {
      await fetchRunning()
      return flashError(errorText(null, t.dashboard.saveFailed))
    }
    if (result.res.status === 401) return onLogout()
    if (!result.res.ok) {
      await fetchRunning()
      flashError(errorText(await errorCode(result.res), t.dashboard.saveFailed))
    }
  }

  // --- derived ---
  const tomorrow = new Date()
  tomorrow.setDate(tomorrow.getDate() + 1)
  const tomorrowIso = localIso(tomorrow)
  const todayIso = localIso(new Date())

  const dueToday = todos.filter((x) => x.status !== 'DONE' && dueLabel(x.dueDate)?.tone === 'today')
  const dueTomorrow = todos.filter((x) => x.status !== 'DONE' && x.dueDate === tomorrowIso)
  const inboxCount = todos.filter((x) => x.status === 'INBOX').length
  const doneToday = todos.filter((x) => x.status === 'DONE' && x.doneAt && localIso(new Date(x.doneAt)) === todayIso)
  const openShop = shopping.filter((s) => !s.checked)
  const runningProject = running ? projects.find((p) => p.id === running.projectId) : undefined
  const elapsed = running ? Math.max(0, Math.floor((nowMs - new Date(running.startedAt).getTime()) / 1000)) : 0

  return (
    <div className="hb-page">
      <PageHead eyebrow={todayLabel()} title={`${greeting()}, ${meName}.`} />

      <div className="hb-quickadd" style={{ marginBottom: 26 }}>
        <Icon name="sparkle" size={19} stroke={2} style={{ color: 'var(--accent)' }} />
        <input
          value={quick}
          aria-label={t.dashboard.quickAddPlaceholder}
          placeholder={t.dashboard.quickAddPlaceholder}
          onChange={(e) => setQuick(e.target.value)}
          onKeyDown={(e) => e.key === 'Enter' && submitQuick()}
        />
        <Button size="sm" icon="plus" onClick={submitQuick} disabled={submitting || !quick.trim()}>{t.dashboard.add}</Button>
      </div>

      {loading ? (
        <p className="hb-muted" style={{ textAlign: 'center', padding: 24 }}>{t.common.loading}</p>
      ) : (
        <>
          <div className="hb-stats">
            <StatTile value={dueToday.length} label={t.dashboard.statDueToday} icon="calendar" onClick={() => onNavigate('todos')} />
            <StatTile value={inboxCount} label={t.dashboard.statInbox} icon="inbox" onClick={() => onNavigate('todos')} />
            <StatTile value={dueTomorrow.length} label={t.dashboard.statDueTomorrow} icon="clock" onClick={() => onNavigate('todos')} />
            <StatTile value={doneToday.length} label={t.dashboard.statDoneToday} icon="checkCircle" onClick={() => onNavigate('todos')} />
          </div>

          <div className="hb-heute-grid">
            <div className="hb-stack" style={{ gap: 'var(--gap)' }}>
              {/* Today's tasks */}
              <Card className="hb-card--pad">
                <div className="hb-cardhead">
                  <h3>{t.dashboard.todayTitle}</h3>
                  <button className="hb-link" onClick={() => onNavigate('todos')}>
                    {t.dashboard.allTasks} <Icon name="chevronRight" size={15} stroke={2.2} />
                  </button>
                </div>
                {dueToday.length === 0 ? (
                  <EmptyState icon="checkCircle" title={t.dashboard.todayEmpty} hint={t.dashboard.todayEmptyHint} />
                ) : (
                  <div className="hb-list">
                    {dueToday.map((todo) => (
                      <div key={todo.id} className="hb-row">
                        <Checkbox checked={false} hue={todo.assignee ? userMeta(todo.assignee)?.hue : undefined} onChange={() => markDone(todo)} />
                        <div className="hb-row__main">
                          <div className="hb-row__title">{todo.title}</div>
                          {todo.priority && (
                            <div className="hb-row__meta"><PriorityDot priority={todo.priority} withLabel /></div>
                          )}
                        </div>
                        <div className="hb-row__right">{todo.assignee && <Avatar user={todo.assignee} size={26} />}</div>
                      </div>
                    ))}
                  </div>
                )}
              </Card>

              {/* Digest preview */}
              <Card className="hb-card--pad hb-digest">
                <div className="hb-cardhead">
                  <h3>
                    <Icon name="send" size={17} stroke={2} style={{ verticalAlign: '-2px', marginRight: 7, color: 'var(--accent)' }} />
                    {t.dashboard.digestTitle}
                  </h3>
                  <Badge tone="neutral">{t.dashboard.digestBadge}</Badge>
                </div>
                <p className="hb-muted" style={{ fontSize: 13.5, margin: '2px 0 14px' }}>{t.dashboard.digestSub}</p>
                <div className="hb-digest__body">
                  <div className="hb-digest__line"><span className="hb-digest__k">✓ {t.dashboard.digestDone}</span><span>{doneToday.length}</span></div>
                  <div className="hb-digest__line"><span className="hb-digest__k">＋ {t.dashboard.digestInbox}</span><span>{inboxCount}</span></div>
                  <div className="hb-digest__line"><span className="hb-digest__k">↻ {t.dashboard.digestTomorrow}</span><span>{dueTomorrow.length}</span></div>
                  {dueTomorrow.slice(0, 3).map((todo) => (
                    <div key={todo.id} className="hb-digest__sub">· {todo.title}{todo.assignee ? ` (${userMeta(todo.assignee)?.name ?? todo.assignee})` : ''}</div>
                  ))}
                </div>
              </Card>
            </div>

            <div className="hb-stack" style={{ gap: 'var(--gap)' }}>
              {/* Running timer */}
              <Card className="hb-card--pad">
                <div className="hb-cardhead">
                  <h3>{t.dashboard.timeTitle}</h3>
                  <button className="hb-link" onClick={() => onNavigate('time')}>
                    {t.dashboard.open} <Icon name="chevronRight" size={15} stroke={2.2} />
                  </button>
                </div>
                {running ? (
                  <div className="hb-runwidget">
                    <span className="hb-runwidget__pdot" style={{ background: runningProject?.color ?? 'var(--ink-3)' }} />
                    <div style={{ flex: 1, minWidth: 0 }}>
                      <div className="hb-row__title" style={{ fontWeight: 600 }}>{runningProject?.name ?? t.dashboard.timeTitle}</div>
                      <div className="hb-muted" style={{ fontSize: 13 }}>{running.description || t.dashboard.timerRunningHint}</div>
                    </div>
                    <span className="hb-mono hb-runwidget__clock">{fmtClock(elapsed)}</span>
                    <IconButton icon="stop" label={t.dashboard.stop} onClick={stopTimer} />
                  </div>
                ) : (
                  <EmptyState icon="timer" title={t.dashboard.noTimer} hint={t.dashboard.noTimerHint} />
                )}
              </Card>

              {/* Shopping peek */}
              <Card className="hb-card--pad">
                <div className="hb-cardhead">
                  <h3>{t.dashboard.shoppingTitle}</h3>
                  <button className="hb-link" onClick={() => onNavigate('shopping')}>
                    {t.dashboard.open} <Icon name="chevronRight" size={15} stroke={2.2} />
                  </button>
                </div>
                {openShop.length === 0 ? (
                  <EmptyState icon="cart" title={t.dashboard.shoppingEmpty} />
                ) : (
                  <>
                    <div className="hb-list">
                      {openShop.slice(0, 5).map((item) => (
                        <div key={item.id} className="hb-row" style={{ padding: '9px 4px' }}>
                          <Checkbox checked={false} onChange={() => checkItem(item)} />
                          <div className="hb-row__main"><div className="hb-row__title">{item.name}</div></div>
                        </div>
                      ))}
                    </div>
                    {openShop.length > 5 && (
                      <div className="hb-muted" style={{ fontSize: 13, marginTop: 10, textAlign: 'center' }}>
                        + {openShop.length - 5} {t.dashboard.moreItems}
                      </div>
                    )}
                  </>
                )}
              </Card>
            </div>
          </div>
        </>
      )}

      {errorToast}
    </div>
  )
}
