// Plan-sheet draft + PUT-body mapping (pure, unit-tested in todoPlan.test.ts).
import type { RecurrenceFreq, TodoPriority, TodoStatus } from '../types'

// Compare two assignee sets order-insensitively — so an untouched picker never PUTs
// `assignees` and can't clobber a concurrent partner change (mirrors the #468/#409 guard).
export const sameAssignees = (a: string[], b: string[]): boolean =>
  a.length === b.length && [...a].sort().join('\u0000') === [...b].sort().join('\u0000')

export interface PlanDraft {
  id: string
  title: string
  description: string
  assignees: string[]
  assigneesOriginal: string[] // assignees at open time — only PUT when the set actually changed
  dueDate: string
  dueDateOriginal: string // due date at open time — only PUT '' (clear) when it was actually emptied (#468)
  dueTime: string // "HH:mm" or '' (#429)
  priority: '' | TodoPriority
  listId: string // target list; '' = no list / inbox (#69; move between lists #409)
  listIdOriginal: string // list at open time — only PUT listId on an actual change (#409)
  recurrenceFreq: '' | RecurrenceFreq // '' = no recurrence
  recurrenceInterval: number
}

// The PUT body for a plan-sheet save (shared by the debounced save + the flush-on-close).
// Conflict-avoidance (#265/#406/#409/#468): assignees/dueDate/listId are only sent when they differ
// from the *Original baseline (rebased after each save), so an untouched field never clobbers a
// concurrent partner edit; title/description/dueTime/priority/recurrence are always sent.
// #627: [currentStatus] is the todo's live status — a DONE todo keeps its status (omitted ⇒ unchanged),
// otherwise saving would send PLANNED/INBOX and the backend would reopen it (doneAt = null).
export function planBody(p: PlanDraft, currentStatus?: TodoStatus): Record<string, unknown> {
  return {
    // an assignee or due date makes it PLANNED; with neither it belongs in the INBOX
    status: currentStatus === 'DONE' ? undefined : p.assignees.length > 0 || p.dueDate ? 'PLANNED' : 'INBOX',
    title: p.title.trim(),
    description: p.description.trim(),
    assignees: sameAssignees(p.assignees, p.assigneesOriginal) ? undefined : p.assignees,
    // #468: send '' (clear) only when there actually was a date at open time; a value sets it.
    dueDate: p.dueDate || (p.dueDateOriginal ? '' : undefined),
    // a time without a date is meaningless — force-clear it when the date is gone.
    dueTime: p.dueDate ? p.dueTime : '',
    priority: p.priority || undefined,
    listId: p.listId !== p.listIdOriginal ? p.listId : undefined,
    // freq "NONE" clears any existing rule; otherwise set/replace it
    recurrence: p.recurrenceFreq ? { freq: p.recurrenceFreq, interval: p.recurrenceInterval } : { freq: 'NONE' },
  }
}
