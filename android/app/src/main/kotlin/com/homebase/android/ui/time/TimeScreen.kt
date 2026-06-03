package com.homebase.android.ui.time

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.homebase.android.R
import com.homebase.android.data.model.ProjectDto
import com.homebase.android.data.model.TimeEntryDto
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

private fun parseColor(hex: String): Color = runCatching {
    Color(android.graphics.Color.parseColor(hex))
}.getOrDefault(Color(0xFF9CA3AF))

private fun isToday(startedAt: String): Boolean = runCatching {
    Instant.parse(startedAt).atZone(ZoneId.systemDefault()).toLocalDate() == LocalDate.now()
}.getOrDefault(false)

private fun formatDuration(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    return when {
        h > 0 -> "${h}h ${m}m"
        m > 0 -> "${m}m"
        else -> "${seconds}s"
    }
}

private fun formatClock(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return "%02d:%02d:%02d".format(h, m, s)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeScreen(viewModel: TimeViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showManual by remember { mutableStateOf(false) }
    var showAddProject by remember { mutableStateOf(false) }

    val projectsById = remember(uiState.projects) { uiState.projects.associateBy { it.id } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.time_title)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary,
                ),
                actions = {
                    IconButton(onClick = { showAddProject = true }) {
                        Icon(Icons.Default.Add, contentDescription = stringResource(R.string.time_add_project_cd))
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showManual = true },
                text = { Text(stringResource(R.string.time_entry)) },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                uiState.isLoading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                else -> {
                    val todayEntries = uiState.entries
                        .filter { it.stoppedAt != null && isToday(it.startedAt) }
                        .sortedByDescending { it.startedAt }
                    val todayTotal = todayEntries.sumOf { it.durationSeconds ?: 0L }

                    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        uiState.running?.let { running ->
                            item(key = "running") {
                                RunningCard(
                                    entry = running,
                                    project = projectsById[running.projectId],
                                    onStop = viewModel::stopTimer,
                                )
                            }
                        }

                        item(key = "quickstart") {
                            Text(stringResource(R.string.time_quickstart), style = MaterialTheme.typography.titleSmall)
                        }
                        if (uiState.activeProjects.isEmpty()) {
                            item(key = "no-projects") {
                                Text(
                                    stringResource(R.string.time_no_projects),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                            }
                        } else {
                            items(uiState.activeProjects, key = { "p-${it.id}" }) { project ->
                                ProjectStartRow(project = project, onStart = { viewModel.startTimer(project.id, null) })
                            }
                        }

                        item(key = "today-header") {
                            Row(
                                Modifier.fillMaxWidth().padding(top = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(stringResource(R.string.time_today), style = MaterialTheme.typography.titleSmall)
                                Text(formatDuration(todayTotal), style = MaterialTheme.typography.titleSmall, fontFamily = FontFamily.Monospace)
                            }
                        }
                        if (todayEntries.isEmpty()) {
                            item(key = "today-empty") {
                                Text(
                                    stringResource(R.string.time_no_entries_today),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                            }
                        } else {
                            items(todayEntries, key = { it.id }) { entry ->
                                EntryRow(
                                    entry = entry,
                                    project = projectsById[entry.projectId],
                                    onDelete = { viewModel.deleteEntry(entry.id) },
                                )
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
            confirmButton = { TextButton(onClick = viewModel::clearError) { Text(stringResource(R.string.action_ok)) } },
            title = { Text(stringResource(R.string.error_title)) },
            text = { Text(msg) },
        )
    }

    if (showManual) {
        ManualEntrySheet(
            projects = uiState.activeProjects,
            onSubmit = { projectId, startedAt, stoppedAt, description ->
                viewModel.addManualEntry(projectId, startedAt, stoppedAt, description)
                showManual = false
            },
            onDismiss = { showManual = false },
        )
    }

    if (showAddProject) {
        AddProjectDialog(
            onConfirm = { name, color ->
                viewModel.addProject(name, color)
                showAddProject = false
            },
            onDismiss = { showAddProject = false },
        )
    }
}

@Composable
private fun RunningCard(entry: TimeEntryDto, project: ProjectDto?, onStop: () -> Unit) {
    // live ticking clock
    val elapsed by produceState(initialValue = currentElapsed(entry.startedAt), entry.startedAt) {
        while (true) {
            value = currentElapsed(entry.startedAt)
            delay(1000)
        }
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(12.dp).clip(CircleShape).background(parseColor(project?.color ?: "#4F46E5")))
            Column(Modifier.weight(1f)) {
                Text(project?.name ?: stringResource(R.string.time_project), style = MaterialTheme.typography.titleMedium)
                entry.description?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(formatClock(elapsed), style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Monospace)
            FilledIconButton(onClick = onStop) {
                // square "stop" glyph
                Text("■")
            }
        }
    }
}

@Composable
private fun ProjectStartRow(project: ProjectDto, onStart: () -> Unit) {
    ListItem(
        leadingContent = {
            Box(Modifier.size(16.dp).clip(CircleShape).background(parseColor(project.color)))
        },
        headlineContent = { Text(project.name) },
        trailingContent = {
            IconButton(onClick = onStart) {
                Icon(Icons.Default.PlayArrow, contentDescription = stringResource(R.string.time_start_timer_cd))
            }
        },
    )
}

@Composable
private fun EntryRow(entry: TimeEntryDto, project: ProjectDto?, onDelete: () -> Unit) {
    val range = buildString {
        append(TIME_FMT.format(Instant.parse(entry.startedAt)))
        append("–")
        entry.stoppedAt?.let { append(TIME_FMT.format(Instant.parse(it))) }
        entry.description?.takeIf { it.isNotBlank() }?.let { append(" · $it") }
    }
    ListItem(
        leadingContent = {
            Box(Modifier.size(12.dp).clip(CircleShape).background(parseColor(project?.color ?: "#9CA3AF")))
        },
        headlineContent = { Text(project?.name ?: stringResource(R.string.time_project)) },
        supportingContent = { Text(range) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(formatDuration(entry.durationSeconds ?: 0L), fontFamily = FontFamily.Monospace)
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.action_delete))
                }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ManualEntrySheet(
    projects: List<ProjectDto>,
    onSubmit: (projectId: String, startedAt: String, stoppedAt: String, description: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    var projectId by remember { mutableStateOf(projects.firstOrNull()?.id ?: "") }
    var date by remember { mutableStateOf(LocalDate.now().toString()) }
    var start by remember { mutableStateOf("09:00") }
    var end by remember { mutableStateOf("10:00") }
    var description by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val invalidTimesMsg = stringResource(R.string.time_invalid_times)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.time_record_entry), style = MaterialTheme.typography.titleLarge)

            ProjectDropdown(projects = projects, selectedId = projectId, onSelect = { projectId = it })

            OutlinedTextField(
                value = date,
                onValueChange = { date = it },
                label = { Text(stringResource(R.string.time_field_date)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = start,
                    onValueChange = { start = it },
                    label = { Text(stringResource(R.string.time_field_start)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = end,
                    onValueChange = { end = it },
                    label = { Text(stringResource(R.string.time_field_end)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text(stringResource(R.string.time_field_description)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

            Button(
                onClick = {
                    val result = buildEntryTimestamps(date, start, end)
                    if (result == null) {
                        error = invalidTimesMsg
                    } else {
                        onSubmit(projectId, result.first, result.second, description)
                    }
                },
                enabled = projectId.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.action_save)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProjectDropdown(projects: List<ProjectDto>, selectedId: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selected = projects.firstOrNull { it.id == selectedId }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected?.name ?: stringResource(R.string.time_select_project),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.time_project)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            projects.forEach { project ->
                DropdownMenuItem(
                    text = { Text(project.name) },
                    onClick = { onSelect(project.id); expanded = false },
                )
            }
        }
    }
}

@Composable
private fun AddProjectDialog(onConfirm: (String, String) -> Unit, onDismiss: () -> Unit) {
    val colors = listOf("#4F46E5", "#10B981", "#F59E0B", "#EF4444", "#EC4899", "#06B6D4", "#8B5CF6", "#64748B")
    var name by remember { mutableStateOf("") }
    var color by remember { mutableStateOf(colors.first()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.time_new_project)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.time_project_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    colors.forEach { c ->
                        Box(
                            Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(parseColor(c))
                                .then(if (c == color) Modifier.padding(2.dp) else Modifier),
                        ) {
                            IconButton(onClick = { color = c }, modifier = Modifier.fillMaxSize()) {
                                if (c == color) Text("✓", color = Color.White)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { if (name.isNotBlank()) onConfirm(name, color) }, enabled = name.isNotBlank()) {
                Text(stringResource(R.string.time_create))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

// --- helpers ---------------------------------------------------------------

private fun currentElapsed(startedAt: String): Long = runCatching {
    val start = Instant.parse(startedAt).toEpochMilli()
    ((System.currentTimeMillis() - start) / 1000).coerceAtLeast(0)
}.getOrDefault(0L)

/** Builds (startIso, stopIso) in UTC from local date/time inputs, or null if invalid / stop ≤ start. */
private fun buildEntryTimestamps(date: String, start: String, end: String): Pair<String, String>? = runCatching {
    val d = LocalDate.parse(date)
    val s = LocalTime.parse(start)
    val e = LocalTime.parse(end)
    val zone = ZoneId.systemDefault()
    val startInstant = d.atTime(s).atZone(zone).toInstant()
    val stopInstant = d.atTime(e).atZone(zone).toInstant()
    if (!stopInstant.isAfter(startInstant)) return null
    startInstant.toString() to stopInstant.toString()
}.getOrNull()
