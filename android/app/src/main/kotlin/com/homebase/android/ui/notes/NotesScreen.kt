@file:OptIn(ExperimentalLayoutApi::class)

package com.homebase.android.ui.notes

import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.homebase.android.data.model.NoteImageDto
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.homebase.android.data.model.NoteDto
import com.homebase.android.ui.components.HbAppBar
import com.homebase.android.ui.components.HbAvatar
import com.homebase.android.ui.components.HbBottomSheet
import com.homebase.android.ui.components.HbButton
import com.homebase.android.ui.components.HbButtonSize
import com.homebase.android.ui.components.HbButtonVariant
import com.homebase.android.ui.components.HbDotSep
import com.homebase.android.ui.components.HbEmpty
import com.homebase.android.ui.components.HbFab
import com.homebase.android.ui.components.HbField
import com.homebase.android.ui.components.HbIcon
import com.homebase.android.ui.components.HbIconButton
import com.homebase.android.ui.components.HbIcons
import com.homebase.android.ui.components.HbPill
import com.homebase.android.ui.components.HbRadius
import com.homebase.android.ui.components.HbScreenScaffold
import com.homebase.android.ui.components.HbSegmented
import com.homebase.android.ui.components.HbTagChip
import com.homebase.android.ui.components.HbTextField
import com.homebase.android.ui.components.displayName
import com.homebase.android.ui.theme.Hb
import com.homebase.android.ui.theme.HbType
import com.homebase.android.ui.util.Format

// ---------------------------------------------------------------------------
// Editor target (create vs. edit) — held in local UI state.
// ---------------------------------------------------------------------------

private sealed interface Editor {
    data object Create : Editor
    data class Edit(val note: NoteDto) : Editor
}

@Composable
@Suppress("UNUSED_PARAMETER")
fun NotesScreen(viewModel: NotesViewModel, currentUser: String?, onOpenDrawer: () -> Unit) {
    // currentUser is part of the shared screen signature; notes are authored server-side.
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // Surface upload/network errors (e.g. "image too large") as a transient toast, then clear
    // so it doesn't re-fire on recomposition.
    val context = LocalContext.current
    LaunchedEffect(state.error) {
        state.error?.let { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            viewModel.clearError()
        }
    }

    var selectedTag by remember { mutableStateOf<String?>(null) }
    // null = all folders; "" = the "no folder" bucket; otherwise a specific folder name (mirrors web)
    var selectedFolder by remember { mutableStateOf<String?>(null) }
    var selectedNoteId by remember { mutableStateOf<String?>(null) }
    var editor by remember { mutableStateOf<Editor?>(null) }

    // Keep the open detail in sync with WS/list updates; close it if the note vanished.
    val openNote = selectedNoteId?.let { id -> state.notes.firstOrNull { it.id == id } }
    if (selectedNoteId != null && openNote == null) {
        selectedNoteId = null
    }

    if (openNote != null) {
        NoteDetail(
            note = openNote,
            onBack = { selectedNoteId = null },
            onEdit = { editor = Editor.Edit(openNote) },
            imageUrl = viewModel::imageUrl,
            onAddImage = { bytes, filename, contentType ->
                viewModel.uploadImage(openNote.id, bytes, filename, contentType)
            },
            onRemoveImage = { imageId -> viewModel.removeImage(openNote.id, imageId) },
        )
    } else {
        NoteList(
            notes = state.notes,
            selectedTag = selectedTag,
            onSelectTag = { selectedTag = it },
            selectedFolder = selectedFolder,
            onSelectFolder = { selectedFolder = it },
            onOpenNote = { selectedNoteId = it.id },
            onCreate = { editor = Editor.Create },
            onOpenDrawer = onOpenDrawer,
        )
    }

    // Folders for the editor's quick-pick are derived client-side from the loaded notes, like tags.
    val allFolders = remember(state.notes) {
        state.notes.mapNotNull { it.folder?.takeIf { f -> f.isNotBlank() } }
            .distinct()
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it })
    }

    when (val e = editor) {
        null -> {}
        is Editor.Create -> NoteEditorSheet(
            note = null,
            knownFolders = allFolders,
            onDismiss = { editor = null },
            onSave = { title, content, tags, folder, visibility ->
                viewModel.saveNote(null, title, content, tags, folder, visibility)
                editor = null
            },
            onDelete = null,
        )
        is Editor.Edit -> NoteEditorSheet(
            note = e.note,
            knownFolders = allFolders,
            onDismiss = { editor = null },
            onSave = { title, content, tags, folder, visibility ->
                viewModel.saveNote(e.note.id, title, content, tags, folder, visibility)
                editor = null
            },
            onDelete = {
                viewModel.deleteNote(e.note.id)
                editor = null
                selectedNoteId = null
            },
        )
    }
}

