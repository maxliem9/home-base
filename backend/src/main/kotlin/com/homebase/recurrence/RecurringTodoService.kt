package com.homebase.recurrence

import com.homebase.db.TodosTable
import com.homebase.model.TodoDto
import com.homebase.routes.listIsShared
import com.homebase.routes.toTodoDto
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.LocalDate

/** A todo whose due date the safety-net advanced, plus whether it lives in a shared list. */
data class RolledTodo(val todo: TodoDto, val shared: Boolean)

/**
 * The persistence side of the recurring-todo safety-net (issue #44). Completion-driven generation
 * lives in the todo route; this service only handles the "zur Fälligkeit" backstop: a recurring
 * todo the user never finished, whose due date is now in the past, is rolled forward to the current
 * period so it stays on schedule. It never creates a second row, so the open instance never piles up.
 */
class RecurringTodoService {

    /**
     * Advances every open, overdue recurring todo past the periods that have fully elapsed
     * (see [Recurrence.rollOpenDueForward]). Returns the todos whose due date actually moved so the
     * caller can broadcast them. Runs in a single transaction.
     */
    fun rollForwardOverdue(today: LocalDate = LocalDate.now()): List<RolledTodo> = transaction {
        val candidates = TodosTable.selectAll().where {
            TodosTable.recurrence.isNotNull() and
                (TodosTable.status neq "DONE") and
                (TodosTable.dueDate less today)
        }.orderBy(TodosTable.createdAt to SortOrder.ASC).toList()

        val rolled = mutableListOf<RolledTodo>()
        for (row in candidates) {
            val due = row[TodosTable.dueDate] ?: continue
            val freq = row[TodosTable.recurrence] ?: continue
            val interval = row[TodosTable.recurrenceInterval] ?: 1
            val newDue = Recurrence.rollOpenDueForward(due, freq, interval, today)
            if (newDue == due) continue
            val todoId = row[TodosTable.id]
            TodosTable.update({ TodosTable.id eq todoId }) { it[dueDate] = newDue }
            val dto = TodosTable.selectAll().where { TodosTable.id eq todoId }.single().toTodoDto()
            rolled += RolledTodo(dto, shared = listIsShared(row[TodosTable.listId]))
        }
        rolled
    }
}
