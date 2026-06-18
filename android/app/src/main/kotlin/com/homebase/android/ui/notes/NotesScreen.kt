@file:OptIn(ExperimentalLayoutApi::class)

package com.homebase.android.ui.notes

import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import kotlinx.coroutines.delay
import com.homebase.android.data.model.NoteDto
import com.homebase.android.ui.components.HbAppBar
import com.homebase.android.ui.components.HbAvatar
import com.homebase.android.ui.components.HbButton
import com.homebase.android.ui.components.HbButtonSize
import com.homebase.android.ui.components.HbButtonVariant
import com.homebase.android.ui.components.HbConfirmDialog
import com.homebase.android.ui.components.HbDotSep
import com.homebase.android.ui.components.bottomBorder
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

@Composable
@Suppress("UNUSED_PARAMETER")
fun NotesScreen(viewModel: NotesViewModel, currentUser: String?, onOpenDrawer: () -> Unit) {
    // currentUser is part of the shared screen signature; notes are authored server-side.
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val editor by viewModel.editorState.collectAsStateWithLifecycle()

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

    // Folders for the editor's quick-pick + the switcher are derived client-side from the loaded
    // notes, like tags.
    val allFolders = remember(state.notes) {
        state.notes.mapNotNull { it.folder?.takeIf { f -> f.isNotBlank() } }
            .distinct()
            .sortedWith(compareBy(java.text.Collator.getInstance(java.util.Locale.GERMAN)) { it }) // deutsche Kollation, Parität zu web localeCompare('de')
    }

    // If the note open in the editor was deleted elsewhere (a partner's delete via WS), close the
    // editor instead of leaving a dangling editor whose auto-save would 404. Only for a *saved* note
    // (id != null) that is genuinely gone — a brand-new note is legitimately absent from the list.
    val openNoteId = editor?.noteId
    val openNoteGone = openNoteId != null && state.notes.none { it.id == openNoteId }
    LaunchedEffect(openNoteId, openNoteGone) {
        if (openNoteGone) viewModel.abandonEditor()
    }

    // editorState != null ⇒ tap-to-edit (#310): the full-screen editor replaces the list, exactly
    // like the old read-only detail did. A new note opens with a null id; the editor's grouped
    // note-switcher (#313) lets the user jump to another note without leaving.
    val openEditor = editor
    if (openEditor != null) {
        // The note this editor is bound to (for the read-only preview's saved images), if it exists.
        val boundNote = openEditor.noteId?.let { id -> state.notes.firstOrNull { it.id == id } }
        NoteEditor(
            editor = openEditor,
            boundNote = boundNote,
            allNotes = state.notes,
            knownFolders = allFolders,
            imageUrl = viewModel::imageUrl,
            // resolve an inline markdown image ref to a loadable URL: `image:<id>` →
            // this note's authed attachment; external http(s) as-is; anything else → null (alt text)
            resolveContentImageUrl = { src ->
                val nid = openEditor.noteId
                when {
                    nid != null && src.startsWith("image:") -> viewModel.imageUrl(nid, src.removePrefix("image:"))
                    src.startsWith("http://", ignoreCase = true) ||
                        src.startsWith("https://", ignoreCase = true) -> src
                    else -> null
                }
            },
            onTitleChange = { viewModel.updateEditor(title = it) },
            onContentChange = { viewModel.updateEditor(content = it) },
            onTagsChange = { viewModel.updateEditor(tags = it) },
            onFolderChange = { viewModel.updateEditor(folder = it) },
            onVisibilityChange = { viewModel.updateEditor(visibility = it) },
            onBack = { viewModel.closeEditor() },
            onCommit = { viewModel.commitEditor() },
            onSwitchNote = { viewModel.switchEditorTo(it) },
            onAddImages = { items -> openEditor.noteId?.let { viewModel.uploadImages(it, items) } },
            onRemoveImage = { imageId -> openEditor.noteId?.let { viewModel.removeImage(it, imageId) } },
            onDelete = { viewModel.deleteEditorNote() },
        )
    } else {
        NoteList(
            notes = state.notes,
            selectedTag = selectedTag,
            onSelectTag = { selectedTag = it },
            selectedFolder = selectedFolder,
            onSelectFolder = { selectedFolder = it },
            onOpenNote = { viewModel.openEditor(it) },
            onCreate = { viewModel.openEditor(null) },
            onOpenDrawer = onOpenDrawer,
            onRefresh = { viewModel.refresh() },
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
                else -> {
                    // Folder-grouped sections (#311): a header (folder glyph + name + count) then
                    // that folder's notes, indented. Folders alphabetical (German Collator), the
                    // "Ohne Ordner" bucket always last; within a group newest-first by updatedAt.
                    val groups = remember(shown) { groupByFolder(shown) }
                    Column(
                        Modifier.padding(horizontal = 18.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        groups.forEach { group ->
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                FolderSectionHeader(
                                    label = group.folder ?: stringResource(R.string.notes_no_folder),
                                    count = group.notes.size,
                                )
                                Column(
                                    Modifier.padding(start = 12.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    group.notes.forEach { note ->
                                        NoteCard(note = note, onClick = { onOpenNote(note) })
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A folder section in the grouped list. [folder] = null is the "Ohne Ordner" bucket. */
private data class NoteFolderGroup(val folder: String?, val notes: List<NoteDto>)

/**
 * Group notes into folder sections for the list (#311): named folders first, alphabetical by the
 * German Collator (parity with web localeCompare('de')), the no-/blank-folder bucket last; each
 * group's notes newest-first by updatedAt (ISO-8601 strings sort lexicographically). Mirrors the
 * web grouping.
 */
private fun groupByFolder(notes: List<NoteDto>): List<NoteFolderGroup> {
    val collator = java.text.Collator.getInstance(java.util.Locale.GERMAN)
    val named = notes.filter { !it.folder.isNullOrBlank() }
        .groupBy { it.folder!! }
        .toSortedMap(collator)
        .map { (folder, items) -> NoteFolderGroup(folder, items.sortedByDescending { it.updatedAt }) }
    val loose = notes.filter { it.folder.isNullOrBlank() }
        .sortedByDescending { it.updatedAt }
    return if (loose.isEmpty()) named else named + NoteFolderGroup(null, loose)
}

/** Section header for a folder group: folder glyph + name + a muted count pill. */
@Composable
private fun FolderSectionHeader(label: String, count: Int) {
    Row(
        Modifier.fillMaxWidth().padding(top = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HbIcon(HbIcons.folder, size = 16.dp, tint = Hb.ink2)
        Text(
            label,
            style = HbType.rowTitle.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
            color = Hb.ink2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Box(
            Modifier
                .clip(HbPill)
                .background(Hb.surface3, HbPill)
                .heightIn(min = 18.dp)
                .padding(horizontal = 7.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(count.toString(), style = HbType.small.copy(fontWeight = FontWeight.SemiBold), color = Hb.ink3)
        }
        Box(Modifier.weight(1f).height(1.dp).background(Hb.lineSoft))
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
// Full-screen editor (tap-to-edit + auto-save, #309/#310). Replaces the old read-only
// detail page AND the editor bottom sheet: tapping a note lands here directly, edits
// auto-save (no Save button), and an Edit/Vorschau toggle keeps the rendered markdown +
// inline images reachable. A left note-switcher (#313) lets you jump to another note.
// ---------------------------------------------------------------------------

@Composable
private fun NoteEditor(
    editor: NoteEditorState,
    boundNote: NoteDto?,
    allNotes: List<NoteDto>,
    knownFolders: List<String>,
    imageUrl: (NoteImageDto) -> String,
    resolveContentImageUrl: (String) -> String?,
    onTitleChange: (String) -> Unit,
    onContentChange: (String) -> Unit,
    onTagsChange: (List<String>) -> Unit,
    onFolderChange: (String) -> Unit,
    onVisibilityChange: (String) -> Unit,
    onBack: () -> Unit,
    onCommit: () -> Unit,
    onSwitchNote: (NoteDto) -> Unit,
    onAddImages: (items: List<NoteImageUpload>) -> Unit,
    onRemoveImage: (imageId: String) -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    var switcherOpen by remember { mutableStateOf(false) }
    // HB-13: a selected note rests in the rendered preview; tapping the title/body switches into
    // the editor in place. A brand-new note (no id yet) opens straight in edit. Reseeds per editor
    // session (note switch) but survives the null→id transition while creating (see session docs).
    var editing by remember(editor.session) { mutableStateOf(editor.noteId == null) }
    // which field to focus on entering edit: title for a new note / a title tap, content otherwise
    var focusContent by remember(editor.session) { mutableStateOf(false) }
    var lightbox by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    val titleFocus = remember { FocusRequester() }
    val contentFocus = remember { FocusRequester() }
    // Focus the chosen field once the editor form is composed (new note → title; tap → that field).
    LaunchedEffect(editing, focusContent, editor.session) {
        if (editing) {
            delay(60) // let the field attach before requesting focus
            runCatching { (if (focusContent) contentFocus else titleFocus).requestFocus() }
        }
    }

    // Leave the current layer: switcher → preview → list. From edit, save and drop to preview; a
    // never-touched brand-new note is discarded straight to the list (mirrors the web exit-edit).
    fun leave() {
        when {
            switcherOpen -> switcherOpen = false
            editing -> {
                if (editor.noteId == null && editor.title.isBlank() && editor.content.isBlank()) {
                    onBack()
                } else {
                    onCommit()
                    editing = false
                }
            }
            else -> onBack()
        }
    }
    BackHandler(enabled = true) { leave() }

    // Caret-bearing content field. Keyed on the editor *session* (not the note id) so it reseeds on a
    // note switch but survives the null→id transition while typing a brand-new note (#309). The text
    // is pushed to the VM via onContentChange; the VM echoes it back into editor.content without
    // mutating it, so the local caret is authoritative within a session.
    var content by remember(editor.session) { mutableStateOf(TextFieldValue(editor.content)) }

    // Tags edited as raw text; committed (split/trim) to the VM on each change, mirroring the sheet.
    var tagsText by remember(editor.session) { mutableStateOf(editor.tags.joinToString(", ")) }

    // Insert an attachment reference at the cursor; MarkdownText resolves image:<id> on read.
    fun insertImage(img: NoteImageDto) {
        val snippet = "![${img.originalName}](image:${img.id})"
        val t = content.text
        val start = content.selection.start.coerceIn(0, t.length)
        val end = content.selection.end.coerceIn(start, t.length)
        val next = t.substring(0, start) + snippet + t.substring(end)
        content = content.copy(text = next, selection = TextRange(start + snippet.length))
        onContentChange(next)
    }

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

    Box(Modifier.fillMaxSize()) {
        HbScreenScaffold(
            appBar = {
                HbAppBar(
                    title = when {
                        editor.noteId == null -> stringResource(R.string.notes_new_title)
                        editing -> stringResource(R.string.notes_edit_title)
                        else -> editor.title.ifBlank { stringResource(R.string.notes_untitled) }
                    },
                    titleSm = true,
                    bordered = true,
                    leftIcon = HbIcons.chevronLeft,
                    onLeft = { leave() },
                    actions = {
                        // edit: live save-status; preview: a pencil to enter the editor (focus body)
                        if (editing) {
                            SaveStatusIndicator(editor.status)
                        } else {
                            HbIconButton(HbIcons.edit, { focusContent = true; editing = true })
                        }
                        // Note-switcher (#313): a left slide-over listing all notes to jump to.
                        HbIconButton(HbIcons.list, { switcherOpen = true })
                        HbIconButton(HbIcons.trash, { confirmDelete = true }, tint = Hb.danger)
                    },
                )
            },
        ) {
            Column(Modifier.padding(horizontal = 18.dp)) {
                Spacer(Modifier.size(4.dp))
                if (editing) {
                    EditorForm(
                        editor = editor,
                        content = content,
                        tagsText = tagsText,
                        knownFolders = knownFolders,
                        imageUrl = imageUrl,
                        titleFocus = titleFocus,
                        contentFocus = contentFocus,
                        onTitleChange = onTitleChange,
                        onContentChange = { tfv -> content = tfv; onContentChange(tfv.text) },
                        onTagsTextChange = { txt ->
                            tagsText = txt
                            onTagsChange(txt.split(",").map { it.trim() }.filter { it.isNotEmpty() })
                        },
                        onFolderChange = onFolderChange,
                        onVisibilityChange = onVisibilityChange,
                        onInsertImage = { insertImage(it) },
                        // No id yet ⇒ nothing to attach to; the upload would be dropped silently (#309).
                        canAddImage = editor.noteId != null,
                        onAddImage = { imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        onRemoveImage = onRemoveImage,
                        onOpenLightbox = { lightbox = it },
                    )
                } else {
                    EditorPreview(
                        editor = editor,
                        author = boundNote?.createdBy,
                        updatedAt = boundNote?.updatedAt,
                        resolveContentImageUrl = resolveContentImageUrl,
                        onEnterEdit = { editBody -> focusContent = editBody; editing = true },
                    )
                }

                Spacer(Modifier.size(8.dp))
            }
        }

        // Note-switcher left slide-over (#313) — distinct from the global app drawer; scrim + a
        // left-anchored sheet with the same folder-grouped list, tap to jump (auto-saving first).
        NoteSwitcherSheet(
            open = switcherOpen,
            notes = allNotes,
            activeNoteId = editor.noteId,
            onSelect = { onSwitchNote(it); switcherOpen = false },
            onDismiss = { switcherOpen = false },
        )
    }

    lightbox?.let { url -> ImageLightbox(url = url, onDismiss = { lightbox = null }) }

    if (confirmDelete) {
        HbConfirmDialog(
            message = stringResource(R.string.notes_delete_confirm_message),
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = { confirmDelete = false; onDelete() },
            onDismiss = { confirmDelete = false },
        )
    }
}

/** App-bar save-status chip: "Speichert…" (spinner) / "Gespeichert" (check) / error (#309). */
@Composable
private fun SaveStatusIndicator(status: SaveStatus) {
    if (status == SaveStatus.IDLE) return
    val (icon, label, tint) = when (status) {
        SaveStatus.SAVING -> Triple(HbIcons.repeat, stringResource(R.string.notes_saving), Hb.ink3)
        SaveStatus.SAVED -> Triple(HbIcons.check, stringResource(R.string.notes_saved), Hb.ink3)
        SaveStatus.ERROR -> Triple(HbIcons.x, stringResource(R.string.notes_save_error), Hb.danger)
        SaveStatus.IDLE -> return
    }
    Row(
        Modifier.padding(end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        HbIcon(icon, size = 14.dp, tint = tint)
        Text(label, style = HbType.small.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium), color = tint)
    }
}

/**
 * The editable form (HB-13 edit mode): title, body (caret-aware), insert-image chips, tags, folder,
 * visibility, and the image-attachment gallery (upload/manage). [titleFocus]/[contentFocus] let the
 * caller focus the right field when edit mode is entered (new note / tapped region).
 */
@Composable
private fun EditorForm(
    editor: NoteEditorState,
    content: TextFieldValue,
    tagsText: String,
    knownFolders: List<String>,
    imageUrl: (NoteImageDto) -> String,
    titleFocus: FocusRequester,
    contentFocus: FocusRequester,
    onTitleChange: (String) -> Unit,
    onContentChange: (TextFieldValue) -> Unit,
    onTagsTextChange: (String) -> Unit,
    onFolderChange: (String) -> Unit,
    onVisibilityChange: (String) -> Unit,
    onInsertImage: (NoteImageDto) -> Unit,
    canAddImage: Boolean,
    onAddImage: () -> Unit,
    onRemoveImage: (imageId: String) -> Unit,
    onOpenLightbox: (url: String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        HbField(stringResource(R.string.notes_field_title)) {
            HbTextField(
                value = editor.title,
                onValueChange = onTitleChange,
                modifier = Modifier.focusRequester(titleFocus),
                placeholder = stringResource(R.string.notes_title_placeholder),
            )
        }
        HbField(stringResource(R.string.notes_field_content)) {
            HbTextField(
                value = content,
                onValueChange = onContentChange,
                modifier = Modifier.focusRequester(contentFocus),
                placeholder = stringResource(R.string.notes_content_placeholder),
                singleLine = false,
                minLines = 6,
            )
        }
        // Tap an existing attachment to drop its ![name](image:id) reference at the cursor.
        if (editor.images.isNotEmpty()) {
            HbField(stringResource(R.string.notes_insert_image)) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    editor.images.forEach { img ->
                        Box(
                            Modifier
                                .size(52.dp)
                                .clip(HbRadiusSm)
                                .background(Hb.surface2)
                                .border(1.dp, Hb.lineSoft, HbRadiusSm)
                                .clickable { onInsertImage(img) },
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
                onValueChange = onTagsTextChange,
                placeholder = stringResource(R.string.notes_tags_placeholder),
            )
        }
        HbField(stringResource(R.string.notes_field_folder)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                HbTextField(
                    value = editor.folder,
                    onValueChange = onFolderChange,
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
                            val active = editor.folder.trim() == folder
                            FolderChip(
                                text = folder,
                                active = active,
                                onClick = { onFolderChange(if (active) "" else folder) },
                            )
                        }
                    }
                }
            }
        }
        HbField(stringResource(R.string.notes_field_visibility)) {
            HbSegmented(
                options = listOf(stringResource(R.string.notes_shared), stringResource(R.string.notes_private)),
                selectedIndex = if (editor.visibility == "PRIVATE") 1 else 0,
                onSelect = { onVisibilityChange(if (it == 1) "PRIVATE" else "SHARED") },
                leadingIcons = listOf(HbIcons.users, HbIcons.lock),
            )
        }
        // Image-attachment gallery (upload / manage) — an editing action, so it lives in edit mode.
        NoteImagesSection(
            images = editor.images,
            imageUrl = imageUrl,
            canAdd = canAddImage,
            onAdd = onAddImage,
            onRemove = onRemoveImage,
            onOpen = onOpenLightbox,
        )
    }
}

/**
 * The rendered preview (HB-13 resting state): title, visibility/folder badges, author·time, tag
 * chips and the rendered markdown body. Tapping the title or the body switches into the editor in
 * place (via [onEnterEdit], whose flag picks the field to focus); an empty note shows a tappable
 * placeholder. Image attachments are managed in edit mode now; inline refs still render here.
 * Uses the live draft (editor.*) so the preview reflects unsaved edits, not just the persisted note.
 */
@Composable
private fun EditorPreview(
    editor: NoteEditorState,
    author: String?,
    updatedAt: String?,
    resolveContentImageUrl: (String) -> String?,
    onEnterEdit: (focusContent: Boolean) -> Unit,
) {
    Column {
        Text(
            editor.title.ifBlank { stringResource(R.string.notes_untitled) },
            style = HbType.docTitle,
            color = if (editor.title.isBlank()) Hb.ink3 else Hb.ink,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onEnterEdit(false) },
        )

        // Meta row: visibility badge + folder badge + author avatar + "Name · vor X" (if persisted).
        Row(
            Modifier.padding(top = 12.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (editor.visibility == "PRIVATE") {
                VisibilityBadge(HbIcons.lock, stringResource(R.string.notes_private))
            } else {
                VisibilityBadge(HbIcons.users, stringResource(R.string.notes_shared))
            }
            if (editor.folder.isNotBlank()) {
                VisibilityBadge(HbIcons.folder, editor.folder.trim())
            }
            if (author != null && updatedAt != null) {
                HbAvatar(author, size = 18.dp)
                Text(
                    stringResource(R.string.notes_meta, displayName(author), Format.relativeTime(updatedAt)),
                    style = HbType.small.copy(fontSize = 12.5.sp),
                    color = Hb.ink3,
                )
            }
        }

        // Static tag chips
        if (editor.tags.isNotEmpty()) {
            FlowRow(
                Modifier.padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                editor.tags.forEach { tag -> HbTagChip(text = tag, static = true) }
            }
        }

        // Rendered markdown body — tap to edit (links inside still navigate); empty → tappable hint.
        Box(
            Modifier
                .fillMaxWidth()
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onEnterEdit(true) },
        ) {
            if (editor.content.isBlank()) {
                Text(
                    stringResource(R.string.notes_empty_doc),
                    style = HbType.body.copy(fontSize = 15.sp),
                    color = Hb.ink3,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            } else {
                MarkdownText(editor.content, resolveImageUrl = resolveContentImageUrl)
            }
        }
    }
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
    // Attachments need a persisted note (a server id) to upload against. A brand-new, not-yet-saved
    // note has no id, so the add button is disabled until the first auto-save creates the note (#309).
    canAdd: Boolean,
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
                enabled = canAdd,
            )
        }
        // Spell out why the button is dead on an unsaved note (the disabled HbButton doesn't dim).
        if (!canAdd) {
            Text(
                stringResource(R.string.notes_add_image_needs_save),
                style = HbType.small,
                color = Hb.ink3,
                modifier = Modifier.padding(bottom = 12.dp),
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
// Note-switcher left slide-over (#313)
// ---------------------------------------------------------------------------

/**
 * A left-anchored slide-over listing every note (folder-grouped, like the main list) so the user can
 * jump to another note without leaving the editor. Deliberately **local** to the notes screen — it
 * is not the app's global navigation drawer (that one stays reachable from the list view). Selecting a
 * note auto-saves the current one first (handled in the ViewModel). Scrim + slide animation mirror
 * the app drawer (MainActivity).
 */
@Composable
private fun BoxScope.NoteSwitcherSheet(
    open: Boolean,
    notes: List<NoteDto>,
    activeNoteId: String?,
    onSelect: (NoteDto) -> Unit,
    onDismiss: () -> Unit,
) {
    // Scrim
    AnimatedVisibility(visible = open, enter = fadeIn(), exit = fadeOut()) {
        val scrimInteraction = remember { MutableInteractionSource() }
        Box(
            Modifier
                .fillMaxSize()
                .background(Hb.scrim)
                .clickable(interactionSource = scrimInteraction, indication = null, onClick = onDismiss),
        )
    }
    // Sheet
    AnimatedVisibility(
        visible = open,
        modifier = Modifier.align(Alignment.CenterStart),
        enter = slideInHorizontally { -it },
        exit = slideOutHorizontally { -it },
    ) {
        val groups = remember(notes) { groupByFolder(notes) }
        val sheetInteraction = remember { MutableInteractionSource() }
        Column(
            Modifier
                .width(308.dp)
                .fillMaxHeight()
                .background(Hb.surface)
                .statusBarsPadding()
                // swallow taps so they don't reach the scrim behind the sheet
                .clickable(interactionSource = sheetInteraction, indication = null) {},
        ) {
            // Header
            Row(
                Modifier
                    .fillMaxWidth()
                    .bottomBorder(Hb.lineSoft)
                    .padding(start = 18.dp, end = 8.dp, top = 8.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.notes_switch_note),
                    style = HbType.sheetTitle,
                    color = Hb.ink,
                    modifier = Modifier.weight(1f),
                )
                HbIconButton(HbIcons.x, onDismiss, iconSize = 22.dp)
            }
            // Grouped, scrollable note list
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp, vertical = 12.dp)
                    .navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (groups.isEmpty()) {
                    Text(
                        stringResource(R.string.notes_empty_title),
                        style = HbType.meta,
                        color = Hb.ink3,
                        modifier = Modifier.padding(8.dp),
                    )
                }
                groups.forEach { group ->
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row(
                            Modifier.padding(start = 8.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(7.dp),
                        ) {
                            HbIcon(HbIcons.folder, size = 14.dp, tint = Hb.ink3)
                            Text(
                                group.folder ?: stringResource(R.string.notes_no_folder),
                                style = HbType.eyebrow,
                                color = Hb.ink3,
                            )
                        }
                        group.notes.forEach { note ->
                            val active = note.id == activeNoteId
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(HbRadiusSm)
                                    .background(if (active) Hb.accentSoft else Color.Transparent, HbRadiusSm)
                                    .clickable { onSelect(note) }
                                    .padding(horizontal = 10.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                if (note.visibility == "PRIVATE") {
                                    HbIcon(HbIcons.lock, size = 13.dp, tint = if (active) Hb.accentInk else Hb.ink3)
                                }
                                Text(
                                    note.title.ifBlank { stringResource(R.string.notes_untitled) },
                                    style = HbType.rowTitle.copy(
                                        fontSize = 14.5.sp,
                                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                                    ),
                                    color = if (active) Hb.accentInk else Hb.ink2,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            }
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
