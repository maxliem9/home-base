package com.homebase.routes

import com.homebase.model.*
import com.homebase.service.TodoService
import com.homebase.ws.WsSessionManager
import com.homebase.ws.broadcastSync
import com.homebase.ws.syncChannel
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

private const val TODO_WS_CHANNEL = "todos"
private const val VISIBILITY_PRIVATE = "PRIVATE"

/**
 * HTTP surface for the todo domain. The handlers only parse the request, call [TodoService], map the
 * result to a status/body and — after the transaction has committed — broadcast. All validation,
 * the tri-state merge, recurrence rules, private-list visibility (#73) and persistence live in the
 * service (issue #546); no handler here touches `TodosTable`/`transaction {` any more.
 */
fun Route.todoRoutes() {
    val service = TodoService()

    route("/todos") {
        // ---- Lists (registered before /{id} so the static segment wins) ----
        route("/lists") {
            get {
                call.respond(service.listLists(call.username()))
            }

            post {
                val username = call.username()
                when (val r = service.createList(call.receive<CreateTodoListRequest>(), username)) {
                    is TodoService.CreateListResult.Invalid -> call.respond(HttpStatusCode.BadRequest, r.error)
                    is TodoService.CreateListResult.Ok -> {
                        broadcastListCreate(r.list)
                        call.respond(HttpStatusCode.Created, r.list)
                    }
                }
            }

            put("/{id}") {
                val username = call.username()
                val id = call.uuidParam() ?: return@put
                when (val r = service.updateList(id, call.receive<UpdateTodoListRequest>(), username)) {
                    is TodoService.UpdateListResult.Invalid -> call.respond(HttpStatusCode.BadRequest, r.error)
                    TodoService.UpdateListResult.NotFound ->
                        call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "List not found"))
                    is TodoService.UpdateListResult.Ok -> {
                        broadcastListUpdate(r.wasShared, r.list, r.revealedTodos)
                        call.respond(r.list)
                    }
                }
            }

            delete("/{id}") {
                val username = call.username()
                val id = call.uuidParam() ?: return@delete
                when (val r = service.deleteList(id, username)) {
                    TodoService.DeleteListResult.NotFound ->
                        call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "List not found"))
                    is TodoService.DeleteListResult.Ok -> {
                        broadcastListDelete(r.list)
                        call.respond(HttpStatusCode.NoContent)
                    }
                }
            }
        }

        get {
            call.respond(service.listTodos(call.username()))
        }

        post {
            val username = call.username()
            when (val r = service.createTodo(call.receive<CreateTodoRequest>(), username)) {
                is TodoService.CreateTodoResult.Invalid -> call.respond(HttpStatusCode.BadRequest, r.error)
                TodoService.CreateTodoResult.NotFound ->
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "List not found"))
                is TodoService.CreateTodoResult.Ok -> {
                    broadcastTodoCreate(r.mutation.isShared, r.mutation.todo)
                    call.respond(HttpStatusCode.Created, r.mutation.todo)
                }
            }
        }

        put("/{id}") {
            val username = call.username()
            val id = call.uuidParam() ?: return@put
            when (val r = service.updateTodo(id, call.receive<UpdateTodoRequest>(), username)) {
                is TodoService.UpdateTodoResult.Invalid -> call.respond(HttpStatusCode.BadRequest, r.error)
                is TodoService.UpdateTodoResult.NotFound ->
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", r.message))
                is TodoService.UpdateTodoResult.Ok -> {
                    val m = r.mutation
                    broadcastTodoUpdate(m.wasShared, m.isShared, m.todo)
                    // the recurrence successor (if any) reaches the other client as a fresh create
                    m.spawned?.let { broadcastTodoCreate(m.spawnedShared, it) }
                    call.respond(m.todo)
                }
            }
        }

        delete("/{id}") {
            val username = call.username()
            val id = call.uuidParam() ?: return@delete
            when (val r = service.deleteTodo(id, username)) {
                TodoService.DeleteTodoResult.NotFound ->
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Todo not found"))
                is TodoService.DeleteTodoResult.Ok -> {
                    broadcastTodoDelete(r.shared, r.todo)
                    call.respond(HttpStatusCode.NoContent)
                }
            }
        }

        // ---- Subtasks ----
        route("/{id}/subtasks") {
            post {
                val username = call.username()
                val todoId = call.uuidParam() ?: return@post
                when (val r = service.addSubtask(todoId, call.receive<CreateSubtaskRequest>(), username)) {
                    is TodoService.SubtaskResult.Invalid -> call.respond(HttpStatusCode.BadRequest, r.error)
                    TodoService.SubtaskResult.NotFound ->
                        call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Todo not found"))
                    is TodoService.SubtaskResult.Ok -> {
                        val m = r.mutation
                        broadcastTodoUpdate(m.wasShared, m.isShared, m.todo)
                        call.respond(HttpStatusCode.Created, m.todo)
                    }
                }
            }

            put("/{subtaskId}") {
                val username = call.username()
                val todoId = call.uuidParam() ?: return@put
                val subtaskId = call.uuidParam("subtaskId") ?: return@put
                when (val r = service.updateSubtask(todoId, subtaskId, call.receive<UpdateSubtaskRequest>(), username)) {
                    is TodoService.SubtaskResult.Invalid -> call.respond(HttpStatusCode.BadRequest, r.error)
                    TodoService.SubtaskResult.NotFound ->
                        call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Subtask not found"))
                    is TodoService.SubtaskResult.Ok -> {
                        val m = r.mutation
                        broadcastTodoUpdate(m.wasShared, m.isShared, m.todo)
                        call.respond(m.todo)
                    }
                }
            }

            delete("/{subtaskId}") {
                val username = call.username()
                val todoId = call.uuidParam() ?: return@delete
                val subtaskId = call.uuidParam("subtaskId") ?: return@delete
                when (val r = service.deleteSubtask(todoId, subtaskId, username)) {
                    is TodoService.SubtaskResult.Invalid -> call.respond(HttpStatusCode.BadRequest, r.error)
                    TodoService.SubtaskResult.NotFound ->
                        call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Subtask not found"))
                    is TodoService.SubtaskResult.Ok -> {
                        val m = r.mutation
                        broadcastTodoUpdate(m.wasShared, m.isShared, m.todo)
                        call.respond(m.todo)
                    }
                }
            }
        }
    }

    syncChannel(TODO_WS_CHANNEL)
}