// ---------------------------------------------------------------------------
// List view
// ---------------------------------------------------------------------------

@Composable
private fun NoteList(
    notes: List<NoteDto>,
    selectedTag: String?,
    onSelectTag: (String?) -> Unit,
    selectedFolder: String?,
    onSelectFolder: (String?) -> Unit,
    onOpenNote: (NoteDto) -> Unit,
    onCreate: () -> Unit,
    onOpenDrawer: () -> Unit,
) {
    val allTags = remember(notes) { notes.flatMap { it.tags }.distinct() }
    // Folders are derived client-side from the loaded notes, like tags — there is no separate
    // folder entity. Blank/absent folders are not their own named folder (mirrors web).
    val allFolders = remember(notes) {
        notes.mapNotNull { it.folder?.takeIf { f -> f.isNotBlank() } }
            .distinct()
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it })
    }
    val shown = remember(notes, selectedTag, selectedFolder) {
        notes.filter { note ->
            if (selectedTag != null && selectedTag !in note.tags) return@filter false
            if (selectedFolder != null) {
                // "" selects notes without a folder; otherwise an exact folder match
                if (selectedFolder == "") {
                    if (!note.folder.isNullOrBlank()) return@filter false
                } else if (note.folder != selectedFolder) {
                    return@filter false
                }
            }
            true
        }
    }

    Box(Modifier.fillMaxSize()) {
        HbScreenScaffold(
            appBar = {
                HbAppBar(
                    eyebrow = "Notizen",
                    title = "Notizen",
                    onLeft = onOpenDrawer,
                    actions = { HbIconButton(HbIcons.search, {}) },
                )
            },
            fab = { HbFab(onClick = onCreate, label = "Notiz") },
        ) {
            // Folder-filter row — only when at least one note has a folder. Full-bleed,
            // horizontally scrollable, with an "all" head and a trailing "no folder" bucket.
            if (allFolders.isNotEmpty()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Spacer(Modifier.width(18.dp))
                    HbTagChip(
                        text = "Alle Ordner",
                        active = selectedFolder == null,
                        onClick = { onSelectFolder(null) },
                    )
                    allFolders.forEach { folder ->
                        FolderChip(
                            text = folder,
                            active = selectedFolder == folder,
                            onClick = { onSelectFolder(folder) },
                        )
                    }
                    HbTagChip(
                        text = "Ohne Ordner",
                        active = selectedFolder == "",
                        onClick = { onSelectFolder("") },
                    )
                    Spacer(Modifier.width(18.dp))
                }
                Spacer(Modifier.size(10.dp))
            }

            // Tag-filter row — full-bleed, horizontally scrollable, 18dp edge spacers.
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Spacer(Modifier.width(18.dp))
                HbTagChip(
                    text = "Alle",
                    active = selectedTag == null,
                    onClick = { onSelectTag(null) },
                )
                allTags.forEach { tag ->
                    HbTagChip(
                        text = tag,
                        active = selectedTag == tag,
                        onClick = { onSelectTag(tag) },
                    )
                }
                Spacer(Modifier.width(18.dp))
            }

            Spacer(Modifier.size(16.dp))

            when {
                notes.isEmpty() -> HbEmpty(
                    HbIcons.note,
                    "Noch keine Notizen",
                    "Tippe auf „Notiz“, um anzufangen.",
                )
                shown.isEmpty() -> HbEmpty(
                    HbIcons.search,
                    "Keine Treffer",
                    "Für diese Auswahl gibt es keine Notizen.",
                )
                else -> Column(
                    Modifier.padding(horizontal = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    shown.forEach { note ->
                        NoteCard(note = note, onClick = { onOpenNote(note) })
                    }
                }
            }
        }
    }
}

