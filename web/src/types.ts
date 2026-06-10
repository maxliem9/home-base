export type TodoStatus = 'INBOX' | 'PLANNED' | 'DONE'
export type TodoPriority = 'LOW' | 'MEDIUM' | 'HIGH'
export type RecurrenceFreq = 'DAILY' | 'WEEKLY' | 'MONTHLY'

// Recurrence rule on a todo (issue #44). `interval` may be omitted by the backend when it is 1.
export interface Recurrence {
  freq: RecurrenceFreq
  interval?: number
}

export interface Subtask {
  id: string
  title: string
  done: boolean
  sortOrder: number
}

export type ListVisibility = 'SHARED' | 'PRIVATE'

export interface TodoList {
  id: string
  name: string
  visibility: ListVisibility
  createdBy: string
  createdAt: string
}

export interface Todo {
  id: string
  title: string
  description?: string
  status: TodoStatus
  assignee?: string
  dueDate?: string
  priority?: TodoPriority
  listId?: string
  recurrence?: Recurrence
  subtasks?: Subtask[]
  createdBy: string
  createdAt: string
  doneAt?: string
}

export interface ShoppingList {
  id: string
  name: string
  createdBy: string
  createdAt: string
}

export interface ShoppingItem {
  id: string
  name: string
  listId?: string
  checked: boolean
  createdBy: string
  createdAt: string
  checkedAt?: string
}

export interface Project {
  id: string
  name: string
  color: string
  archived: boolean
  createdBy: string
  createdAt: string
}

export interface TimeEntry {
  id: string
  projectId: string
  userId: string
  startedAt: string
  stoppedAt?: string
  description?: string
  durationSeconds?: number
  createdAt: string
  updatedAt: string
}

// LUNCH was dropped (collapsed into DINNER) — see backend migration V17.
export type RecipeCategory = 'BREAKFAST' | 'DINNER' | 'SNACK' | 'DESSERT' | 'DRINK'

export interface Ingredient {
  id: string
  name: string
  amount?: number
  unit?: string
  /** optional group label, e.g. "Boden" / "Topping"; absent = ungrouped (top section) */
  section?: string
  sortOrder: number
}

export interface RecipeStep {
  id: string
  stepNumber: number
  description: string
}

export interface Recipe {
  id: string
  title: string
  description?: string
  servings: number
  prepTimeMinutes?: number
  cookTimeMinutes?: number
  category: RecipeCategory
  ingredients: Ingredient[]
  steps: RecipeStep[]
  createdBy: string
  createdAt: string
  updatedAt: string
}

// --- Abwesenheit / Familienkalender ---------------------------------------

export type AbsenceType = 'URLAUB' | 'KRANK' | 'KIND_KRANK'
export type HalfDay = 'vm' | 'nm'

export interface Absence {
  id: string
  userId: string
  date: string // YYYY-MM-DD
  type: AbsenceType
  half?: HalfDay | null
}

export interface PartTimeRule {
  id: string
  userId: string
  weekday: number // ISO 1=Mon … 7=Sun
  start: string // YYYY-MM-DD
  end?: string | null
}

export interface KitaClosure {
  id: string
  date: string
  label: string
}

export interface AbsSettings {
  userId: string
  year: number // settings are stored per calendar year (#144)
  state: string // German Bundesland code
  allowance: number
  carryover: number
  carryoverExpires?: string | null
  kindKrankCap: number
}

export interface AbsenceState {
  users: string[]
  absences: Absence[]
  partTime: PartTimeRule[]
  kitaClosures: KitaClosure[]
  settings: AbsSettings[]
}

// A household member (2 fixed users). From GET /api/v1/users — used to resolve
// "the other user" for shared timers (#142).
export interface User {
  username: string
}

export type NoteVisibility = 'PRIVATE' | 'SHARED'

export interface NoteImage {
  id: string
  noteId: string
  originalName: string
  contentType: string
  sizeBytes: number
  sortOrder: number
  createdBy: string
  createdAt: string
}

export interface Note {
  id: string
  title: string
  content: string
  tags: string[]
  visibility: NoteVisibility
  images: NoteImage[]
  createdBy: string
  createdAt: string
  updatedAt: string
}
