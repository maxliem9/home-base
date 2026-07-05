package com.homebase.android.ui.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.text.Normalizer

/**
 * Writes exported bytes to a private cache file and opens the system share sheet via
 * [FileProvider]. The cache dir + provider authority (`<applicationId>.fileprovider`)
 * are declared in AndroidManifest/res/xml/file_paths.xml.
 */
object FileShare {

    fun share(context: Context, filename: String, mimeType: String, bytes: ByteArray, chooserTitle: String = "Teilen") {
        val uri = cacheUri(context, filename, bytes)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(send, chooserTitle).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /**
     * Opens the system share sheet for a plain-text payload (no file) — used for the Familienkalender
     * iCal subscription URL (#488). The token rides in the URL's query string, so this shares a
     * link, not an attachment; ACTION_SEND with text/plain lets the user drop it into any app.
     */
    fun shareText(context: Context, text: String, chooserTitle: String = "Teilen") {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(
            Intent.createChooser(send, chooserTitle).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /**
     * Opens downloaded bytes in the system viewer (ACTION_VIEW via the chooser) — used for note file
     * attachments (#437): download → cache → hand off to whatever app handles the MIME type. Returns
     * false (no exception) if no app can open the type, so the caller can surface a message.
     */
    fun open(context: Context, filename: String, mimeType: String, bytes: ByteArray, chooserTitle: String = "Öffnen mit"): Boolean {
        val uri = cacheUri(context, filename, bytes)
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        // Wrap in a chooser so the file always has a target even with several capable apps. We do NOT
        // pre-check with resolveActivity: under targetSdk 30+ package-visibility filtering (no <queries>
        // element) it returns null even when a capable app is installed → spurious "no app found".
        // Instead just launch and treat the (rare) ActivityNotFoundException as the real "nothing can
        // open this" signal, so the caller can surface a message.
        return try {
            context.startActivity(Intent.createChooser(view, chooserTitle).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    /** Writes [bytes] to the shared `exports` cache file and returns its FileProvider content URI. */
    private fun cacheUri(context: Context, filename: String, bytes: ByteArray) = run {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        // Keep the cache from growing: a prior share/open has handed off by the time the user
        // triggers another, so stale files are safe to drop.
        dir.listFiles()?.forEach { it.delete() }
        // Strip any path components from the (backend-stored, unsanitized) original name so a crafted
        // "../…" can't resolve outside exports/ (FileProvider would otherwise throw). File(name).name
        // keeps just the last path segment.
        val file = File(dir, File(filename).name.ifBlank { "datei" })
        file.writeBytes(bytes)
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    /**
     * ASCII slug for an export filename, mirroring the backend's `recipeSlug`: German
     * umlauts expand (ä→ae …), remaining accents are stripped, the rest collapses to hyphens.
     */
    fun slug(title: String): String {
        val expanded = title.lowercase()
            .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss")
        val noAccents = Normalizer.normalize(expanded, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        val slug = buildString {
            for (c in noAccents) append(if (c in 'a'..'z' || c in '0'..'9') c else '-')
        }.replace(Regex("-+"), "-").trim('-')
        return slug.ifBlank { "rezept" }
    }
}
