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
