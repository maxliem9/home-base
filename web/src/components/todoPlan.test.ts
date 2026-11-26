import { describe, expect, it } from 'vitest'
import { PlanDraft, planBody } from './todoPlan'

const draft = (over: Partial<PlanDraft> = {}): PlanDraft => ({
  id: 't1',
  title: 'T',
  description: '',
  assignees: [],
  assigneesOriginal: [],
  dueDate: '',
  dueDateOriginal: '',
  dueTime: '',
  priority: '',
  listId: '',
  listIdOriginal: '',
  recurrenceFreq: '',
  recurrenceInterval: 1,
  ...over,
})

describe('planBody status derivation', () => {
  it('derives PLANNED from a due date or assignee, INBOX otherwise', () => {
    expect(planBody(draft({ dueDate: '2026-07-10' }), 'INBOX').status).toBe('PLANNED')
    expect(planBody(draft({ assignees: ['max'] }), 'INBOX').status).toBe('PLANNED')
    expect(planBody(draft(), 'PLANNED').status).toBe('INBOX')
  })

  it('#627: leaves a DONE todo DONE (status omitted ⇒ unchanged)', () => {
    expect(planBody(draft({ dueDate: '2026-07-10' }), 'DONE').status).toBeUndefined()
    expect(planBody(draft(), 'DONE').status).toBeUndefined()
  })
})
