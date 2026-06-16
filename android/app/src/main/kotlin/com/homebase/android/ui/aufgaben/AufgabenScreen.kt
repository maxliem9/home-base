@file:OptIn(ExperimentalLayoutApi::class)

package com.homebase.android.ui.aufgaben

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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.homebase.android.R
import com.homebase.android.data.model.RecurrenceDto
import com.homebase.android.data.model.SubtaskDto
import com.homebase.android.data.model.TodoDto
import com.homebase.android.data.model.TodoListDto
import com.homebase.android.data.model.UpdateTodoRequest
import com.homebase.android.ui.components.HbAppBar
import com.homebase.android.ui.components.HbAvatar
import com.homebase.android.ui.components.HbBadge
import com.homebase.android.ui.components.HbTone
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
import com.homebase.android.ui.components.HbPickText
import com.homebase.android.ui.components.HbPill
import com.homebase.android.ui.components.HbPriority
import com.homebase.android.ui.components.HbQuickAdd
import com.homebase.android.ui.components.HbScreenScaffold
import com.homebase.android.ui.components.HbSegmented
import com.homebase.android.ui.components.HbTextField
import com.homebase.android.ui.components.bottomBorder
import com.homebase.android.ui.components.displayName
import com.homebase.android.ui.theme.Hb
import com.homebase.android.ui.theme.HbType
import com.homebase.android.ui.util.Format
import com.homebase.android.ui.components.HbRadiusSm
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

// ---------------------------------------------------------------------------
// Sheet state
// ---------------------------------------------------------------------------

private sealed interface AufgabenSheet {
    /** Edit an existing todo, or create a new one when [todo] is null. */
    data class Edit(val todo: TodoDto?) : AufgabenSheet
    data object NewList : AufgabenSheet
}

