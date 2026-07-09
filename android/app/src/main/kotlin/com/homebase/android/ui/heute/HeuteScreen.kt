package com.homebase.android.ui.heute

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import com.homebase.android.data.model.UserForecastDto
import com.homebase.android.ui.aufgaben.TodoViewModel
import com.homebase.android.ui.aufgaben.TodosFocus
import com.homebase.android.ui.aufgaben.isDueToday
import com.homebase.android.ui.aufgaben.isDueTomorrow
import com.homebase.android.ui.aufgaben.isOverdue
import com.homebase.android.ui.aufgaben.recurrenceLabel
import com.homebase.android.ui.components.HbAvatar
import com.homebase.android.ui.components.HbAvatarRow
import com.homebase.android.ui.components.HbAppBar
import com.homebase.android.ui.components.HbBadge
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
import com.homebase.android.ui.components.HbPill
import com.homebase.android.ui.components.HbPriority
import com.homebase.android.ui.components.HbQuickAdd
import com.homebase.android.ui.components.HbRadius
import com.homebase.android.ui.components.HbRoute
import com.homebase.android.ui.components.HbRow
import com.homebase.android.ui.components.HbScreenScaffold
import com.homebase.android.ui.components.HbToast
import com.homebase.android.ui.components.HbTone
import com.homebase.android.ui.components.displayName
import com.homebase.android.ui.shopping.ShoppingViewModel
import com.homebase.android.ui.theme.Hb
import com.homebase.android.ui.theme.HbType
import com.homebase.android.ui.time.TimeViewModel
import com.homebase.android.ui.time.liveExtraSeconds
import com.homebase.android.ui.time.withLiveExtra
import com.homebase.android.ui.util.Format
import kotlinx.coroutines.delay
import java.time.Instant
import kotlin.math.roundToInt

