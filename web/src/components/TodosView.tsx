import { useState, useEffect, useCallback } from 'react'
import { API_BASE, authFetch, withWsToken } from '../api'
import { t } from '../i18n'
import { Todo, TodoStatus, TodoPriority } from '../types'
import { useWebSocket } from '../hooks/useWebSocket'
import { Icon } from '../ui/Icon'
import {
  Avatar,
  Badge,
  Button,
  Card,
  Checkbox,
  EmptyState,
  Field,
  IconButton,
  Modal,
  PageHead,
  PriorityDot,
  SegmentedControl,
  Select,
  TextInput,
} from '../ui/primitives'
import { dueLabel } from '../ui/format'

const WS_SCHEME = window.location.protocol === 'https:' ? 'wss' : 'ws'
const WS_URL = import.meta.env.VITE_WS_URL ?? `${WS_SCHEME}://${window.location.host}/api/v1/ws/todos`

interface PlanDraft {
  id: string
  assignee: string
  dueDate: string
  priority: '' | TodoPriority
}

interface TodosViewProps {
  token: string
  onLogout: () => void
}

export function TodosView({ token, onLogout }: TodosViewProps) {
  const [todos, setTodos] = useState<Todo[]>([])
  const [loading, setLoading] = useState(true)
  const [segment, setSegment] = useState<TodoStatus>('INBOX')
  const [newTitle, setNewTitle] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [plan, setPlan] = useState<PlanDraft | null>(null)

  const fetchTodos = useCallback(async () => {
    try {
      const res = await authFetch(token, `${API_BASE}/todos`)
      if (res.status === 401) {
        onLogout()
        return
      }
      if (!res.ok) return
      setTodos(await res.json())
    } finally {
      setLoading(false)
    }
  }, [onLogout, token])

  useEffect(() => { fetchTodos() }, [fetchTodos])

  useWebSocket(withWsToken(WS_URL, token), (raw) => {
    try {
      const msg = JSON.parse(raw)
      if (!msg.payload) return
      if (msg.type === 'TODO_CREATED') {
        setTodos((prev) => (prev.some((x) => x.id === msg.payload.id) ? prev : [msg.payload, ...prev]))
      } else if (msg.type === 'TODO_UPDATED') {
        setTodos((prev) =>
          prev.some((x) => x.id === msg.payload.id)
            ? prev.map((x) => (x.id === msg.payload.id ? msg.payload : x))
            : [msg.payload, ...prev],
        )
      } else if (msg.type === 'TODO_DELETED') {
        setTodos((prev) => prev.filter((x) => x.id !== msg.payload.id))
      }
    } catch {
      // ignore malformed frames
    }
  })

  const patchTodo = async (id: string, body: Record<string, unknown>) => {
    const res = await authFetch(token, `${API_BASE}/todos/${id}`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    })
    if (res.ok) {
      const updated: Todo = await res.json()
      setTodos((prev) => prev.map((x) => (x.id === updated.id ? updated : x)))
    }
  }

  const handleAdd = async () => {
    if (!newTitle.trim()) return
    setSubmitting(true)
    try {
      const res = await authFetch(token, `${API_BASE}/todos`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ title: newTitle.trim() }),
      })
      if (res.ok) {
        const created: Todo = await res.json()
        setTodos((prev) => [created, ...prev])
      }
      setNewTitle('')
    } finally {
      setSubmitting(false)
    }
  }

  const toggleDone = (todo: Todo) => {
    if (todo.status === 'DONE') {
      patchTodo(todo.id, { status: todo.dueDate || todo.assignee ? 'PLANNED' : 'INBOX' })
    } else {
      patchTodo(todo.id, { status: 'DONE' })
    }
  }

  const handlePlan = async () => {
    if (!plan) return
    if (!plan.assignee.trim() && !plan.dueDate) return
    await patchTodo(plan.id, {
      status: 'PLANNED',
      assignee: plan.assignee.trim() || undefined,
      dueDate: plan.dueDate || undefined,
      priority: plan.priority || undefined,
    })
    setPlan(null)
  }

  const deleteTodo = async (id: string) => {
    setTodos((prev) => prev.filter((x) => x.id !== id))
    await authFetch(token, `${API_BASE}/todos/${id}`, { method: 'DELETE' })
  }

  const inbox = todos.filter((x) => x.status === 'INBOX')
  const planned = todos
    .filter((x) => x.status === 'PLANNED')
    .sort((a, b) => {
      if (a.dueDate && b.dueDate) return a.dueDate.localeCompare(b.dueDate)
      if (a.dueDate) return -1
      if (b.dueDate) return 1
      return 0
    })
  const done = todos
    .filter((x) => x.status === 'DONE')
    .sort((a, b) => (b.doneAt ?? '').localeCompare(a.doneAt ?? ''))

  const visible = segment === 'INBOX' ? inbox : segment === 'PLANNED' ? planned : done
  const emptyText =
    segment === 'INBOX' ? t.todos.emptyInbox : segment === 'PLANNED' ? t.todos.emptyPlanned : t.todos.emptyDone

  return (
    <div className="hb-page">
      <PageHead
        eyebrow={`${inbox.length} ${t.todos.open}`}
        title={t.todos.title}
        actions={
          <SegmentedControl
            value={segment}
            onChange={setSegment}
            options={[
              { value: 'INBOX', label: t.todos.segInbox, count: inbox.length },
              { value: 'PLANNED', label: t.todos.segPlanned, count: planned.length },
              { value: 'DONE', label: t.todos.segDone, count: done.length },
            ]}
          />
        }
      />

      <div className="hb-quickadd" style={{ marginBottom: 22 }}>
        <Icon name="plus" size={18} stroke={2.2} style={{ color: 'var(--ink-3)' }} />
        <input
          value={newTitle}
          placeholder={t.todos.quickAddPlaceholder}
          onChange={(e) => setNewTitle(e.target.value)}
          onKeyDown={(e) => e.key === 'Enter' && handleAdd()}
        />
        <Button size="sm" onClick={handleAdd} disabled={submitting || !newTitle.trim()}>{t.common.add}</Button>
      </div>

      <Card className="hb-card--pad">
        {loading ? (
          <p className="hb-muted" style={{ textAlign: 'center', padding: 24 }}>{t.common.loading}</p>
        ) : visible.length === 0 ? (
          <EmptyState
            icon={segment === 'DONE' ? 'checkCircle' : 'inbox'}
            title={emptyText}
            hint={segment === 'INBOX' ? t.todos.addHint : undefined}
          />
        ) : (
          <div className="hb-list">
            {visible.map((todo) => {
              const due = dueLabel(todo.dueDate)
              return (
                <div key={todo.id} className={`hb-row${todo.status === 'DONE' ? ' hb-row--done' : ''}`}>
                  <Checkbox checked={todo.status === 'DONE'} onChange={() => toggleDone(todo)} />
                  <div className="hb-row__main">
                    <div className="hb-row__title">{todo.title}</div>
                    <div className="hb-row__meta">
                      {todo.assignee && (
                        <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}>
                          <Avatar user={todo.assignee} size={18} />
                          {todo.assignee}
                        </span>
                      )}
                      {due && <Badge tone={due.tone}>{due.text}</Badge>}
                      {todo.priority && <PriorityDot priority={todo.priority} withLabel />}
                      {todo.status === 'INBOX' && <span>{t.common.by} {todo.createdBy}</span>}
                    </div>
                  </div>
                  <div className="hb-row__right">
                    {todo.status === 'INBOX' && (
                      <Button
                        variant="soft"
                        size="sm"
                        icon="calendar"
                        onClick={() => setPlan({ id: todo.id, assignee: todo.assignee ?? '', dueDate: todo.dueDate ?? '', priority: todo.priority ?? '' })}
                      >
                        {t.todos.plan}
                      </Button>
                    )}
                    <div className="hb-row__actions">
                      <IconButton icon="trash" label={t.common.delete} danger onClick={() => deleteTodo(todo.id)} />
                    </div>
                  </div>
                </div>
              )
            })}
          </div>
        )}
      </Card>

      <Modal
        open={!!plan}
        onClose={() => setPlan(null)}
        title={t.todos.planTitle}
        footer={
          <>
            <Button variant="ghost" onClick={() => setPlan(null)}>{t.common.cancel}</Button>
            <Button onClick={handlePlan} disabled={!plan || (!plan.assignee.trim() && !plan.dueDate)}>{t.todos.plan}</Button>
          </>
        }
      >
        {plan && (
          <>
            <p className="hb-muted" style={{ margin: 0, fontSize: 13.5 }}>{t.todos.planHint}</p>
            <Field label={t.todos.assignee}>
              <TextInput autoFocus value={plan.assignee} onChange={(v) => setPlan({ ...plan, assignee: v })} placeholder={t.todos.assigneePlaceholder} />
            </Field>
            <Field label={t.todos.dueDate}>
              <TextInput type="date" value={plan.dueDate} onChange={(v) => setPlan({ ...plan, dueDate: v })} />
            </Field>
            <Field label={t.todos.priority}>
              <Select value={plan.priority} onChange={(v) => setPlan({ ...plan, priority: v as PlanDraft['priority'] })}>
                <option value="">{t.todos.priorityNone}</option>
                <option value="LOW">LOW</option>
                <option value="MEDIUM">MEDIUM</option>
                <option value="HIGH">HIGH</option>
              </Select>
            </Field>
          </>
        )}
      </Modal>
    </div>
  )
}
