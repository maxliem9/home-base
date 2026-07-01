package com.homebase.notifications

import com.homebase.db.TodoListsTable
import com.homebase.db.TodosTable
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import java.util.UUID

/**
 * Ids of PRIVATE todo lists.
 *
 * Todos in a private list must stay out of the household-wide notifications (the reminder scheduler
 * and both Telegram digests). Those channels reach the *one shared* household chat and *every*
 * registered push device — there is no per-user routing — so surfacing a private-list todo's title
 * there leaks it to the other member. This mirrors the WebSocket layer, which already never pushes a
 * private todo to anyone but its owner; the notification layer was the one place that still did.
 *
 * Because the channels are shared, the only privacy-preserving option is to omit private-list todos
 * entirely (the owner forgoes reminders/digests for them). Must run inside a transaction.
 */
fun privateTodoListIds(): Set<UUID> =
    TodoListsTable.selectAll()
        .where { TodoListsTable.visibility eq "PRIVATE" }
        .map { it[TodoListsTable.id] }
        .toSet()

/**
 * Whether this todo row may appear in household-wide notifications: true unless it lives in a
 * private list. A list-less todo (listId == null) is always shareable. [privateListIds] is the set
 * from [privateTodoListIds], computed once per pass.
 */
fun ResultRow.todoIsShareable(privateListIds: Set<UUID>): Boolean {
    val listId = this[TodosTable.listId]
    return listId == null || listId !in privateListIds
}
