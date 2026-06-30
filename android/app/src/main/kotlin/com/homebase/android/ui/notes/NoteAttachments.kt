package com.homebase.android.ui.notes

/**
 * Pure helpers for the note file-attachment list (#437). Compose-free so they're unit-testable and
 * mirror the web (`NotesView.tsx` `formatBytes` + the file `accept` whitelist).
 */
object NoteAttachments {

    /**
     * MIME types the system document picker should offer for note attachments. Mirrors the backend
     * whitelist (`ALLOWED_ATTACHMENT_TYPES`, #431): PDF, txt, csv, md, rtf, doc(x), xls(x), ppt(x),
     * odt/ods/odp, zip. Passed to `OpenDocument`/`GetContent` as the type filter; the backend
     * re-validates, so this is only a convenience filter (and not all pickers honour it strictly).
     */
    val ACCEPT_MIME_TYPES: Array<String> = arrayOf(
        "application/pdf",
        "text/plain",
        "text/csv",
        "text/markdown",
        "application/rtf",
        "text/rtf",
        "application/msword",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.ms-excel",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/vnd.ms-powerpoint",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "application/vnd.oasis.opendocument.text",
        "application/vnd.oasis.opendocument.spreadsheet",
        "application/vnd.oasis.opendocument.presentation",
        "application/zip",
    )

    /**
     * Human-readable file size for the attachment chip (B / KB / MB). Byte-for-byte parity with the
     * web `formatBytes`: < 1 KiB → bytes, < 1 MiB → rounded KB, otherwise MB with one decimal.
     */
    fun formatFileSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${Math.round(bytes / 1024.0)} KB"
        else -> "${"%.1f".format(java.util.Locale.US, bytes / (1024.0 * 1024.0))} MB"
    }

    /**
     * Whether a content type renders as an image. Attachments are never images (the backend stores
     * pictures in the separate `images[]` gallery), but this guards the chip path defensively and is
     * the unit-tested classification the issue calls for.
     */
    fun isImageContentType(contentType: String?): Boolean =
        contentType?.trim()?.lowercase()?.startsWith("image/") == true
}
