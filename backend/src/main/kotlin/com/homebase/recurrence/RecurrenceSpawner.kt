package com.homebase.recurrence

import com.homebase.db.TodoAssigneesTable
import com.homebase.db.TodoSubtasksTable
import com.homebase.db.TodosTable
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/**
 * The completion-driven half of the recurring-todo engine: when a recurring todo first transitions
 * into DONE the caller (TodoService) spawns its successor instance. The date math, subtask reset and
 * assignee inheritance used to live inline in the ~180-line PUT /todos handler; pulling them into
 * this class makes them unit-testable without an HTTP round-trip (issue #546).
 *
 * The successor is a fresh, unchecked instance carried onto the next due date: its schedule anchor is
 * the *merged* (post-update) due date, its subtasks are copied unchecked, and it inherits the
 * completed instance's assignee set (the recurrence rule moves onto it). The safety-net roll-forward
 * for *open, overdue* recurring todos lives separately in [RecurringTodoService].
 */
class RecurrenceSpawner {

    /**
     * Everything the successor needs, taken from the merged state of the just-completed todo.
     * [anchorDueDate] is the completed instance's (merged) due date — the schedule anchor from which
     * the next due date is computed; a recurring todo always has one (validation enforces it).
     */
    data class Spec(
        val sourceTodoId: UUID,
        val title: String,
        val description: String?,
        val dueTime: String?,
        val reminderLeadMinutes: Int?,
        val priority: String?,
        val listId: UUID?,
        val freq: String,
        val interval: Int,
        val anchorDueDate: LocalDate,
        val createdBy: String,
        val assignees: List<String>,
    )

    /**
     * Inserts the successor instance and returns its id. The new due date is the first occurrence
     * strictly after [today] (an on-time completion is just one step on; a long-overdue one skips the
     * elapsed periods, see [Recurrence.nextDueAfterCompletion]). Subtasks are copied unchecked in
     * their original order and the assignee set is inherited. Must run inside a transaction.
     */
    fun spawn(spec: Spec, today: LocalDate = LocalDate.now(), now: Instant = Instant.now()): UUID {
        val successorDue = Recurrence.nextDueAfterCompletion(spec.anchorDueDate, spec.freq, spec.interval, today)
        val newId = UUID.randomUUID()
        TodosTable.insert {
            it[TodosTable.id] = newId
            it[title] = spec.title
            it[description] = spec.description
            it[status] = "PLANNED" // always has a dueDate, so PLANNED is valid
            it[dueDate] = successorDue
            // carry the due time + reminder onto the successor (it keeps its dueDate anchor)
            it[dueTime] = spec.dueTime?.let { t -> LocalTime.parse(t) }
            it[reminderLeadMinutes] = spec.reminderLeadMinutes
            it[priority] = spec.priority
            it[listId] = spec.listId
            it[recurrence] = spec.freq
            it[recurrenceInterval] = spec.interval
            it[createdBy] = spec.createdBy
            it[createdAt] = now
            it[updatedAt] = now
        }
        // carry the subtasks over as a fresh, unchecked checklist for the new instance
        TodoSubtasksTable.selectAll().where { TodoSubtasksTable.todoId eq spec.sourceTodoId }
            .orderBy(TodoSubtasksTable.sortOrder to SortOrder.ASC)
            .forEach { sub ->
                TodoSubtasksTable.insert {
                    it[TodoSubtasksTable.id] = UUID.randomUUID()
                    it[todoId] = newId
                    it[title] = sub[TodoSubtasksTable.title]
                    it[done] = false
                    it[sortOrder] = sub[TodoSubtasksTable.sortOrder]
                    it[createdAt] = now
                }
            }
        // the successor inherits the assignee set (recurrence rule moves onto it)
        spec.assignees.forEach { u ->
            TodoAssigneesTable.insert {
                it[todoId] = newId
                it[username] = u
            }
        }
        return newId
    }
}
