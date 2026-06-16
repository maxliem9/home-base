package com.homebase.android.ui.heute

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.homebase.android.R
import com.homebase.android.data.model.ShoppingItemDto
import com.homebase.android.data.model.TimeEntryDto
import com.homebase.android.data.model.TodoDto
import com.homebase.android.ui.aufgaben.TodoViewModel
import com.homebase.android.ui.components.HbAvatar
import com.homebase.android.ui.components.HbAppBar
import com.homebase.android.ui.components.HbButton
import com.homebase.android.ui.components.HbButtonSize
import com.homebase.android.ui.components.HbButtonVariant
import com.homebase.android.ui.components.HbCard
import com.homebase.android.ui.components.HbCardHead
import com.homebase.android.ui.components.HbCheck
import com.homebase.android.ui.components.HbConfirm
import com.homebase.android.ui.components.HbConfirmDialog
import com.homebase.android.ui.components.HbIcon
import com.homebase.android.ui.components.HbIconButton
import com.homebase.android.ui.components.HbIcons
import com.homebase.android.ui.components.HbPriority
import com.homebase.android.ui.components.HbQuickAdd
import com.homebase.android.ui.components.HbRadius
import com.homebase.android.ui.components.HbRoute
import com.homebase.android.ui.components.HbRow
import com.homebase.android.ui.components.HbScreenScaffold
import com.homebase.android.ui.components.displayName
import com.homebase.android.ui.shopping.ShoppingViewModel
import com.homebase.android.ui.theme.Hb
import com.homebase.android.ui.theme.HbType
import com.homebase.android.ui.time.TimeViewModel
import com.homebase.android.ui.util.Format
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.ZoneId

