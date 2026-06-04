import { useState, useEffect, useCallback } from 'react'
import { API_BASE, authFetch, withWsToken } from '../api'
import { t } from '../i18n'
import { Todo, TodoList, TodoStatus, TodoPriority, Subtask } from '../types'
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

// 'ALL' = every todo, 'NONE' = todos without a list, otherwise a list id
const FILTER_ALL = 'ALL'
const FILTER_NONE = 'NONE'

const LIST_COLORS = ['#6366f1', '#0ea5e9', '#10b981', '#f59e0b', '#ef4444', '#ec4899', '#8b5cf6', '#64748b']

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
  const [lists, setLists] = useState<TodoList[]>([])
  const [loading, setLoading] = useState(true)
  const [segment, setSegment] = useState<TodoStatus>('INBOX')
  const [listFilter, setListFilter] = useState<string>(FILTER_ALL)
  const [newTitle, setNewTitle] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [plan, setPlan] = useState<PlanDraft | null>(null)
  const [expanded, setExpanded] = useState<Set<string>>(new Set())
  const [subDrafts, setSubDrafts] = useState<Record<string, string>>({})
  const [listModal, setListModal] = useState(false)
  const [newListName, setNewListName] = useState('')
  const [newListColor, setNewListColor] = useState(LIST_COLORS[0])

  const fetchTodos = useCallback(async () => {
    try {
      const [todoRes, listRes] = await Promise.all([
        authFetch(token, `${API_BASE}/todos`),
        authFetch(token, `${API_BASE}/todos/lists`),
      ])
      if (todoRes.status === 401 || listRes.status === 401) {
        onLogout()
        return
      }
      if (todoRes.ok) setTodos(await todoRes.json())
      if (listRes.ok) setLists(await listRes.json())
    } finally {
      setLoading(false)
    }
  }, [onLogout, token])

  useEffect(() => { fetchTodos() }, [fetchTodos])

  useWebSocket(withWsToken(WS_URL, token), (raw) => {
    try {
      const msg = JSON.parse(raw)
      if (!msg.payload) return
      switch (msg.type) {
        case 'TODO_CREATED':
          setTodos((prev) => (prev.some((x) => x.id === msg.payload.id) ? prev : [msg.payload, ...prev]))
          break
        case 'TODO_UPDATED':
          setTodos((prev) =>
            prev.some((x) => x.id === msg.payload.id)
              ? prev.map((x) => (x.id === msg.payload.id ? msg.payload : x))
              : [msg.payload, ...prev],
          )
          break
        case 'TODO_DELETED':
          setTodos((prev) => prev.filter((x) => x.id !== msg.payload.id))
          break
        case 'TODO_LIST_CREATED':
          setLists((prev) => (prev.some((x) => x.id === msg.payload.id) ? prev : [...prev, msg.payload]))
          break
        case 'TODO_LIST_UPDATED':
          setLists((prev) => prev.map((x) => (x.id === msg.payload.id ? msg.payload : x)))
          break
        case 'TODO_LIST_DELETED':
          setLists((prev) => prev.filter((x) => x.id !== msg.payload.id))
          setTodos((prev) => prev.map((x) => (x.listId === msg.payload.id ? { ...x, listId: undefined } : x)))
          setListFilter((f) => (f === msg.payload.id ? FILTER_ALL : f))
          break
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
      const body: Record<string, unknown> = { title: newTitle.trim() }
      if (listFilter !== FILTER_ALL && listFilter !== FILTER_NONE) body.listId = listFilter
      const res = await authFetch(token, `${API_BASE}/todos`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
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

  // --- Subtasks ---
  const applyTodo = (updated: Todo) => setTodos((prev) => prev.map((x) => (x.id === updated.id ? updated : x)))

  const addSubtask = async (todoId: string) => {
    const title = (subDrafts[todoId] ?? '').trim()
    if (!title) return
    const res = await authFetch(token, `${API_BASE}/todos/${todoId}/subtasks`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ title }),
    })
    if (res.ok) {
      applyTodo(await res.json())
      setSubDrafts((d) => ({ ...d, [todoId]: '' }))
    }
  }

  const toggleSubtask = async (todoId: string, sub: Subtask) => {
    const res = await authFetch(token, `${API_BASE}/todos/${todoId}/subtasks/${sub.id}`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ done: !sub.done }),
    })
    if (res.ok) applyTodo(await res.json())
  }

  const deleteSubtask = async (todoId: string, subId: string) => {
    const res = await authFetch(token, `${API_BASE}/todos/${todoId}/subtasks/${subId}`, { method: 'DELETE' })
    if (res.ok) applyTodo(await res.json())
  }

  const toggleExpand = (id: string) =>
    setExpanded((prev) => {
      const next = new Set(prev)
      next.has(id) ? next.delete(id) : next.add(id)
      return next
    })

  // --- Lists ---
  const createList = async () => {
    if (!newListName.trim()) return
    const res = await authFetch(token, `${API_BASE}/todos/lists`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name: newListName.trim(), color: newListColor }),
    })
    if (res.ok) {
      const created: TodoList = await res.json()
      setLists((prev) => (prev.some((x) => x.id === created.id) ? prev : [...prev, created]))
      setNewListName('')
      setNewListColor(LIST_COLORS[0])
    }
  }

  const deleteList = async (id: string) => {
    const res = await authFetch(token, `${API_BASE}/todos/lists/${id}`, { method: 'DELETE' })
    if (res.ok) {
      setLists((prev) => prev.filter((x) => x.id !== id))
      setTodos((prev) => prev.map((x) => (x.listId === id ? { ...x, listId: undefined } : x)))
      setListFilter((f) => (f === id ? FILTER_ALL : f))
    }
  }

  const listById = (id?: string) => lists.find((l) => l.id === id)

  const matchesList = (x: Todo) =>
    listFilter === FILTER_ALL ? true : listFilter === FILTER_NONE ? !x.listId : x.listId === listFilter

  const base = todos.filter(matchesList)
  const inbox = base.filter((x) => x.status === 'INBOX')
  const planned = base
    .filter((x) => x.status === 'PLANNED')
    .sort((a, b) => {
      if (a.dueDate && b.dueDate) return a.dueDate.localeCompare(b.dueDate)
      if (a.dueDate) return -1
      if (b.dueDate) return 1
      return 0
    })
  const done = base
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

      <div className="hb-listbar">
        <button
          className={`hb-listchip${listFilter === FILTER_ALL ? ' is-active' : ''}`}
          onClick={() => setListFilter(FILTER_ALL)}
        >
          {t.todos.allLists}
        </button>
        {lists.map((l) => (
          <button
            key={l.id}
            className={`hb-listchip${listFilter === l.id ? ' is-active' : ''}`}
            onClick={() => setListFilter(l.id)}
          >
            <span className="hb-listdot" style={{ background: l.color }} />
            {l.name}
          </button>
        ))}
        {lists.length > 0 && (
          <button
            className={`hb-listchip${listFilter === FILTER_NONE ? ' is-active' : ''}`}
            onClick={() => setListFilter(FILTER_NONE)}
          >
            {t.todos.noList}
          </button>
        )}
        <button className="hb-listchip hb-listchip--add" onClick={() => setListModal(true)}>
          <Icon name="plus" size={15} stroke={2.2} />
          {t.todos.newList}
        </button>
      </div>

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
              const subs = todo.subtasks ?? []
              const doneCount = subs.filter((s) => s.done).length
              const isOpen = expanded.has(todo.id)
              const list = listById(todo.listId)
              return (
                <div key={todo.id} className="hb-todo">
                  <div className={`hb-row${todo.status === 'DONE' ? ' hb-row--done' : ''}`}>
                    <Checkbox checked={todo.status === 'DONE'} onChange={() => toggleDone(todo)} />
                    <div className="hb-row__main">
                      <div className="hb-row__title">{todo.title}</div>
                      <div className="hb-row__meta">
                        {list && (
                          <span className="hb-listtag">
                            <span className="hb-listdot" style={{ background: list.color }} />
                            {list.name}
                          </span>
                        )}
                        {todo.assignee && (
                          <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}>
                            <Avatar user={todo.assignee} size={18} />
                            {todo.assignee}
                          </span>
                        )}
                        {due && <Badge tone={due.tone}>{due.text}</Badge>}
                        {todo.priority && <PriorityDot priority={todo.priority} withLabel />}
                        {subs.length > 0 && (
                          <button className="hb-subbadge" onClick={() => toggleExpand(todo.id)}>
                            <Icon name="checkCircle" size={13} stroke={2} />
                            {doneCount}/{subs.length}
                          </button>
                        )}
                        {todo.status === 'INBOX' && <span>{t.common.by} {todo.createdBy}</span>}
                      </div>
                    </div>
                    <div className="hb-row__right">
                      {lists.length > 0 && (
                        <span className="hb-listpick">
                          <span className="hb-listdot" style={{ background: list?.color ?? 'var(--line)' }} />
                          <select
                            value={todo.listId ?? ''}
                            onChange={(e) => patchTodo(todo.id, { listId: e.target.value })}
                          >
                            <option value="">{t.todos.noList}</option>
                            {lists.map((l) => (
                              <option key={l.id} value={l.id}>{l.name}</option>
                            ))}
                          </select>
                        </span>
                      )}
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
                      <IconButton
                        icon={isOpen ? 'chevronDown' : 'chevronRight'}
                        label={t.todos.subtasks}
                        active={isOpen}
                        onClick={() => toggleExpand(todo.id)}
                      />
                      <div className="hb-row__actions">
                        <IconButton icon="trash" label={t.common.delete} danger onClick={() => deleteTodo(todo.id)} />
                      </div>
                    </div>
                  </div>

                  {isOpen && (
                    <div className="hb-subtasks">
                      {subs.map((s) => (
                        <div key={s.id} className={`hb-subtask${s.done ? ' hb-subtask--done' : ''}`}>
                          <Checkbox checked={s.done} onChange={() => toggleSubtask(todo.id, s)} />
                          <span className="hb-subtask__title">{s.title}</span>
                          <IconButton icon="trash" label={t.common.delete} danger size={15} onClick={() => deleteSubtask(todo.id, s.id)} />
                        </div>
                      ))}
                      <div className="hb-subadd">
                        <Icon name="plus" size={15} stroke={2.2} style={{ color: 'var(--ink-3)' }} />
                        <input
                          value={subDrafts[todo.id] ?? ''}
                          placeholder={t.todos.addSubtask}
                          onChange={(e) => setSubDrafts((d) => ({ ...d, [todo.id]: e.target.value }))}
                          onKeyDown={(e) => e.key === 'Enter' && addSubtask(todo.id)}
                        />
                      </div>
                    </div>
                  )}
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

      <Modal
        open={listModal}
        onClose={() => setListModal(false)}
        title={t.todos.manageLists}
        footer={<Button variant="ghost" onClick={() => setListModal(false)}>{t.common.close}</Button>}
      >
        <Field label={t.todos.listName}>
          <TextInput
            value={newListName}
            onChange={setNewListName}
            placeholder={t.todos.listNamePlaceholder}
            onKeyDown={(e) => e.key === 'Enter' && createList()}
          />
        </Field>
        <Field label={t.todos.listColor}>
          <div className="hb-swatches">
            {LIST_COLORS.map((c) => (
              <button
                key={c}
                type="button"
                className={`hb-swatch${newListColor === c ? ' is-active' : ''}`}
                style={{ background: c }}
                aria-label={c}
                onClick={() => setNewListColor(c)}
              />
            ))}
          </div>
        </Field>
        <Button icon="plus" onClick={createList} disabled={!newListName.trim()} style={{ marginTop: 4 }}>
          {t.todos.createList}
        </Button>

        {lists.length > 0 ? (
          <div className="hb-list" style={{ marginTop: 18 }}>
            {lists.map((l) => (
              <div key={l.id} className="hb-row">
                <span className="hb-listdot" style={{ background: l.color, width: 12, height: 12 }} />
                <div className="hb-row__main"><div className="hb-row__title">{l.name}</div></div>
                <IconButton
                  icon="trash"
                  label={t.common.delete}
                  danger
                  onClick={() => { if (confirm(t.todos.deleteListConfirm)) deleteList(l.id) }}
                />
              </div>
            ))}
          </div>
        ) : (
          <p className="hb-muted" style={{ marginTop: 16, fontSize: 13.5 }}>{t.todos.emptyLists}</p>
        )}
      </Modal>
    </div>
  )
}