@Composable
private fun NoteCard(note: NoteDto, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .shadow(1.dp, HbRadius, clip = false, ambientColor = Hb.ink, spotColor = Hb.ink)
            .clip(HbRadius)
            .background(Hb.surface)
            .border(1.dp, Hb.lineSoft, HbRadius)
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 15.dp),
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (note.visibility == "PRIVATE") {
                    HbIcon(HbIcons.lock, size = 14.dp, tint = Hb.ink3)
                }
                Text(
                    note.title,
                    style = HbType.rowTitle.copy(fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold),
                    color = Hb.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }

            val preview = plainPreview(note.content)
            if (preview.isNotEmpty()) {
                Text(
                    preview,
                    style = HbType.meta.copy(fontSize = 13.5.sp, lineHeight = 19.5.sp),
                    color = Hb.ink3,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 5.dp),
                )
            }

            Row(
                Modifier.padding(top = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                HbAvatar(note.createdBy, size = 18.dp)
                Text(
                    Format.relativeTime(note.updatedAt),
                    style = HbType.small,
                    color = Hb.ink3,
                )
                if (!note.folder.isNullOrBlank()) {
                    HbDotSep()
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        HbIcon(HbIcons.folder, size = 13.dp, tint = Hb.ink3)
                        Text(note.folder, style = HbType.small, color = Hb.ink3)
                    }
                }
                if (note.tags.isNotEmpty()) {
                    HbDotSep()
                    note.tags.forEach { tag ->
                        HbTagChip(text = tag, static = true)
                    }
                }
            }
        }
    }
}

