package com.homebase.service

import com.homebase.db.NoteAttachmentsTable
import com.homebase.db.NoteImagesTable
import com.homebase.db.NotesTable
import com.homebase.db.dbQuery
import com.homebase.model.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import java.time.Instant
import java.util.UUID

// VISIBILITY_SHARED/VISIBILITY_PRIVATE are the package-level constants declared in TodoService.kt
// (com.homebase.service); reused here rather than redeclared to avoid a duplicate-symbol clash.

/**
 * Owns the notes domain's persistence and visibility rules (issue #563, following the TodoService
 * pattern of #546): note CRUD plus the DB side of the image/attachment galleries. Methods are
 * `suspend` + `dbQuery {}` (#549).
 *
 * The route keeps the file-I/O and multipart concerns (upload parsing, promoting/deleting the bytes
 * on disk, streaming a download) and the post-commit broadcasts; this service never touches the
 * filesystem — an upload method takes the already-stored filename + metadata, a download method
 * returns just the row it resolved. Private-note visibility (a private note is invisible to the other
 * user → treated as 404, never leaked over the shared WS channel) is enforced here.
 */
class NoteService {

    /** Metadata of an already-stored upload the route hands to [addImage]/[addAttachment]. */
    class StoredUpload(
        val id: UUID,
        val storedName: String,
        val originalName: String,
        val contentType: String,
        val size: Long,
    )

    /** What the route needs to stream a child download: the on-disk name + how to label it. */
    class ChildDownload(val filename: String, val contentType: String, val originalName: String)

    /** A removed child's on-disk filename plus the note to broadcast after the file is deleted. */
    class ChildDeleteOutcome(val filename: String, val note: NoteDto)

    /** A deleted note plus the image/attachment filenames to clean off disk afterwards. */
    class NoteDeleteOutcome(val note: NoteDto, val files: List<String>)

    sealed interface UpdateResult {
        data object NotFound : UpdateResult
        data object Forbidden : UpdateResult
        data class Success(val wasShared: Boolean, val note: NoteDto) : UpdateResult
    }

    // ---- Notes -----------------------------------------------------------

    suspend fun list(username: String, query: String?, folder: String?): List<NoteDto> = dbQuery {
        val rows = NotesTable.selectAll()
            .where {
                var cond = visibleTo(username)
                if (folder != null) cond = cond and (NotesTable.folder eq folder)
                cond
            }
            .orderBy(NotesTable.updatedAt, SortOrder.DESC)
            .toList()
        val ids = rows.map { it[NotesTable.id] }
        val imagesByNote = loadImagesFor(ids)
        val attachmentsByNote = loadAttachmentsFor(ids)
        rows.map {
            val id = it[NotesTable.id]
            it.toDto(NoteChildren(imagesByNote[id].orEmpty(), attachmentsByNote[id].orEmpty()))
        }
    }.let { all ->
        if (query.isNullOrEmpty()) all
        else all.filter { note ->
            note.title.lowercase().contains(query) ||
                note.content.lowercase().contains(query) ||
                note.tags.any { it.lowercase().contains(query) }
        }
    }

    /** Caller has validated the title (non-blank) and resolved [visibility] to a valid value. */
    suspend fun create(title: String, content: String?, tags: List<String>?, folder: String?, visibility: String, username: String): NoteDto = dbQuery {
        val id = UUID.randomUUID()
        val now = Instant.now()
        NotesTable.insert {
            it[NotesTable.id] = id
            it[NotesTable.title] = title
            it[NotesTable.content] = content ?: ""
            it[NotesTable.tags] = encodeTags(tags)
            it[NotesTable.folder] = normalizeFolder(folder)
            it[NotesTable.visibility] = visibility
            it[createdBy] = username
            it[createdAt] = now
            it[updatedAt] = now
        }
        // a freshly created note has no images or attachments yet
        NotesTable.selectAll().where { NotesTable.id eq id }.single().toDto(NoteChildren(emptyList(), emptyList()))
    }

