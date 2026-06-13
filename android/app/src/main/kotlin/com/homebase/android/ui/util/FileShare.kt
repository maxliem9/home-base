package com.homebase.android.ui.util

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
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        // Keep the export cache from growing: a prior share has handed off by the time
        // the user triggers another, so stale files are safe to drop.
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, filename)
        file.writeBytes(bytes)

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
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
