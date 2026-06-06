import { useState, useEffect, useCallback } from 'react'
import { API_BASE, authFetch, withWsToken } from '../api'
import { t } from '../i18n'
import { Todo, TodoList, TodoPriority, Subtask, ListVisibility, RecurrenceFreq } from '../types'
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
  Select,
  TextInput,
} from '../ui/primitives'
import { dueLabel, relTime, userMeta, usernameFromToken } from '../ui/format'

const WS_SCHEME = window.location.protocol === 'https:' ? 'wss' : 'ws'
const WS_URL = import.meta.env.VITE_WS_URL ?? `${WS_SCHEME}://${window.location.host}/api/v1/ws/todos`

// open todos are grouped into these due-date buckets, in this order
const BUCKETS: { key: string; label: string }[] = [
  { key: 'over', label: t.todos.bucketOver },
  { key: 'today', label: t.todos.bucketToday },
  { key: 'soon', label: t.todos.bucketSoon },
  { key: 'far', label: t.todos.bucketFar },
  { key: 'none', label: t.todos.bucketNone },
]

interface PlanDraft {
  id: string
  assignee: string
  dueDate: string
  priority: '' | TodoPriority
  recurrenceFreq: '' | RecurrenceFreq // '' = no recurrence
  recurrenceInterval: number
}

// short label for the recurrence badge on a todo row, e.g. "wöchentl." or "alle 2 Wochen"
function recurrenceBadge(rec: { freq: RecurrenceFreq; interval?: number }): string {
  const n = rec.interval ?? 1
  if (n <= 1) {
    return { DAILY: t.todos.recurBadgeDaily, WEEKLY: t.todos.recurBadgeWeekly, MONTHLY: t.todos.recurBadgeMonthly }[rec.freq]
  }
  const unit = { DAILY: t.todos.recurUnitDay, WEEKLY: t.todos.recurUnitWeek, MONTHLY: t.todos.recurUnitMonth }[rec.freq]
  return `${t.todos.recurBadgeEvery} ${n} ${unit}`
}

interface TodosViewProps {
  token: string
  onLogout: () => void
}