    /** Caller has validated the title (non-blank if present) and resolved [newVisibility] (valid or null). */
    suspend fun update(id: UUID, req: UpdateNoteRequest, newVisibility: String?, username: String): UpdateResult = dbQuery {
        val existing = NotesTable.selectAll().where { NotesTable.id eq id }.singleOrNull()
            ?: return@dbQuery UpdateResult.NotFound
        // hide notes the caller cannot see (private notes of the other user)
        if (!existing.isVisibleTo(username)) return@dbQuery UpdateResult.NotFound

        // Shared notes are editable by both users, but only the owner may change a note's visibility.
        // Otherwise a user could flip the other user's shared note to private — silently handing it
        // off and losing access to it themselves.
        if (newVisibility != null &&
            newVisibility != existing[NotesTable.visibility] &&
            existing[NotesTable.createdBy] != username
        ) {
            return@dbQuery UpdateResult.Forbidden
        }

        val wasShared = existing[NotesTable.visibility] == VISIBILITY_SHARED

        NotesTable.update({ NotesTable.id eq id }) {
            req.title?.let { v -> it[title] = v }
            req.content?.let { v -> it[content] = v }
            req.tags?.let { v -> it[tags] = encodeTags(v) }
            // a present folder field updates (blank ⇒ clears to null); omitting it (null) leaves the
            // current folder untouched, like content/tags above.
            req.folder?.let { v -> it[folder] = normalizeFolder(v) }
            newVisibility?.let { v -> it[visibility] = v }
            it[updatedAt] = Instant.now()
        }
        val updated = NotesTable.selectAll().where { NotesTable.id eq id }.single().toDto(loadChildren(id))
        UpdateResult.Success(wasShared, updated)
    }

    /** Returns null when the note is missing/invisible (→ 404). */
    suspend fun delete(id: UUID, username: String): NoteDeleteOutcome? = dbQuery {
        val existing = NotesTable.selectAll().where { NotesTable.id eq id }.singleOrNull()
            ?: return@dbQuery null
        if (!existing.isVisibleTo(username)) return@dbQuery null
        // Capture the image + attachment filenames before the cascade removes their rows so we can
        // clean up the files on disk afterwards.
        val files = NoteImagesTable.selectAll().where { NoteImagesTable.noteId eq id }
            .map { it[NoteImagesTable.filename] } +
            NoteAttachmentsTable.selectAll().where { NoteAttachmentsTable.noteId eq id }
                .map { it[NoteAttachmentsTable.filename] }
        NotesTable.deleteWhere { NotesTable.id eq id }
        NoteDeleteOutcome(existing.toDto(NoteChildren(emptyList(), emptyList())), files)
    }

    suspend fun isNoteVisible(noteId: UUID, username: String): Boolean = dbQuery {
        NotesTable.selectAll().where { NotesTable.id eq noteId }.singleOrNull()?.isVisibleTo(username) ?: false
    }

    // ---- Images ----------------------------------------------------------

    /** Inserts the image row after the file is on disk. Returns null when the note vanished/is invisible. */
    suspend fun addImage(noteId: UUID, username: String, upload: StoredUpload): NoteDto? = dbQuery {
        val note = NotesTable.selectAll().where { NotesTable.id eq noteId }.singleOrNull()
            ?: return@dbQuery null
        if (!note.isVisibleTo(username)) return@dbQuery null
        // append after the existing images (0-based index == current count)
        val nextOrder = NoteImagesTable.selectAll().where { NoteImagesTable.noteId eq noteId }.count().toInt()
        NoteImagesTable.insert {
            it[NoteImagesTable.id] = upload.id
            it[NoteImagesTable.noteId] = noteId
            it[filename] = upload.storedName
            it[NoteImagesTable.originalName] = upload.originalName
            it[NoteImagesTable.contentType] = upload.contentType
            it[sizeBytes] = upload.size
            it[sortOrder] = nextOrder
            it[createdBy] = username
            it[createdAt] = Instant.now()
        }
        NotesTable.update({ NotesTable.id eq noteId }) { it[updatedAt] = Instant.now() }
        NotesTable.selectAll().where { NotesTable.id eq noteId }.single().toDto(loadChildren(noteId))
    }

    /** Resolves an image for download (visibility-gated). Returns null when note/image is missing/invisible. */
    suspend fun imageForDownload(noteId: UUID, imageId: UUID, username: String): ChildDownload? = dbQuery {
        val note = NotesTable.selectAll().where { NotesTable.id eq noteId }.singleOrNull()
            ?: return@dbQuery null
        if (!note.isVisibleTo(username)) return@dbQuery null
        val row = NoteImagesTable.selectAll()
            .where { (NoteImagesTable.id eq imageId) and (NoteImagesTable.noteId eq noteId) }
            .singleOrNull() ?: return@dbQuery null
        ChildDownload(row[NoteImagesTable.filename], row[NoteImagesTable.contentType], row[NoteImagesTable.originalName])
    }

