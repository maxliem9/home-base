package com.homebase.android.ui.shopping

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.homebase.android.data.model.ShoppingItemDto

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShoppingScreen(viewModel: ShoppingViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showAddDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Einkaufsliste") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
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
                uiState.items.isEmpty() -> Text(
                    "Liste ist leer — nutze + zum Hinzufügen.",
                    modifier = Modifier.align(Alignment.Center),
                    style = MaterialTheme.typography.bodyLarge,
                )
                else -> {
                    // group by category, uncategorised ("Sonstiges") last
                    val grouped = uiState.items.groupBy { it.category?.trim()?.ifEmpty { null } ?: "Sonstiges" }
                    val categories = grouped.keys.sortedWith(
                        compareBy({ it == "Sonstiges" }, { it })
                    )
                    LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                        categories.forEach { category ->
                            item(key = "header-$category") {
                                Text(
                                    text = category.uppercase(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline,
                                    modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                                )
                            }
                            items(grouped.getValue(category), key = { it.id }) { item ->
                                ShoppingRow(
                                    item = item,
                                    onToggle = { viewModel.toggleChecked(item) },
                                    onDelete = { viewModel.deleteItem(item.id) },
                                )
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        }
    }

    uiState.error?.let { msg ->
        AlertDialog(
            onDismissRequest = viewModel::clearError,
            confirmButton = { TextButton(onClick = viewModel::clearError) { Text("OK") } },
            title = { Text("Fehler") },
            text = { Text(msg) },
        )
    }

    if (showAddDialog) {
        AddItemDialog(
            onConfirm = { name, category ->
                viewModel.addItem(name, category)
                showAddDialog = false
            },
            onDismiss = { showAddDialog = false },
        )
    }
}

@Composable
private fun ShoppingRow(
    item: ShoppingItemDto,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
) {
    ListItem(
        leadingContent = {
            Checkbox(checked = item.checked, onCheckedChange = { onToggle() })
        },
        headlineContent = {
            Text(
                text = item.name,
                textDecoration = if (item.checked) TextDecoration.LineThrough else null,
            )
        },
        trailingContent = {
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Löschen")
            }
        },
    )
}

@Composable
private fun AddItemDialog(onConfirm: (String, String?) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Neuer Artikel") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Artikel") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = category,
                    onValueChange = { category = it },
                    label = { Text("Kategorie (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank()) onConfirm(name, category) },
                enabled = name.isNotBlank(),
            ) { Text("Hinzufügen") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}
