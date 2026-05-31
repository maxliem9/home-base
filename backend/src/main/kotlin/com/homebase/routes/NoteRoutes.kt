package com.homebase.routes

import com.homebase.db.NotesTable
import com.homebase.model.*
import com.homebase.ws.WsSessionManager
import io.ktor.server.application.*
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant
import java.util.UUID

private const val VISIBILITY_PRIVATE = "PRIVATE"
private const val VISIBILITY_SHARED = "SHARED"
private const val NOTES_WS_CHANNEL = "notes"
private val VALID_VISIBILITIES = setOf(VISIBILITY_PRIVATE, VISIBILITY_SHARED)

fun Route.noteRoutes() {
    val json = Json { ignoreUnknownKeys = true }

    route("/notes") {
        // List notes visible to the caller (own notes + all shared), newest first.
        // Optional ?q= performs a case-insensitive search over title, content and tags.
        get {
            val username = call.username()
            val query = call.request.queryParameters["q"]?.trim()?.lowercase()

            val notes = transaction {
                NotesTable.selectAll()
                    .where { visibleTo(username) }
                    .orderBy(NotesTable.updatedAt, SortOrder.DESC)
                    .map { it.toDto() }
            }.let { all ->
                if (query.isNullOrEmpty()) all
                else all.filter { note ->
                    note.title.lowercase().contains(query) ||
                        note.content.lowercase().contains(query) ||
                        note.tags.any { it.lowercase().contains(query) }
                }
            }
            call.respond(notes)
        }

        post {
            val username = call.username()
            val req = call.receive<CreateNoteRequest>()

            if (req.title.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_NOTE", "title must not be blank"))
                return@post
            }
            val visibility = req.visibility?.uppercase() ?: VISIBILITY_SHARED
            if (visibility !in VALID_VISIBILITIES) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_VISIBILITY", "visibility must be PRIVATE or SHARED"))
                return@post
            }

            val note = transaction {
                val id = UUID.randomUUID()
                val now = Instant.now()
                NotesTable.insert {
                    it[NotesTable.id] = id
                    it[title] = req.title
                    it[content] = req.content ?: ""
                    it[tags] = encodeTags(req.tags)
                    it[NotesTable.visibility] = visibility
                    it[createdBy] = username
                    it[createdAt] = now
                    it[updatedAt] = now
                }
                NotesTable.selectAll().where { NotesTable.id eq id }.single().toDto()
            }

            broadcastCreate(json, note)
            call.respond(HttpStatusCode.Created, note)
        }

        put("/{id}") {
            val username = call.username()
            val id = call.uuidParam() ?: return@put
            val req = call.receive<UpdateNoteRequest>()

            if (req.title?.isBlank() == true) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_NOTE", "title must not be blank"))
                return@put
            }
            val newVisibility = req.visibility?.uppercase()
            if (newVisibility != null && newVisibility !in VALID_VISIBILITIES) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_VISIBILITY", "visibility must be PRIVATE or SHARED"))
                return@put
            }

            val result = transaction {
                val existing = NotesTable.selectAll().where { NotesTable.id eq id }.singleOrNull()
                    ?: return@transaction null
                // hide notes the caller cannot see (private notes of the other user)
                if (!existing.isVisibleTo(username)) return@transaction null

                val wasShared = existing[NotesTable.visibility] == VISIBILITY_SHARED

                NotesTable.update({ NotesTable.id eq id }) {
                    req.title?.let { v -> it[title] = v }
                    req.content?.let { v -> it[content] = v }
                    req.tags?.let { v -> it[tags] = encodeTags(v) }
                    newVisibility?.let { v -> it[visibility] = v }
                    it[updatedAt] = Instant.now()
                }
                val updated = NotesTable.selectAll().where { NotesTable.id eq id }.single().toDto()
                wasShared to updated
            }

            if (result == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Note not found"))
                return@put
            }
            val (wasShared, updated) = result
            broadcastUpdate(json, wasShared, updated)
            call.respond(updated)
        }

        delete("/{id}") {
            val username = call.username()
            val id = call.uuidParam() ?: return@delete

            val deleted = transaction {
                val existing = NotesTable.selectAll().where { NotesTable.id eq id }.singleOrNull()
                    ?: return@transaction null
                if (!existing.isVisibleTo(username)) return@transaction null
                NotesTable.deleteWhere { NotesTable.id eq id }
                existing.toDto()
            }
            if (deleted == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Note not found"))
                return@delete
            }
            // only notify the other client about notes it could actually see
            if (deleted.visibility == VISIBILITY_SHARED) {
                WsSessionManager.broadcast(NOTES_WS_CHANNEL, json.encodeToString(NoteWsMessage("NOTE_DELETED", deleted)))
            }
            call.respond(HttpStatusCode.NoContent)
        }
    }

    webSocket("/ws/notes") {
        WsSessionManager.add(NOTES_WS_CHANNEL, this)
        try {
            for (frame in incoming) {
                if (frame is Frame.Close) break
            }
        } finally {
            WsSessionManager.remove(NOTES_WS_CHANNEL, this)
        }
    }
}

private fun ApplicationCall.username(): String =
    principal<JWTPrincipal>()!!.payload.getClaim("username").asString()

// A note is visible to a user if it is shared or they created it.
private fun SqlExpressionBuilder.visibleTo(username: String): Op<Boolean> =
    (NotesTable.visibility eq VISIBILITY_SHARED) or (NotesTable.createdBy eq username)

private fun ResultRow.isVisibleTo(username: String): Boolean =
    this[NotesTable.visibility] == VISIBILITY_SHARED || this[NotesTable.createdBy] == username

/**
 * The shared WS channel reaches both users, so we must never push a private note over it.
 * On visibility transitions we translate the change into the event the *other* client needs:
 * a note becoming private looks like a deletion; a note becoming shared looks like a creation.
 */
private suspend fun broadcastCreate(json: Json, note: NoteDto) {
    if (note.visibility == VISIBILITY_SHARED) {
        WsSessionManager.broadcast(NOTES_WS_CHANNEL, json.encodeToString(NoteWsMessage("NOTE_CREATED", note)))
    }
}

private suspend fun broadcastUpdate(json: Json, wasShared: Boolean, note: NoteDto) {
    val type = when {
        note.visibility == VISIBILITY_SHARED -> "NOTE_UPDATED"     // other client upserts
        wasShared -> "NOTE_DELETED"                                 // shared -> private: remove it
        else -> return                                              // private -> private: nothing to share
    }
    WsSessionManager.broadcast(NOTES_WS_CHANNEL, json.encodeToString(NoteWsMessage(type, note)))
}

private fun encodeTags(tags: List<String>?): String =
    tags.orEmpty().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(",")

private fun decodeTags(raw: String): List<String> =
    raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }

private fun ResultRow.toDto() = NoteDto(
    id = this[NotesTable.id].toString(),
    title = this[NotesTable.title],
    content = this[NotesTable.content],
    tags = decodeTags(this[NotesTable.tags]),
    visibility = this[NotesTable.visibility],
    createdBy = this[NotesTable.createdBy],
    createdAt = this[NotesTable.createdAt].toString(),
    updatedAt = this[NotesTable.updatedAt].toString()
)
