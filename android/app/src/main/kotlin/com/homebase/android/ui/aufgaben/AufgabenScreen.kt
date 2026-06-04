package com.homebase.android.ui.aufgaben

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.homebase.android.data.model.SubtaskDto
import com.homebase.android.data.model.TodoDto
import com.homebase.android.data.model.TodoListDto
import com.homebase.android.data.model.UpdateTodoRequest
import com.homebase.android.ui.components.HbAppBar
import com.homebase.android.ui.components.HbAvatar
import com.homebase.android.ui.components.HbBadge
import com.homebase.android.ui.components.HbBottomSheet
import com.homebase.android.ui.components.HbButton
import com.homebase.android.ui.components.HbButtonSize
import com.homebase.android.ui.components.HbButtonVariant
import com.homebase.android.ui.components.HbCheck
import com.homebase.android.ui.components.HbEmpty
import com.homebase.android.ui.components.HbFab
import com.homebase.android.ui.components.HbField
import com.homebase.android.ui.components.HbIcon
import com.homebase.android.ui.components.HbIconButton
import com.homebase.android.ui.components.HbIcons
import com.homebase.android.ui.components.HbPick
import com.homebase.android.ui.components.HbPill
import com.homebase.android.ui.components.HbPriority
import com.homebase.android.ui.components.HbQuickAdd
import com.homebase.android.ui.components.HbScreenScaffold
import com.homebase.android.ui.components.HbSegmented
import com.homebase.android.ui.components.HbTextField
import com.homebase.android.ui.components.bottomBorder
import com.homebase.android.ui.theme.Hb
import com.homebase.android.ui.theme.HbType
import com.homebase.android.ui.util.Format

// ---------------------------------------------------------------------------
// Sheet state
// ---------------------------------------------------------------------------

private sealed interface AufgabenSheet {
    /** Edit an existing todo, or create a new one when [todo] is null. */
    data class Edit(val todo: TodoDto?) : AufgabenSheet
    data object NewList : AufgabenSheet
}

@Composable
fun AufgabenScreen(viewModel: TodoViewModel, currentUser: String?, onOpenDrawer: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    var quickAddText by remember { mutableStateOf("") }
    var sheet by remember { mutableStateOf<AufgabenSheet?>(null) }
    var expandedTaskId by remember { mutableStateOf<String?>(null) }
    var doneCollapsed by remember { mutableStateOf(true) }

    val openTodos = state.visibleTodos.filter { it.status != "DONE" }
    val doneTodos = state.visibleTodos.filter { it.status == "DONE" }

    Box(Modifier.fillMaxSize()) {
        HbScreenScaffold(
            appBar = {
                HbAppBar(
                    eyebrow = "Aufgaben",
                    title = state.activeList?.name ?: "Aufgaben",
                    onLeft = onOpenDrawer,
                    actions = { HbIconButton(HbIcons.more, {}) },
                )
            },
            fab = { HbFab(onClick = { sheet = AufgabenSheet.Edit(null) }, label = "Aufgabe") },
        ) {
            // List tabs — full-bleed scrollable strip with a bottom hairline.
            ListTabs(
                lists = state.lists,
                todos = state.todos,
                activeId = state.activeList?.id,
                onSelect = { viewModel.selectList(it) },
                onNewList = { sheet = AufgabenSheet.NewList },
            )

            Spacer(Modifier.size(18.dp))

            // Quick-add bar.
            Box(Modifier.padding(horizontal = 18.dp)) {
                HbQuickAdd(
                    value = quickAddText,
                    onValueChange = { quickAddText = it },
                    placeholder = "Aufgabe hinzufügen …",
                    leading = HbIcons.plus,
                    onSubmit = {
                        viewModel.addTodo(quickAddText)
                        quickAddText = ""
                    },
                )
            }

            if (openTodos.isEmpty()) {
                HbEmpty(
                    HbIcons.checkCircle,
                    "Alles erledigt",
                    "Keine offenen Aufgaben in dieser Liste.\nFüge oben eine neue hinzu.",
                )
            } else {
                // Due-date groups in fixed order, skipping empty ones.
                val grouped = openTodos.groupBy { Format.dueGroup(it.dueDate) }
                Format.DueGroup.entries.sortedBy { it.order }.forEach { group ->
                    val items = grouped[group] ?: return@forEach
                    if (items.isEmpty()) return@forEach

                    GroupLabel(group.label, items.size)
                    Column(Modifier.padding(horizontal = 18.dp)) {
                        items.forEach { todo ->
                            TaskRow(
                                todo = todo,
                                expanded = expandedTaskId == todo.id,
                                onToggleDone = { viewModel.toggleDone(todo) },
                                onToggleExpand = {
                                    expandedTaskId = if (expandedTaskId == todo.id) null else todo.id
                                },
                                onOpenEdit = { sheet = AufgabenSheet.Edit(todo) },
                                onToggleSubtask = { sub -> viewModel.toggleSubtask(todo.id, sub) },
                                onAddSubtask = { title -> viewModel.addSubtask(todo.id, title) },
                            )
                        }
                    }
                }
            }

            // "Erledigt" collapsible footer.
            if (doneTodos.isNotEmpty()) {
                DoneSection(
                    collapsed = doneCollapsed,
                    todos = doneTodos,
                    onToggle = { doneCollapsed = !doneCollapsed },
                    onRestore = { viewModel.toggleDone(it) },
                )
            }
        }

        // Sheets — siblings of the scaffold, overlaying the whole screen.
        when (val s = sheet) {
            is AufgabenSheet.Edit -> EditSheet(
                todo = s.todo,
                onDismiss = { sheet = null },
                onSaveCreate = { title -> viewModel.addTodo(title) },
                onSaveEdit = { id, req -> viewModel.updateTodo(id, req) },
                onDelete = { id -> viewModel.deleteTodo(id) },
            )
            AufgabenSheet.NewList -> NewListSheet(
                onDismiss = { sheet = null },
                onCreate = { name, visibility -> viewModel.createList(name, visibility) },
            )
            null -> {}
        }
    }
}