@Composable
fun AufgabenScreen(viewModel: TodoViewModel, currentUser: String?, householdUsers: List<String>, onOpenDrawer: () -> Unit) {
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
                    eyebrow = stringResource(R.string.todo_eyebrow),
                    title = if (state.inboxActive) stringResource(R.string.todo_inbox) else state.activeList?.name ?: stringResource(R.string.todo_title),
                    onLeft = onOpenDrawer,
                    actions = { HbIconButton(HbIcons.more, {}) },
                )
            },
            fab = { HbFab(onClick = { sheet = AufgabenSheet.Edit(null) }, label = stringResource(R.string.todo_fab)) },
            onRefresh = { viewModel.refresh() },
        ) {
            // Inbox + list tabs — full-bleed scrollable strip with a bottom hairline.
            ListTabs(
                lists = state.lists,
                todos = state.todos,
                inboxActive = state.inboxActive,
                inboxCount = state.inboxCount,
                activeId = state.activeList?.id,
                onSelect = { viewModel.selectList(it) },
                onNewList = { sheet = AufgabenSheet.NewList },
            )

            Spacer(Modifier.size(18.dp))

            // Quick-add bar. In the Inbox tab the todo is created without a listId (#77).
            Box(Modifier.padding(horizontal = 18.dp)) {
                HbQuickAdd(
                    value = quickAddText,
                    onValueChange = { quickAddText = it },
                    placeholder = if (state.inboxActive) stringResource(R.string.todo_quick_add_inbox) else stringResource(R.string.todo_quick_add),
                    leading = HbIcons.plus,
                    onSubmit = {
                        viewModel.addTodo(quickAddText)
                        quickAddText = ""
                    },
                )
            }

            if (openTodos.isEmpty()) {
                if (state.inboxActive) {
                    HbEmpty(
                        HbIcons.inbox,
                        stringResource(R.string.todo_inbox_empty_title),
                        stringResource(R.string.todo_inbox_empty_hint),
                    )
                } else {
                    HbEmpty(
                        HbIcons.checkCircle,
                        stringResource(R.string.todo_list_empty_title),
                        stringResource(R.string.todo_list_empty_hint),
                    )
                }
            } else {
                // Due-date groups in fixed order, skipping empty ones.
                val grouped = openTodos.groupBy { Format.dueGroup(it.dueDate) }
                Format.DueGroup.entries.sortedBy { it.order }.forEach { group ->
                    val items = grouped[group] ?: return@forEach
                    if (items.isEmpty()) return@forEach

                    GroupLabel(stringResource(group.labelRes), items.size)
                    Column(Modifier.padding(horizontal = 18.dp)) {
                        items.forEach { todo ->
                            TaskRow(
                                todo = todo,
                                // Herkunfts-Liste als Meta: nur im Inbox-Tab für Status-INBOX-Todos,
                                // die schon in einer Liste liegen (#71/#77).
                                listName = if (state.inboxActive) {
                                    todo.listId?.let { lid -> state.lists.firstOrNull { it.id == lid }?.name }
                                } else {
                                    null
                                },
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
                lists = state.lists,
                householdUsers = householdUsers,
                onDismiss = { sheet = null },
                // suspend saves return null on success / the error message on failure, so the
                // sheet stays open and shows the reason inline instead of silently reverting (#277)
                onSaveCreate = { title -> viewModel.createTodo(title).exceptionOrNull()?.message },
                onSaveEdit = { id, req, targetListId -> viewModel.saveTodo(id, req, targetListId).exceptionOrNull()?.message },
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
    inboxActive: Boolean,
    inboxCount: Int,
    activeId: String?,
    onSelect: (String) -> Unit,
    onNewList: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .bottomBorder(Hb.lineSoft),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(18.dp))
        // Built-in Inbox tab before the lists; its badge counts status-INBOX todos (#77).
        ListTab(
            name = stringResource(R.string.todo_inbox),
            count = inboxCount,
            icon = HbIcons.inbox,
            active = inboxActive,
            onClick = { onSelect(INBOX_TAB_ID) },
        )
        lists.forEach { list ->
            val active = !inboxActive && activeId == list.id
            // Exactly the list's own open todos — list-less ones live in the Inbox tab now.
            val count = todos.count { it.listId == list.id && it.status != "DONE" }
            ListTab(
                name = list.name,
                count = count,
                icon = if (list.visibility == "PRIVATE") HbIcons.lock else null,
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
                stringResource(R.string.todo_new_list_tab),
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
    icon: ImageVector?,
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
        if (icon != null) HbIcon(icon, size = 14.dp, tint = if (active) Hb.ink else Hb.ink3)
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
    // Herkunfts-Liste, im Inbox-Tab als Meta gezeigt, damit unverplante Listen-Todos
    // von listen-losen unterscheidbar sind (#71/#77).
    listName: String? = null,
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
                val hasMeta = listName != null || todo.priority != null || badge != null ||
                    todo.recurrence != null || !todo.description.isNullOrBlank()
                if (hasMeta) {
                    Row(
                        Modifier.padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(9.dp),
                    ) {
                        if (listName != null) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                HbIcon(HbIcons.folder, size = 12.dp, tint = Hb.ink3)
                                Text(
                                    listName,
                                    style = HbType.meta,
                                    color = Hb.ink3,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.widthIn(max = 140.dp),
                                )
                            }
                        }
                        HbPriority(todo.priority)
                        badge?.let { HbBadge(it.label, it.tone) }
                        todo.recurrence?.let { HbBadge("↻ ${recurrenceLabel(it)}", HbTone.Neutral) }
                        // recurrenceLabel is @Composable (uses stringResource); see helper below.
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
                        text = stringResource(R.string.todo_plan),
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
                stringResource(R.string.todo_subtasks),
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
                            stringResource(R.string.todo_add_subtask),
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
            Text(stringResource(R.string.todo_done_section).uppercase(), style = HbType.sectionLabel, color = Hb.ink3)
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
    lists: List<TodoListDto>,
    householdUsers: List<String>,
    onDismiss: () -> Unit,
    // Saves are suspending and return null on success / a user-facing error message on failure,
    // so the sheet can stay open and surface the reason inline instead of silently reverting (#277).
    onSaveCreate: suspend (String) -> String?,
    onSaveEdit: suspend (String, UpdateTodoRequest, String?) -> String?,
    onDelete: (String) -> Unit,
) {
    val isEdit = todo != null
    var title by remember { mutableStateOf(todo?.title ?: "") }
    var description by remember { mutableStateOf(todo?.description ?: "") }
    // assignee: null = "Niemand"/unset; otherwise a household username
    var assignee by remember { mutableStateOf(todo?.assignee) }
    // due date as a real LocalDate (null = no date); set via the Material date picker (#265)
    var dueDate by remember { mutableStateOf(Format.parseLocalDate(todo?.dueDate)) }
    var priority by remember { mutableStateOf(todo?.priority) }
    // recurrence: null freq = no repetition; needs a due date as its anchor
    var recurrenceFreq by remember { mutableStateOf(todo?.recurrence?.freq) }
    var intervalText by remember { mutableStateOf((todo?.recurrence?.interval ?: 1).toString()) }
    val recurrenceNeedsDue = recurrenceFreq != null && dueDate == null
    // Listen-Auswahl beim Planen: nur für listen-lose Todos — Listen-Todos behalten ihre
    // Liste (#77, wie der Web-Plan-Dialog). Null = „Bleibt in der Inbox".
    val showListPicker = isEdit && todo?.listId == null && lists.isNotEmpty()
    var targetListId by remember { mutableStateOf<String?>(null) }
    // Save in flight + the last in-sheet save error (#277). On failure the sheet stays open and
    // shows the message; only a successful save dismisses it (mirrors the web plan modal).
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }

    HbBottomSheet(
        onDismiss = onDismiss,
        title = if (isEdit) stringResource(R.string.todo_edit_title) else stringResource(R.string.todo_new_title),
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
            HbButton(text = stringResource(R.string.action_cancel), onClick = onDismiss, variant = HbButtonVariant.Secondary)
            HbButton(
                text = stringResource(R.string.action_save),
                enabled = !recurrenceNeedsDue && !saving && title.isNotBlank(),
                onClick = {
                    if (title.isBlank() || saving) return@HbButton
                    saving = true
                    saveError = null
                    scope.launch {
                        val error = if (isEdit) {
                            val dueIso = dueDate?.toString()
                            onSaveEdit(
                                todo!!.id,
                                UpdateTodoRequest(
                                    title = title.trim(),
                                    description = description.ifBlank { "" },
                                    // "" clears the field on the backend, a value sets it (#265)
                                    assignee = assignee ?: "",
                                    dueDate = dueIso ?: "",
                                    priority = priority ?: "",
                                    status = if (assignee != null || dueIso != null) "PLANNED" else "INBOX",
                                    // "NONE" clears any existing rule; otherwise set/replace it
                                    recurrence = recurrenceFreq
                                        ?.let { RecurrenceDto(it, intervalText.toIntOrNull()?.coerceIn(1, 1000) ?: 1) }
                                        ?: RecurrenceDto("NONE"),
                                ),
                                // target list picked while planning (#77); null = stays in the inbox
                                targetListId,
                            )
                        } else {
                            onSaveCreate(title.trim())
                        }
                        saving = false
                        // null = saved → close; otherwise keep the sheet open and show the reason
                        if (error == null) onDismiss() else saveError = error
                    }
                },
                variant = HbButtonVariant.Primary,
            )
        },
    ) {
        // In-sheet save error (#277): stays until the next save attempt so the user sees why the
        // save did not go through instead of the sheet closing on a stale value.
        saveError?.let { msg ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(HbRadiusSm)
                    .background(Hb.claySoft, HbRadiusSm)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                HbIcon(HbIcons.x, size = 16.dp, tint = Hb.clay)
                Text(msg, style = HbType.small, color = Hb.clay)
            }
        }
        HbField(stringResource(R.string.common_field_title)) {
            HbTextField(value = title, onValueChange = { title = it }, placeholder = stringResource(R.string.common_field_title))
        }
        HbField(stringResource(R.string.common_field_description)) {
            HbTextField(
                value = description,
                onValueChange = { description = it },
                placeholder = stringResource(R.string.common_field_description),
                singleLine = false,
                minLines = 2,
            )
        }
        if (showListPicker) {
            HbField(stringResource(R.string.todo_field_list)) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    HbPickText(
                        stringResource(R.string.todo_stays_in_inbox),
                        active = targetListId == null,
                        onClick = { targetListId = null },
                    )
                    lists.forEach { list ->
                        HbPickText(
                            list.name,
                            active = targetListId == list.id,
                            onClick = { targetListId = list.id },
                        )
                    }
                }
            }
        }
        HbField(stringResource(R.string.todo_field_assignee)) {
            // A current assignee that isn't a household member (legacy free-text) stays
            // shown so it remains selectable and isn't silently dropped on save.
            val chipUsers = assignee
                ?.takeIf { a -> householdUsers.none { it.equals(a, ignoreCase = true) } }
                ?.let { householdUsers + it }
                ?: householdUsers
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                chipUsers.forEach { user ->
                    val active = assignee?.lowercase() == user.lowercase()
                    // tapping the active chip clears the assignee, mirroring the web picker
                    HbPick(active = active, onClick = { assignee = if (active) null else user }) {
                        HbAvatar(user, size = 20.dp)
                        Text(
                            displayName(user),
                            style = HbType.label.copy(fontSize = 13.5.sp),
                            color = if (active) Hb.accentInk else Hb.ink2,
                        )
                    }
                }
                HbPick(active = assignee == null, onClick = { assignee = null }) {
                    Text(
                        stringResource(R.string.todo_assignee_nobody),
                        style = HbType.label.copy(fontSize = 13.5.sp),
                        color = if (assignee == null) Hb.accentInk else Hb.ink2,
                    )
                }
            }
        }
        HbField(stringResource(R.string.todo_field_due)) {
            DueDateField(value = dueDate, onChange = { dueDate = it })
        }
        HbField(stringResource(R.string.todo_field_priority)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                PriorityPick("LOW", stringResource(R.string.priority_low), Hb.prioLow, priority) { priority = it }
                PriorityPick("MEDIUM", stringResource(R.string.priority_medium), Hb.prioMedium, priority) { priority = it }
                PriorityPick("HIGH", stringResource(R.string.priority_high), Hb.prioHigh, priority) { priority = it }
            }
        }
        HbField(stringResource(R.string.todo_field_recurrence)) {
            val freqOptions = listOf<String?>(null, "DAILY", "WEEKLY", "MONTHLY")
            HbSegmented(
                options = listOf(
                    stringResource(R.string.todo_recurrence_none),
                    stringResource(R.string.todo_recurrence_daily),
                    stringResource(R.string.todo_recurrence_weekly),
                    stringResource(R.string.todo_recurrence_monthly),
                ),
                selectedIndex = freqOptions.indexOf(recurrenceFreq).coerceAtLeast(0),
                onSelect = { recurrenceFreq = freqOptions[it] },
            )
            if (recurrenceFreq != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.todo_recurrence_every), style = HbType.label.copy(fontSize = 13.5.sp), color = Hb.ink2)
                    HbTextField(
                        value = intervalText,
                        onValueChange = { intervalText = it.filter(Char::isDigit).take(4) },
                        mono = true,
                        modifier = Modifier.width(64.dp),
                    )
                    Text(
                        when (recurrenceFreq) {
                            "DAILY" -> stringResource(R.string.todo_recurrence_days)
                            "WEEKLY" -> stringResource(R.string.todo_recurrence_weeks)
                            else -> stringResource(R.string.todo_recurrence_months)
                        },
                        style = HbType.label.copy(fontSize = 13.5.sp),
                        color = Hb.ink2,
                    )
                }
                if (dueDate == null) {
                    Text(
                        stringResource(R.string.todo_recurrence_needs_due),
                        style = HbType.small,
                        color = Hb.clay,
                    )
                }
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