export function TodosView({ token, onLogout }: TodosViewProps) {
  const me = usernameFromToken(token)
  const [todos, setTodos] = useState<Todo[]>([])
  const [lists, setLists] = useState<TodoList[]>([])
  const [loading, setLoading] = useState(true)
  const [activeId, setActiveId] = useState<string | null>(null)
  const [newTitle, setNewTitle] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [plan, setPlan] = useState<PlanDraft | null>(null)
  const [expanded, setExpanded] = useState<Set<string>>(new Set())
  const [subDrafts, setSubDrafts] = useState<Record<string, string>>({})
  const [doneOpen, setDoneOpen] = useState(false)
  const [newListOpen, setNewListOpen] = useState(false)
  const [editListOpen, setEditListOpen] = useState(false)
  const [confirmDelete, setConfirmDelete] = useState(false)

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

  // keep an active tab selected as lists load / change
  useEffect(() => {
    if (lists.length === 0) {
      if (activeId !== null) setActiveId(null)
    } else if (!activeId || !lists.some((l) => l.id === activeId)) {
      setActiveId(lists[0].id)
    }
  }, [lists, activeId])

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
          // A shared→private flip is broadcast as a delete whose payload is the now-PRIVATE list.
          // For its owner that means "keep it, just hide it from the other user" — so mark it private
          // instead of dropping it. A genuine delete always carries a SHARED list; everyone else drops
          // it either way (they lost access). See issue #75 / the private-list visibility model.
          if (msg.payload.visibility === 'PRIVATE' && msg.payload.createdBy === me) {
            setLists((prev) => prev.map((x) => (x.id === msg.payload.id ? msg.payload : x)))
          } else {
            setLists((prev) => prev.filter((x) => x.id !== msg.payload.id))
            setTodos((prev) => prev.filter((x) => x.listId !== msg.payload.id))
          }
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
    if (!newTitle.trim() || !active) return
    setSubmitting(true)
    try {
      const res = await authFetch(token, `${API_BASE}/todos`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ title: newTitle.trim(), listId: active.id }),
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
    // a recurrence needs a due date as its schedule anchor (backend enforces this too)
    if (plan.recurrenceFreq && !plan.dueDate) return
    await patchTodo(plan.id, {
      status: 'PLANNED',
      assignee: plan.assignee.trim() || undefined,
      dueDate: plan.dueDate || undefined,
      priority: plan.priority || undefined,
      // freq "NONE" clears any existing rule; otherwise set/replace it
      recurrence: plan.recurrenceFreq
        ? { freq: plan.recurrenceFreq, interval: plan.recurrenceInterval }
        : { freq: 'NONE' },
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
  const createList = async (name: string, visibility: ListVisibility) => {
    const res = await authFetch(token, `${API_BASE}/todos/lists`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name, visibility }),
    })
    if (res.ok) {
      const created: TodoList = await res.json()
      setLists((prev) => (prev.some((x) => x.id === created.id) ? prev : [...prev, created]))
      setActiveId(created.id)
      setNewListOpen(false)
    }
  }

  // rename and/or change a list's visibility. private→shared reveals the list (and its todos via the
  // backend replay) to the other user; shared→private hides it again. (issue #75)
  const updateList = async (name: string, visibility: ListVisibility) => {
    if (!active) return
    const res = await authFetch(token, `${API_BASE}/todos/lists/${active.id}`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name, visibility }),
    })
    if (res.ok) {
      const updated: TodoList = await res.json()
      setLists((prev) => prev.map((x) => (x.id === updated.id ? updated : x)))
      setEditListOpen(false)
    }
  }

  // confirmed via the delete-list modal — removes the list and its todos (backend cascades)
  const removeList = async () => {
    if (!active || lists.length <= 1) return
    const removedId = active.id
    const idx = lists.findIndex((l) => l.id === removedId)
    const next = lists[idx + 1] ?? lists[idx - 1]
    setConfirmDelete(false)
    setLists((prev) => prev.filter((l) => l.id !== removedId))
    setTodos((prev) => prev.filter((x) => x.listId !== removedId))
    setActiveId(next ? next.id : null)
    await authFetch(token, `${API_BASE}/todos/lists/${removedId}`, { method: 'DELETE' })
  }

  const active = lists.find((l) => l.id === activeId) ?? null
  const openCount = (id: string) => todos.filter((x) => x.listId === id && x.status !== 'DONE').length

  const listTodos = active ? todos.filter((x) => x.listId === active.id) : []
  const openTodos = listTodos.filter((x) => x.status !== 'DONE')
  const done = listTodos
    .filter((x) => x.status === 'DONE')
    .sort((a, b) => (b.doneAt ?? '').localeCompare(a.doneAt ?? ''))

  // bucket open todos by due tone, each bucket sorted by date
  const buckets: Record<string, Todo[]> = { over: [], today: [], soon: [], far: [], none: [] }
  openTodos.forEach((todo) => {
    const d = dueLabel(todo.dueDate)
    buckets[d ? d.tone : 'none'].push(todo)
  })
  Object.values(buckets).forEach((b) =>
    b.sort((a, c) => (a.dueDate ?? '9999').localeCompare(c.dueDate ?? '9999')),
  )
  const groups = BUCKETS.filter((g) => buckets[g.key].length)

  return (
    <div className="hb-page">
      <PageHead eyebrow={t.todos.eyebrow} title={t.todos.title} />

      {/* Listen-Tabs */}
      <div className="hb-tabs" role="tablist">
        {lists.map((l) => (
          <button
            key={l.id}
            role="tab"
            aria-selected={active?.id === l.id}
            className={`hb-tab${active?.id === l.id ? ' is-active' : ''}`}
            onClick={() => setActiveId(l.id)}
          >
            {l.visibility === 'PRIVATE' && <Icon name="lock" size={13} stroke={2} style={{ opacity: 0.7 }} />}
            {l.name}
            {openCount(l.id) > 0 && <span className="hb-tab__count">{openCount(l.id)}</span>}
          </button>
        ))}
        <button className="hb-tab hb-tab--add" onClick={() => setNewListOpen(true)}>
          <Icon name="plus" size={16} stroke={2.2} />
          {t.todos.newList}
        </button>
      </div>

      {loading ? (
        <p className="hb-muted" style={{ textAlign: 'center', padding: 24 }}>{t.common.loading}</p>
      ) : !active ? (
        <Card className="hb-card--pad">
          <EmptyState icon="inbox" title={t.todos.noLists} hint={t.todos.noListsHint} />
        </Card>
      ) : (
        <>
          <div className="hb-quickadd" style={{ marginBottom: 24 }}>
            <Icon name="plus" size={19} stroke={2} style={{ color: 'var(--ink-3)' }} />
            <input
              value={newTitle}
              placeholder={`${t.todos.quickAddPlaceholder.replace(' …', '')} in „${active.name}" …`}
              onChange={(e) => setNewTitle(e.target.value)}
              onKeyDown={(e) => e.key === 'Enter' && handleAdd()}
            />
            <Button size="sm" icon="plus" onClick={handleAdd} disabled={submitting || !newTitle.trim()}>
              {t.todos.addTask}
            </Button>
          </div>

          {openTodos.length === 0 ? (
            <Card className="hb-card--pad"><EmptyState icon="checkCircle" title={t.todos.allDone} hint={t.todos.allDoneHint} /></Card>
          ) : (
            groups.map((g) => (
              <div key={g.key} style={{ marginBottom: 22 }}>
                <div className="hb-sectionlabel">
                  {g.label}{' '}
                  <span style={{ fontFamily: 'var(--font-mono)', color: 'var(--ink-3)', fontWeight: 500 }}>{buckets[g.key].length}</span>
                </div>
                <Card className="hb-card--pad" style={{ paddingTop: 6, paddingBottom: 6 }}>
                  <div className="hb-list">
                    {buckets[g.key].map((todo) => (
                      <TodoRow
                        key={todo.id}
                        todo={todo}
                        open={expanded.has(todo.id)}
                        draft={subDrafts[todo.id] ?? ''}
                        onToggleDone={() => toggleDone(todo)}
                        onToggleExpand={() => toggleExpand(todo.id)}
                        onPlan={() => setPlan({ id: todo.id, assignee: todo.assignee ?? '', dueDate: todo.dueDate ?? '', priority: todo.priority ?? '', recurrenceFreq: todo.recurrence?.freq ?? '', recurrenceInterval: todo.recurrence?.interval ?? 1 })}
                        onDelete={() => deleteTodo(todo.id)}
                        onToggleSub={(s) => toggleSubtask(todo.id, s)}
                        onDeleteSub={(sid) => deleteSubtask(todo.id, sid)}
                        onDraft={(v) => setSubDrafts((d) => ({ ...d, [todo.id]: v }))}
                        onAddSub={() => addSubtask(todo.id)}
                      />
                    ))}
                  </div>
                </Card>
              </div>
            ))
          )}

          {done.length > 0 && (
            <div style={{ marginTop: 30 }}>
              <button className={`hb-donehead${doneOpen ? ' is-open' : ''}`} onClick={() => setDoneOpen((v) => !v)}>
                <Icon name="chevronDown" size={16} stroke={2.4} className="hb-donehead__chev" />
                <span className="hb-sectionlabel" style={{ margin: 0 }}>{t.todos.doneSection}</span>
                <span className="hb-donehead__c">{done.length}</span>
              </button>
              {doneOpen && (
                <Card className="hb-card--pad" style={{ paddingTop: 6, paddingBottom: 6, marginTop: 12 }}>
                  <div className="hb-list">
                    {done.map((todo) => (
                      <TodoRow
                        key={todo.id}
                        todo={todo}
                        open={expanded.has(todo.id)}
                        draft={subDrafts[todo.id] ?? ''}
                        onToggleDone={() => toggleDone(todo)}
                        onToggleExpand={() => toggleExpand(todo.id)}
                        onPlan={() => setPlan({ id: todo.id, assignee: todo.assignee ?? '', dueDate: todo.dueDate ?? '', priority: todo.priority ?? '', recurrenceFreq: todo.recurrence?.freq ?? '', recurrenceInterval: todo.recurrence?.interval ?? 1 })}
                        onDelete={() => deleteTodo(todo.id)}
                        onToggleSub={(s) => toggleSubtask(todo.id, s)}
                        onDeleteSub={(sid) => deleteSubtask(todo.id, sid)}
                        onDraft={(v) => setSubDrafts((d) => ({ ...d, [todo.id]: v }))}
                        onAddSub={() => addSubtask(todo.id)}
                      />
                    ))}
                  </div>
                </Card>
              )}
            </div>
          )}

          <div style={{ marginTop: 26, display: 'flex', gap: 20, alignItems: 'center' }}>
            <button className="hb-link" onClick={() => setEditListOpen(true)}>
              <Icon name="edit" size={14} stroke={2} style={{ verticalAlign: '-2px', marginRight: 5 }} />
              {t.todos.editList} „{active.name}"
            </button>
            {lists.length > 1 && (
              <button className="hb-link hb-link--danger" onClick={() => setConfirmDelete(true)}>
                <Icon name="trash" size={14} stroke={2} style={{ verticalAlign: '-2px', marginRight: 5 }} />
                {t.todos.deleteList} „{active.name}"
              </button>
            )}
          </div>
        </>
      )}

      <Modal
        open={!!plan}
        onClose={() => setPlan(null)}
        title={t.todos.planTitle}
        footer={
          <>
            <Button variant="ghost" onClick={() => setPlan(null)}>{t.common.cancel}</Button>
            <Button
              onClick={handlePlan}
              disabled={!plan || (!plan.assignee.trim() && !plan.dueDate) || (!!plan.recurrenceFreq && !plan.dueDate)}
            >
              {t.todos.plan}
            </Button>
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
            <Field
              label={t.todos.recurrence}
              hint={plan.recurrenceFreq && !plan.dueDate ? t.todos.recurrenceNeedsDue : undefined}
            >
              <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
                <Select
                  value={plan.recurrenceFreq}
                  onChange={(v) => setPlan({ ...plan, recurrenceFreq: v as PlanDraft['recurrenceFreq'] })}
                >
                  <option value="">{t.todos.recurrenceNone}</option>
                  <option value="DAILY">{t.todos.recurrenceDaily}</option>
                  <option value="WEEKLY">{t.todos.recurrenceWeekly}</option>
                  <option value="MONTHLY">{t.todos.recurrenceMonthly}</option>
                </Select>
                {plan.recurrenceFreq && (
                  <>
                    <span className="hb-muted" style={{ fontSize: 13.5, whiteSpace: 'nowrap' }}>{t.todos.recurrenceEvery}</span>
                    <TextInput
                      type="number"
                      value={String(plan.recurrenceInterval)}
                      onChange={(v) => setPlan({ ...plan, recurrenceInterval: Math.max(1, Math.min(1000, Number(v) || 1)) })}
                      style={{ width: 72 }}
                    />
                    <span className="hb-muted" style={{ fontSize: 13.5, whiteSpace: 'nowrap' }}>
                      {{ DAILY: t.todos.recurUnitDay, WEEKLY: t.todos.recurUnitWeek, MONTHLY: t.todos.recurUnitMonth }[plan.recurrenceFreq]}
                    </span>
                  </>
                )}
              </div>
            </Field>
          </>
        )}
      </Modal>

      {newListOpen && <NewListModal onClose={() => setNewListOpen(false)} onCreate={createList} />}

      {editListOpen && active && (
        <EditListModal list={active} onClose={() => setEditListOpen(false)} onSave={updateList} />
      )}

      <Modal
        open={confirmDelete && !!active}
        onClose={() => setConfirmDelete(false)}
        title={t.todos.deleteListTitle}
        width={440}
        footer={
          <>
            <Button variant="ghost" onClick={() => setConfirmDelete(false)}>{t.common.cancel}</Button>
            <Button variant="danger" icon="trash" onClick={removeList}>{t.todos.deleteListConfirm}</Button>
          </>
        }
      >
        {active && (
          <p className="hb-muted" style={{ margin: 0, fontSize: 14, lineHeight: 1.55 }}>
            {listTodos.length === 0 ? (
              <>Die leere Liste „<strong>{active.name}</strong>" wird gelöscht.</>
            ) : (
              <>
                „<strong>{active.name}</strong>" und{' '}
                <strong>{listTodos.length} {listTodos.length === 1 ? t.todos.taskOne : t.todos.taskMany}</strong>{' '}
                darin werden gelöscht. {t.todos.deleteListWarn}
              </>
            )}
          </p>
        )}
      </Modal>
    </div>
  )
}

