package com.homebase.android.ui.notes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.homebase.android.R
import com.homebase.android.data.model.NoteDto

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NotesScreen(viewModel: NotesViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<NoteDto?>(null) }
    var creating by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(stringResource(R.string.notes_title)) },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                )
                OutlinedTextField(
                    value = uiState.query,
                    onValueChange = viewModel::onQueryChange,
                    placeholder = { Text(stringResource(R.string.notes_search)) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { creating = true }) {
                Text("+", style = MaterialTheme.typography.headlineMedium)
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                uiState.isLoading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                uiState.notes.isEmpty() -> Text(
                    if (uiState.query.isBlank()) stringResource(R.string.notes_empty)
                    else stringResource(R.string.notes_no_results),
                    modifier = Modifier.align(Alignment.Center),
                    style = MaterialTheme.typography.bodyLarge,
                )
                else -> LazyColumn(
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(uiState.notes, key = { it.id }) { note ->
                        NoteCard(note = note, onClick = { editing = note })
                    }
                }
            }
        }
    }

    uiState.error?.let { msg ->
        AlertDialog(
            onDismissRequest = viewModel::clearError,
            confirmButton = { TextButton(onClick = viewModel::clearError) { Text(stringResource(R.string.action_ok)) } },
            title = { Text(stringResource(R.string.error_title)) },
            text = { Text(msg) },
        )
    }

    if (creating) {
        NoteEditorDialog(
            note = null,
            onSave = { title, content, tags, visibility ->
                viewModel.saveNote(null, title, content, tags, visibility)
                creating = false
            },
            onDismiss = { creating = false },
        )
    }

    editing?.let { note ->
        NoteEditorDialog(
            note = note,
            onSave = { title, content, tags, visibility ->
                viewModel.saveNote(note.id, title, content, tags, visibility)
                editing = null
            },
            onDelete = {
                viewModel.deleteNote(note.id)
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NoteCard(note: NoteDto, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = note.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(if (note.visibility == "PRIVATE") "🔒" else "👥")
            }
            if (note.content.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = note.content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (note.tags.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    note.tags.forEach { tag ->
                        AssistChip(onClick = onClick, label = { Text("#$tag") })
                    }
                }
            }
        }
    }
}

@Composable
private fun NoteEditorDialog(
    note: NoteDto?,
    onSave: (title: String, content: String, tags: List<String>, visibility: String) -> Unit,
    onDelete: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    var title by remember { mutableStateOf(note?.title ?: "") }
    var content by remember { mutableStateOf(note?.content ?: "") }
    var tags by remember { mutableStateOf(note?.tags?.joinToString(", ") ?: "") }
    var visibility by remember { mutableStateOf(note?.visibility ?: "SHARED") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (note == null) R.string.notes_new else R.string.notes_edit)) },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.field_title)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text(stringResource(R.string.notes_field_content)) },
                    minLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = tags,
                    onValueChange = { tags = it },
                    label = { Text(stringResource(R.string.notes_field_tags)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.notes_visibility), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.width(8.dp))
                    FilterChip(
                        selected = visibility == "SHARED",
                        onClick = { visibility = if (visibility == "SHARED") "PRIVATE" else "SHARED" },
                        label = { Text(stringResource(if (visibility == "PRIVATE") R.string.notes_private else R.string.notes_shared)) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (title.isNotBlank()) {
                        val parsedTags = tags.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                        onSave(title, content, parsedTags, visibility)
                    }
                },
                enabled = title.isNotBlank(),
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) {
                        Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        },
    )
}
