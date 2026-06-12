package com.homebase.routes

import com.homebase.model.ErrorResponse
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.utils.io.*
import java.nio.file.Files
import java.nio.file.Path

// Shared image-upload plumbing used by both the note and recipe image endpoints: streaming a
// single multipart file part to disk under a size cap, the on-disk filename helpers and the
// canonical rejection responses. Kept domain-agnostic — the routes own the DB rows + visibility.

/** Where uploaded images live on disk and how large a single upload may be. */
data class ImageUploadConfig(val uploadDir: Path, val maxBytes: Long)

// Accepted image content types mapped to the on-disk file extension.
val ALLOWED_IMAGE_TYPES = mapOf(
    "image/jpeg" to "jpg",
    "image/jpg" to "jpg",
    "image/png" to "png",
    "image/webp" to "webp",
    "image/gif" to "gif",
)

// An accepted upload whose bytes already live in a temp file on disk, ready to be promoted.
class PendingUpload(
    val tempFile: Path,
    val contentType: String,
    val originalName: String,
    val size: Long,
)

enum class ImageRejection { UnsupportedType, TooLarge, Empty }

/** Outcome of reading a single image file part from a multipart request. */
sealed interface ImageUploadResult {
    data class Accepted(val upload: PendingUpload) : ImageUploadResult
    data class Rejected(val reason: ImageRejection) : ImageUploadResult
    data object None : ImageUploadResult // no file part in the request at all
}

/**
 * Read the first image file part from this multipart request, streaming it straight to a temp
 * file while enforcing the type + size limits as the bytes arrive — the body is never fully
 * buffered in the heap (see issue #48). Returns a ready [PendingUpload], the reason it was
 * rejected, or [ImageUploadResult.None] when no file part was present.
 */
suspend fun ApplicationCall.receiveImageUpload(config: ImageUploadConfig): ImageUploadResult {
    var pending: PendingUpload? = null
    var rejected: ImageRejection? = null

    val multipart = receiveMultipart()
    while (true) {
        val part = multipart.readPart() ?: break
        if (part is PartData.FileItem && pending == null && rejected == null) {
            val ct = (part.contentType?.let { "${it.contentType}/${it.contentSubtype}" }
                ?: part.originalFileName?.let { contentTypeFromName(it) })?.lowercase()
            if (ct == null || ct !in ALLOWED_IMAGE_TYPES) {
                rejected = ImageRejection.UnsupportedType
            } else {
                when (val outcome = part.streamToTempFile(config)) {
                    StreamOutcome.Empty -> rejected = ImageRejection.Empty
                    StreamOutcome.TooLarge -> rejected = ImageRejection.TooLarge
                    is StreamOutcome.Ok -> pending = PendingUpload(
                        tempFile = outcome.file,
                        contentType = ct,
                        originalName = part.originalFileName?.takeIf { it.isNotBlank() } ?: "image",
                        size = outcome.size,
                    )
                }
            }
        }
        part.dispose()
    }

    return when {
        rejected != null -> ImageUploadResult.Rejected(rejected)
        pending != null -> ImageUploadResult.Accepted(pending)
        else -> ImageUploadResult.None
    }
}

/** Respond with the canonical error for a rejected image upload. */
suspend fun ApplicationCall.respondImageRejection(reason: ImageRejection, config: ImageUploadConfig) {
    when (reason) {
        ImageRejection.UnsupportedType -> respond(
            HttpStatusCode.UnsupportedMediaType,
            ErrorResponse("UNSUPPORTED_TYPE", "image must be JPEG, PNG, WebP or GIF"),
        )
        ImageRejection.TooLarge -> {
            val mb = config.maxBytes / (1024 * 1024)
            respond(HttpStatusCode.PayloadTooLarge, ErrorResponse("IMAGE_TOO_LARGE", "image exceeds the ${mb} MB limit"))
        }
        ImageRejection.Empty -> respond(
            HttpStatusCode.BadRequest,
            ErrorResponse("EMPTY_IMAGE", "uploaded image was empty"),
        )
    }
}

