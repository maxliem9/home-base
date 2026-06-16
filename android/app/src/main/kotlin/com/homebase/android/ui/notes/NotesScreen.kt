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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.homebase.android.R
import com.homebase.android.data.model.NoteImageDto
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
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
import com.homebase.android.ui.components.HbRadiusSm
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
            // resolve an inline markdown image ref to a loadable URL: `image:<id>` →
            // this note's authed attachment; external http(s) as-is; anything else → null (alt text)
            resolveContentImageUrl = { src ->
                when {
                    src.startsWith("image:") -> viewModel.imageUrl(openNote.id, src.removePrefix("image:"))
                    src.startsWith("http://", ignoreCase = true) ||
                        src.startsWith("https://", ignoreCase = true) -> src
                    else -> null
                }
            },
            onAddImages = { items -> viewModel.uploadImages(openNote.id, items) },
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
            onRefresh = { viewModel.refresh() },
        )
    }

    // Folders for the editor's quick-pick are derived client-side from the loaded notes, like tags.
    val allFolders = remember(state.notes) {
        state.notes.mapNotNull { it.folder?.takeIf { f -> f.isNotBlank() } }
            .distinct()
            .sortedWith(compareBy(java.text.Collator.getInstance(java.util.Locale.GERMAN)) { it }) // deutsche Kollation, Parität zu web localeCompare('de')
    }

    when (val e = editor) {
        null -> {}
        is Editor.Create -> NoteEditorSheet(
            note = null,
            knownFolders = allFolders,
            imageUrl = viewModel::imageUrl,
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
            imageUrl = viewModel::imageUrl,
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
    onRefresh: suspend () -> Unit,
) {
    val allTags = remember(notes) { notes.flatMap { it.tags }.distinct() }
    // Folders are derived client-side from the loaded notes, like tags — there is no separate
    // folder entity. Blank/absent folders are not their own named folder (mirrors web).
    val allFolders = remember(notes) {
        notes.mapNotNull { it.folder?.takeIf { f -> f.isNotBlank() } }
            .distinct()
            .sortedWith(compareBy(java.text.Collator.getInstance(java.util.Locale.GERMAN)) { it }) // deutsche Kollation, Parität zu web localeCompare('de')
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
                    eyebrow = stringResource(R.string.notes_eyebrow),
                    title = stringResource(R.string.notes_title),
                    onLeft = onOpenDrawer,
                    actions = { HbIconButton(HbIcons.search, {}) },
                )
            },
            fab = { HbFab(onClick = onCreate, label = stringResource(R.string.notes_fab)) },
            onRefresh = onRefresh,
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
                        text = stringResource(R.string.notes_all_folders),
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
                        text = stringResource(R.string.notes_no_folder),
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
                    text = stringResource(R.string.notes_all_tags),
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
                    stringResource(R.string.notes_empty_title),
                    stringResource(R.string.notes_empty_hint),
                )
                shown.isEmpty() -> HbEmpty(
                    HbIcons.search,
                    stringResource(R.string.notes_no_results_title),
                    stringResource(R.string.notes_no_results_hint),
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
    resolveContentImageUrl: (String) -> String?,
    onAddImages: (items: List<NoteImageUpload>) -> Unit,
    onRemoveImage: (imageId: String) -> Unit,
) {
    BackHandler(onBack = onBack)

    val context = LocalContext.current
    var lightbox by remember { mutableStateOf<String?>(null) }

    // Multi-select photo picker (#266): read every chosen image and hand the batch up to the
    // ViewModel, which uploads them one after another (each its own request, correct sort_order).
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (uris.isNotEmpty()) {
            val resolver = context.contentResolver
            val items = uris.mapNotNull { uri ->
                val type = resolver.getType(uri) ?: "image/jpeg"
                val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                } ?: "image"
                resolver.openInputStream(uri)?.use { it.readBytes() }
                    ?.let { bytes -> NoteImageUpload(bytes, name, type) }
            }
            if (items.isNotEmpty()) onAddImages(items)
        }
    }

    HbScreenScaffold(
        appBar = {
            HbAppBar(
                title = stringResource(R.string.notes_detail_title),
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
                    VisibilityBadge(HbIcons.lock, stringResource(R.string.notes_private))
                } else {
                    VisibilityBadge(HbIcons.users, stringResource(R.string.notes_shared))
                }
                if (!note.folder.isNullOrBlank()) {
                    VisibilityBadge(HbIcons.folder, note.folder)
                }
                HbAvatar(note.createdBy, size = 18.dp)
                Text(
                    stringResource(R.string.notes_meta, displayName(note.createdBy), Format.relativeTime(note.updatedAt)),
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

            // Rendered markdown body (inline images + links)
            MarkdownText(note.content, resolveImageUrl = resolveContentImageUrl)

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
                if (images.isEmpty()) stringResource(R.string.notes_images) else stringResource(R.string.notes_images_count, images.size),
                style = HbType.meta.copy(fontWeight = FontWeight.SemiBold),
                color = Hb.ink2,
            )
            HbButton(
                stringResource(R.string.notes_add_image),
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
    imageUrl: (NoteImageDto) -> String,
    onDismiss: () -> Unit,
    onSave: (title: String, content: String, tags: List<String>, folder: String, visibility: String) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var title by remember { mutableStateOf(note?.title ?: "") }
    // TextFieldValue (not String) so an image insert lands at the caret / replaces the selection
    var content by remember { mutableStateOf(TextFieldValue(note?.content ?: "")) }
    var tagsText by remember { mutableStateOf(note?.tags?.joinToString(", ") ?: "") }
    var folderText by remember { mutableStateOf(note?.folder ?: "") }
    var segIndex by remember { mutableStateOf(if (note?.visibility == "PRIVATE") 1 else 0) }

    // Insert an attachment reference at the cursor; MarkdownText resolves image:<id> on read.
    fun insertImage(img: NoteImageDto) {
        val snippet = "![${img.originalName}](image:${img.id})"
        val t = content.text
        val start = content.selection.start.coerceIn(0, t.length)
        val end = content.selection.end.coerceIn(start, t.length)
        val next = t.substring(0, start) + snippet + t.substring(end)
        content = content.copy(text = next, selection = TextRange(start + snippet.length))
    }

    fun submit() {
        val tags = tagsText.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val visibility = if (segIndex == 0) "SHARED" else "PRIVATE"
        onSave(title.trim(), content.text, tags, folderText, visibility)
    }

    HbBottomSheet(
        onDismiss = onDismiss,
        title = if (note == null) stringResource(R.string.notes_new_title) else stringResource(R.string.notes_edit_title),
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
            HbButton(stringResource(R.string.action_cancel), onClick = onDismiss, variant = HbButtonVariant.Secondary)
            HbButton(stringResource(R.string.action_save), onClick = { submit() }, enabled = title.isNotBlank())
        },
    ) {
        HbField(stringResource(R.string.notes_field_title)) {
            HbTextField(value = title, onValueChange = { title = it }, placeholder = stringResource(R.string.notes_title_placeholder))
        }
        HbField(stringResource(R.string.notes_field_content)) {
            HbTextField(
                value = content,
                onValueChange = { content = it },
                placeholder = stringResource(R.string.notes_content_placeholder),
                singleLine = false,
                minLines = 6,
            )
        }
        // Tap an existing attachment to drop its ![name](image:id) reference at the cursor.
        if (note != null && note.images.isNotEmpty()) {
            HbField(stringResource(R.string.notes_insert_image)) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    note.images.forEach { img ->
                        Box(
                            Modifier
                                .size(52.dp)
                                .clip(HbRadiusSm)
                                .background(Hb.surface2)
                                .border(1.dp, Hb.lineSoft, HbRadiusSm)
                                .clickable { insertImage(img) },
                        ) {
                            AsyncImage(
                                model = imageUrl(img),
                                contentDescription = img.originalName,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }
        }
        HbField(stringResource(R.string.notes_field_tags)) {
            HbTextField(
                value = tagsText,
                onValueChange = { tagsText = it },
                placeholder = stringResource(R.string.notes_tags_placeholder),
            )
        }
        HbField(stringResource(R.string.notes_field_folder)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                HbTextField(
                    value = folderText,
                    onValueChange = { folderText = it },
                    placeholder = stringResource(R.string.notes_folder_placeholder),
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
        HbField(stringResource(R.string.notes_field_visibility)) {
            HbSegmented(
                options = listOf(stringResource(R.string.notes_shared), stringResource(R.string.notes_private)),
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
private fun MarkdownText(md: String, resolveImageUrl: (String) -> String? = { null }) {
    val blocks = remember(md) { parseMarkdown(md) }
    // Theme tokens resolved here and threaded into the non-composable inlineSpans builder (#244).
    val inlineColors = MdInlineColors(codeBg = Hb.surface2, ink = Hb.ink, link = Hb.accent)
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Heading2 -> Text(inlineSpans(block.text, inlineColors), style = MdHeading2, color = Hb.ink)
                is MdBlock.Heading3 -> Text(inlineSpans(block.text, inlineColors), style = MdHeading3, color = Hb.ink2)
                is MdBlock.Paragraph -> Text(inlineSpans(block.text, inlineColors), style = MdBodyStyle, color = Hb.ink)
                is MdBlock.Image -> {
                    val url = resolveImageUrl(block.src)
                    if (url != null) {
                        AsyncImage(
                            model = url,
                            contentDescription = block.alt.ifBlank { null },
                            contentScale = ContentScale.FillWidth,
                            // reserve space + a surface tile while loading / on failure, so the body
                            // doesn't jump as images arrive and a failed load isn't an invisible gap
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 120.dp)
                                .clip(HbRadius)
                                .background(Hb.surface2),
                        )
                    } else if (block.alt.isNotBlank()) {
                        // unresolved / disallowed src → show the alt text, never a broken or unsafe image
                        Text(inlineSpans(block.alt, inlineColors), style = MdBodyStyle, color = Hb.ink)
                    }
                }
                is MdBlock.Quote -> Row(
                    Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min)
                        .clip(RoundedCornerShape(topEnd = 8.dp, bottomEnd = 8.dp))
                        .background(Hb.surface2),
                ) {
                    Box(Modifier.width(3.dp).fillMaxHeight().background(Hb.accent))
                    Text(
                        inlineSpans(block.text, inlineColors),
                        style = MdBodyStyle,
                        color = Hb.ink2,
                        modifier = Modifier.padding(horizontal = 15.dp, vertical = 9.dp),
                    )
                }
                is MdBlock.BulletList -> Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    block.items.forEach { item ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("•", style = MdBodyStyle, color = Hb.ink2)
                            Text(inlineSpans(item, inlineColors), style = MdBodyStyle, color = Hb.ink, modifier = Modifier.weight(1f))
                        }
                    }
                }
                is MdBlock.NumberedList -> Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    block.items.forEachIndexed { i, item ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("${i + 1}.", style = MdBodyStyle, color = Hb.ink2)
                            Text(inlineSpans(item, inlineColors), style = MdBodyStyle, color = Hb.ink, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

// MdBlock, parseMarkdown and the URL allowlist (isSafeLinkUrl) live in the Compose-free
// NotesMarkdown.kt so they can be unit-tested (see NotesMarkdownTest).

private val MonoFamily = FontFamily.Monospace

/**
 * Theme tokens the inline markdown spans need (#244). Resolved once in the composable
 * ([MarkdownText]) and threaded into [inlineSpans], which is plain (non-composable) code and so
 * can't read the `Hb.*` getters itself.
 */
private data class MdInlineColors(val codeBg: Color, val ink: Color, val link: Color)

/** Build an [AnnotatedString] with **bold**, *italic*, `code` and link inline spans. */
private fun inlineSpans(text: String, colors: MdInlineColors): AnnotatedString = buildAnnotatedString {
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
                            background = colors.codeBg,
                            color = colors.ink,
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
            // link [text](url) — '!' prefix means an image, which is handled as its own block
            c == '[' && (i == 0 || text[i - 1] != '!') -> {
                val close = text.indexOf(']', i + 1)
                val urlEnd = if (close > i && close + 1 < n && text[close + 1] == '(') {
                    text.indexOf(')', close + 2).takeIf { it > close + 1 }
                } else null
                if (urlEnd != null) {
                    val label = text.substring(i + 1, close)
                    val href = text.substring(close + 2, urlEnd).trim()
                    if (isSafeLinkUrl(href)) {
                        withLink(
                            LinkAnnotation.Url(
                                href,
                                TextLinkStyles(SpanStyle(color = colors.link, textDecoration = TextDecoration.Underline)),
                            ),
                        ) { append(label) }
                    } else {
                        append(label) // disallowed scheme → keep the words, drop the link
                    }
                    i = urlEnd + 1
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
