package com.homebase.routes

import com.homebase.db.NoteImagesTable
import com.homebase.db.NotesTable
import com.homebase.model.*
import com.homebase.ws.WsSessionManager
import io.ktor.server.application.*
import io.ktor.http.*
import io.ktor.server.http.content.LocalFileContent
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import com.homebase.plugins.appJson
import kotlinx.serialization.encodeToString
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Files
import java.time.Instant
import java.util.UUID

private const val VISIBILITY_PRIVATE = "PRIVATE"
private const val VISIBILITY_SHARED = "SHARED"
private const val NOTES_WS_CHANNEL = "notes"
private val VALID_VISIBILITIES = setOf(VISIBILITY_PRIVATE, VISIBILITY_SHARED)

fun Route.noteRoutes(imageConfig: ImageUploadConfig) {
    route("/notes") {
        // List notes visible to the caller (own notes + all shared), newest first.
        // Optional ?q= performs a case-insensitive search over title, content and tags.
        get {
            val username = call.username()
            val query = call.request.queryParameters["q"]?.trim()?.lowercase()
            // optional exact-match folder filter; blank/missing ⇒ no folder restriction
            val folder = call.request.queryParameters["folder"]?.trim()?.takeIf { it.isNotEmpty() }

            val notes = transaction {
                val rows = NotesTable.selectAll()
                    .where {
                        var cond = visibleTo(username)
                        if (folder != null) cond = cond and (NotesTable.folder eq folder)
                        cond
                    }
                    .orderBy(NotesTable.updatedAt, SortOrder.DESC)
                    .toList()
                val imagesByNote = loadImagesFor(rows.map { it[NotesTable.id] })
                rows.map { it.toDto(imagesByNote[it[NotesTable.id]].orEmpty()) }
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
                    it[folder] = normalizeFolder(req.folder)
                    it[NotesTable.visibility] = visibility
                    it[createdBy] = username
                    it[createdAt] = now
                    it[updatedAt] = now
                }
                // a freshly created note has no images yet
                NotesTable.selectAll().where { NotesTable.id eq id }.single().toDto(emptyList())
            }

            broadcastCreate(note)
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
                    ?: return@transaction NoteUpdateResult.NotFound
                // hide notes the caller cannot see (private notes of the other user)
                if (!existing.isVisibleTo(username)) return@transaction NoteUpdateResult.NotFound

                // Shared notes are editable by both users, but only the owner may change a note's
                // visibility. Otherwise a user could flip the other user's shared note to private —
                // silently handing it off and losing access to it themselves.
                if (newVisibility != null &&
                    newVisibility != existing[NotesTable.visibility] &&
                    existing[NotesTable.createdBy] != username
                ) {
                    return@transaction NoteUpdateResult.Forbidden
                }

                val wasShared = existing[NotesTable.visibility] == VISIBILITY_SHARED

                NotesTable.update({ NotesTable.id eq id }) {
                    req.title?.let { v -> it[title] = v }
                    req.content?.let { v -> it[content] = v }
                    req.tags?.let { v -> it[tags] = encodeTags(v) }
                    // a present folder field updates (blank ⇒ clears to null); omitting it
                    // (null) leaves the current folder untouched, like content/tags above.
                    req.folder?.let { v -> it[folder] = normalizeFolder(v) }
                    newVisibility?.let { v -> it[visibility] = v }
                    it[updatedAt] = Instant.now()
                }
                val updated = NotesTable.selectAll().where { NotesTable.id eq id }.single().toDto(loadImages(id))
                NoteUpdateResult.Success(wasShared, updated)
            }

            when (result) {
                NoteUpdateResult.NotFound ->
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Note not found"))
                NoteUpdateResult.Forbidden ->
                    call.respond(
                        HttpStatusCode.Forbidden,
                        ErrorResponse("VISIBILITY_FORBIDDEN", "only the note's owner may change its visibility"),
                    )
                is NoteUpdateResult.Success -> {
                    broadcastUpdate(result.wasShared, result.note)
                    call.respond(result.note)
                }
            }
        }

        delete("/{id}") {
            val username = call.username()
            val id = call.uuidParam() ?: return@delete

            val outcome = transaction {
                val existing = NotesTable.selectAll().where { NotesTable.id eq id }.singleOrNull()
                    ?: return@transaction null
                if (!existing.isVisibleTo(username)) return@transaction null
                // Capture the image filenames before the cascade removes their rows so we can
                // clean up the files on disk afterwards.
                val files = NoteImagesTable.selectAll().where { NoteImagesTable.noteId eq id }
                    .map { it[NoteImagesTable.filename] }
                NotesTable.deleteWhere { NotesTable.id eq id }
                existing.toDto(emptyList()) to files
            }
            if (outcome == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Note not found"))
                return@delete
            }
            val (deleted, files) = outcome
            files.forEach { deleteImageFile(imageConfig, it) }
            // only notify the other client about notes it could actually see
            if (deleted.visibility == VISIBILITY_SHARED) {
                WsSessionManager.broadcast(NOTES_WS_CHANNEL, appJson.encodeToString(NoteWsMessage("NOTE_DELETED", deleted)))
            }
            call.respond(HttpStatusCode.NoContent)
        }

        // --- Images -------------------------------------------------------

        // Upload an image to a note. Returns the updated note (with its images embedded),
        // mirroring the recipe pattern of returning the full aggregate after a child change.
        post("/{id}/images") {
            val username = call.username()
            val noteId = call.uuidParam() ?: return@post

            val visible = transaction {
                NotesTable.selectAll().where { NotesTable.id eq noteId }.singleOrNull()?.isVisibleTo(username) ?: false
            }
            if (!visible) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Note not found"))
                return@post
            }

            val upload = when (val received = call.receiveImageUpload(imageConfig)) {
                is ImageUploadResult.Rejected -> {
                    call.respondImageRejection(received.reason, imageConfig)
                    return@post
                }
                ImageUploadResult.None -> {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("NO_IMAGE", "no image file in request"))
                    return@post
                }
                is ImageUploadResult.Accepted -> received.upload
            }

            val imageId = UUID.randomUUID()
            val storedName = "$imageId.${ALLOWED_IMAGE_TYPES.getValue(upload.contentType)}"
            // The bytes are already streamed to a temp file; promote it to its final name.
            finalizeImageFile(imageConfig, upload.tempFile, storedName)

            val result = transaction {
                val note = NotesTable.selectAll().where { NotesTable.id eq noteId }.singleOrNull()
                    ?: return@transaction null
                if (!note.isVisibleTo(username)) return@transaction null
                // append after the existing images (0-based index == current count)
                val nextOrder = NoteImagesTable.selectAll().where { NoteImagesTable.noteId eq noteId }.count().toInt()
                NoteImagesTable.insert {
                    it[NoteImagesTable.id] = imageId
                    it[NoteImagesTable.noteId] = noteId
                    it[filename] = storedName
                    it[NoteImagesTable.originalName] = upload.originalName
                    it[NoteImagesTable.contentType] = upload.contentType
                    it[sizeBytes] = upload.size
                    it[sortOrder] = nextOrder
                    it[createdBy] = username
                    it[createdAt] = Instant.now()
                }
                NotesTable.update({ NotesTable.id eq noteId }) { it[updatedAt] = Instant.now() }
                NotesTable.selectAll().where { NotesTable.id eq noteId }.single().toDto(loadImages(noteId))
            }
            if (result == null) {
                // note vanished between the visibility check and the insert — undo the file
                deleteImageFile(imageConfig, storedName)
                call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Note not found"))
                return@post
            }

            broadcastUpdate(wasShared = result.visibility == VISIBILITY_SHARED, note = result)
            call.respond(HttpStatusCode.Created, result)
        }

        // Serve the raw image bytes. Access follows the note's visibility.
        get("/{id}/images/{imageId}") {
            val username = call.username()
            val noteId = call.uuidParam() ?: return@get
            val imageId = call.uuidParam("imageId") ?: return@get

            val row = transaction {
                val note = NotesTable.selectAll().where { NotesTable.id eq noteId }.singleOrNull()
                    ?: return@transaction null
                if (!note.isVisibleTo(username)) return@transaction null
                NoteImagesTable.selectAll()
                    .where { (NoteImagesTable.id eq imageId) and (NoteImagesTable.noteId eq noteId) }
                    .singleOrNull()
            }
            if (row == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Image not found"))
                return@get
            }
            val file = imageConfig.uploadDir.resolve(row[NoteImagesTable.filename])
            if (!Files.exists(file)) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Image file missing"))
                return@get
            }
            // Stored names are immutable (UUID-based), so the bytes never change.
            call.response.headers.append(HttpHeaders.CacheControl, "private, max-age=31536000, immutable")
            // The stored content-type is the *declared* one from upload; the bytes are never
            // sniffed/validated to be a real image. Tell the browser not to MIME-sniff so a
            // crafted file (e.g. HTML mislabelled image/png) can never be reinterpreted as markup.
            call.response.headers.append("X-Content-Type-Options", "nosniff")
            // Stream the file straight from disk (LocalFileContent) instead of reading the whole
            // image into the heap; it also adds Content-Length and supports range requests.
            call.respond(LocalFileContent(file.toFile(), ContentType.parse(row[NoteImagesTable.contentType])))
        }

        // Remove an image from a note. Returns the updated note.
        delete("/{id}/images/{imageId}") {
            val username = call.username()
            val noteId = call.uuidParam() ?: return@delete
            val imageId = call.uuidParam("imageId") ?: return@delete

            val outcome = transaction {
                val note = NotesTable.selectAll().where { NotesTable.id eq noteId }.singleOrNull()
                    ?: return@transaction null
                if (!note.isVisibleTo(username)) return@transaction null
                val image = NoteImagesTable.selectAll()
                    .where { (NoteImagesTable.id eq imageId) and (NoteImagesTable.noteId eq noteId) }
                    .singleOrNull() ?: return@transaction null
                val filename = image[NoteImagesTable.filename]
                NoteImagesTable.deleteWhere { (NoteImagesTable.id eq imageId) and (NoteImagesTable.noteId eq noteId) }
                NotesTable.update({ NotesTable.id eq noteId }) { it[updatedAt] = Instant.now() }
                filename to NotesTable.selectAll().where { NotesTable.id eq noteId }.single().toDto(loadImages(noteId))
            }
            if (outcome == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Image not found"))
                return@delete
            }
            val (filename, note) = outcome
            deleteImageFile(imageConfig, filename)
            broadcastUpdate(wasShared = note.visibility == VISIBILITY_SHARED, note = note)
            call.respond(note)
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

private sealed interface NoteUpdateResult {
    data object NotFound : NoteUpdateResult
    data object Forbidden : NoteUpdateResult
    data class Success(val wasShared: Boolean, val note: NoteDto) : NoteUpdateResult
}

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
private suspend fun broadcastCreate(note: NoteDto) {
    if (note.visibility == VISIBILITY_SHARED) {
        WsSessionManager.broadcast(NOTES_WS_CHANNEL, appJson.encodeToString(NoteWsMessage("NOTE_CREATED", note)))
    }
}

private suspend fun broadcastUpdate(wasShared: Boolean, note: NoteDto) {
    val type = when {
        note.visibility == VISIBILITY_SHARED -> "NOTE_UPDATED"     // other client upserts
        wasShared -> "NOTE_DELETED"                                 // shared -> private: remove it
        else -> return                                              // private -> private: nothing to share
    }
    WsSessionManager.broadcast(NOTES_WS_CHANNEL, appJson.encodeToString(NoteWsMessage(type, note)))
}

private fun encodeTags(tags: List<String>?): String =
    tags.orEmpty().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(",")

// A folder is a trimmed label; blank ⇒ no folder (null), so empty input never
// creates an empty-string folder that would show up as its own bucket client-side.
private fun normalizeFolder(folder: String?): String? =
    folder?.trim()?.takeIf { it.isNotEmpty() }

private fun decodeTags(raw: String): List<String> =
    raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }

// --- Image persistence helpers (must be called inside a transaction) ----------

private fun loadImages(noteId: UUID): List<NoteImageDto> =
    NoteImagesTable.selectAll()
        .where { NoteImagesTable.noteId eq noteId }
        .orderBy(NoteImagesTable.sortOrder to SortOrder.ASC, NoteImagesTable.createdAt to SortOrder.ASC)
        .map { it.toImageDto() }

private fun loadImagesFor(noteIds: List<UUID>): Map<UUID, List<NoteImageDto>> {
    if (noteIds.isEmpty()) return emptyMap()
    return NoteImagesTable.selectAll()
        .where { NoteImagesTable.noteId inList noteIds }
        .orderBy(NoteImagesTable.sortOrder to SortOrder.ASC, NoteImagesTable.createdAt to SortOrder.ASC)
        .groupBy({ it[NoteImagesTable.noteId] }, { it.toImageDto() })
}

private fun ResultRow.toImageDto() = NoteImageDto(
    id = this[NoteImagesTable.id].toString(),
    noteId = this[NoteImagesTable.noteId].toString(),
    originalName = this[NoteImagesTable.originalName],
    contentType = this[NoteImagesTable.contentType],
    sizeBytes = this[NoteImagesTable.sizeBytes],
    sortOrder = this[NoteImagesTable.sortOrder],
    createdBy = this[NoteImagesTable.createdBy],
    createdAt = this[NoteImagesTable.createdAt].toString(),
)

private fun ResultRow.toDto(images: List<NoteImageDto>) = NoteDto(
    id = this[NotesTable.id].toString(),
    title = this[NotesTable.title],
    content = this[NotesTable.content],
    tags = decodeTags(this[NotesTable.tags]),
    folder = this[NotesTable.folder],
    visibility = this[NotesTable.visibility],
    images = images,
    createdBy = this[NotesTable.createdBy],
    createdAt = this[NotesTable.createdAt].toString(),
    updatedAt = this[NotesTable.updatedAt].toString()
)