/** Filter chip with a leading folder glyph — HbTagChip has no icon slot, so wrap it manually. */
@Composable
private fun FolderChip(text: String, active: Boolean, onClick: () -> Unit) {
    val bg = if (active) Hb.accent else Hb.surface
    val fg = if (active) Hb.onAccent else Hb.ink2
    Row(
        Modifier
            .clip(HbPill)
            .background(bg, HbPill)
            .then(if (active) Modifier else Modifier.border(1.dp, Hb.line, HbPill))
            .clickable { onClick() }
            .padding(horizontal = 13.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        HbIcon(HbIcons.folder, size = 13.dp, tint = fg)
        Text(text, style = HbType.meta.copy(fontWeight = FontWeight.SemiBold), color = fg)
    }
}

// ---------------------------------------------------------------------------
// Detail view (full-screen page)
// ---------------------------------------------------------------------------

@Composable
private fun NoteDetail(
    note: NoteDto,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    imageUrl: (NoteImageDto) -> String,
    onAddImage: (bytes: ByteArray, filename: String, contentType: String) -> Unit,
    onRemoveImage: (imageId: String) -> Unit,
) {
    BackHandler(onBack = onBack)

    val context = LocalContext.current
    var lightbox by remember { mutableStateOf<String?>(null) }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            val resolver = context.contentResolver
            val type = resolver.getType(uri) ?: "image/jpeg"
            val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            } ?: "image"
            val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
            if (bytes != null) onAddImage(bytes, name, type)
        }
    }

    HbScreenScaffold(
        appBar = {
            HbAppBar(
                title = "Notiz",
                titleSm = true,
                bordered = true,
                leftIcon = HbIcons.chevronLeft,
                onLeft = onBack,
                actions = {
                    HbIconButton(HbIcons.edit, onEdit)
                    HbIconButton(HbIcons.more, {})
                },
            )
        },
    ) {
        Column(Modifier.padding(horizontal = 18.dp)) {
            Text(
                note.title,
                style = HbType.docTitle,
                color = Hb.ink,
                modifier = Modifier.padding(top = 8.dp),
            )

            // Meta row: visibility badge + author avatar + "Name · vor X"
            Row(
                Modifier.padding(top = 12.dp, bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (note.visibility == "PRIVATE") {
                    VisibilityBadge(HbIcons.lock, "Privat")
                } else {
                    VisibilityBadge(HbIcons.users, "Geteilt")
                }
                if (!note.folder.isNullOrBlank()) {
                    VisibilityBadge(HbIcons.folder, note.folder)
                }
                HbAvatar(note.createdBy, size = 18.dp)
                Text(
                    "${displayName(note.createdBy)} · ${Format.relativeTime(note.updatedAt)}",
                    style = HbType.small.copy(fontSize = 12.5.sp),
                    color = Hb.ink3,
                )
            }

            // Static tag chips
            if (note.tags.isNotEmpty()) {
                Row(
                    Modifier.padding(bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    note.tags.forEach { tag -> HbTagChip(text = tag, static = true) }
                }
            }

            // Rendered markdown body
            MarkdownText(note.content)

            NoteImagesSection(
                images = note.images,
                imageUrl = imageUrl,
                onAdd = { imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                onRemove = onRemoveImage,
                onOpen = { lightbox = it },
            )

            Spacer(Modifier.size(8.dp))
        }
    }

    lightbox?.let { url -> ImageLightbox(url = url, onDismiss = { lightbox = null }) }
}

/** Small badge with a leading glyph (HbBadge has no icon slot). */
@Composable
private fun VisibilityBadge(icon: ImageVector, label: String) {
    Row(
        Modifier
            .clip(HbPill)
            .background(Hb.accentSoft, HbPill)
            .padding(horizontal = 9.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        HbIcon(icon, size = 13.dp, tint = Hb.accentInk)
        Text(label, style = HbType.small.copy(fontWeight = FontWeight.SemiBold), color = Hb.accentInk)
    }
}

// ---------------------------------------------------------------------------
// Image attachments
// ---------------------------------------------------------------------------

@Composable
private fun NoteImagesSection(
    images: List<NoteImageDto>,
    imageUrl: (NoteImageDto) -> String,
    onAdd: () -> Unit,
    onRemove: (imageId: String) -> Unit,
    onOpen: (url: String) -> Unit,
) {
    Column(Modifier.padding(top = 20.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                if (images.isEmpty()) "Bilder" else "Bilder (${images.size})",
                style = HbType.meta.copy(fontWeight = FontWeight.SemiBold),
                color = Hb.ink2,
            )
            HbButton(
                "Bild hinzufügen",
                onClick = onAdd,
                variant = HbButtonVariant.Secondary,
                size = HbButtonSize.Sm,
                icon = HbIcons.plus,
            )
        }
        if (images.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                images.forEach { img ->
                    Box(
                        Modifier
                            .size(104.dp)
                            .clip(HbRadius)
                            .background(Hb.surface2)
                            .border(1.dp, Hb.lineSoft, HbRadius)
                            .clickable { onOpen(imageUrl(img)) },
                    ) {
                        AsyncImage(
                            model = imageUrl(img),
                            contentDescription = img.originalName,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                        Box(
                            Modifier
                                .align(Alignment.TopEnd)
                                .padding(4.dp)
                                .size(26.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.5f))
                                .clickable { onRemove(img.id) },
                            contentAlignment = Alignment.Center,
                        ) {
                            HbIcon(HbIcons.x, size = 14.dp, tint = Color.White)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ImageLightbox(url: String, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.85f))
                .clickable { onDismiss() },
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Editor bottom sheet (create or edit)
// ---------------------------------------------------------------------------

@Composable
private fun NoteEditorSheet(
    note: NoteDto?,
    knownFolders: List<String>,
    onDismiss: () -> Unit,
    onSave: (title: String, content: String, tags: List<String>, folder: String, visibility: String) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var title by remember { mutableStateOf(note?.title ?: "") }
    var content by remember { mutableStateOf(note?.content ?: "") }
    var tagsText by remember { mutableStateOf(note?.tags?.joinToString(", ") ?: "") }
    var folderText by remember { mutableStateOf(note?.folder ?: "") }
    var segIndex by remember { mutableStateOf(if (note?.visibility == "PRIVATE") 1 else 0) }

    fun submit() {
        val tags = tagsText.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val visibility = if (segIndex == 0) "SHARED" else "PRIVATE"
        onSave(title.trim(), content, tags, folderText, visibility)
    }

    HbBottomSheet(
        onDismiss = onDismiss,
        title = if (note == null) "Neue Notiz" else "Notiz bearbeiten",
        full = true,
        footer = {
            if (onDelete != null) {
                HbButton(
                    "",
                    onClick = onDelete,
                    variant = HbButtonVariant.Danger,
                    icon = HbIcons.trash,
                )
            }
            Spacer(Modifier.weight(1f))
            HbButton("Abbrechen", onClick = onDismiss, variant = HbButtonVariant.Secondary)
            HbButton("Speichern", onClick = { submit() }, enabled = title.isNotBlank())
        },
    ) {
        HbField("Titel") {
            HbTextField(value = title, onValueChange = { title = it }, placeholder = "Titel")
        }
        HbField("Inhalt") {
            HbTextField(
                value = content,
                onValueChange = { content = it },
                placeholder = "Schreib etwas …",
                singleLine = false,
                minLines = 6,
            )
        }
        HbField("Tags") {
            HbTextField(
                value = tagsText,
                onValueChange = { tagsText = it },
                placeholder = "urlaub, zuhause",
            )
        }
        HbField("Ordner") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                HbTextField(
                    value = folderText,
                    onValueChange = { folderText = it },
                    placeholder = "Ordner (optional) …",
                )
                // Quick-pick from folders already in use (Compose equivalent of the web datalist):
                // tap to fill, tap the active one again to clear.
                if (knownFolders.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        knownFolders.forEach { folder ->
                            val active = folderText.trim() == folder
                            FolderChip(
                                text = folder,
                                active = active,
                                onClick = { folderText = if (active) "" else folder },
                            )
                        }
                    }
                }
            }
        }
        HbField("Sichtbarkeit") {
            HbSegmented(
                options = listOf("Geteilt", "Privat"),
                selectedIndex = segIndex,
                onSelect = { segIndex = it },
                leadingIcons = listOf(HbIcons.users, HbIcons.lock),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Markdown rendering (mirrors the .hb-md* spec)
// ---------------------------------------------------------------------------

private val MdHeading2 = HbType.body.copy(fontSize = 18.sp, fontWeight = FontWeight.SemiBold, lineHeight = 24.sp)
private val MdHeading3 = HbType.body.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, lineHeight = 22.sp)
private val MdBodyStyle = HbType.body.copy(fontSize = 15.sp, lineHeight = 24.sp)

@Composable
private fun MarkdownText(md: String) {
    val blocks = remember(md) { parseMarkdown(md) }
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Heading2 -> Text(inlineSpans(block.text), style = MdHeading2, color = Hb.ink)
                is MdBlock.Heading3 -> Text(inlineSpans(block.text), style = MdHeading3, color = Hb.ink2)
                is MdBlock.Paragraph -> Text(inlineSpans(block.text), style = MdBodyStyle, color = Hb.ink)
                is MdBlock.Quote -> Row(
                    Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min)
                        .clip(RoundedCornerShape(topEnd = 8.dp, bottomEnd = 8.dp))
                        .background(Hb.surface2),
                ) {
                    Box(Modifier.width(3.dp).fillMaxHeight().background(Hb.accent))
                    Text(
                        inlineSpans(block.text),
                        style = MdBodyStyle,
                        color = Hb.ink2,
                        modifier = Modifier.padding(horizontal = 15.dp, vertical = 9.dp),
                    )
                }
                is MdBlock.BulletList -> Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    block.items.forEach { item ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("•", style = MdBodyStyle, color = Hb.ink2)
                            Text(inlineSpans(item), style = MdBodyStyle, color = Hb.ink, modifier = Modifier.weight(1f))
                        }
                    }
                }
                is MdBlock.NumberedList -> Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    block.items.forEachIndexed { i, item ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("${i + 1}.", style = MdBodyStyle, color = Hb.ink2)
                            Text(inlineSpans(item), style = MdBodyStyle, color = Hb.ink, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

private sealed interface MdBlock {
    data class Heading2(val text: String) : MdBlock
    data class Heading3(val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class BulletList(val items: List<String>) : MdBlock
    data class NumberedList(val items: List<String>) : MdBlock
}

/** Line-by-line markdown block parser. Robust to plain text (→ paragraphs). */
private fun parseMarkdown(md: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val lines = md.replace("\r\n", "\n").replace("\r", "\n").split("\n")

    val paragraph = StringBuilder()
    var bullets: MutableList<String>? = null
    var numbers: MutableList<String>? = null

    fun flushParagraph() {
        val text = paragraph.toString().trim()
        if (text.isNotEmpty()) blocks.add(MdBlock.Paragraph(text))
        paragraph.setLength(0)
    }
    fun flushBullets() {
        bullets?.let { if (it.isNotEmpty()) blocks.add(MdBlock.BulletList(it)) }
        bullets = null
    }
    fun flushNumbers() {
        numbers?.let { if (it.isNotEmpty()) blocks.add(MdBlock.NumberedList(it)) }
        numbers = null
    }
    fun flushAll() { flushParagraph(); flushBullets(); flushNumbers() }

    for (raw in lines) {
        val trimmed = raw.trim()
        when {
            trimmed.isEmpty() -> flushAll()

            trimmed.startsWith("### ") -> {
                flushAll(); blocks.add(MdBlock.Heading3(trimmed.removePrefix("### ").trim()))
            }
            trimmed.startsWith("## ") -> {
                flushAll(); blocks.add(MdBlock.Heading2(trimmed.removePrefix("## ").trim()))
            }
            trimmed.startsWith("# ") -> {
                flushAll(); blocks.add(MdBlock.Heading2(trimmed.removePrefix("# ").trim()))
            }
            trimmed.startsWith("> ") -> {
                flushParagraph(); flushBullets(); flushNumbers()
                blocks.add(MdBlock.Quote(trimmed.removePrefix("> ").trim()))
            }
            trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                flushParagraph(); flushNumbers()
                val list = bullets ?: mutableListOf<String>().also { bullets = it }
                list.add(trimmed.substring(2).trim())
            }
            isOrderedItem(trimmed) -> {
                flushParagraph(); flushBullets()
                val list = numbers ?: mutableListOf<String>().also { numbers = it }
                list.add(trimmed.substringAfter(". ").trim())
            }
            else -> {
                flushBullets(); flushNumbers()
                if (paragraph.isNotEmpty()) paragraph.append(' ')
                paragraph.append(trimmed)
            }
        }
    }
    flushAll()
    return blocks
}

/** True for lines like "1. text" / "12. text". */
private fun isOrderedItem(line: String): Boolean {
    val dot = line.indexOf(". ")
    if (dot <= 0) return false
    return line.substring(0, dot).all { it.isDigit() }
}

private val MonoFamily = FontFamily.Monospace

/** Build an [AnnotatedString] with **bold**, *italic*, and `code` inline spans. */
private fun inlineSpans(text: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    val n = text.length
    while (i < n) {
        val c = text[i]
        when {
            // inline code `...`
            c == '`' -> {
                val end = text.indexOf('`', i + 1)
                if (end > i) {
                    withStyle(
                        SpanStyle(
                            fontFamily = MonoFamily,
                            fontSize = 13.5.sp,
                            background = Hb.surface2,
                            color = Hb.ink,
                        ),
                    ) { append(text.substring(i + 1, end)) }
                    i = end + 1
                } else {
                    append(c); i++
                }
            }
            // bold **...**
            c == '*' && i + 1 < n && text[i + 1] == '*' -> {
                val end = text.indexOf("**", i + 2)
                if (end > i) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(text.substring(i + 2, end)) }
                    i = end + 2
                } else {
                    append(c); i++
                }
            }
            // italic *...*
            c == '*' -> {
                val end = text.indexOf('*', i + 1)
                if (end > i) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(text.substring(i + 1, end)) }
                    i = end + 1
                } else {
                    append(c); i++
                }
            }
            else -> { append(c); i++ }
        }
    }
}

/** Strip markdown syntax to a plain-ish single string for card previews. */
private fun plainPreview(md: String): String {
    val sb = StringBuilder()
    md.replace("\r\n", "\n").replace("\r", "\n").split("\n").forEach { raw ->
        var line = raw.trim()
        if (line.isEmpty()) return@forEach
        line = line.trimStart('#', '>', '-', '*', ' ')
        line = stripInline(line)
        if (line.isNotEmpty()) {
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(line)
        }
    }
    return sb.toString().trim()
}

/** Remove inline markdown markers (**, *, `) from a one-line string. */
private fun stripInline(text: String): String =
    text.replace("**", "").replace("`", "").replace("*", "")
