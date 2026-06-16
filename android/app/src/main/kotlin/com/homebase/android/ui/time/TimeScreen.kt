package com.homebase.android.ui.time

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.homebase.android.R
import com.homebase.android.data.model.ProjectDto
import com.homebase.android.data.model.TimeEntryDto
import com.homebase.android.data.model.UpdateTimeEntryRequest
import com.homebase.android.data.model.UserForecastDto
import com.homebase.android.data.model.WorkTargetDto
import com.homebase.android.ui.components.HbAvatar
import com.homebase.android.ui.components.LocalAvatarHues
import com.homebase.android.ui.components.HbAppBar
import com.homebase.android.ui.components.HbBottomSheet
import com.homebase.android.ui.components.HbButton
import com.homebase.android.ui.components.HbButtonSize
import com.homebase.android.ui.components.HbConfirm
import com.homebase.android.ui.components.HbConfirmDialog
import com.homebase.android.ui.components.HbButtonVariant
import com.homebase.android.ui.components.HbField
import com.homebase.android.ui.components.HbIcon
import com.homebase.android.ui.components.HbIconButton
import com.homebase.android.ui.components.HbIcons
import com.homebase.android.ui.components.HbPill
import com.homebase.android.ui.components.HbRadius
import com.homebase.android.ui.components.HbRadiusSm
import com.homebase.android.ui.components.HbScreenScaffold
import com.homebase.android.ui.components.HbFab
import com.homebase.android.ui.components.HbTextField
import com.homebase.android.ui.components.HbToast
import com.homebase.android.ui.components.displayName
import com.homebase.android.ui.theme.Hb
import com.homebase.android.ui.theme.HbType
import com.homebase.android.ui.util.Format
import kotlinx.coroutines.delay
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.roundToLong

// ---------------------------------------------------------------------------
// Time tracking (Zeiterfassung) — running timer, projects grid, recent entries,
// project-detail sheet and new-project sheet. Mirrors the original design's
// .hb-timerhero/.hb-projcard/.hb-weekbar tokens.
// ---------------------------------------------------------------------------