// ---------------------------------------------------------------------------
// List tabs
// ---------------------------------------------------------------------------

@Composable
private fun ListTabs(
    lists: List<TodoListDto>,
    todos: List<TodoDto>,
    activeId: String?,
    onSelect: (String) -> Unit,
    onNewList: () -> Unit,
) {
    val firstId = lists.firstOrNull()?.id
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .bottomBorder(Hb.lineSoft),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(18.dp))
        lists.forEach { list ->
            val active = activeId == list.id
            val count = todos.count { todo ->
                todo.status != "DONE" &&
                    (todo.listId == list.id || (list.id == firstId && todo.listId == null))
            }
            ListTab(
                name = list.name,
                count = count,
                locked = list.visibility == "PRIVATE",
                active = active,
                onClick = { onSelect(list.id) },
            )
        }
        // Trailing "+ Neue Liste" tab.
        Row(
            Modifier
                .clickable { onNewList() }
                .padding(horizontal = 13.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HbIcon(HbIcons.plus, size = 16.dp, tint = Hb.accentInk)
            Text(
                "Neue Liste",
                style = HbType.label.copy(fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold),
                color = Hb.accentInk,
                maxLines = 1,
            )
        }
        Spacer(Modifier.width(18.dp))
    }
}

@Composable
private fun ListTab(
    name: String,
    count: Int,
    locked: Boolean,
    active: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .clickable { onClick() }
            .then(if (active) Modifier.bottomBorder2dp(Hb.accent) else Modifier)
            .padding(horizontal = 13.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (locked) HbIcon(HbIcons.lock, size = 14.dp, tint = if (active) Hb.ink else Hb.ink3)
        Text(
            name,
            style = HbType.label.copy(fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold),
            color = if (active) Hb.ink else Hb.ink3,
            maxLines = 1,
        )
        CountPill(count.toString(), active)
    }
}

/** Mono count pill used by tabs: accent-soft when active, surface3 otherwise. */
@Composable
private fun CountPill(text: String, active: Boolean) {
    Box(
        Modifier
            .heightIn(min = 19.dp)
            .clip(HbPill)
            .background(if (active) Hb.accentSoft else Hb.surface3, HbPill)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = HbType.mono.copy(fontSize = 11.5.sp, fontWeight = FontWeight.Bold),
            color = if (active) Hb.accentInk else Hb.ink2,
        )
    }
}

// ---------------------------------------------------------------------------
// Group label
// ---------------------------------------------------------------------------

