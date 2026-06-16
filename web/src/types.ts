export type TodoStatus = 'INBOX' | 'PLANNED' | 'DONE'
export type TodoPriority = 'LOW' | 'MEDIUM' | 'HIGH'
export type RecurrenceFreq = 'DAILY' | 'WEEKLY' | 'MONTHLY'

// Recurrence rule on a todo. `interval` may be omitted by the backend when it is 1.
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

// Named "standard/template shopping list" (#215): a saved set of item names the
// household re-adds for the recurring weekly shop. `items` is omitted by the
// backend when empty (encodeDefaults=false) — normalize with ?? [] on the way in.
export interface ShoppingTemplateItem {
  id: string
  name: string
  sortOrder: number
}

export interface ShoppingTemplate {
  id: string
  name: string
  items: ShoppingTemplateItem[]
  createdBy: string
  createdAt: string
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

// --- Wochensoll & Forecast (#31) -------------------------------------------

// Weekly work-hour target of one person on one project. `isDefault` marks the
// person's one default project — absence/holiday credits are booked there.
export interface WorkTarget {
  userId: string
  projectId: string
  weeklyHours: number
  isDefault: boolean
}

export interface ProjectForecast {
  projectId: string
  weeklyHours: number
  recordedSeconds: number
  creditedSeconds: number
  /** recorded + credited − target (negative = behind) */
  deltaSeconds: number
}

// Server-computed week forecast per person (GET /api/v1/time/forecast).
// "remaining" values are signed (negative = already over target).
export interface UserForecast {
  userId: string
  weeklyTargetHours: number
  workdayCount: number
  weekTargetSeconds: number
  weekRecordedSeconds: number
  weekCreditedSeconds: number
  weekRemainingSeconds: number
  todayTargetSeconds: number
  todayRecordedSeconds: number
  todayRemainingSeconds: number
  /** projected stop time while a timer runs; omitted otherwise */
  expectedEndAt?: string
  /** omitted by the backend when empty (encodeDefaults=false) — normalize with ?? [] */
  projects?: ProjectForecast[]
}

export interface TimeForecast {
  date: string
  weekStart: string
  /** omitted by the backend when empty (encodeDefaults=false) — normalize with ?? [] */
  users?: UserForecast[]
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

export interface RecipeImage {
  id: string
  recipeId: string
  originalName: string
  contentType: string
  sizeBytes: number
  createdBy: string
  createdAt: string
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
  // optional single cover image; omitted by the backend when the recipe has none
  image?: RecipeImage
  createdBy: string
  createdAt: string
  updatedAt: string
}

// --- Wochenplan / Essensplaner (#218) -------------------------------------

// The three planner meal slots (grid rows). Deliberately independent of RecipeCategory
// (which has no LUNCH since V17): any recipe can be planned into any slot.
export type MealSlot = 'BREAKFAST' | 'LUNCH' | 'DINNER'

// One meal planned into a (date, slot). Exactly one of recipeId / dishTitle is set (#293):
// recipe-backed entries also carry the joined recipeTitle/recipeCategory so the grid renders
// without a second fetch; free-text entries carry only dishTitle. With the backend's
// encodeDefaults=false the unused fields are simply absent — hence all optional here.
export interface MealPlanEntry {
  id: string
  date: string // YYYY-MM-DD
  slot: MealSlot
  recipeId?: string
  recipeTitle?: string
  recipeCategory?: RecipeCategory
  // free-text dish name when no recipe is referenced (#293)
  dishTitle?: string
  // portions to cook (#251); omitted/undefined = use the recipe's own servings (1× as authored)
  servings?: number
  createdBy: string
  createdAt: string
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

// Household-wide custom holiday (#51), recurring every year on a fixed month+day.
// `half` = a half free day (rendered with a ½ marker; counts 0.5 toward the work
// target in #31). No user/Bundesland — it applies to everyone (e.g. Heiligabend/Silvester).
export interface CustomHoliday {
  id: string
  month: number // 1–12
  day: number // 1–31
  half: boolean
  label: string
}

export interface AbsSettings {
  userId: string
  year: number // settings are stored per calendar year
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
  customHolidays: CustomHoliday[]
  settings: AbsSettings[]
}

// A household member (2 fixed users). From GET /api/v1/users — used to resolve
// "the other user" for shared timers.
// avatarHue (Teil von #100): the member's chosen avatar hue (0..359); absent/null =
// automatic (derive from the username hash). Household-visible so the partner sees it.
export interface User {
  username: string
  avatarHue?: number | null
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
  // single-level folder label (issue #30); omitted by the backend when unset
  folder?: string
  visibility: NoteVisibility
  images: NoteImage[]
  createdBy: string
  createdAt: string
  updatedAt: string
}
