package com.homebase.routes

import com.homebase.model.*
import com.homebase.service.NoteService
import com.homebase.ws.*
import io.ktor.http.*
import io.ktor.server.http.content.LocalFileContent
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.nio.file.Files
import java.util.UUID

private const val VISIBILITY_PRIVATE = "PRIVATE"
private const val VISIBILITY_SHARED = "SHARED"
private const val NOTES_WS_CHANNEL = "notes"
private val VALID_VISIBILITIES = setOf(VISIBILITY_PRIVATE, VISIBILITY_SHARED)

/**
 * HTTP surface for the notes domain. Handlers parse/validate the request, keep the file-I/O and
 * multipart concerns (upload parsing, promoting/deleting bytes on disk, streaming a download), call
 * [NoteService] for all persistence + visibility rules, then broadcast after commit. No handler
 * touches `Notes*Table`/`dbQuery {}` (issue #563, following the TodoService pattern of #546). The
 * broadcast wire format is the generic SyncEnvelope via broadcastSync (#552).
 */
fun Route.noteRoutes(imageConfig: ImageUploadConfig) {
    val service = NoteService()

    route("/notes") {
        // List notes visible to the caller (own notes + all shared), newest first.
        // Optional ?q= performs a case-insensitive search over title, content and tags.
        get {
            val username = call.username()
            val query = call.request.queryParameters["q"]?.trim()?.lowercase()
            // optional exact-match folder filter; blank/missing ⇒ no folder restriction
            val folder = call.request.queryParameters["folder"]?.trim()?.takeIf { it.isNotEmpty() }
            call.respond(service.list(username, query, folder))
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

            val note = service.create(req.title, req.content, req.tags, req.folder, visibility, username)
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

            when (val result = service.update(id, req, newVisibility, username)) {
                NoteService.UpdateResult.NotFound ->
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Note not found"))
                NoteService.UpdateResult.Forbidden ->
                    call.respond(
                        HttpStatusCode.Forbidden,
                        ErrorResponse("VISIBILITY_FORBIDDEN", "only the note's owner may change its visibility"),
                    )
                is NoteService.UpdateResult.Success -> {
                    broadcastUpdate(result.wasShared, result.note)
                    call.respond(result.note)
                }
            }
        }

        delete("/{id}") {
            val username = call.username()
            val id = call.uuidParam() ?: return@delete

            val outcome = service.delete(id, username)
            if (outcome == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Note not found"))
                return@delete
            }
            outcome.files.forEach { deleteImageFile(imageConfig, it) }
            // only notify the other client about notes it could actually see
            if (outcome.note.visibility == VISIBILITY_SHARED) {
                WsSessionManager.broadcastSync(NOTES_WS_CHANNEL, "NOTE_DELETED", outcome.note, NoteDto.serializer())
            }
            call.respond(HttpStatusCode.NoContent)
        }

        // --- Images -------------------------------------------------------

        // Upload an image to a note. Returns the updated note (with its images embedded).
        post("/{id}/images") {
            val username = call.username()
            val noteId = call.uuidParam() ?: return@post

            if (!service.isNoteVisible(noteId, username)) {
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

            val note = service.addImage(
                noteId, username,
                NoteService.StoredUpload(imageId, storedName, upload.originalName, upload.contentType, upload.size),
            )
            if (note == null) {
                // note vanished between the visibility check and the insert — undo the file
                deleteImageFile(imageConfig, storedName)
                call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Note not found"))
                return@post
            }

            broadcastUpdate(wasShared = note.visibility == VISIBILITY_SHARED, note = note)
            call.respond(HttpStatusCode.Created, note)
        }

        // Serve the raw image bytes. Access follows the note's visibility.
        get("/{id}/images/{imageId}") {
            val username = call.username()
            val noteId = call.uuidParam() ?: return@get
            val imageId = call.uuidParam("imageId") ?: return@get

            val row = service.imageForDownload(noteId, imageId, username)
            if (row == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Image not found"))
                return@get
            }
            val file = imageConfig.uploadDir.resolve(row.filename)
            if (!Files.exists(file)) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Image file missing"))
                return@get
            }
            // Stored names are immutable (UUID-based), so the bytes never change.
            call.response.headers.append(HttpHeaders.CacheControl, "private, max-age=31536000, immutable")
            // The stored content-type is the *declared* one from upload; the bytes are never
            // sniffed/validated to be a real image. Tell the browser not to MIME-sniff so a crafted
            // file (e.g. HTML mislabelled image/png) can never be reinterpreted as markup.
            call.response.headers.append("X-Content-Type-Options", "nosniff")
            // Hand the browser the original upload name so a download is saved as e.g. "Urlaub.jpg".
            // Use *inline* (not attachment) so Android Coil keeps rendering the image in place.
            val downloadName = safeImageFilename(row.originalName, row.contentType)
            call.response.header(
                HttpHeaders.ContentDisposition,
                ContentDisposition.Inline.withParameter(ContentDisposition.Parameters.FileName, downloadName).toString(),
            )
            // Stream the file straight from disk (LocalFileContent) instead of reading the whole image
            // into the heap; it also adds Content-Length and supports range requests.
            call.respond(LocalFileContent(file.toFile(), ContentType.parse(row.contentType)))
        }

        // Remove an image from a note. Returns the updated note.
        delete("/{id}/images/{imageId}") {
            val username = call.username()
            val noteId = call.uuidParam() ?: return@delete
            val imageId = call.uuidParam("imageId") ?: return@delete

            val outcome = service.deleteImage(noteId, imageId, username)
            if (outcome == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Image not found"))
                return@delete
            }
            deleteImageFile(imageConfig, outcome.filename)
            broadcastUpdate(wasShared = outcome.note.visibility == VISIBILITY_SHARED, note = outcome.note)
            call.respond(outcome.note)
        }

        // --- Attachments (#431) ------------------------------------------
        // Arbitrary whitelisted file attachments (PDF, office docs, text, …). Mirrors the image
        // endpoints but accepts the document type set and serves with Content-Disposition: attachment
        // (force download, never inline) so a mislabelled HTML/SVG file can't run as markup.

        // Upload a file attachment to a note. Returns the updated note (with attachments embedded).
        post("/{id}/attachments") {
            val username = call.username()
            val noteId = call.uuidParam() ?: return@post

            if (!service.isNoteVisible(noteId, username)) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Note not found"))
                return@post
            }

            val upload = when (val received = call.receiveAttachmentUpload(imageConfig)) {
                is ImageUploadResult.Rejected -> {
                    call.respondAttachmentRejection(received.reason, imageConfig)
                    return@post
                }
                ImageUploadResult.None -> {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("NO_ATTACHMENT", "no file in request"))
                    return@post
                }
                is ImageUploadResult.Accepted -> received.upload
            }

            val attachmentId = UUID.randomUUID()
            val storedName = "$attachmentId.${ALLOWED_ATTACHMENT_TYPES.getValue(upload.contentType)}"
            // The bytes are already streamed to a temp file; promote it to its final name.
            finalizeImageFile(imageConfig, upload.tempFile, storedName)

            val note = service.addAttachment(
                noteId, username,
                NoteService.StoredUpload(attachmentId, storedName, upload.originalName, upload.contentType, upload.size),
            )
            if (note == null) {
                // note vanished between the visibility check and the insert — undo the file
                deleteImageFile(imageConfig, storedName)
                call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Note not found"))
                return@post
            }

            broadcastUpdate(wasShared = note.visibility == VISIBILITY_SHARED, note = note)
            call.respond(HttpStatusCode.Created, note)
        }

        // Serve the raw attachment bytes for download. Access follows the note's visibility.
        get("/{id}/attachments/{attachmentId}") {
            val username = call.username()
            val noteId = call.uuidParam() ?: return@get
            val attachmentId = call.uuidParam("attachmentId") ?: return@get

            val row = service.attachmentForDownload(noteId, attachmentId, username)
            if (row == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Attachment not found"))
                return@get
            }
            val file = imageConfig.uploadDir.resolve(row.filename)
            if (!Files.exists(file)) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Attachment file missing"))
                return@get
            }
            // Stored names are immutable (UUID-based), so the bytes never change.
            call.response.headers.append(HttpHeaders.CacheControl, "private, max-age=31536000, immutable")
            // The stored content-type is the *declared/derived* one from upload; the bytes are never
            // validated to actually be that type. Tell the browser not to MIME-sniff so a crafted file
            // can never be reinterpreted as markup.
            call.response.headers.append("X-Content-Type-Options", "nosniff")
            // Force a download under the original name (sanitized) — NEVER inline. Serving an HTML/SVG
            // attachment inline would be stored XSS; attachment disposition neutralises that.
            val downloadName = safeAttachmentFilename(row.originalName, row.contentType)
            call.response.header(
                HttpHeaders.ContentDisposition,
                ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, downloadName).toString(),
            )
            // Stream the file straight from disk (LocalFileContent) instead of reading it all into the
            // heap; it also adds Content-Length and supports range requests.
            call.respond(LocalFileContent(file.toFile(), ContentType.parse(row.contentType)))
        }

        // Remove an attachment from a note. Returns the updated note.
        delete("/{id}/attachments/{attachmentId}") {
            val username = call.username()
            val noteId = call.uuidParam() ?: return@delete
            val attachmentId = call.uuidParam("attachmentId") ?: return@delete

            val outcome = service.deleteAttachment(noteId, attachmentId, username)
            if (outcome == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Attachment not found"))
                return@delete
            }
            deleteImageFile(imageConfig, outcome.filename)
            broadcastUpdate(wasShared = outcome.note.visibility == VISIBILITY_SHARED, note = outcome.note)
            call.respond(outcome.note)
        }
    }

    syncChannel(NOTES_WS_CHANNEL)
}

// ---- Broadcasts (kept in the route: fired only after the service's transaction committed) -------

/**
 * The shared WS channel reaches both users, so we must never push a private note over it. On
 * visibility transitions we translate the change into the event the *other* client needs: a note
 * becoming private looks like a deletion; a note becoming shared looks like a creation.
 */
private suspend fun broadcastCreate(note: NoteDto) {
    if (note.visibility == VISIBILITY_SHARED) {
        WsSessionManager.broadcastSync(NOTES_WS_CHANNEL, "NOTE_CREATED", note, NoteDto.serializer())
    }
}

private suspend fun broadcastUpdate(wasShared: Boolean, note: NoteDto) {
    val type = when {
        note.visibility == VISIBILITY_SHARED -> "NOTE_UPDATED"     // other client upserts
        wasShared -> "NOTE_DELETED"                                 // shared -> private: remove it
        else -> return                                              // private -> private: nothing to share
    }
    WsSessionManager.broadcastSync(NOTES_WS_CHANNEL, type, note, NoteDto.serializer())
}