@Composable
private fun GroupLabel(label: String, count: Int) {
    Row(
        Modifier.padding(start = 20.dp, end = 18.dp, top = 18.dp, bottom = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(label.uppercase(), style = HbType.sectionLabel, color = Hb.ink3)
        Text(
            count.toString(),
            style = HbType.mono.copy(fontSize = 12.5.sp, fontWeight = FontWeight.Bold),
            color = Hb.ink3,
        )
    }
}

// ---------------------------------------------------------------------------
// Task row
// ---------------------------------------------------------------------------

@Composable
private fun TaskRow(
    todo: TodoDto,
    expanded: Boolean,
    onToggleDone: () -> Unit,
    onToggleExpand: () -> Unit,
    onOpenEdit: () -> Unit,
    onToggleSubtask: (SubtaskDto) -> Unit,
    onAddSubtask: (String) -> Unit,
) {
    val undated = todo.dueDate == null && todo.assignee == null
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { onOpenEdit() }
                .padding(horizontal = 2.dp, vertical = 13.dp),
            horizontalArrangement = Arrangement.spacedBy(13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HbCheck(checked = false, onCheckedChange = onToggleDone)

            Column(Modifier.weight(1f)) {
                Text(todo.title, style = HbType.rowTitle, color = Hb.ink)
                val badge = Format.dueBadge(todo.dueDate)
                val hasMeta = todo.priority != null || badge != null || !todo.description.isNullOrBlank()
                if (hasMeta) {
                    Row(
                        Modifier.padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(9.dp),
                    ) {
                        HbPriority(todo.priority)
                        badge?.let { HbBadge(it.label, it.tone) }
                        if (!todo.description.isNullOrBlank()) {
                            Text(
                                todo.description!!,
                                style = HbType.meta,
                                color = Hb.ink3,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                        }
                    }
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                SubPill(
                    done = todo.subtasks.count { it.done },
                    total = todo.subtasks.size,
                    open = expanded,
                    onClick = onToggleExpand,
                )
                if (undated) {
                    HbButton(
                        text = "Planen",
                        onClick = onOpenEdit,
                        variant = HbButtonVariant.Secondary,
                        size = HbButtonSize.Sm,
                    )
                } else {
                    HbAvatar(todo.assignee, size = 26.dp)
                }
            }
        }
        // hairline divider
        Box(Modifier.fillMaxWidth().size(1.dp).background(Hb.lineSoft))

        if (expanded) {
            SubtasksPanel(
                subtasks = todo.subtasks,
                onToggle = onToggleSubtask,
                onAdd = onAddSubtask,
            )
        }
    }
}

@Composable
private fun SubPill(done: Int, total: Int, open: Boolean, onClick: () -> Unit) {
    val empty = total == 0
    val bg = if (open) Hb.accentSoft else Hb.surface
    val fg = when { open -> Hb.accentInk; empty -> Hb.ink3; else -> Hb.ink2 }
    Row(
        Modifier
            .clip(HbPill)
            .background(bg, HbPill)
            .then(if (open) Modifier else Modifier.border(1.dp, Hb.line, HbPill))
            .clickable { onClick() }
            .padding(horizontal = 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (empty) {
            Text(
                "Unteraufgaben",
                style = HbType.small.copy(fontWeight = FontWeight.SemiBold),
                color = fg,
            )
        } else {
            Text(
                "$done/$total",
                style = HbType.mono.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                color = fg,
            )
        }
        HbIcon(if (open) HbIcons.chevronUp else HbIcons.chevronDown, size = 14.dp, tint = fg)
    }
}

@Composable
private fun SubtasksPanel(
    subtasks: List<SubtaskDto>,
    onToggle: (SubtaskDto) -> Unit,
    onAdd: (String) -> Unit,
) {
    var newSubtask by remember { mutableStateOf("") }
    Column(Modifier.padding(start = 38.dp, end = 2.dp, top = 2.dp, bottom = 12.dp)) {
        subtasks.sortedBy { it.sortOrder }.forEach { sub ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(11.dp),
            ) {
                HbCheck(checked = sub.done, onCheckedChange = { onToggle(sub) }, size = 19.dp)
                Text(
                    sub.title,
                    style = HbType.body.copy(fontSize = 14.5.sp),
                    color = if (sub.done) Hb.ink3 else Hb.ink,
                    textDecoration = if (sub.done) TextDecoration.LineThrough else null,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        // inline add row
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            HbIcon(HbIcons.plus, size = 16.dp, tint = Hb.ink3)
            BasicTextField(
                value = newSubtask,
                onValueChange = { newSubtask = it },
                modifier = Modifier.weight(1f),
                textStyle = HbType.body.copy(fontSize = 14.5.sp, color = Hb.ink),
                singleLine = true,
                cursorBrush = SolidColor(Hb.accent),
                decorationBox = { inner ->
                    if (newSubtask.isEmpty()) {
                        Text(
                            "Unteraufgabe hinzufügen …",
                            style = HbType.body.copy(fontSize = 14.5.sp, color = Hb.ink3),
                        )
                    }
                    inner()
                },
            )
            if (newSubtask.isNotBlank()) {
                HbIconButton(
                    HbIcons.check,
                    {
                        onAdd(newSubtask)
                        newSubtask = ""
                    },
                    iconSize = 18.dp,
                    tint = Hb.accent,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Done section
// ---------------------------------------------------------------------------

@Composable
private fun DoneSection(
    collapsed: Boolean,
    todos: List<TodoDto>,
    onToggle: () -> Unit,
    onRestore: (TodoDto) -> Unit,
) {
    Column(Modifier.padding(horizontal = 18.dp)) {
        Row(
            Modifier
                .clickable { onToggle() }
                .padding(start = 2.dp, end = 2.dp, top = 22.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            HbIcon(
                if (collapsed) HbIcons.chevronRight else HbIcons.chevronDown,
                size = 16.dp,
                tint = Hb.ink3,
            )
            Text("Erledigt".uppercase(), style = HbType.sectionLabel, color = Hb.ink3)
            Box(
                Modifier
                    .clip(HbPill)
                    .background(Hb.surface2, HbPill)
                    .padding(horizontal = 9.dp, vertical = 2.dp),
            ) {
                Text(
                    todos.size.toString(),
                    style = HbType.mono.copy(fontSize = 12.5.sp),
                    color = Hb.ink3,
                )
            }
        }
        if (!collapsed) {
            todos.forEach { todo ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 2.dp, vertical = 13.dp),
                    horizontalArrangement = Arrangement.spacedBy(13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    HbCheck(checked = true, onCheckedChange = { onRestore(todo) })
                    Text(
                        todo.title,
                        style = HbType.rowTitle,
                        color = Hb.ink3,
                        textDecoration = TextDecoration.LineThrough,
                        modifier = Modifier.weight(1f),
                    )
                }
                Box(Modifier.fillMaxWidth().size(1.dp).background(Hb.lineSoft))
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Edit / Create sheet
// ---------------------------------------------------------------------------

@Composable
private fun EditSheet(
    todo: TodoDto?,
    onDismiss: () -> Unit,
    onSaveCreate: (String) -> Unit,
    onSaveEdit: (String, UpdateTodoRequest) -> Unit,
    onDelete: (String) -> Unit,
) {
    val isEdit = todo != null
    var title by remember { mutableStateOf(todo?.title ?: "") }
    var description by remember { mutableStateOf(todo?.description ?: "") }
    // assignee: null = "Niemand"/unset; "max" / "lea" otherwise
    var assignee by remember { mutableStateOf(todo?.assignee) }
    var dueText by remember { mutableStateOf(todo?.dueDate ?: "") }
    var priority by remember { mutableStateOf(todo?.priority) }

    HbBottomSheet(
        onDismiss = onDismiss,
        title = if (isEdit) "Aufgabe bearbeiten" else "Neue Aufgabe",
        footer = {
            if (isEdit) {
                HbButton(
                    text = "",
                    onClick = {
                        onDelete(todo!!.id)
                        onDismiss()
                    },
                    variant = HbButtonVariant.Danger,
                    icon = HbIcons.trash,
                )
            }
            Spacer(Modifier.weight(1f))
            HbButton(text = "Abbrechen", onClick = onDismiss, variant = HbButtonVariant.Secondary)
            HbButton(
                text = "Speichern",
                onClick = {
                    if (title.isNotBlank()) {
                        if (isEdit) {
                            onSaveEdit(
                                todo!!.id,
                                UpdateTodoRequest(
                                    title = title.trim(),
                                    description = description.ifBlank { "" },
                                    assignee = assignee ?: "",
                                    dueDate = dueText.ifBlank { null },
                                    priority = priority,
                                    status = if (assignee != null || dueText.isNotBlank()) "PLANNED" else "INBOX",
                                ),
                            )
                        } else {
                            onSaveCreate(title.trim())
                        }
                    }
                    onDismiss()
                },
                variant = HbButtonVariant.Primary,
            )
        },
    ) {
        HbField("Titel") {
            HbTextField(value = title, onValueChange = { title = it }, placeholder = "Titel")
        }
        HbField("Beschreibung") {
            HbTextField(
                value = description,
                onValueChange = { description = it },
                placeholder = "Beschreibung",
                singleLine = false,
                minLines = 2,
            )
        }
        HbField("Zuständig") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                HbPick(active = assignee?.lowercase() == "max", onClick = { assignee = "max" }) {
                    HbAvatar("max", size = 20.dp)
                    Text(
                        "Max",
                        style = HbType.label.copy(fontSize = 13.5.sp),
                        color = if (assignee?.lowercase() == "max") Hb.accentInk else Hb.ink2,
                    )
                }
                HbPick(active = assignee?.lowercase() == "lea", onClick = { assignee = "lea" }) {
                    HbAvatar("lea", size = 20.dp)
                    Text(
                        "Lea",
                        style = HbType.label.copy(fontSize = 13.5.sp),
                        color = if (assignee?.lowercase() == "lea") Hb.accentInk else Hb.ink2,
                    )
                }
                HbPick(active = assignee == null, onClick = { assignee = null }) {
                    Text(
                        "Niemand",
                        style = HbType.label.copy(fontSize = 13.5.sp),
                        color = if (assignee == null) Hb.accentInk else Hb.ink2,
                    )
                }
            }
        }
        HbField("Fällig") {
            HbTextField(
                value = dueText,
                onValueChange = { dueText = it },
                placeholder = "2026-06-04",
                mono = true,
            )
            Text(
                "Format: JJJJ-MM-TT, z. B. ${Format.dueFieldLabel("2026-06-04") ?: "2026-06-04"}",
                style = HbType.small,
                color = Hb.ink3,
            )
        }
        HbField("Priorität") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                PriorityPick("LOW", "Niedrig", Hb.prioLow, priority) { priority = it }
                PriorityPick("MEDIUM", "Mittel", Hb.prioMedium, priority) { priority = it }
                PriorityPick("HIGH", "Hoch", Hb.prioHigh, priority) { priority = it }
            }
        }
    }
}

@Composable
private fun PriorityPick(
    value: String,
    label: String,
    dotColor: Color,
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    val active = selected == value
    HbPick(active = active, onClick = { onSelect(if (active) null else value) }) {
        Box(Modifier.size(8.dp).clip(HbPill).background(dotColor))
        Text(
            label,
            style = HbType.label.copy(fontSize = 13.5.sp),
            color = if (active) Hb.accentInk else Hb.ink2,
        )
    }
}

// ---------------------------------------------------------------------------
// New-list sheet
// ---------------------------------------------------------------------------

@Composable
private fun NewListSheet(
    onDismiss: () -> Unit,
    onCreate: (String, String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var segIndex by remember { mutableStateOf(0) }

    HbBottomSheet(
        onDismiss = onDismiss,
        title = "Neue Liste",
        footer = {
            HbButton(
                text = "Abbrechen",
                onClick = onDismiss,
                variant = HbButtonVariant.Secondary,
                modifier = Modifier.weight(1f),
            )
            HbButton(
                text = "Erstellen",
                onClick = {
                    if (name.isNotBlank()) {
                        onCreate(name.trim(), if (segIndex == 0) "SHARED" else "PRIVATE")
                    }
                    onDismiss()
                },
                variant = HbButtonVariant.Primary,
                modifier = Modifier.weight(1f),
            )
        },
    ) {
        HbField("Name") {
            HbTextField(value = name, onValueChange = { name = it }, placeholder = "z. B. Garten")
        }
        HbField("Sichtbarkeit") {
            HbSegmented(
                options = listOf("Geteilt", "Privat"),
                selectedIndex = segIndex,
                onSelect = { segIndex = it },
                leadingIcons = listOf(HbIcons.users, HbIcons.lock),
            )
            Text(
                "Geteilte Listen sehen beide. Private nur du.",
                style = HbType.small,
                color = Hb.ink3,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/** Draw a 2dp bottom underline (active tab accent). */
private fun Modifier.bottomBorder2dp(color: Color): Modifier = drawBehind {
    val stroke = 2.dp.toPx()
    val y = size.height - stroke / 2f
    drawLine(
        color = color,
        start = Offset(0f, y),
        end = Offset(size.width, y),
        strokeWidth = stroke,
    )
}
