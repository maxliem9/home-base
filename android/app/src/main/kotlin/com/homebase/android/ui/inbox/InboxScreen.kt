package com.homebase.android.ui.inbox

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.homebase.android.R
import com.homebase.android.data.model.TodoDto

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(viewModel: InboxViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showAddDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.inbox_title)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.inbox_add_todo_cd))
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
                uiState.todos.isEmpty() -> Text(
                    stringResource(R.string.inbox_empty),
                    modifier = Modifier.align(Alignment.Center),
                    style = MaterialTheme.typography.bodyLarge,
                )
                else -> LazyColumn(
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    items(uiState.todos, key = { it.id }) { todo ->
                        TodoItem(
                            todo = todo,
                            onMarkDone = { viewModel.markDone(todo.id) },
                            onDelete = { viewModel.deleteTodo(todo.id) },
                        )
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

    if (showAddDialog) {
        AddTodoDialog(
            onConfirm = { title ->
                viewModel.createTodo(title)
                showAddDialog = false
            },
            onDismiss = { showAddDialog = false },
        )
    }
}

@Composable
private fun TodoItem(
    todo: TodoDto,
    onMarkDone: () -> Unit,
    onDelete: () -> Unit,
) {
    val isDone = todo.status == "DONE"
    ListItem(
        headlineContent = {
            Text(
                text = todo.title,
                textDecoration = if (isDone) TextDecoration.LineThrough else null,
            )
        },
        supportingContent = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                todo.priority?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                todo.assignee?.let { Text("@$it", style = MaterialTheme.typography.labelSmall) }
                todo.dueDate?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
            }
        },
        trailingContent = {
            Row {
                if (!isDone) {
                    IconButton(onClick = onMarkDone) {
                        Icon(Icons.Default.Check, contentDescription = stringResource(R.string.inbox_mark_done_cd))
                    }
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.action_delete))
                }
            }
        },
    )
    HorizontalDivider()
}

@Composable
private fun AddTodoDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var title by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.inbox_new_item)) },
        text = {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text(stringResource(R.string.field_title)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (title.isNotBlank()) onConfirm(title) },
                enabled = title.isNotBlank(),
            ) { Text(stringResource(R.string.action_add)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