// ---- Broadcasts ----------------------------------------------------------
// Kept in the route layer: the service returns the visibility flags, the route fires the WS message
// only after its transaction has committed. This keeps "broadcast only after commit" structural.
// The wire format is the generic SyncEnvelope via broadcastSync (#552).

private suspend fun broadcastTodoCreate(shared: Boolean, todo: TodoDto) {
    if (shared) {
        WsSessionManager.broadcastSync(TODO_WS_CHANNEL, "TODO_CREATED", todo, TodoDto.serializer())
    }
}

/**
 * Enforces list visibility on the shared channel and translates visibility transitions for the
 * *other* client: a todo entering a private list looks like a deletion; a todo that is (or becomes)
 * shared looks like an upsert; a todo that stays private is never sent. Also used by the
 * recurring-todo safety-net scheduler.
 */
internal suspend fun broadcastTodoUpdate(wasShared: Boolean, isShared: Boolean, todo: TodoDto) {
    val type = when {
        isShared -> "TODO_UPDATED"   // other client upserts (covers private -> shared too)
        wasShared -> "TODO_DELETED"  // shared -> private: remove it for the other client
        else -> return               // stays private: nothing to share
    }
    WsSessionManager.broadcastSync(TODO_WS_CHANNEL, type, todo, TodoDto.serializer())
}

private suspend fun broadcastTodoDelete(shared: Boolean, todo: TodoDto) {
    if (shared) {
        WsSessionManager.broadcastSync(TODO_WS_CHANNEL, "TODO_DELETED", todo, TodoDto.serializer())
    }
}

private suspend fun broadcastListCreate(list: TodoListDto) {
    if (list.visibility != VISIBILITY_PRIVATE) {
        WsSessionManager.broadcastSync(TODO_WS_CHANNEL, "TODO_LIST_CREATED", list, TodoListDto.serializer())
    }
}

/** Same visibility rules as todos, applied to the list's own metadata (its name leaks otherwise). */
private suspend fun broadcastListUpdate(
    wasShared: Boolean,
    list: TodoListDto,
    revealedTodos: List<TodoDto>,
) {
    val isShared = list.visibility != VISIBILITY_PRIVATE
    val type = when {
        isShared && wasShared -> "TODO_LIST_UPDATED"  // normal edit: other client replaces it
        isShared -> "TODO_LIST_CREATED"               // private -> shared: other client gains it
        wasShared -> "TODO_LIST_DELETED"              // shared -> private: other client drops list + todos
        else -> return                                // stays private: nothing to share
    }
    WsSessionManager.broadcastSync(TODO_WS_CHANNEL, type, list, TodoListDto.serializer())
    // private -> shared: the TODO_LIST_CREATED above only carries list metadata. The list's todos were
    // never broadcast while it was private, so the other client would render it empty until a manual
    // reload. Replay each as a TODO_CREATED upsert (the frontend handler is idempotent). See issue #75.
    if (isShared && !wasShared) {
        revealedTodos.forEach { todo ->
            WsSessionManager.broadcastSync(TODO_WS_CHANNEL, "TODO_CREATED", todo, TodoDto.serializer())
        }
    }
}

private suspend fun broadcastListDelete(list: TodoListDto) {
    if (list.visibility != VISIBILITY_PRIVATE) {
        WsSessionManager.broadcastSync(TODO_WS_CHANNEL, "TODO_LIST_DELETED", list, TodoListDto.serializer())
    }
}