fun contentTypeFromName(name: String): String? = when (name.substringAfterLast('.', "").lowercase()) {
    "jpg", "jpeg" -> "image/jpeg"
    "png" -> "image/png"
    "webp" -> "image/webp"
    "gif" -> "image/gif"
    else -> null
}

// --- Filesystem helpers -------------------------------------------------------

private const val STREAM_BUFFER_BYTES = 64 * 1024

// In-progress uploads are streamed to a "upload-<random>.tmp" file and only renamed to their
// final name once fully received. The glob matches the createTempFile() prefix + suffix below.
private const val TEMP_UPLOAD_PREFIX = "upload-"
private const val TEMP_UPLOAD_SUFFIX = ".tmp"
private const val TEMP_UPLOAD_GLOB = "$TEMP_UPLOAD_PREFIX*$TEMP_UPLOAD_SUFFIX"

private sealed interface StreamOutcome {
    data class Ok(val file: Path, val size: Long) : StreamOutcome
    data object Empty : StreamOutcome
    data object TooLarge : StreamOutcome
}

/**
 * Stream this file part to a temp file in the upload dir, enforcing [ImageUploadConfig.maxBytes]
 * as the bytes arrive. The whole body is never held in the heap: as soon as the running total
 * would exceed the limit we stop, drop the partial temp file and report [StreamOutcome.TooLarge]
 * instead of buffering everything first. An empty part is reported as [StreamOutcome.Empty].
 */
private suspend fun PartData.FileItem.streamToTempFile(config: ImageUploadConfig): StreamOutcome {
    Files.createDirectories(config.uploadDir)
    val temp = Files.createTempFile(config.uploadDir, TEMP_UPLOAD_PREFIX, TEMP_UPLOAD_SUFFIX)
    val channel = provider()
    var total = 0L
    var tooLarge = false
    try {
        Files.newOutputStream(temp).use { out ->
            val buffer = ByteArray(STREAM_BUFFER_BYTES)
            while (true) {
                val read = channel.readAvailable(buffer, 0, buffer.size)
                if (read < 0) break
                if (total + read > config.maxBytes) {
                    tooLarge = true
                    break
                }
                out.write(buffer, 0, read)
                total += read
            }
        }
    } catch (e: Throwable) {
        Files.deleteIfExists(temp)
        throw e
    }
    return when {
        tooLarge -> { Files.deleteIfExists(temp); StreamOutcome.TooLarge }
        total == 0L -> { Files.deleteIfExists(temp); StreamOutcome.Empty }
        else -> StreamOutcome.Ok(temp, total)
    }
}

/**
 * Delete orphaned upload temp files left behind when a stream was interrupted before its rename
 * (process killed mid-upload, or the engine threw between streaming and [finalizeImageFile]).
 * Meant to run once at startup before any request is served, so it can't race a live upload.
 * Returns the number of files removed. Never throws — a failed sweep must not block startup.
 */
fun sweepStaleImageUploads(config: ImageUploadConfig): Int {
    if (!Files.isDirectory(config.uploadDir)) return 0
    return runCatching {
        var swept = 0
        Files.newDirectoryStream(config.uploadDir, TEMP_UPLOAD_GLOB).use { stream ->
            for (path in stream) {
                if (runCatching { Files.deleteIfExists(path) }.getOrDefault(false)) swept++
            }
        }
        swept
    }.getOrDefault(0)
}

// Promote a fully-streamed temp file to its final stored name (same dir, so a plain move is atomic).
fun finalizeImageFile(config: ImageUploadConfig, tempFile: Path, filename: String) {
    try {
        Files.move(tempFile, config.uploadDir.resolve(filename))
    } catch (e: Throwable) {
        Files.deleteIfExists(tempFile)
        throw e
    }
}

fun deleteImageFile(config: ImageUploadConfig, filename: String) {
    runCatching { Files.deleteIfExists(config.uploadDir.resolve(filename)) }
}