@Composable
fun TimeScreen(
    viewModel: TimeViewModel,
    currentUser: String?,
    onOpenDrawer: () -> Unit,
    // The Einstellungen → Zeiterfassung subpage shares this TimeViewModel and renders the
    // same state.error toast. While that overlay sits on top of us (route == ZEIT), suppress
    // the tracker's copy so one error never shows twice — the visible settings toast owns it
    // and stays the single OK target (#193).
    settingsOpen: Boolean = false,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    var showNewProject by remember { mutableStateOf(false) }
    var showRecordEntry by remember { mutableStateOf(false) }
    var detailProjectId by remember { mutableStateOf<String?>(null) }
    var editEntry by remember { mutableStateOf<TimeEntryDto?>(null) }
    var splitEntry by remember { mutableStateOf<TimeEntryDto?>(null) }
    // Cross-person action awaiting confirmation (partner's timer or entry, #140).
    var pendingConfirm by remember { mutableStateOf<HbConfirm?>(null) }

    // Edit/split/delete are offered on BOTH members' entries — the household manages
    // them together; a click on the partner's entry confirms first via the dialog
    // (#129/#140, web parity). `{name}` is the entry owner. Acting on own entries is
    // immediate. A null currentUser (username not yet known) can't tell own from
    // partner, so it confirms defensively rather than acting silently.
    // Confirm-message templates captured here (composable scope) so the request* helpers,
    // which run from non-composable click lambdas, can format them with the entry owner.
    val confirmEditTpl = stringResource(R.string.confirm_edit_partner_entry)
    val confirmSplitTpl = stringResource(R.string.confirm_split_partner_entry)
    val confirmDeleteTpl = stringResource(R.string.confirm_delete_partner_entry)
    fun isPartnerEntry(entry: TimeEntryDto) = currentUser == null || entry.userId != currentUser
    fun requestEdit(entry: TimeEntryDto) {
        if (isPartnerEntry(entry)) {
            pendingConfirm = HbConfirm(confirmEditTpl.format(displayName(entry.userId))) { editEntry = entry }
        } else editEntry = entry
    }
    fun requestSplit(entry: TimeEntryDto) {
        if (isPartnerEntry(entry)) {
            pendingConfirm = HbConfirm(confirmSplitTpl.format(displayName(entry.userId))) { splitEntry = entry }
        } else splitEntry = entry
    }
    fun requestDelete(entry: TimeEntryDto) {
        if (isPartnerEntry(entry)) {
            pendingConfirm = HbConfirm(confirmDeleteTpl.format(displayName(entry.userId))) { viewModel.deleteEntry(entry.id) }
        } else viewModel.deleteEntry(entry.id)
    }

    val projectsById = remember(state.projects) { state.projects.associateBy { it.id } }
    val entriesByProject = remember(state.entries) { state.entries.groupBy { it.projectId } }

    // Active projects first, then archived (shown only when the archive toggle is on).
    var showArchived by remember { mutableStateOf(false) }
    val gridProjects = remember(state.projects, showArchived) {
        val active = state.projects.filter { !it.archived }
        val archived = state.projects.filter { it.archived }
        if (showArchived) active + archived else active
    }

    val detailProject = detailProjectId?.let { projectsById[it] }

    Box(Modifier.fillMaxSize()) {
        HbScreenScaffold(
            appBar = {
                HbAppBar(
                    eyebrow = stringResource(R.string.time_eyebrow),
                    title = stringResource(R.string.time_title),
                    onLeft = onOpenDrawer,
                    actions = {
                        // "Eintrag erfassen" — manual entry, also for the partner (web parity #140).
                        HbIconButton(HbIcons.calendar, { showRecordEntry = true }, tint = Hb.ink2)
                    },
                )
            },
            fab = { HbFab(onClick = { showNewProject = true }, label = stringResource(R.string.time_fab)) },
        ) {
            // --- Timer hero ---
            Box(Modifier.padding(horizontal = 18.dp)) {
                val running = state.running
                if (running != null) {
                    RunningHero(
                        running = running,
                        project = projectsById[running.projectId],
                        // forecast ETA (#31/#55) — only with a configured Wochensoll
                        eta = state.forecastFor(running.userId)?.expectedEndAt,
                        onStop = { viewModel.stopTimer() },
                        onEdit = { editEntry = running },
                    )
                } else {
                    IdleHero()
                }
            }

            // --- Partner strip: the other member's timer — see & stop, or start for them ---
            val others = remember(state.users, currentUser) { state.users.filter { it != currentUser } }
            if (others.isNotEmpty()) {
                Spacer(Modifier.size(10.dp))
                Column(
                    Modifier.padding(horizontal = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    others.forEach { user ->
                        val stopMsg = stringResource(R.string.confirm_stop_partner_timer, displayName(user))
                        val startMsg = stringResource(R.string.confirm_start_partner_timer, displayName(user))
                        PartnerTimerCard(
                            user = user,
                            running = state.othersRunning.firstOrNull { it.userId == user },
                            projectsById = projectsById,
                            projects = state.activeProjects,
                            eta = state.forecastFor(user)?.expectedEndAt,
                            onStop = { pendingConfirm = HbConfirm(stopMsg) { viewModel.stopTimer(user) } },
                            onStart = { pid -> pendingConfirm = HbConfirm(startMsg) { viewModel.startTimer(pid, null, user) } },
                        )
                    }
                }
            }

            // --- Wochensoll (#31/#55): per-person week balance — only once a target exists ---
            if (state.weekUsers.isNotEmpty()) {
                Spacer(Modifier.size(12.dp))
                // all open entries (own + partner) — running timers tick the snapshot live (#64)
                val openEntries = remember(state.entries) { state.entries.filter { it.stoppedAt == null } }
                WeekTargetsCard(
                    users = state.weekUsers,
                    projectsById = projectsById,
                    openEntries = openEntries,
                    forecastAt = state.forecastAt,
                    modifier = Modifier.padding(horizontal = 18.dp),
                )
            }

            Spacer(Modifier.size(22.dp))

            // --- Projekte ---
            Row(
                Modifier.padding(horizontal = 18.dp).fillMaxWidth().padding(bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(stringResource(R.string.time_section_projects).uppercase(), style = HbType.sectionLabel, color = Hb.ink3)
                Row(
                    Modifier
                        .clip(HbRadiusSm)
                        .clickable { showArchived = !showArchived }
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        if (showArchived) stringResource(R.string.time_show_active) else stringResource(R.string.time_show_archive),
                        style = HbType.meta.copy(fontWeight = FontWeight.SemiBold),
                        color = Hb.ink3,
                    )
                    HbIcon(HbIcons.chevronRight, size = 14.dp, tint = Hb.ink3)
                }
            }

            if (gridProjects.isEmpty()) {
                Text(
                    stringResource(R.string.time_no_projects_hint),
                    style = HbType.meta,
                    color = Hb.ink3,
                    modifier = Modifier.padding(horizontal = 18.dp),
                )
            } else {
                Column(
                    Modifier.padding(horizontal = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    gridProjects.chunked(2).forEach { rowProjects ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            rowProjects.forEach { project ->
                                ProjectCard(
                                    project = project,
                                    entries = entriesByProject[project.id].orEmpty(),
                                    isRunning = state.running?.projectId == project.id,
                                    onStart = { viewModel.startTimer(project.id, null) },
                                    onStop = { viewModel.stopTimer() },
                                    onOpen = { detailProjectId = project.id },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            // Pad an odd final row so the single card keeps half-width.
                            if (rowProjects.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }

            Spacer(Modifier.size(26.dp))

            // --- Letzte Einträge ---
            Text(
                stringResource(R.string.time_section_recent).uppercase(),
                style = HbType.sectionLabel,
                color = Hb.ink3,
                modifier = Modifier.padding(horizontal = 18.dp).padding(start = 2.dp),
            )

            val recent = remember(state.entries) {
                state.entries.filter { it.stoppedAt != null }.sortedByDescending { it.startedAt }
            }
            if (recent.isEmpty()) {
                Text(
                    stringResource(R.string.time_no_entries),
                    style = HbType.meta,
                    color = Hb.ink3,
                    modifier = Modifier.padding(horizontal = 18.dp).padding(top = 12.dp),
                )
            } else {
                EntriesByDay(
                    entries = recent,
                    projectsById = projectsById,
                    showProjectName = true,
                    onDelete = { requestDelete(it) },
                    onEdit = { requestEdit(it) },
                    onSplit = { requestSplit(it) },
                    modifier = Modifier.padding(horizontal = 18.dp),
                )
            }
        }

        // --- Sheets ---
        if (showNewProject) {
            NewProjectSheet(
                onDismiss = { showNewProject = false },
                onCreate = { name, hex ->
                    viewModel.addProject(name, hex)
                    showNewProject = false
                },
            )
        }

        if (showRecordEntry) {
            val confirmRecordTpl = stringResource(R.string.confirm_record_for_partner)
            RecordEntrySheet(
                projects = state.activeProjects,
                users = state.users,
                currentUser = currentUser,
                onDismiss = { showRecordEntry = false },
                onCreate = { projectId, startedAt, stoppedAt, description, forUser ->
                    // Only send userId when it differs from the caller; a partner target
                    // confirms first (web parity #129/#140) and commits on confirm.
                    val partner = forUser?.takeIf { currentUser != null && it != currentUser }
                    if (partner != null) {
                        pendingConfirm = HbConfirm(confirmRecordTpl.format(displayName(partner))) {
                            viewModel.addManualEntry(projectId, startedAt, stoppedAt, description, partner)
                            showRecordEntry = false
                        }
                    } else {
                        viewModel.addManualEntry(projectId, startedAt, stoppedAt, description, null)
                        showRecordEntry = false
                    }
                },
            )
        }

        if (detailProject != null) {
            ProjectDetailSheet(
                project = detailProject,
                entries = entriesByProject[detailProject.id].orEmpty(),
                isRunning = state.running?.projectId == detailProject.id,
                onDelete = { requestDelete(it) },
                onEdit = { requestEdit(it) },
                onSplit = { requestSplit(it) },
                onDismiss = { detailProjectId = null },
            )
        }

        editEntry?.let { entry ->
            EditEntrySheet(
                entry = entry,
                project = projectsById[entry.projectId],
                activeProjects = state.activeProjects,
                onSave = { request ->
                    viewModel.updateEntry(entry.id, request)
                    editEntry = null
                },
                onDismiss = { editEntry = null },
            )
        }

        splitEntry?.let { entry ->
            SplitEntrySheet(
                entry = entry,
                onSave = { splitAt, breakMinutes ->
                    viewModel.splitEntry(entry.id, splitAt, breakMinutes)
                    splitEntry = null
                },
                onDismiss = { splitEntry = null },
            )
        }

        // Suppressed while the settings → Zeiterfassung overlay is open: it renders the
        // same shared state.error toast on top, so showing it here too would duplicate (#193).
        if (!settingsOpen) {
            state.error?.let { msg ->
                HbToast(
                    message = msg,
                    icon = HbIcons.x,
                    actionLabel = stringResource(R.string.action_ok),
                    onAction = { viewModel.clearError() },
                )
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
// Timer hero (.hb-timerhero)
// ---------------------------------------------------------------------------

@Composable
private fun RunningHero(running: TimeEntryDto, project: ProjectDto?, eta: String?, onStop: () -> Unit, onEdit: () -> Unit) {
    val elapsed by produceState(Format.elapsedSeconds(running.startedAt), running.startedAt) {
        while (true) {
            value = Format.elapsedSeconds(running.startedAt)
            delay(1000)
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .shadow(1.dp, HbRadius, clip = false, ambientColor = Hb.ink, spotColor = Hb.ink)
            .clip(HbRadius)
            .background(Brush.linearGradient(0f to Hb.accentSoft, 0.78f to Hb.surface))
            .padding(22.dp),
    ) {
        // "LÄUFT" live row (+ edit affordance for the running timer's start time)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(9.dp).clip(HbPill).background(Hb.clay))
            Text(
                stringResource(R.string.time_running).uppercase(),
                style = HbType.eyebrow.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.05.em),
                color = Hb.accentInk,
            )
            Spacer(Modifier.weight(1f))
            HbIconButton(HbIcons.edit, onEdit, tint = Hb.ink3, iconSize = 18.dp)
        }
        // Project dot + name
        Row(
            Modifier.padding(top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                Modifier
                    .size(11.dp)
                    .clip(HbPill)
                    .background(if (project != null) Format.parseColor(project.color) else Hb.ink3),
            )
            Text(
                project?.name ?: stringResource(R.string.time_project_fallback),
                style = HbType.cardTitle.copy(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
                color = Hb.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!running.description.isNullOrBlank()) {
            Text(
                running.description!!,
                style = HbType.body.copy(fontSize = 14.5.sp),
                color = Hb.ink2,
                modifier = Modifier.padding(top = 6.dp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            Format.clock(elapsed),
            style = HbType.mono(46.0),
            color = Hb.ink,
            modifier = Modifier.padding(top = 16.dp, bottom = if (eta != null) 4.dp else 16.dp),
        )
        // "Voraussichtlich fertig um 16:32" under the live clock; flips to "Tagessoll
        // erreicht" once the projected end has passed (#31/#55). expectedEndAt is a
        // stable anchor — the per-second `elapsed` tick above already recomposes us,
        // so the flip needs no own ticker.
        if (eta != null) {
            val reached = Format.parseInstant(eta)?.isAfter(Instant.now()) == false
            Text(
                if (reached) stringResource(R.string.time_eta_reached) else stringResource(R.string.time_eta_until, Format.clockOfDay(eta)),
                style = HbType.meta,
                color = Hb.ink3,
                modifier = Modifier.padding(bottom = 14.dp),
            )
        }
        HbButton(
            stringResource(R.string.time_stop_timer),
            onClick = onStop,
            variant = HbButtonVariant.Primary,
            icon = HbIcons.stop,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun IdleHero() {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(HbRadius)
            .background(Hb.surface)
            .border(1.dp, Hb.lineSoft, HbRadius)
            .padding(22.dp),
    ) {
        Text(
            stringResource(R.string.time_idle_title).uppercase(),
            style = HbType.eyebrow.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.05.em),
            color = Hb.ink3,
        )
        Text(
            "00:00:00",
            style = HbType.mono(34.0),
            color = Hb.ink3,
            modifier = Modifier.padding(top = 14.dp, bottom = 4.dp),
        )
        Text(
            stringResource(R.string.time_idle_hint),
            style = HbType.body.copy(fontSize = 14.sp),
            color = Hb.ink3,
        )
    }
}

// ---------------------------------------------------------------------------
// Partner strip — the other household member's timer
// ---------------------------------------------------------------------------

@Composable
private fun PartnerTimerCard(
    user: String,
    running: TimeEntryDto?,
    projectsById: Map<String, ProjectDto>,
    projects: List<ProjectDto>,
    eta: String?,
    onStop: () -> Unit,
    onStart: (String) -> Unit,
) {
    var picking by remember { mutableStateOf(false) }
    val project = running?.let { projectsById[it.projectId] }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(HbRadius)
            .background(Hb.surface)
            .border(1.dp, Hb.lineSoft, HbRadius)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HbAvatar(user, size = 26.dp)
            if (running != null) {
                Box(Modifier.size(10.dp).clip(HbPill).background(if (project != null) Format.parseColor(project.color) else Hb.ink3))
                Column(Modifier.weight(1f)) {
                    Text(
                        project?.name ?: stringResource(R.string.time_project_fallback),
                        style = HbType.rowTitle.copy(fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold),
                        color = Hb.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        listOfNotNull(
                            displayName(user),
                            running.description?.takeIf { it.isNotBlank() },
                            // compact forecast suffix (#31/#55), e.g. "bis ca. 16:32"
                            Format.etaShortLabel(eta),
                        ).joinToString(" · "),
                        style = HbType.meta,
                        color = Hb.ink3,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val elapsed by produceState(Format.elapsedSeconds(running.startedAt), running.startedAt) {
                    while (true) {
                        value = Format.elapsedSeconds(running.startedAt)
                        delay(1000)
                    }
                }
                Text(Format.clock(elapsed), style = HbType.mono(18.0), color = Hb.ink)
                HbButton(stringResource(R.string.time_stop), onStop, variant = HbButtonVariant.Soft, size = HbButtonSize.Sm, icon = HbIcons.stop)
            } else {
                Column(Modifier.weight(1f)) {
                    Text(
                        displayName(user),
                        style = HbType.rowTitle.copy(fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold),
                        color = Hb.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(stringResource(R.string.time_partner_no_timer), style = HbType.meta, color = Hb.ink3)
                }
                if (projects.isNotEmpty()) {
                    HbButton(
                        if (picking) stringResource(R.string.action_cancel) else stringResource(R.string.time_partner_for, displayName(user)),
                        { picking = !picking },
                        variant = HbButtonVariant.Primary,
                        size = HbButtonSize.Sm,
                        icon = if (picking) HbIcons.x else HbIcons.play,
                    )
                }
            }
        }
        if (picking && running == null && projects.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                projects.forEach { p ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(HbRadiusSm)
                            .clickable { onStart(p.id); picking = false }
                            .padding(vertical = 9.dp, horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(9.dp),
                    ) {
                        Box(Modifier.size(10.dp).clip(HbPill).background(Format.parseColor(p.color)))
                        Text(
                            p.name,
                            style = HbType.rowTitle.copy(fontSize = 14.sp),
                            color = Hb.ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Wochensoll (#31/#55) — week-balance card (.hb-weektargets) and targets sheet
// ---------------------------------------------------------------------------

@Composable
private fun WeekTargetsCard(
    users: List<UserForecastDto>,
    projectsById: Map<String, ProjectDto>,
    openEntries: List<TimeEntryDto>,
    forecastAt: Instant?,
    modifier: Modifier = Modifier,
) {
    // While any timer runs, tick the snapshot figures live (#64): re-evaluate "now"
    // every second and add the seconds since the forecast fetch per person below.
    val hasRunning = openEntries.isNotEmpty()
    val now by produceState(Instant.now(), hasRunning, forecastAt) {
        while (hasRunning) {
            value = Instant.now()
            delay(1000)
        }
    }
    Column(
        modifier
            .fillMaxWidth()
            .shadow(1.dp, HbRadius, clip = false, ambientColor = Hb.ink, spotColor = Hb.ink)
            .clip(HbRadius)
            .background(Hb.surface)
            .border(1.dp, Hb.lineSoft, HbRadius)
            .padding(start = 18.dp, end = 10.dp, top = 6.dp, bottom = 18.dp),
    ) {
        Text(stringResource(R.string.time_weektargets_title), style = HbType.cardTitle, color = Hb.ink)
        Column(
            Modifier.padding(end = 8.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            users.forEach { u ->
                // a running timer ticks the snapshot numbers live: add the seconds
                // elapsed since the forecast was fetched (#64, web parity to #59)
                val running = openEntries.firstOrNull { it.userId == u.userId }
                val extra = if (running != null) liveExtraSeconds(forecastAt, now) else 0L
                WeekBalanceBlock(u.withLiveExtra(extra, running?.projectId), projectsById)
            }
        }
    }
}

/**
 * One person's week balance: Soll/Ist row with progress bar, today's redistributed
 * target, credits and the per-project saldo for projects with a target.
 */
@Composable
private fun WeekBalanceBlock(u: UserForecastDto, projectsById: Map<String, ProjectDto>) {
    val done = u.weekRecordedSeconds + u.weekCreditedSeconds
    val frac = if (u.weekTargetSeconds > 0) (done.toFloat() / u.weekTargetSeconds.toFloat()).coerceIn(0f, 1f) else 0f
    val weekOver = u.weekRemainingSeconds < 0
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // head: avatar + name + "22:00 / 42:00" + "noch 20:00"/"+1:30"
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HbAvatar(u.userId, size = 24.dp)
            Text(
                displayName(u.userId),
                style = HbType.rowTitle.copy(fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold),
                color = Hb.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                "${Format.hoursMinutes(done)} / ${Format.hoursMinutes(u.weekTargetSeconds)}",
                style = HbType.mono.copy(fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold),
                color = Hb.ink,
            )
            Text(
                if (weekOver) stringResource(R.string.time_week_over, Format.hoursMinutes(-u.weekRemainingSeconds))
                else stringResource(R.string.time_week_remaining, Format.hoursMinutes(u.weekRemainingSeconds)),
                style = HbType.meta.copy(fontWeight = if (weekOver) FontWeight.SemiBold else FontWeight.Normal),
                color = if (weekOver) Hb.accentInk else Hb.ink3,
            )
        }
        // progress bar (.hb-weekbar)
        Box(
            Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(HbPill)
                .background(Hb.surface2, HbPill),
        ) {
            if (frac > 0f) {
                Box(
                    Modifier
                        .fillMaxWidth(frac)
                        .fillMaxHeight()
                        .clip(HbPill)
                        // week-bar segment honours the per-user avatar-hue override (Teil von #100)
                        .background(Hb.userColor(u.userId, LocalAvatarHues.current[u.userId])),
                )
            }
        }
        // today's redistributed target + credits
        val todayLine = when {
            u.todayRemainingSeconds >= 60 -> stringResource(R.string.time_today_remaining, Format.hoursMinutes(u.todayRemainingSeconds))
            u.todayRemainingSeconds <= -60 -> stringResource(R.string.time_today_over, Format.hoursMinutes(-u.todayRemainingSeconds))
            else -> stringResource(R.string.time_today_reached)
        }
        val credits = if (u.weekCreditedSeconds > 0) " " + stringResource(R.string.time_credited, Format.hoursMinutes(u.weekCreditedSeconds)) else ""
        Text(todayLine + credits, style = HbType.meta, color = Hb.ink3)
        // per-project saldi — deliberately a soll view: projects with recorded time but no target stay out
        val projects = u.projects.filter { it.weeklyHours > 0 }
        if (projects.isNotEmpty()) {
            Column(Modifier.padding(top = 2.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                projects.forEach { p ->
                    val proj = projectsById[p.projectId]
                    val ahead = p.deltaSeconds >= 0
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        Box(
                            Modifier
                                .size(9.dp)
                                .clip(HbPill)
                                .background(if (proj != null) Format.parseColor(proj.color) else Hb.ink3),
                        )
                        Text(
                            proj?.name ?: stringResource(R.string.time_project_fallback),
                            style = HbType.meta,
                            color = Hb.ink2,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "${Format.hoursMinutes(p.recordedSeconds + p.creditedSeconds)} / ${Format.hoursMinutes((p.weeklyHours * 3600).roundToLong())}",
                            style = HbType.mono.copy(fontSize = 12.5.sp),
                            color = Hb.ink3,
                        )
                        Text(
                            if (p.deltaSeconds < 0) "-${Format.hoursMinutes(-p.deltaSeconds)}"
                            else "+${Format.hoursMinutes(p.deltaSeconds)}",
                            style = HbType.meta.copy(fontWeight = if (ahead) FontWeight.SemiBold else FontWeight.Normal),
                            color = if (ahead) Hb.accentInk else Hb.ink3,
                            textAlign = TextAlign.End,
                            modifier = Modifier.widthIn(min = 52.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Wochensoll configuration (#55): weekly hours per person × project plus the person's
 * default project (absence/holiday credits are booked there). Saving emits only the
 * changed cells; the household may edit either person (like the absence planner).
 */
@Composable
// internal: also opened from the central settings → Zeiterfassung subpage (#101), which is now
// the only entry point — the tracker no longer hosts the Wochensoll editor.
internal fun TargetsSheet(
    users: List<String>,
    projects: List<ProjectDto>,
    targets: List<WorkTargetDto>,
    onSave: (List<TargetChange>) -> Unit,
    onDismiss: () -> Unit,
) {
    fun targetFor(u: String, p: String) = targets.firstOrNull { it.userId == u && it.projectId == p }
    fun defaultFor(u: String) = targets.firstOrNull { it.userId == u && it.isDefault }?.projectId ?: ""

    // user → projectId → hours text ("7,5"); user → default projectId
    // ("" only for legacy data without one — hours > 0 enforce a default, #59)
    var hours by remember {
        mutableStateOf(
            users.associateWith { u ->
                projects.associate { p ->
                    val h = targetFor(u, p.id)?.weeklyHours ?: 0.0
                    p.id to if (h > 0) Format.amount(h).replace('.', ',') else ""
                }
            }
        )
    }
    var defaults by remember { mutableStateOf(users.associateWith { defaultFor(it) }) }
    var error by remember { mutableStateOf<String?>(null) }

    val errHoursRange = stringResource(R.string.time_targets_hours_range)
    val errPickDefault = stringResource(R.string.time_targets_pick_default)
    HbBottomSheet(
        onDismiss = onDismiss,
        title = stringResource(R.string.time_targets_title),
        full = true,
        footer = {
            HbButton(
                stringResource(R.string.action_cancel),
                onClick = onDismiss,
                variant = HbButtonVariant.Secondary,
                modifier = Modifier.weight(1f),
            )
            HbButton(
                stringResource(R.string.action_save),
                onClick = {
                    // validate every cell, then collect only the changed ones (mirrors the web modal)
                    val changes = mutableListOf<TargetChange>()
                    for (u in users) {
                        var sumHours = 0.0
                        for (p in projects) {
                            val raw = hours[u]?.get(p.id).orEmpty().trim()
                            val parsed = if (raw.isEmpty()) 0.0 else raw.replace(',', '.').toDoubleOrNull()
                            if (parsed == null || !parsed.isFinite() || parsed < 0 || parsed > 168) {
                                error = errHoursRange
                                return@HbButton
                            }
                            sumHours += parsed
                            var change = TargetChange(u, p.id)
                            if (parsed != (targetFor(u, p.id)?.weeklyHours ?: 0.0)) {
                                change = change.copy(weeklyHours = parsed)
                            }
                            // setting the new default clears the old one server-side; isDefault=false
                            // is never sent — hours > 0 require a default (#59, 409 DEFAULT_REQUIRED)
                            if (defaults[u] != defaultFor(u) && defaults[u] == p.id) {
                                change = change.copy(isDefault = true)
                            }
                            if (change.weeklyHours != null || change.isDefault != null) changes += change
                        }
                        // hours > 0 ⇒ a default project must be chosen (#59); auto-select normally
                        // covers this — backstop for legacy data without a default
                        if (sumHours > 0 && (defaults[u] ?: "").isEmpty()) {
                            error = errPickDefault
                            return@HbButton
                        }
                    }
                    onSave(changes)
                },
                variant = HbButtonVariant.Primary,
                modifier = Modifier.weight(1f),
            )
        },
    ) {
        Text(
            stringResource(R.string.time_targets_hint),
            style = HbType.meta,
            color = Hb.ink3,
        )
        if (projects.isEmpty()) {
            Text(stringResource(R.string.time_targets_create_project_first), style = HbType.meta, color = Hb.ink3)
        } else {
            users.forEach { user ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        HbAvatar(user, size = 22.dp)
                        Text(displayName(user).uppercase(), style = HbType.sectionLabel, color = Hb.ink3)
                    }
                    // column headers
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(stringResource(R.string.time_targets_col_project), style = HbType.small, color = Hb.ink3, modifier = Modifier.weight(1f))
                        Text(stringResource(R.string.time_targets_col_hours), style = HbType.small, color = Hb.ink3, modifier = Modifier.width(86.dp))
                        Text(stringResource(R.string.time_targets_col_default), style = HbType.small, color = Hb.ink3)
                    }
                    projects.forEach { p ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(
                                Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Box(Modifier.size(10.dp).clip(HbPill).background(Format.parseColor(p.color)))
                                Text(
                                    if (p.archived) stringResource(R.string.time_project_archived_suffix, p.name) else p.name,
                                    style = HbType.body.copy(fontSize = 14.sp),
                                    color = if (p.archived) Hb.ink3 else Hb.ink,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            HbTextField(
                                value = hours[user]?.get(p.id).orEmpty(),
                                onValueChange = { v ->
                                    hours = hours + (user to (hours[user].orEmpty() + (p.id to v)))
                                    // hours > 0 require a default project (#59) — the person's first
                                    // hours auto-select this project (never steals an existing default)
                                    if ((defaults[user] ?: "").isEmpty() &&
                                        (v.trim().replace(',', '.').toDoubleOrNull() ?: 0.0) > 0
                                    ) {
                                        defaults = defaults + (user to p.id)
                                    }
                                },
                                modifier = Modifier.width(86.dp),
                                placeholder = "0",
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            )
                            RadioButton(
                                selected = defaults[user] == p.id,
                                onClick = { defaults = defaults + (user to p.id) },
                                colors = RadioButtonDefaults.colors(selectedColor = Hb.accent, unselectedColor = Hb.ink3),
                            )
                        }
                    }
                }
            }
        }
        error?.let { Text(it, style = HbType.meta, color = Hb.clay) }
    }
}

// ---------------------------------------------------------------------------
// Project card (.hb-projcard)
// ---------------------------------------------------------------------------

@Composable
private fun ProjectCard(
    project: ProjectDto,
    entries: List<TimeEntryDto>,
    isRunning: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Day + week saldo instead of the all-time total (#64), with a fallback to the
    // last active day/week. A running timer (own or partner's) ticks the figures live.
    val hasRunning = entries.any { it.stoppedAt == null }
    val now by produceState(Instant.now(), hasRunning) {
        while (hasRunning) {
            value = Instant.now()
            delay(1000)
        }
    }
    val stats = remember(entries, now) { projectCardStats(entries, now) }
    val ringModifier = if (isRunning) {
        Modifier
            .shadow(0.dp, HbRadius, clip = false, ambientColor = Hb.accentSoft, spotColor = Hb.accentSoft)
            .clip(HbRadius)
            .background(Hb.surface)
            .border(3.dp, Hb.accentSoft, HbRadius)
            .border(1.dp, Hb.accent, HbRadius)
            .padding(15.dp)
    } else {
        Modifier
            .shadow(1.dp, HbRadius, clip = false, ambientColor = Hb.ink, spotColor = Hb.ink)
            .clip(HbRadius)
            .background(Hb.surface)
            .border(1.dp, Hb.lineSoft, HbRadius)
            .padding(15.dp)
    }
    Column(
        modifier
            .then(ringModifier)
            .then(if (project.archived) Modifier.alpha(0.72f) else Modifier),
        verticalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        // head: dot + name (whole upper area opens the detail sheet)
        Column(Modifier.clickable { onOpen() }, verticalArrangement = Arrangement.spacedBy(11.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Box(Modifier.size(11.dp).clip(HbPill).background(Format.parseColor(project.color)))
                Text(
                    project.name,
                    style = HbType.rowTitle.copy(fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold),
                    color = Hb.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            // "7:30 Heute" / "32:15 Diese Woche" (.hb-projcard__stat/__stat2)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(
                        Format.hoursMinutes(stats.daySeconds),
                        style = HbType.mono(21.0),
                        color = Hb.ink,
                        modifier = Modifier.alignByBaseline(),
                    )
                    Text(
                        stats.dayLabel,
                        style = HbType.meta,
                        color = Hb.ink3,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.alignByBaseline(),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(
                        Format.hoursMinutes(stats.weekSeconds),
                        style = HbType.mono(13.5),
                        color = Hb.ink2,
                        modifier = Modifier.alignByBaseline(),
                    )
                    Text(
                        stats.weekLabel,
                        style = HbType.meta,
                        color = Hb.ink3,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.alignByBaseline(),
                    )
                }
            }
        }
        if (isRunning) {
            HbButton(
                stringResource(R.string.time_stop),
                onClick = onStop,
                variant = HbButtonVariant.Soft,
                size = HbButtonSize.Sm,
                icon = HbIcons.stop,
            )
        } else {
            HbButton(
                stringResource(R.string.time_start),
                onClick = onStart,
                variant = HbButtonVariant.Primary,
                size = HbButtonSize.Sm,
                icon = HbIcons.play,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Day-grouped entry list (.hb-daysep + .hb-row)
// ---------------------------------------------------------------------------

@Composable
private fun EntriesByDay(
    entries: List<TimeEntryDto>,
    projectsById: Map<String, ProjectDto>,
    showProjectName: Boolean,
    onDelete: (TimeEntryDto) -> Unit,
    onEdit: (TimeEntryDto) -> Unit,
    onSplit: (TimeEntryDto) -> Unit,
    modifier: Modifier = Modifier,
) {
    // entries arrive already newest-first; preserve that order across day buckets.
    val groups = remember(entries) {
        val ordered = LinkedHashMap<String, MutableList<TimeEntryDto>>()
        entries.forEach { entry ->
            ordered.getOrPut(Format.dayGroupLabel(entry.startedAt)) { mutableListOf() }.add(entry)
        }
        ordered.toList()
    }
    Column(modifier.fillMaxWidth()) {
        groups.forEach { (label, dayEntries) ->
            val daySum = sumSeconds(dayEntries)
            // .hb-daysep
            Row(
                Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    label.uppercase(),
                    style = HbType.small.copy(fontSize = 11.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.06.em),
                    color = Hb.ink3,
                )
                Box(Modifier.weight(1f).size(1.dp).background(Hb.lineSoft))
                Text(
                    stringResource(R.string.time_day_sum, Format.durationLong(daySum)),
                    style = HbType.small.copy(fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold),
                    color = Hb.ink2,
                )
            }
            dayEntries.forEach { entry ->
                EntryRow(
                    entry = entry,
                    project = projectsById[entry.projectId],
                    showProjectName = showProjectName,
                    onDelete = { onDelete(entry) },
                    onEdit = { onEdit(entry) },
                    onSplit = { onSplit(entry) },
                )
            }
        }
    }
}

@Composable
private fun EntryRow(
    entry: TimeEntryDto,
    project: ProjectDto?,
    showProjectName: Boolean,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
    onSplit: () -> Unit,
) {
    val duration = Format.entrySeconds(entry.startedAt, entry.stoppedAt)
    val range = "${Format.clockOfDay(entry.startedAt)}–${Format.clockOfDay(entry.stoppedAt)}"
    val projectFallback = stringResource(R.string.time_project_fallback)
    val title = if (showProjectName) (project?.name ?: projectFallback) else (entry.description ?: project?.name ?: stringResource(R.string.time_entry_fallback))

    Column {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(13.dp),
        ) {
            Box(
                Modifier
                    .size(11.dp)
                    .clip(HbPill)
                    .background(if (project != null) Format.parseColor(project.color) else Hb.ink3),
            )
            Column(Modifier.weight(1f)) {
                Row {
                    Text(
                        title,
                        style = HbType.rowTitle.copy(fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold),
                        color = Hb.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (showProjectName && !entry.description.isNullOrBlank()) {
                        Text(
                            " · ${entry.description}",
                            style = HbType.rowTitle.copy(fontSize = 14.5.sp, fontWeight = FontWeight.Normal),
                            color = Hb.ink3,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Row(
                    Modifier.padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    HbAvatar(entry.userId, size = 18.dp)
                    Text(range, style = HbType.meta, color = Hb.ink3)
                }
            }
            Text(
                Format.durationLong(duration),
                style = HbType.mono.copy(fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold),
                color = Hb.ink2,
            )
            // Edit/split/delete are offered on EVERY entry — also the partner's (the
            // household manages them together); cross-person clicks are confirmed
            // upstream via the dialog in TimeScreen (#129/#140, web parity).
            HbIconButton(HbIcons.edit, onEdit, tint = Hb.ink3, iconSize = 18.dp)
            // splitting needs a fixed end — only completed entries (#66)
            if (entry.stoppedAt != null) {
                HbIconButton(HbIcons.scissors, onSplit, tint = Hb.ink3, iconSize = 18.dp)
            }
            HbIconButton(HbIcons.trash, onDelete, tint = Hb.ink3, iconSize = 18.dp)
        }
        Box(Modifier.fillMaxWidth().size(1.dp).background(Hb.lineSoft))
    }
}

// ---------------------------------------------------------------------------
// New-project sheet (Name + swatch picker)
// ---------------------------------------------------------------------------

@Composable
private fun NewProjectSheet(onDismiss: () -> Unit, onCreate: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(Hb.projectSwatches.first()) }

    HbBottomSheet(
        onDismiss = onDismiss,
        title = stringResource(R.string.time_new_project_title),
        footer = {
            HbButton(
                stringResource(R.string.action_cancel),
                onClick = onDismiss,
                variant = HbButtonVariant.Secondary,
                modifier = Modifier.weight(1f),
            )
            HbButton(
                stringResource(R.string.action_create),
                onClick = { if (name.isNotBlank()) onCreate(name, hexOf(selected)) },
                variant = HbButtonVariant.Primary,
                modifier = Modifier.weight(1f),
            )
        },
    ) {
        HbField(stringResource(R.string.common_field_name)) {
            HbTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = stringResource(R.string.time_project_name_placeholder),
            )
        }
        HbField(stringResource(R.string.time_field_color)) {
            Row(horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                Hb.projectSwatches.forEach { color ->
                    val isActive = color == selected
                    Box(
                        Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(11.dp))
                            .background(color, RoundedCornerShape(11.dp))
                            .then(
                                if (isActive) {
                                    Modifier
                                        .border(2.dp, Hb.surface, RoundedCornerShape(11.dp))
                                        .border(4.dp, Hb.ink2, RoundedCornerShape(13.dp))
                                } else {
                                    Modifier
                                }
                            )
                            .clickable { selected = color },
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Record-entry sheet (#140) — manual past entry: project + person + date +
// from/to + description. Person defaults to me; only household members with a
// partner are selectable, and the caller confirms a partner target before it
// posts. UI reference: ManualEntryModal in TimeView.tsx.
// ---------------------------------------------------------------------------

@Composable
private fun RecordEntrySheet(
    projects: List<ProjectDto>,
    users: List<String>,
    currentUser: String?,
    onCreate: (projectId: String, startedAt: String, stoppedAt: String, description: String?, forUser: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val zone = ZoneId.systemDefault()
    var projectId by remember { mutableStateOf(projects.firstOrNull()?.id ?: "") }
    var date by remember { mutableStateOf(LocalDate.now()) }
    var startTime by remember { mutableStateOf(LocalTime.of(9, 0)) }
    var stopTime by remember { mutableStateOf(LocalTime.of(10, 0)) }
    var description by remember { mutableStateOf("") }
    // Who the entry is for — self by default; partners (other members) are selectable.
    // Without a known own username the selector stays hidden and we record as self.
    var forUser by remember { mutableStateOf(currentUser ?: "") }
    var error by remember { mutableStateOf<String?>(null) }

    val partners = remember(users, currentUser) {
        if (currentUser == null) emptyList() else users.filter { it != currentUser }
    }
    val selectedProjectName = projects.firstOrNull { it.id == projectId }?.name ?: stringResource(R.string.time_project_fallback)
    val errPickProject = stringResource(R.string.time_pick_project)
    val errEndAfterStart = stringResource(R.string.time_end_after_start)

    HbBottomSheet(
        onDismiss = onDismiss,
        title = stringResource(R.string.time_record_title),
        footer = {
            HbButton(
                stringResource(R.string.action_cancel),
                onClick = onDismiss,
                variant = HbButtonVariant.Secondary,
                modifier = Modifier.weight(1f),
            )
            HbButton(
                stringResource(R.string.action_save),
                onClick = {
                    if (projectId.isEmpty()) {
                        error = errPickProject
                        return@HbButton
                    }
                    val startInstant = date.atTime(startTime).atZone(zone).toInstant()
                    val stopInstant = date.atTime(stopTime).atZone(zone).toInstant()
                    if (!stopInstant.isAfter(startInstant)) {
                        error = errEndAfterStart
                        return@HbButton
                    }
                    // Clear a stale validation error on the validated path (web parity):
                    // a partner target keeps this sheet open under the confirm dialog, so
                    // a previously-shown, since-fixed error would otherwise linger.
                    error = null
                    onCreate(
                        projectId,
                        startInstant.toString(),
                        stopInstant.toString(),
                        description.trim().takeIf { it.isNotEmpty() },
                        forUser.takeIf { it.isNotEmpty() },
                    )
                },
                variant = HbButtonVariant.Primary,
                // No selectable project ⇒ the action is a no-op; disable it honestly (web parity).
                enabled = projectId.isNotEmpty(),
                modifier = Modifier.weight(1f),
            )
        },
    ) {
        if (projects.isEmpty()) {
            Text(stringResource(R.string.time_targets_create_project_first), style = HbType.meta, color = Hb.ink3)
        } else {
            HbField(stringResource(R.string.time_field_project)) {
                SelectField(
                    value = selectedProjectName,
                    options = projects.map { it.name to it.id },
                    onSelect = { projectId = it },
                )
            }
            if (partners.isNotEmpty() && currentUser != null) {
                HbField(stringResource(R.string.time_field_person)) {
                    SelectField(
                        value = displayName(forUser.takeIf { it.isNotEmpty() } ?: currentUser),
                        options = (listOf(currentUser) + partners).map { displayName(it) to it },
                        onSelect = { forUser = it },
                    )
                }
            }
            HbField(stringResource(R.string.time_field_date)) {
                DateField(date) { date = it }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f)) { HbField(stringResource(R.string.time_field_from)) { TimeField(startTime) { startTime = it } } }
                Box(Modifier.weight(1f)) { HbField(stringResource(R.string.time_field_to)) { TimeField(stopTime) { stopTime = it } } }
            }
            HbField(stringResource(R.string.common_description_optional)) {
                HbTextField(
                    value = description,
                    onValueChange = { description = it },
                    placeholder = stringResource(R.string.common_description_optional),
                )
            }
            error?.let { Text(it, style = HbType.meta, color = Hb.clay) }
        }
    }
}

// ---------------------------------------------------------------------------
// Edit-entry sheet (project / start / stop / description; running → start only)
// ---------------------------------------------------------------------------

@Composable
private fun EditEntrySheet(
    entry: TimeEntryDto,
    project: ProjectDto?,
    activeProjects: List<ProjectDto>,
    onSave: (UpdateTimeEntryRequest) -> Unit,
    onDismiss: () -> Unit,
) {
    val running = entry.stoppedAt == null
    val zone = ZoneId.systemDefault()
    val startZdt = remember(entry.id) { (Format.parseInstant(entry.startedAt) ?: Instant.now()).atZone(zone) }
    val stopZdt = remember(entry.id) { Format.parseInstant(entry.stoppedAt)?.atZone(zone) }

    var projectId by remember(entry.id) { mutableStateOf(entry.projectId) }
    var startDate by remember(entry.id) { mutableStateOf(startZdt.toLocalDate()) }
    var startTime by remember(entry.id) { mutableStateOf(startZdt.toLocalTime().withSecond(0).withNano(0)) }
    var stopDate by remember(entry.id) { mutableStateOf((stopZdt ?: startZdt).toLocalDate()) }
    var stopTime by remember(entry.id) { mutableStateOf((stopZdt ?: startZdt).toLocalTime().withSecond(0).withNano(0)) }
    var description by remember(entry.id) { mutableStateOf(entry.description ?: "") }
    var error by remember(entry.id) { mutableStateOf<String?>(null) }

    // Offer the active projects plus the entry's current one (so an archived current
    // project still shows as the no-op default, but you can't switch *to* one).
    val options = remember(activeProjects, entry.projectId, project) {
        if (activeProjects.any { it.id == entry.projectId }) activeProjects
        else listOfNotNull(project) + activeProjects
    }
    val selectedName = options.firstOrNull { it.id == projectId }?.name ?: project?.name ?: stringResource(R.string.time_project_fallback)
    val errStartNotFuture = stringResource(R.string.time_start_not_future)
    val errEndAfterStart = stringResource(R.string.time_end_after_start)

    HbBottomSheet(
        onDismiss = onDismiss,
        title = if (running) stringResource(R.string.time_edit_running_title) else stringResource(R.string.time_edit_entry_title),
        footer = {
            HbButton(
                stringResource(R.string.action_cancel),
                onClick = onDismiss,
                variant = HbButtonVariant.Secondary,
                modifier = Modifier.weight(1f),
            )
            HbButton(
                stringResource(R.string.action_save),
                onClick = {
                    val startInstant = startDate.atTime(startTime).atZone(zone).toInstant()
                    // Only resend the project when it changed — otherwise an archived
                    // current project would trip the backend's PROJECT_ARCHIVED guard.
                    val projectChange = projectId.takeIf { it != entry.projectId }
                    if (running) {
                        // The backend skips its range check while stoppedAt is null, so
                        // guard here against a future start that would freeze the clock.
                        if (startInstant.isAfter(Instant.now())) {
                            error = errStartNotFuture
                            return@HbButton
                        }
                        // Still running: leave stoppedAt open — send project + start only.
                        onSave(UpdateTimeEntryRequest(projectId = projectChange, startedAt = startInstant.toString()))
                    } else {
                        val stopInstant = stopDate.atTime(stopTime).atZone(zone).toInstant()
                        if (!stopInstant.isAfter(startInstant)) {
                            error = errEndAfterStart
                            return@HbButton
                        }
                        onSave(
                            UpdateTimeEntryRequest(
                                projectId = projectChange,
                                startedAt = startInstant.toString(),
                                stoppedAt = stopInstant.toString(),
                                description = description.trim(), // empty clears it server-side
                            )
                        )
                    }
                },
                variant = HbButtonVariant.Primary,
                modifier = Modifier.weight(1f),
            )
        },
    ) {
        if (running) {
            Text(
                stringResource(R.string.time_running_hint),
                style = HbType.meta,
                color = Hb.ink3,
            )
        }
        HbField(stringResource(R.string.time_field_project)) {
            SelectField(value = selectedName, options = options.map { it.name to it.id }, onSelect = { projectId = it })
        }
        HbField(if (running) stringResource(R.string.time_field_start_running) else stringResource(R.string.time_field_start)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f)) { DateField(startDate) { startDate = it } }
                Box(Modifier.weight(1f)) { TimeField(startTime) { startTime = it } }
            }
        }
        if (!running) {
            HbField(stringResource(R.string.time_field_end)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.weight(1f)) { DateField(stopDate) { stopDate = it } }
                    Box(Modifier.weight(1f)) { TimeField(stopTime) { stopTime = it } }
                }
            }
            HbField(stringResource(R.string.common_description_optional)) {
                HbTextField(
                    value = description,
                    onValueChange = { description = it },
                    placeholder = stringResource(R.string.common_description_optional),
                )
            }
        }
        error?.let { Text(it, style = HbType.meta, color = Hb.clay) }
    }
}

// ---------------------------------------------------------------------------
// Split-entry sheet (#66) — cut a completed entry in two at a Trennzeit, with
// an optional untracked break between the parts. Wording mirrors the web
// (de.ts time.split*), UI reference: SplitEntryModal in TimeView.tsx.
// ---------------------------------------------------------------------------

@Composable
private fun SplitEntrySheet(
    entry: TimeEntryDto,
    onSave: (splitAt: String, breakMinutes: Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    // the split action is only offered on completed entries; bail out defensively
    val stoppedAt = entry.stoppedAt ?: return
    val zone = ZoneId.systemDefault()
    // default cut: the entry's midpoint, snapped to the full minute
    val initialCut = remember(entry.id) {
        (defaultSplitAt(entry.startedAt, stoppedAt) ?: Instant.now()).atZone(zone)
    }
    var cutDate by remember(entry.id) { mutableStateOf(initialCut.toLocalDate()) }
    var cutTime by remember(entry.id) { mutableStateOf(initialCut.toLocalTime().withSecond(0).withNano(0)) }
    var breakText by remember(entry.id) { mutableStateOf("") }
    // Holds a @StringRes id of the validation error (localized on render), or null.
    var errorRes by remember(entry.id) { mutableStateOf<Int?>(null) }

    val check = checkSplit(
        startedAtIso = entry.startedAt,
        stoppedAtIso = stoppedAt,
        splitAt = cutDate.atTime(cutTime).atZone(zone).toInstant(),
        breakText = breakText,
    )

    HbBottomSheet(
        onDismiss = onDismiss,
        title = stringResource(R.string.time_split_title),
        footer = {
            HbButton(
                stringResource(R.string.action_cancel),
                onClick = onDismiss,
                variant = HbButtonVariant.Secondary,
                modifier = Modifier.weight(1f),
            )
            HbButton(
                stringResource(R.string.action_save),
                onClick = {
                    when (check) {
                        is SplitCheck.Invalid -> errorRes = check.messageRes
                        is SplitCheck.Valid ->
                            onSave(check.splitAt.toString(), check.breakMinutes.takeIf { it > 0 })
                    }
                },
                variant = HbButtonVariant.Primary,
                icon = HbIcons.scissors,
                modifier = Modifier.weight(1f),
            )
        },
    ) {
        Text(
            stringResource(R.string.time_split_hint),
            style = HbType.meta,
            color = Hb.ink3,
        )
        HbField(stringResource(R.string.time_split_at)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f)) { DateField(cutDate) { cutDate = it } }
                Box(Modifier.weight(1f)) { TimeField(cutTime) { cutTime = it } }
            }
        }
        HbField(stringResource(R.string.time_split_break)) {
            HbTextField(
                value = breakText,
                onValueChange = { breakText = it },
                placeholder = "0",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
        }
        // live preview of both resulting parts, e.g. "Teil 1: 14:03–17:33 · Teil 2: 18:18–21:03"
        if (check is SplitCheck.Valid) {
            Text(
                stringResource(
                    R.string.time_split_preview,
                    Format.clockOfDay(entry.startedAt),
                    Format.clockOfDay(check.splitAt.toString()),
                    Format.clockOfDay(check.secondStart.toString()),
                    Format.clockOfDay(stoppedAt),
                ),
                style = HbType.mono.copy(fontSize = 13.5.sp),
                color = Hb.ink3,
            )
        }
        errorRes?.let { Text(stringResource(it), style = HbType.meta, color = Hb.clay) }
    }
}

/** Read-only field that opens a dropdown of [options] (label to value). */
@Composable
private fun SelectField(value: String, options: List<Pair<String, String>>, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth().clip(HbRadiusSm).background(Hb.surface, HbRadiusSm)
                .border(1.dp, Hb.line, HbRadiusSm).clickable { open = true }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(value, style = HbType.body.copy(fontSize = 14.sp), color = Hb.ink, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            HbIcon(HbIcons.chevronDown, size = 16.dp, tint = Hb.ink3)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (label, v) ->
                DropdownMenuItem(text = { Text(label, style = HbType.body, color = Hb.ink) }, onClick = { onSelect(v); open = false })
            }
        }
    }
}

/** Date field: shows dd.MM.yyyy, opens a Material date picker on tap. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateField(value: LocalDate, onChange: (LocalDate) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(
        Modifier.fillMaxWidth().clip(HbRadiusSm).background(Hb.surface, HbRadiusSm)
            .border(1.dp, Hb.line, HbRadiusSm).clickable { open = true }
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text("%02d.%02d.%04d".format(value.dayOfMonth, value.monthValue, value.year), style = HbType.body.copy(fontSize = 14.sp), color = Hb.ink)
    }
    if (open) {
        val initialMillis = value.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
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

/** Time field: shows HH:mm, opens a Material time picker (24h) on tap. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeField(value: LocalTime, onChange: (LocalTime) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(
        Modifier.fillMaxWidth().clip(HbRadiusSm).background(Hb.surface, HbRadiusSm)
            .border(1.dp, Hb.line, HbRadiusSm).clickable { open = true }
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text("%02d:%02d".format(value.hour, value.minute), style = HbType.body.copy(fontSize = 14.sp), color = Hb.ink)
    }
    if (open) {
        val timeState = rememberTimePickerState(initialHour = value.hour, initialMinute = value.minute, is24Hour = true)
        Dialog(onDismissRequest = { open = false }) {
            Surface(shape = HbRadius, color = Hb.surface) {
                Column(
                    Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    TimePicker(state = timeState)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { open = false }) { Text(stringResource(R.string.action_cancel)) }
                        TextButton(onClick = {
                            onChange(LocalTime.of(timeState.hour, timeState.minute))
                            open = false
                        }) { Text(stringResource(R.string.action_ok)) }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Project-detail sheet (stats, per-user chips, weekly bars, all entries)
// ---------------------------------------------------------------------------

@Composable
private fun ProjectDetailSheet(
    project: ProjectDto,
    entries: List<TimeEntryDto>,
    isRunning: Boolean,
    onDelete: (TimeEntryDto) -> Unit,
    onEdit: (TimeEntryDto) -> Unit,
    onSplit: (TimeEntryDto) -> Unit,
    onDismiss: () -> Unit,
) {
    val finished = remember(entries) { entries.filter { it.stoppedAt != null } }
    val totalSeconds = remember(finished) { sumSeconds(finished) }
    val count = finished.size
    val avgSeconds = if (count > 0) totalSeconds / count else 0L

    val today = LocalDate.now()
    val thisWeekStart = today.with(DayOfWeek.MONDAY)
    val thisWeekSeconds = remember(finished) {
        finished.filter { weekStartOf(it.startedAt) == thisWeekStart }.let { sumSeconds(it) }
    }

    // Per-user totals.
    val byUser = remember(finished) {
        finished.groupBy { it.userId }
            .mapValues { (_, list) -> sumSeconds(list) }
            .toList()
            .sortedByDescending { it.second }
    }

    // Weekly aggregation: Monday -> (per-user seconds, entry count).
    val weeks = remember(finished) { buildWeeks(finished) }
    val busiestWeek = weeks.maxOfOrNull { it.totalSeconds } ?: 0L

    val projectColor = Format.parseColor(project.color)

    HbBottomSheet(
        onDismiss = onDismiss,
        title = project.name,
        full = true,
        footer = {
            HbButton(
                stringResource(R.string.action_close),
                onClick = onDismiss,
                variant = HbButtonVariant.Secondary,
                modifier = Modifier.weight(1f),
            )
        },
    ) {
        // active marker
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(13.dp).clip(HbPill).background(projectColor))
            Text(
                if (isRunning) stringResource(R.string.time_detail_active_project) else stringResource(R.string.time_detail_project),
                style = HbType.label.copy(fontSize = 13.sp),
                color = Hb.ink3,
            )
        }

        // 4 stat tiles (.hb-detail-stats / .hb-fact)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FactTile(Format.durationLong(totalSeconds), stringResource(R.string.time_fact_total), Modifier.weight(1f))
                FactTile(Format.durationLong(thisWeekSeconds), stringResource(R.string.time_fact_this_week), Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FactTile(count.toString(), stringResource(R.string.time_fact_entries), Modifier.weight(1f))
                FactTile(Format.durationLong(avgSeconds), stringResource(R.string.time_fact_avg), Modifier.weight(1f))
            }
        }

        // per-user chips (.hb-detail-user)
        if (byUser.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                byUser.forEach { (userId, seconds) ->
                    Row(
                        Modifier
                            .clip(HbPill)
                            .background(Hb.surface2, HbPill)
                            .padding(start = 6.dp, end = 14.dp, top = 5.dp, bottom = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(9.dp),
                    ) {
                        HbAvatar(userId, size = 24.dp)
                        Text(
                            displayName(userId),
                            style = HbType.label.copy(fontSize = 13.5.sp),
                            color = Hb.ink,
                        )
                        Text(
                            Format.durationLong(seconds),
                            style = HbType.label.copy(fontSize = 13.sp),
                            color = Hb.ink2,
                        )
                    }
                }
            }
        }

        // Pro Woche
        if (weeks.isNotEmpty()) {
            Text(stringResource(R.string.time_per_week).uppercase(), style = HbType.sectionLabel, color = Hb.ink3)
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                weeks.forEach { week ->
                    WeekRow(week = week, busiest = busiestWeek, today = today)
                }
            }
        }

        // Alle Einträge
        if (finished.isNotEmpty()) {
            Text(stringResource(R.string.time_all_entries).uppercase(), style = HbType.sectionLabel, color = Hb.ink3)
            EntriesByDay(
                entries = finished.sortedByDescending { it.startedAt },
                projectsById = mapOf(project.id to project),
                showProjectName = false,
                onDelete = onDelete,
                onEdit = onEdit,
                onSplit = onSplit,
            )
        }
    }
}

@Composable
private fun FactTile(value: String, label: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(HbRadiusSm)
            .background(Hb.surface2, HbRadiusSm)
            .padding(horizontal = 15.dp, vertical = 13.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            value,
            style = HbType.cardTitle.copy(fontSize = 20.sp, fontWeight = FontWeight.Bold, lineHeight = 22.sp),
            color = Hb.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(label, style = HbType.small.copy(fontWeight = FontWeight.Medium), color = Hb.ink3)
    }
}

@Composable
private fun WeekRow(week: WeekStat, busiest: Long, today: LocalDate) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        // head: label (+ range) + total
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                weekLabel(week.weekStart, today),
                style = HbType.rowTitle.copy(fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold),
                color = Hb.ink,
            )
            Spacer(Modifier.weight(1f))
            Text(
                Format.durationLong(week.totalSeconds),
                style = HbType.label.copy(fontSize = 14.sp, fontWeight = FontWeight.Bold),
                color = Hb.ink,
            )
        }
        // bar (.hb-weekbar) — per-user segments scaled so the busiest week fills the bar
        Row(
            Modifier
                .fillMaxWidth()
                .height(12.dp)
                .clip(HbPill)
                .background(Hb.surface2, HbPill),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            val scale = if (busiest > 0) week.totalSeconds.toFloat() / busiest.toFloat() else 0f
            week.byUser.forEach { (userId, seconds) ->
                if (seconds > 0 && week.totalSeconds > 0) {
                    val frac = (seconds.toFloat() / week.totalSeconds.toFloat()) * scale
                    Box(
                        Modifier
                            .weight(frac.coerceAtLeast(0.0001f))
                            .widthIn(min = 4.dp) // mirrors .hb-weekbar__seg min-width
                            .fillMaxHeight()
                            .clip(HbPill)
                            // week-bar segment honours the per-user avatar-hue override (Teil von #100)
                            .background(Hb.userColor(userId, LocalAvatarHues.current[userId])),
                    )
                }
            }
            // remaining space so shorter weeks don't fill the whole bar
            val remaining = (1f - scale).coerceIn(0f, 1f)
            if (remaining > 0f) Spacer(Modifier.weight(remaining))
        }
        Text(pluralStringResource(R.plurals.time_week_entries, week.count, week.count), style = HbType.small, color = Hb.ink3)
    }
}

// ---------------------------------------------------------------------------
// Weekly aggregation helpers
// ---------------------------------------------------------------------------

private data class WeekStat(
    val weekStart: LocalDate,
    val byUser: List<Pair<String, Long>>,
    val totalSeconds: Long,
    val count: Int,
)

/** Build week stats (Monday-anchored) for weeks that have entries, newest first, max 6. */
private fun buildWeeks(entries: List<TimeEntryDto>): List<WeekStat> {
    val byWeek = LinkedHashMap<LocalDate, MutableList<TimeEntryDto>>()
    entries.forEach { entry ->
        val ws = weekStartOf(entry.startedAt) ?: return@forEach
        byWeek.getOrPut(ws) { mutableListOf() }.add(entry)
    }
    return byWeek.entries
        .map { (weekStart, list) ->
            val perUser = list.groupBy { it.userId }
                .mapValues { (_, l) -> sumSeconds(l) }
                .toList()
                .sortedByDescending { it.second }
            WeekStat(
                weekStart = weekStart,
                byUser = perUser,
                totalSeconds = sumSeconds(list),
                count = list.size,
            )
        }
        .sortedByDescending { it.weekStart }
        .take(6)
}

private val DETAIL_ZONE: ZoneId get() = ZoneId.systemDefault()

private fun weekStartOf(iso: String?): LocalDate? =
    Format.parseInstant(iso)?.atZone(DETAIL_ZONE)?.toLocalDate()?.with(DayOfWeek.MONDAY)

@Composable
private fun weekLabel(weekStart: LocalDate, today: LocalDate): String {
    val currentWeekStart = today.with(DayOfWeek.MONDAY)
    return when (weekStart) {
        currentWeekStart -> stringResource(R.string.time_this_week)
        currentWeekStart.minusWeeks(1) -> stringResource(R.string.time_last_week)
        else -> {
            val end = weekStart.plusDays(6)
            "%02d.%02d.–%02d.%02d.".format(
                weekStart.dayOfMonth, weekStart.monthValue,
                end.dayOfMonth, end.monthValue,
            )
        }
    }
}

/** Sum of entry durations (uses live "now" for any still-running entry). */
private fun sumSeconds(entries: List<TimeEntryDto>): Long =
    entries.sumOf { Format.entrySeconds(it.startedAt, it.stoppedAt) }

/** Convert a Compose [Color] back to a "#rrggbb" hex string for the API. */
private fun hexOf(color: Color): String = "#%06X".format(0xFFFFFF and color.toArgb())