    /** Deletes the image row. Returns null when note/image is missing/invisible (→ 404). */
    suspend fun deleteImage(noteId: UUID, imageId: UUID, username: String): ChildDeleteOutcome? = dbQuery {
        val note = NotesTable.selectAll().where { NotesTable.id eq noteId }.singleOrNull()
            ?: return@dbQuery null
        if (!note.isVisibleTo(username)) return@dbQuery null
        val image = NoteImagesTable.selectAll()
            .where { (NoteImagesTable.id eq imageId) and (NoteImagesTable.noteId eq noteId) }
            .singleOrNull() ?: return@dbQuery null
        val filename = image[NoteImagesTable.filename]
        NoteImagesTable.deleteWhere { (NoteImagesTable.id eq imageId) and (NoteImagesTable.noteId eq noteId) }
        NotesTable.update({ NotesTable.id eq noteId }) { it[updatedAt] = Instant.now() }
        ChildDeleteOutcome(filename, NotesTable.selectAll().where { NotesTable.id eq noteId }.single().toDto(loadChildren(noteId)))
    }

    // ---- Attachments (#431) ---------------------------------------------

    suspend fun addAttachment(noteId: UUID, username: String, upload: StoredUpload): NoteDto? = dbQuery {
        val note = NotesTable.selectAll().where { NotesTable.id eq noteId }.singleOrNull()
            ?: return@dbQuery null
        if (!note.isVisibleTo(username)) return@dbQuery null
        // append after the existing attachments (0-based index == current count)
        val nextOrder = NoteAttachmentsTable.selectAll().where { NoteAttachmentsTable.noteId eq noteId }.count().toInt()
        NoteAttachmentsTable.insert {
            it[NoteAttachmentsTable.id] = upload.id
            it[NoteAttachmentsTable.noteId] = noteId
            it[filename] = upload.storedName
            it[NoteAttachmentsTable.originalName] = upload.originalName
            it[NoteAttachmentsTable.contentType] = upload.contentType
            it[sizeBytes] = upload.size
            it[sortOrder] = nextOrder
            it[createdBy] = username
            it[createdAt] = Instant.now()
        }
        NotesTable.update({ NotesTable.id eq noteId }) { it[updatedAt] = Instant.now() }
        NotesTable.selectAll().where { NotesTable.id eq noteId }.single().toDto(loadChildren(noteId))
    }

    suspend fun attachmentForDownload(noteId: UUID, attachmentId: UUID, username: String): ChildDownload? = dbQuery {
        val note = NotesTable.selectAll().where { NotesTable.id eq noteId }.singleOrNull()
            ?: return@dbQuery null
        if (!note.isVisibleTo(username)) return@dbQuery null
        val row = NoteAttachmentsTable.selectAll()
            .where { (NoteAttachmentsTable.id eq attachmentId) and (NoteAttachmentsTable.noteId eq noteId) }
            .singleOrNull() ?: return@dbQuery null
        ChildDownload(row[NoteAttachmentsTable.filename], row[NoteAttachmentsTable.contentType], row[NoteAttachmentsTable.originalName])
    }

    suspend fun deleteAttachment(noteId: UUID, attachmentId: UUID, username: String): ChildDeleteOutcome? = dbQuery {
        val note = NotesTable.selectAll().where { NotesTable.id eq noteId }.singleOrNull()
            ?: return@dbQuery null
        if (!note.isVisibleTo(username)) return@dbQuery null
        val attachment = NoteAttachmentsTable.selectAll()
            .where { (NoteAttachmentsTable.id eq attachmentId) and (NoteAttachmentsTable.noteId eq noteId) }
            .singleOrNull() ?: return@dbQuery null
        val filename = attachment[NoteAttachmentsTable.filename]
        NoteAttachmentsTable.deleteWhere { (NoteAttachmentsTable.id eq attachmentId) and (NoteAttachmentsTable.noteId eq noteId) }
        NotesTable.update({ NotesTable.id eq noteId }) { it[updatedAt] = Instant.now() }
        ChildDeleteOutcome(filename, NotesTable.selectAll().where { NotesTable.id eq noteId }.single().toDto(loadChildren(noteId)))
    }
}

