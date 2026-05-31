export type TodoStatus = 'INBOX' | 'PLANNED' | 'DONE'
export type TodoPriority = 'LOW' | 'MEDIUM' | 'HIGH'

export interface Todo {
  id: string
  title: string
  description?: string
  status: TodoStatus
  assignee?: string
  dueDate?: string
  priority?: TodoPriority
  createdBy: string
  createdAt: string
  doneAt?: string
}

export interface ShoppingItem {
  id: string
  name: string
  category?: string
  checked: boolean
  createdBy: string
  createdAt: string
  checkedAt?: string
}

export type NoteVisibility = 'PRIVATE' | 'SHARED'

export interface Note {
  id: string
  title: string
  content: string
  tags: string[]
  visibility: NoteVisibility
  createdBy: string
  createdAt: string
  updatedAt: string
}