@Composable
fun HeuteScreen(
    todoVm: TodoViewModel,
    shoppingVm: ShoppingViewModel,
    timeVm: TimeViewModel,
    currentUser: String?,
    onOpenDrawer: () -> Unit,
    onNavigate: (HbRoute) -> Unit,
    // Stat tiles deep-link into the matching cross-list tasks view (#255/#256). Distinct from
    // onNavigate (which lands on the default tab) — this also selects the tile's tab.
    onOpenTodos: (TodosFocus) -> Unit,
) {
    val todoState by todoVm.uiState.collectAsStateWithLifecycle()
    val shoppingState by shoppingVm.uiState.collectAsStateWithLifecycle()
    val timeState by timeVm.uiState.collectAsStateWithLifecycle()

    var value by remember { mutableStateOf("") }
    // Cross-person action awaiting confirmation (stopping the partner's timer).
    var pendingConfirm by remember { mutableStateOf<HbConfirm?>(null) }

    // --- Derived counts / lists ---
    // The four stat tiles share their rules with the cross-list smart-view tabs (#256), so the
    // counts here and on the matching tab agree by construction — reuse the same predicates/state.
    val dueTodayCount = todoState.todos.count(::isDueToday)
    // Same rule as the Inbox-tab badge in AufgabenScreen (TodoUiState.inboxCount, #71/#77).
    val inboxCount = todoState.inboxCount
    val overdueCount = todoState.todos.count(::isOverdue)
    val dueTomorrowCount = todoState.todos.count(::isDueTomorrow)

    // "Heute dran" (#307): overdue (due date strictly before today, not done) belong here too —
    // they're still things to do today. Overdue first (DueGroup.order: overdue=0, today=1), each
    // sub-group oldest due date first. The stat tile above stays today-only.
    val todayAndOverdue = todoState.todos
        .filter {
            it.status != "DONE" &&
                Format.dueGroup(it.dueDate).let { g -> g == Format.DueGroup.UEBERFAELLIG || g == Format.DueGroup.HEUTE }
        }
        .sortedWith(compareBy({ Format.dueGroup(it.dueDate).order }, { it.dueDate ?: "" }))
    val heuteDran: List<TodoDto> = (
        todayAndOverdue.ifEmpty { todoState.todos.filter { it.status != "DONE" } }
        ).take(3)

    val openShopping = shoppingState.items.filter { !it.checked }
    // Peek count matches the web dashboard's 5-item shopping peek (#498).
    val shoppingShown = openShopping.take(5)

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
                    StatCard(HbIcons.inbox, inboxCount.toString(), stringResource(R.string.dashboard_stat_inbox), Modifier.weight(1f), onClick = { onOpenTodos(TodosFocus.INBOX) })
                    StatCard(HbIcons.flag, overdueCount.toString(), stringResource(R.string.dashboard_stat_overdue), Modifier.weight(1f), onClick = { onOpenTodos(TodosFocus.OVERDUE) })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatCard(HbIcons.calendar, dueTodayCount.toString(), stringResource(R.string.dashboard_stat_due_today), Modifier.weight(1f), onClick = { onOpenTodos(TodosFocus.TODAY) })
                    StatCard(HbIcons.clock, dueTomorrowCount.toString(), stringResource(R.string.dashboard_stat_due_tomorrow), Modifier.weight(1f), onClick = { onOpenTodos(TodosFocus.TOMORROW) })
                }
            }

            Spacer(Modifier.size(18.dp))

            // "Heute dran"
            HbCard(Modifier.padding(horizontal = 18.dp)) {
                Column {
                    HbCardHead(
                        stringResource(R.string.dashboard_today_card),
                        linkText = stringResource(R.string.dashboard_all_tasks),
                        // "Alle Aufgaben" opens the cross-list "Alle" smart view (#256), mirroring web.
                        onLink = { onOpenTodos(TodosFocus.ALL) },
                    )
                    if (heuteDran.isEmpty()) {
                        Text(stringResource(R.string.dashboard_nothing_today), style = HbType.meta, color = Hb.ink3)
                    } else {
                        Column {
                            heuteDran.forEachIndexed { index, todo ->
                                val isOverdue = Format.dueGroup(todo.dueDate) == Format.DueGroup.UEBERFAELLIG
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
                                        // Overdue items stay recognizable here (#307) — same "Überfällig"
                                        // marker the web dashboard uses. Recurring todos get a repeat
                                        // badge too, mirroring the web dashboard's recurring indicator (#498).
                                        if (isOverdue || todo.priority != null || todo.recurrence != null) {
                                            Row(
                                                Modifier.padding(top = 4.dp),
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                if (isOverdue) {
                                                    HbBadge(stringResource(R.string.due_group_overdue), HbTone.Over)
                                                }
                                                todo.priority?.let { HbPriority(it) }
                                                // Same "↻ <freq>" badge the AufgabenScreen rows use.
                                                todo.recurrence?.let { HbBadge("↻ ${recurrenceLabel(it)}", HbTone.Neutral) }
                                            }
                                        }
                                    }
                                    HbAvatarRow(todo.assignees)
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

            // "Wochensoll" — weekly work-target peek (HB-10/#31); only when a target is
            // configured for the current user. Mirrors the web DashboardView card (#498).
            timeState.forecastFor(currentUser)?.let { myForecast ->
                Spacer(Modifier.size(16.dp))
                WorkTargetCard(
                    forecast = myForecast,
                    ownRunning = timeState.running,
                    forecastAt = timeState.forecastAt,
                    onOpen = { onNavigate(HbRoute.ZEIT) },
                )
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

        // Global todo error toast (#288): the dashboard quick-add and "Heute dran" toggle-done are
        // fire-and-forget on the shared TodoViewModel and set state.error on failure — surface it the
        // same way AufgabenScreen/AbwesenheitScreen do, otherwise these mutations fail silently here.
        todoState.error?.let { msg ->
            HbToast(message = msg, icon = HbIcons.x, actionLabel = stringResource(R.string.action_ok), onAction = { todoVm.clearError() })
        }
    }
}

// ---------------------------------------------------------------------------
// Stat card (mirrors .hb-stat)
// ---------------------------------------------------------------------------

@Composable
private fun StatCard(icon: ImageVector, value: String, label: String, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    Box(
        modifier
            .shadow(1.dp, HbRadius, clip = false, ambientColor = Hb.ink, spotColor = Hb.ink)
            .clip(HbRadius)
            .background(Hb.surface)
            .border(1.dp, Hb.lineSoft, HbRadius)
            // Tappable deep-link into the matching tasks view (#255/#256); clip-then-clickable so
            // the ripple is bounded to the card's rounded shape.
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
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
// Weekly work-target peek (HB-10/#31) — mirrors web .hb-worktarget (#498)
// ---------------------------------------------------------------------------

@Composable
private fun WorkTargetCard(
    forecast: UserForecastDto,
    ownRunning: TimeEntryDto?,
    forecastAt: Instant?,
    onOpen: () -> Unit,
) {
    // While the own timer runs, tick the snapshot figures up live (#64/#59 parity):
    // re-read "now" each second and add the seconds since the forecast fetch.
    val hasOwnRunning = ownRunning != null
    val now by produceState(Instant.now(), hasOwnRunning, forecastAt) {
        while (hasOwnRunning) {
            value = Instant.now()
            delay(1000)
        }
    }
    val extra = if (hasOwnRunning) liveExtraSeconds(forecastAt, now) else 0L
    val u = forecast.withLiveExtra(extra, ownRunning?.projectId)

    val weekDone = u.weekRecordedSeconds + u.weekCreditedSeconds
    val pct = if (u.weekTargetSeconds > 0)
        ((weekDone.toDouble() / u.weekTargetSeconds) * 100).roundToInt().coerceIn(0, 100)
    else 0
    val frac = pct / 100f
    // Recompute today's remainder like the web card does (target − recorded, live-ticked).
    val todayLeft = u.todayTargetSeconds - u.todayRecordedSeconds

    HbCard(Modifier.padding(horizontal = 18.dp)) {
        Column {
            HbCardHead(
                stringResource(R.string.dashboard_worktarget_title),
                linkText = stringResource(R.string.dashboard_open),
                onLink = onOpen,
            )
            // Soll/Ist row: "2 Std 5 Min / 40 Std" + percentage
            Row(
                Modifier.fillMaxWidth().padding(bottom = 9.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        Format.durationLong(weekDone),
                        style = HbType.rowTitle.copy(fontWeight = FontWeight.SemiBold),
                        color = Hb.ink,
                    )
                    Text(
                        " / ${trimHours(u.weeklyTargetHours)} ${stringResource(R.string.dashboard_worktarget_hours)}",
                        style = HbType.meta.copy(fontWeight = FontWeight.Medium),
                        color = Hb.ink3,
                    )
                }
                Text(
                    "$pct%",
                    style = HbType.mono.copy(fontWeight = FontWeight.Bold, fontSize = 13.sp),
                    color = Hb.accentInk,
                )
            }
            // progress bar (.hb-worktarget__bar)
            Box(
                Modifier.fillMaxWidth().height(8.dp).clip(HbPill).background(Hb.surface2, HbPill),
            ) {
                if (frac > 0f) {
                    Box(Modifier.fillMaxWidth(frac).fillMaxHeight().clip(HbPill).background(Hb.accent))
                }
            }
            // today's redistributed target line
            Text(
                if (todayLeft <= 0) stringResource(R.string.dashboard_worktarget_today_reached)
                else stringResource(R.string.dashboard_worktarget_today_left, Format.durationLong(todayLeft)),
                style = HbType.meta,
                color = Hb.ink3,
                modifier = Modifier.padding(top = 9.dp),
            )
        }
    }
}

/** "40" / "38.5" — weekly hours without a trailing ".0". */
private fun trimHours(hours: Double): String =
    if (hours % 1.0 == 0.0) hours.toInt().toString() else hours.toString()

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