// ---- Visibility, encoding & mappers (moved verbatim; each runs inside a transaction) -----------

// A note is visible to a user if it is shared or they created it.
private fun SqlExpressionBuilder.visibleTo(username: String): Op<Boolean> =
    (NotesTable.visibility eq VISIBILITY_SHARED) or (NotesTable.createdBy eq username)

private fun ResultRow.isVisibleTo(username: String): Boolean =
    this[NotesTable.visibility] == VISIBILITY_SHARED || this[NotesTable.createdBy] == username

private fun encodeTags(tags: List<String>?): String =
    tags.orEmpty().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(",")

// A folder is a trimmed label; blank ⇒ no folder (null), so empty input never creates an
// empty-string folder that would show up as its own bucket client-side.
private fun normalizeFolder(folder: String?): String? =
    folder?.trim()?.takeIf { it.isNotEmpty() }

private fun decodeTags(raw: String): List<String> =
    raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }

private fun loadImagesFor(noteIds: List<UUID>): Map<UUID, List<NoteImageDto>> {
    if (noteIds.isEmpty()) return emptyMap()
    return NoteImagesTable.selectAll()
        .where { NoteImagesTable.noteId inList noteIds }
        .orderBy(NoteImagesTable.sortOrder to SortOrder.ASC, NoteImagesTable.createdAt to SortOrder.ASC)
        .groupBy({ it[NoteImagesTable.noteId] }, { it.toImageDto() })
}

private fun loadImages(noteId: UUID): List<NoteImageDto> =
    NoteImagesTable.selectAll()
        .where { NoteImagesTable.noteId eq noteId }
        .orderBy(NoteImagesTable.sortOrder to SortOrder.ASC, NoteImagesTable.createdAt to SortOrder.ASC)
        .map { it.toImageDto() }

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

private fun loadAttachments(noteId: UUID): List<NoteAttachmentDto> =
    NoteAttachmentsTable.selectAll()
        .where { NoteAttachmentsTable.noteId eq noteId }
        .orderBy(NoteAttachmentsTable.sortOrder to SortOrder.ASC, NoteAttachmentsTable.createdAt to SortOrder.ASC)
        .map { it.toAttachmentDto() }

private fun loadAttachmentsFor(noteIds: List<UUID>): Map<UUID, List<NoteAttachmentDto>> {
    if (noteIds.isEmpty()) return emptyMap()
    return NoteAttachmentsTable.selectAll()
        .where { NoteAttachmentsTable.noteId inList noteIds }
        .orderBy(NoteAttachmentsTable.sortOrder to SortOrder.ASC, NoteAttachmentsTable.createdAt to SortOrder.ASC)
        .groupBy({ it[NoteAttachmentsTable.noteId] }, { it.toAttachmentDto() })
}

private fun ResultRow.toAttachmentDto() = NoteAttachmentDto(
    id = this[NoteAttachmentsTable.id].toString(),
    noteId = this[NoteAttachmentsTable.noteId].toString(),
    originalName = this[NoteAttachmentsTable.originalName],
    contentType = this[NoteAttachmentsTable.contentType],
    sizeBytes = this[NoteAttachmentsTable.sizeBytes],
    sortOrder = this[NoteAttachmentsTable.sortOrder],
    createdBy = this[NoteAttachmentsTable.createdBy],
    createdAt = this[NoteAttachmentsTable.createdAt].toString(),
)

// Load the full embedded child collections (images + attachments) for one note. Used by every
// single-note response so both arrays are always populated.
private fun loadChildren(noteId: UUID): NoteChildren =
    NoteChildren(loadImages(noteId), loadAttachments(noteId))

private data class NoteChildren(val images: List<NoteImageDto>, val attachments: List<NoteAttachmentDto>)

private fun ResultRow.toDto(children: NoteChildren) = NoteDto(
    id = this[NotesTable.id].toString(),
    title = this[NotesTable.title],
    content = this[NotesTable.content],
    tags = decodeTags(this[NotesTable.tags]),
    folder = this[NotesTable.folder],
    visibility = this[NotesTable.visibility],
    images = children.images,
    attachments = children.attachments,
    createdBy = this[NotesTable.createdBy],
    createdAt = this[NotesTable.createdAt].toString(),
    updatedAt = this[NotesTable.updatedAt].toString(),
)