/**
 * Optional due-date field (#265): a tappable row that shows the localized date label
 * (e.g. "Heute · 3. Juni") or a "Datum wählen" placeholder, opening a Material date picker.
 * A trailing ✕ clears the date. Mirrors the TimeScreen/Abwesenheit DatePicker pattern
 * (UTC epoch millis ↔ LocalDate, so no timezone drift on the date-only value).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DueDateField(value: LocalDate?, onChange: (LocalDate?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier
                .weight(1f)
                .clip(HbRadiusSm)
                .background(Hb.surface, HbRadiusSm)
                .border(1.dp, Hb.line, HbRadiusSm)
                .clickable { open = true }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            HbIcon(HbIcons.calendar, size = 16.dp, tint = if (value == null) Hb.ink3 else Hb.accentInk)
            Text(
                text = value?.let { Format.dueFieldLabel(it.toString()) } ?: stringResource(R.string.todo_due_pick),
                style = HbType.body.copy(fontSize = 14.5.sp),
                color = if (value == null) Hb.ink3 else Hb.ink,
            )
        }
        if (value != null) {
            HbIconButton(
                HbIcons.x,
                onClick = { onChange(null) },
                iconSize = 18.dp,
                tint = Hb.ink3,
            )
        }
    }
    if (open) {
        val initialMillis = (value ?: LocalDate.now()).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { ms ->
                        onChange(Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate())
                    }
                    open = false
                }) { Text(stringResource(R.string.action_ok)) }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.action_cancel)) } },
        ) {
            DatePicker(state = pickerState)
        }
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
        title = stringResource(R.string.todo_new_list_title),
        footer = {
            HbButton(
                text = stringResource(R.string.action_cancel),
                onClick = onDismiss,
                variant = HbButtonVariant.Secondary,
                modifier = Modifier.weight(1f),
            )
            HbButton(
                text = stringResource(R.string.action_create),
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
        HbField(stringResource(R.string.common_field_name)) {
            HbTextField(value = name, onValueChange = { name = it }, placeholder = stringResource(R.string.todo_list_name_placeholder))
        }
        HbField(stringResource(R.string.todo_field_visibility)) {
            HbSegmented(
                options = listOf(stringResource(R.string.todo_visibility_shared), stringResource(R.string.todo_visibility_private)),
                selectedIndex = segIndex,
                onSelect = { segIndex = it },
                leadingIcons = listOf(HbIcons.users, HbIcons.lock),
            )
            Text(
                stringResource(R.string.todo_visibility_hint),
                style = HbType.small,
                color = Hb.ink3,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/** Compact localized label for a recurrence rule, e.g. "wöchentl."/"weekly" or "alle 2 Wochen". */
@Composable
private fun recurrenceLabel(rec: RecurrenceDto): String {
    val n = rec.interval.coerceAtLeast(1)
    return if (n <= 1) {
        when (rec.freq) {
            "DAILY" -> stringResource(R.string.todo_recurrence_short_daily)
            "WEEKLY" -> stringResource(R.string.todo_recurrence_short_weekly)
            "MONTHLY" -> stringResource(R.string.todo_recurrence_short_monthly)
            else -> rec.freq.lowercase()
        }
    } else {
        val unit = when (rec.freq) {
            "DAILY" -> stringResource(R.string.todo_recurrence_days)
            "WEEKLY" -> stringResource(R.string.todo_recurrence_weeks)
            "MONTHLY" -> stringResource(R.string.todo_recurrence_months)
            else -> ""
        }
        stringResource(R.string.todo_recurrence_short_every, n, unit)
    }
}

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