@Composable
fun HeuteScreen(
    todoVm: TodoViewModel,
    shoppingVm: ShoppingViewModel,
    timeVm: TimeViewModel,
    currentUser: String?,
    onOpenDrawer: () -> Unit,
    onNavigate: (HbRoute) -> Unit,
) {
    val todoState by todoVm.uiState.collectAsStateWithLifecycle()
    val shoppingState by shoppingVm.uiState.collectAsStateWithLifecycle()
    val timeState by timeVm.uiState.collectAsStateWithLifecycle()

    val today = LocalDate.now()
    var value by remember { mutableStateOf("") }
    // Cross-person action awaiting confirmation (stopping the partner's timer).
    var pendingConfirm by remember { mutableStateOf<HbConfirm?>(null) }

    // --- Derived counts / lists ---
    val dueTodayCount = todoState.todos.count {
        it.status != "DONE" && Format.dueGroup(it.dueDate) == Format.DueGroup.HEUTE
    }
    // Same rule as the Inbox-tab badge in AufgabenScreen (TodoUiState.inboxCount, #71/#77).
    val inboxCount = todoState.inboxCount
    val tomorrow = today.plusDays(1)
    val dueTomorrowCount = todoState.todos.count {
        it.status != "DONE" && Format.parseLocalDate(it.dueDate) == tomorrow
    }
    val doneTodayCount = todoState.todos.count {
        it.status == "DONE" && doneLocalDate(it.doneAt) == today
    }

    val dueTodayTodos = todoState.todos.filter {
        it.status != "DONE" && Format.dueGroup(it.dueDate) == Format.DueGroup.HEUTE
    }
    val heuteDran: List<TodoDto> = (
        dueTodayTodos.ifEmpty { todoState.todos.filter { it.status != "DONE" } }
        ).take(3)

    val openShopping = shoppingState.items.filter { !it.checked }
    val shoppingShown = openShopping.take(4)

    Box(Modifier.fillMaxSize()) {
        HbScreenScaffold(
            appBar = {
                HbAppBar(
                    title = "",
                    onLeft = onOpenDrawer,
                    leftIcon = HbIcons.menu,
                    actions = {
                        HbIconButton(HbIcons.search, {})
                        HbIconButton(HbIcons.bell, {})
                    },
                )
            },
        ) {
            // Eyebrow date
            Text(
                Format.longWeekdayDate(),
                style = HbType.eyebrow,
                color = Hb.ink3,
                modifier = Modifier.padding(horizontal = 18.dp).padding(start = 2.dp),
            )

            // Greeting (two lines). The greeting word is localized here (not via the German-only
            // Format.greeting) so it follows the in-app language; the time-of-day buckets mirror it.
            val greetingWord = when (java.time.LocalTime.now().hour) {
                in 5..10 -> stringResource(R.string.greeting_morning)
                in 11..17 -> stringResource(R.string.greeting_day)
                else -> stringResource(R.string.greeting_evening)
            }
            Text(
                stringResource(R.string.dashboard_greeting, greetingWord, displayName(currentUser)),
                style = HbType.greeting,
                color = Hb.ink,
                modifier = Modifier.padding(horizontal = 18.dp).padding(top = 6.dp, bottom = 18.dp),
            )

            // Quick-add pill
            HbQuickAdd(
                value = value,
                onValueChange = { value = it },
                onSubmit = {
                    if (value.isNotBlank()) {
                        todoVm.addTodo(value)
                        value = ""
                    }
                },
                placeholder = stringResource(R.string.dashboard_quick_add),
                leading = HbIcons.sparkle,
                modifier = Modifier.padding(horizontal = 18.dp),
            )

            Spacer(Modifier.size(20.dp))

            // Quick stats — 2×2 grid
            Column(
                Modifier.padding(horizontal = 18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatCard(HbIcons.calendar, dueTodayCount.toString(), stringResource(R.string.dashboard_stat_due_today), Modifier.weight(1f))
                    StatCard(HbIcons.inbox, inboxCount.toString(), stringResource(R.string.dashboard_stat_inbox), Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatCard(HbIcons.clock, dueTomorrowCount.toString(), stringResource(R.string.dashboard_stat_due_tomorrow), Modifier.weight(1f))
                    StatCard(HbIcons.checkCircle, doneTodayCount.toString(), stringResource(R.string.dashboard_stat_done_today), Modifier.weight(1f))
                }
            }

            Spacer(Modifier.size(18.dp))

            // "Heute dran"
            HbCard(Modifier.padding(horizontal = 18.dp)) {
                Column {
                    HbCardHead(
                        stringResource(R.string.dashboard_today_card),
                        linkText = stringResource(R.string.dashboard_all_tasks),
                        onLink = { onNavigate(HbRoute.AUFGABEN) },
                    )
                    if (heuteDran.isEmpty()) {
                        Text(stringResource(R.string.dashboard_nothing_today), style = HbType.meta, color = Hb.ink3)
                    } else {
                        Column {
                            heuteDran.forEachIndexed { index, todo ->
                                HbRow(divider = index < heuteDran.lastIndex) {
                                    HbCheck(
                                        checked = todo.status == "DONE",
                                        onCheckedChange = { todoVm.toggleDone(todo) },
                                    )
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            todo.title,
                                            style = HbType.rowTitle,
                                            color = Hb.ink,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        if (todo.priority != null) {
                                            HbPriority(todo.priority, Modifier.padding(top = 4.dp))
                                        }
                                    }
                                    HbAvatar(todo.assignee)
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.size(16.dp))

            // "Zeiterfassung"
            HbCard(Modifier.padding(horizontal = 18.dp)) {
                Column {
                    HbCardHead(stringResource(R.string.dashboard_time_card), linkText = stringResource(R.string.dashboard_open), onLink = { onNavigate(HbRoute.ZEIT) })
                    val ownRunning = timeState.running
                    val othersRunning = timeState.othersRunning
                    if (ownRunning == null && othersRunning.isEmpty()) {
                        Text(stringResource(R.string.dashboard_no_timer), style = HbType.meta, color = Hb.ink3)
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            // own timer first
                            if (ownRunning != null) {
                                val project = timeState.projects.firstOrNull { it.id == ownRunning.projectId }
                                Column {
                                    RunWidget(
                                        running = ownRunning,
                                        projectName = project?.name,
                                        projectColor = project?.color,
                                        // forecast peek (#31/#55): "bis ca. 16:32" / "Soll erreicht"
                                        eta = timeState.forecastFor(ownRunning.userId)?.expectedEndAt,
                                    )
                                    HbButton(
                                        stringResource(R.string.dashboard_stop),
                                        { timeVm.stopTimer() },
                                        modifier = Modifier.padding(top = 14.dp),
                                        variant = HbButtonVariant.Soft,
                                        size = HbButtonSize.Sm,
                                        icon = HbIcons.stop,
                                    )
                                }
                            }
                            // partner's running timer(s) — see & stop
                            othersRunning.forEach { entry ->
                                val project = timeState.projects.firstOrNull { it.id == entry.projectId }
                                val stopPartnerMsg = stringResource(R.string.confirm_stop_partner_timer, displayName(entry.userId))
                                Column {
                                    RunWidget(
                                        running = entry,
                                        projectName = project?.name,
                                        projectColor = project?.color,
                                        owner = entry.userId,
                                        eta = timeState.forecastFor(entry.userId)?.expectedEndAt,
                                    )
                                    HbButton(
                                        stringResource(R.string.dashboard_stop),
                                        { pendingConfirm = HbConfirm(stopPartnerMsg) { timeVm.stopTimer(entry.userId) } },
                                        modifier = Modifier.padding(top = 14.dp),
                                        variant = HbButtonVariant.Soft,
                                        size = HbButtonSize.Sm,
                                        icon = HbIcons.stop,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.size(16.dp))

            // "Einkaufsliste"
            HbCard(Modifier.padding(horizontal = 18.dp)) {
                Column {
                    HbCardHead(stringResource(R.string.dashboard_shopping_card), linkText = stringResource(R.string.dashboard_open), onLink = { onNavigate(HbRoute.EINKAUF) })
                    if (shoppingShown.isEmpty()) {
                        Text(stringResource(R.string.dashboard_list_empty), style = HbType.meta, color = Hb.ink3)
                    } else {
                        Column {
                            shoppingShown.forEachIndexed { index, item ->
                                ShopRow(item = item, divider = index < shoppingShown.lastIndex, onToggle = { shoppingVm.toggleChecked(item) })
                            }
                        }
                        val remaining = openShopping.size - shoppingShown.size
                        if (remaining > 0) {
                            Text(
                                stringResource(R.string.dashboard_more_items, remaining),
                                style = HbType.meta.copy(fontWeight = FontWeight.SemiBold),
                                color = Hb.ink3,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 12.dp),
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
        }

        pendingConfirm?.let { c ->
            HbConfirmDialog(
                message = c.message,
                onConfirm = { c.onConfirm(); pendingConfirm = null },
                onDismiss = { pendingConfirm = null },
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Stat card (mirrors .hb-stat)
// ---------------------------------------------------------------------------

@Composable
private fun StatCard(icon: ImageVector, value: String, label: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .shadow(1.dp, HbRadius, clip = false, ambientColor = Hb.ink, spotColor = Hb.ink)
            .clip(HbRadius)
            .background(Hb.surface)
            .border(1.dp, Hb.lineSoft, HbRadius)
            .padding(horizontal = 16.dp, vertical = 15.dp),
    ) {
        Column {
            Box(
                Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(Hb.accentSoft, RoundedCornerShape(9.dp)),
                contentAlignment = Alignment.Center,
            ) { HbIcon(icon, size = 19.dp, tint = Hb.accentInk) }
            Text(
                value,
                style = HbType.cardTitle.copy(
                    fontSize = 30.sp,
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = 30.sp,
                    letterSpacing = (-0.02).em,
                ),
                color = Hb.ink,
                modifier = Modifier.padding(top = 9.dp),
            )
            Text(
                label,
                style = HbType.meta.copy(fontWeight = FontWeight.Medium),
                color = Hb.ink3,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Running-timer widget (mirrors .hb-runwidget)
// ---------------------------------------------------------------------------

@Composable
private fun RunWidget(
    running: TimeEntryDto,
    projectName: String?,
    projectColor: String?,
    owner: String? = null,
    eta: String? = null,
) {
    val elapsed by produceState(Format.elapsedSeconds(running.startedAt), running.startedAt) {
        while (true) {
            value = Format.elapsedSeconds(running.startedAt)
            delay(1000)
        }
    }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // For a partner's timer, lead with their avatar so whose timer it is reads at a glance.
        if (owner != null) HbAvatar(owner, size = 22.dp)
        Box(
            Modifier
                .size(12.dp)
                .clip(RoundedCornerShape(percent = 50))
                .background(if (projectColor != null) Format.parseColor(projectColor) else Hb.ink3),
        )
        Column(Modifier.weight(1f)) {
            Text(
                projectName ?: stringResource(R.string.time_project_fallback),
                style = HbType.rowTitle.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                color = Hb.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // partner row → their name (+ description); own row → description only;
            // both get the compact forecast suffix "· bis ca. HH:MM" / "· Soll erreicht" (#31/#55)
            val subtitle = listOfNotNull(
                owner?.let { displayName(it) },
                running.description?.takeIf { it.isNotBlank() },
                Format.etaShortLabel(eta),
            ).joinToString(" · ").takeIf { it.isNotEmpty() }
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    style = HbType.meta,
                    color = Hb.ink3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(Format.clock(elapsed), style = HbType.mono(20.0), color = Hb.ink)
    }
}

// ---------------------------------------------------------------------------
// Shopping mini row
// ---------------------------------------------------------------------------

@Composable
private fun ShopRow(item: ShoppingItemDto, divider: Boolean, onToggle: () -> Unit) {
    HbRow(divider = divider) {
        HbCheck(checked = false, onCheckedChange = onToggle)
        Text(
            item.name,
            style = HbType.rowTitle,
            color = Hb.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        HbAvatar(item.createdBy, size = 24.dp)
    }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

private fun doneLocalDate(doneAt: String?): LocalDate? =
    Format.parseInstant(doneAt)?.atZone(ZoneId.systemDefault())?.toLocalDate()