function TodoRow({
  todo,
  open,
  draft,
  onToggleDone,
  onToggleExpand,
  onPlan,
  onDelete,
  onToggleSub,
  onDeleteSub,
  onDraft,
  onAddSub,
}: {
  todo: Todo
  open: boolean
  draft: string
  onToggleDone: () => void
  onToggleExpand: () => void
  onPlan: () => void
  onDelete: () => void
  onToggleSub: (s: Subtask) => void
  onDeleteSub: (subId: string) => void
  onDraft: (v: string) => void
  onAddSub: () => void
}) {
  const due = dueLabel(todo.dueDate)
  const subs = todo.subtasks ?? []
  const doneCount = subs.filter((s) => s.done).length
  const isDone = todo.status === 'DONE'

  return (
    <div className="hb-todo">
      <div className={`hb-row${isDone ? ' hb-row--done' : ''}`}>
        <Checkbox checked={isDone} hue={todo.assignee ? userMeta(todo.assignee)?.hue : undefined} onChange={onToggleDone} />
        <div className="hb-row__main">
          <div className="hb-row__title">{todo.title}</div>
          <div className="hb-row__meta">
            {todo.description && (
              <span style={{ maxWidth: 280, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{todo.description}</span>
            )}
            {todo.priority && !isDone && <PriorityDot priority={todo.priority} withLabel />}
            {todo.recurrence && !isDone && (
              <span style={{ display: 'inline-flex', alignItems: 'center', gap: 4 }}>
                <Icon name="repeat" size={12} stroke={2} />
                {recurrenceBadge(todo.recurrence)}
              </span>
            )}
            {isDone && todo.doneAt && <span>{t.todos.markDone.toLowerCase()} {relTime(todo.doneAt)}</span>}
          </div>
        </div>
        <div className="hb-row__right">
          <button
            className={`hb-subtoggle${open ? ' is-open' : ''}${subs.length ? '' : ' is-empty'}`}
            onClick={onToggleExpand}
            title={t.todos.subtasks}
            aria-label={t.todos.subtasks}
          >
            <Icon name="checkCircle" size={14} stroke={2} />
            {subs.length > 0 && <span className="hb-subtoggle__c">{doneCount}/{subs.length}</span>}
            <Icon name="chevronDown" size={13} stroke={2.4} className="hb-subtoggle__chev" />
          </button>
          {due && !isDone && <Badge tone={due.tone}>{due.text}</Badge>}
          {todo.assignee ? (
            <Avatar user={todo.assignee} size={28} />
          ) : !isDone && !todo.dueDate ? (
            <Button size="sm" variant="soft" icon="calendar" onClick={onPlan}>{t.todos.plan}</Button>
          ) : (
            <Avatar user={null} size={28} />
          )}
          <div className="hb-row__actions">
            {!isDone && <IconButton icon="edit" label={t.todos.edit} size={16} onClick={onPlan} />}
            <IconButton icon="trash" label={t.common.delete} danger size={16} onClick={onDelete} />
          </div>
        </div>
      </div>

      {open && (
        <div className="hb-subtasks">
          {subs.map((s) => (
            <div key={s.id} className={`hb-subtask${s.done ? ' hb-subtask--done' : ''}`}>
              <Checkbox checked={s.done} onChange={() => onToggleSub(s)} />
              <span className="hb-subtask__title">{s.title}</span>
              <IconButton icon="trash" label={t.common.delete} danger size={15} onClick={() => onDeleteSub(s.id)} />
            </div>
          ))}
          <div className="hb-subadd">
            <Icon name="plus" size={15} stroke={2.2} style={{ color: 'var(--ink-3)' }} />
            <input
              value={draft}
              placeholder={t.todos.addSubtask}
              onChange={(e) => onDraft(e.target.value)}
              onKeyDown={(e) => e.key === 'Enter' && onAddSub()}
            />
          </div>
        </div>
      )}
    </div>
  )
}

function NewListModal({ onClose, onCreate }: { onClose: () => void; onCreate: (name: string, visibility: ListVisibility) => void }) {
  const [name, setName] = useState('')
  const [visibility, setVisibility] = useState<ListVisibility>('SHARED')
  const create = () => { if (name.trim()) onCreate(name.trim(), visibility) }

  return (
    <Modal
      open
      onClose={onClose}
      title={t.todos.newListTitle}
      width={440}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>{t.common.cancel}</Button>
          <Button variant="primary" icon="check" onClick={create} disabled={!name.trim()}>{t.todos.createList}</Button>
        </>
      }
    >
      <Field label={t.todos.listName}>
        <TextInput value={name} onChange={setName} placeholder={t.todos.listNamePlaceholder} autoFocus onKeyDown={(e) => e.key === 'Enter' && create()} />
      </Field>
      <Field label={t.todos.visibility}>
        <VisibilityPicker visibility={visibility} onChange={setVisibility} />
      </Field>
    </Modal>
  )
}

function EditListModal({
  list,
  onClose,
  onSave,
}: {
  list: TodoList
  onClose: () => void
  onSave: (name: string, visibility: ListVisibility) => void
}) {
  const [name, setName] = useState(list.name)
  const [visibility, setVisibility] = useState<ListVisibility>(list.visibility)
  const dirty = name.trim() !== list.name || visibility !== list.visibility
  const save = () => { if (name.trim() && dirty) onSave(name.trim(), visibility) }

  return (
    <Modal
      open
      onClose={onClose}
      title={t.todos.editListTitle}
      width={440}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>{t.common.cancel}</Button>
          <Button variant="primary" icon="check" onClick={save} disabled={!name.trim() || !dirty}>{t.todos.saveList}</Button>
        </>
      }
    >
      <Field label={t.todos.listName}>
        <TextInput value={name} onChange={setName} placeholder={t.todos.listNamePlaceholder} autoFocus onKeyDown={(e) => e.key === 'Enter' && save()} />
      </Field>
      <Field label={t.todos.visibility} hint={visibility === 'SHARED' ? t.todos.visSharedHint : t.todos.visPrivateHint}>
        <VisibilityPicker visibility={visibility} onChange={setVisibility} />
      </Field>
    </Modal>
  )
}

function VisibilityPicker({ visibility, onChange }: { visibility: ListVisibility; onChange: (v: ListVisibility) => void }) {
  return (
    <div className="hb-pickrow">
      <button className={`hb-pick${visibility === 'SHARED' ? ' is-active' : ''}`} onClick={() => onChange('SHARED')}>
        <Icon name="users" size={16} stroke={2} /> {t.todos.visShared}
      </button>
      <button className={`hb-pick${visibility === 'PRIVATE' ? ' is-active' : ''}`} onClick={() => onChange('PRIVATE')}>
        <Icon name="lock" size={16} stroke={2} /> {t.todos.visPrivate}
      </button>
    </div>
  )
}
