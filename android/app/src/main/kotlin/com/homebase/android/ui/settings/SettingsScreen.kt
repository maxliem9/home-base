package com.homebase.android.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.homebase.android.data.model.DigestConfigResponse
import com.homebase.android.data.repository.AuthRepository
import com.homebase.android.data.repository.ConfigRepository
import com.homebase.android.ui.abwesenheit.AbsenceViewModel
import com.homebase.android.ui.abwesenheit.AbwSettingsPanel
import com.homebase.android.ui.abwesenheit.buildContext
import com.homebase.android.ui.components.HbAppBar
import com.homebase.android.ui.components.HbAvatar
import com.homebase.android.ui.components.HbButton
import com.homebase.android.ui.components.HbButtonVariant
import com.homebase.android.ui.components.HbCard
import com.homebase.android.ui.components.HbConfirmDialog
import com.homebase.android.ui.components.HbField
import com.homebase.android.ui.components.HbIcon
import com.homebase.android.ui.components.HbIconButton
import com.homebase.android.ui.components.HbIcons
import com.homebase.android.ui.components.HbRadiusSm
import com.homebase.android.ui.components.HbScreenScaffold
import com.homebase.android.ui.components.HbTextField
import com.homebase.android.ui.components.LocalAvatarHues
import com.homebase.android.ui.components.displayName
import com.homebase.android.ui.theme.Hb
import com.homebase.android.ui.theme.HbType
import com.homebase.android.ui.time.TargetsSheet
import com.homebase.android.ui.time.TimeViewModel
import java.time.LocalDate
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Zentrale Einstellungen (#101) — the Android pendant of the web settings hub
 * (web/src/components/settings). A dedicated screen reached from the drawer's account-corner
 * gear, split into per-domain subpages reached from a list (a phone-friendly take on the web's
 * left nav rail). Mirrors the web subpages: Haushalt · Konto (mit Logout, #141) ·
 * Benachrichtigungen · Zeiterfassung · Abwesenheit. The list is built to grow.
 */

private enum class SettingsSub { HOUSEHOLD, KONTO, NOTIFICATIONS, ZEITERFASSUNG, ABWESENHEIT }

@Composable
fun SettingsScreen(
    configRepository: ConfigRepository,
    authRepository: AuthRepository,
    timeViewModel: TimeViewModel,
    absenceViewModel: AbsenceViewModel,
    currentUser: String?,
    householdName: String,
    onHouseholdRenamed: (String) -> Unit,
    onLoggedOut: () -> Unit,
    onClose: () -> Unit,
) {
    var sub by rememberSaveable { mutableStateOf<SettingsSub?>(null) }
    BackHandler { if (sub != null) sub = null else onClose() }

    when (sub) {
        null -> SettingsRoot(onPick = { sub = it }, onClose = onClose)
        SettingsSub.HOUSEHOLD -> HouseholdPage(
            configRepository = configRepository,
            initialName = householdName,
            onRenamed = onHouseholdRenamed,
            onBack = { sub = null },
        )
        SettingsSub.KONTO -> KontoPage(
            authRepository = authRepository,
            currentUser = currentUser,
            onLoggedOut = onLoggedOut,
            onBack = { sub = null },
        )
        SettingsSub.NOTIFICATIONS -> NotificationsPage(
            configRepository = configRepository,
            onBack = { sub = null },
        )
        SettingsSub.ZEITERFASSUNG -> ZeiterfassungPage(
            timeViewModel = timeViewModel,
            onBack = { sub = null },
        )
        SettingsSub.ABWESENHEIT -> AbwesenheitPage(
            absenceViewModel = absenceViewModel,
            onBack = { sub = null },
        )
    }
}

@Composable
private fun SettingsRoot(onPick: (SettingsSub) -> Unit, onClose: () -> Unit) {
    HbScreenScaffold(
        appBar = {
            HbAppBar(
                eyebrow = "Einstellungen",
                title = "Übersicht",
                leftIcon = HbIcons.chevronLeft,
                onLeft = onClose,
                bordered = true,
            )
        },
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp)) {
            Spacer(Modifier.size(8.dp))
            SettingsNavRow(
                icon = HbIcons.home,
                title = "Haushalt",
                subtitle = "Name des Haushalts",
                onClick = { onPick(SettingsSub.HOUSEHOLD) },
            )
            SettingsNavRow(
                icon = HbIcons.lock,
                title = "Konto",
                subtitle = "Passwort ändern",
                onClick = { onPick(SettingsSub.KONTO) },
            )
            SettingsNavRow(
                icon = HbIcons.bell,
                title = "Benachrichtigungen",
                subtitle = "Digest-Uhrzeiten (morgens & abends)",
                onClick = { onPick(SettingsSub.NOTIFICATIONS) },
            )
            SettingsNavRow(
                icon = HbIcons.clock,
                title = "Zeiterfassung",
                subtitle = "Wochensoll",
                onClick = { onPick(SettingsSub.ZEITERFASSUNG) },
            )
            SettingsNavRow(
                icon = HbIcons.calendar,
                title = "Abwesenheit",
                subtitle = "Kontingente, Teilzeit, Feier- & Schließtage",
                onClick = { onPick(SettingsSub.ABWESENHEIT) },
            )
        }
    }
}

@Composable
private fun SettingsNavRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(HbRadiusSm)
            .clickable { onClick() }
            .padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        HbIcon(icon, size = 21.dp, tint = Hb.ink2)
        Column(Modifier.weight(1f)) {
            Text(title, style = HbType.rowTitle.copy(fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold), color = Hb.ink)
            Text(subtitle, style = HbType.small.copy(fontSize = 12.5.sp), color = Hb.ink3)
        }
        HbIcon(HbIcons.chevronRight, size = 18.dp, tint = Hb.ink3)
    }
}

@Composable
private fun HouseholdPage(
    configRepository: ConfigRepository,
    initialName: String,
    onRenamed: (String) -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(initialName) }
    var saving by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // Auto-clear the "Gespeichert" confirmation, mirroring the web's 2.5s timeout.
    LaunchedEffect(saved) { if (saved) { delay(2500); saved = false } }

    val save = {
        val trimmed = name.trim()
        if (trimmed.isNotEmpty() && !saving) {
            saving = true
            error = null
            saved = false
            scope.launch {
                configRepository.updateHouseholdName(trimmed)
                    .onSuccess { persisted -> name = persisted; onRenamed(persisted); saved = true }
                    .onFailure { e -> error = e.message ?: "Speichern fehlgeschlagen." }
                saving = false
            }
        }
    }

    HbScreenScaffold(
        appBar = {
            HbAppBar(
                eyebrow = "Einstellungen",
                title = "Haushalt",
                leftIcon = HbIcons.chevronLeft,
                onLeft = onBack,
                bordered = true,
            )
        },
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp)) {
            Spacer(Modifier.size(10.dp))
            HbCard {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            "Haushaltsname",
                            style = HbType.rowTitle.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                            color = Hb.ink,
                        )
                        Text(
                            "Wird in der Seitenleiste angezeigt. Beide können ihn ändern.",
                            style = HbType.small.copy(fontSize = 12.5.sp),
                            color = Hb.ink3,
                        )
                    }
                    HbField("Name") {
                        HbTextField(
                            value = name,
                            onValueChange = { name = it.take(60); saved = false; error = null },
                            placeholder = "Mäxchen",
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        HbButton("Speichern", onClick = save, enabled = !saving && name.trim().isNotEmpty())
                        if (saved) SavedHint()
                    }
                    if (error != null) ErrorText(error!!)
                }
            }
        }
    }
}

@Composable
private fun KontoPage(
    authRepository: AuthRepository,
    currentUser: String?,
    onLoggedOut: () -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var current by remember { mutableStateOf("") }
    var next by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmLogout by remember { mutableStateOf(false) }

    LaunchedEffect(done) { if (done) { delay(3000); done = false } }

    val canSubmit = !saving && current.isNotEmpty() && next.isNotEmpty() && confirm.isNotEmpty()
    // Client-side checks mirror the web (length ≥ 8, confirm match) so the only server 400 is a
    // wrong current password — see AuthRepository.changePassword.
    val submit = {
        error = null
        done = false
        when {
            next.length < 8 -> error = "Das neue Passwort muss mindestens 8 Zeichen haben."
            next != confirm -> error = "Die neuen Passwörter stimmen nicht überein."
            canSubmit -> {
                saving = true
                scope.launch {
                    authRepository.changePassword(current, next)
                        .onSuccess { current = ""; next = ""; confirm = ""; done = true }
                        .onFailure { e -> error = e.message ?: "Passwort konnte nicht geändert werden." }
                    saving = false
                }
            }
        }
        Unit
    }

    HbScreenScaffold(
        appBar = {
            HbAppBar(eyebrow = "Einstellungen", title = "Konto", leftIcon = HbIcons.chevronLeft, onLeft = onBack, bordered = true)
        },
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp)) {
            Spacer(Modifier.size(10.dp))
            HbCard {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        HbAvatar(currentUser, size = 28.dp)
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Passwort ändern",
                                style = HbType.rowTitle.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                                color = Hb.ink,
                            )
                            Text(
                                "Angemeldet als ${displayName(currentUser)}",
                                style = HbType.small.copy(fontSize = 12.5.sp),
                                color = Hb.ink3,
                            )
                        }
                    }
                    HbField("Aktuelles Passwort") {
                        HbTextField(value = current, onValueChange = { current = it; error = null; done = false }, password = true)
                    }
                    HbField("Neues Passwort") {
                        HbTextField(value = next, onValueChange = { next = it; error = null; done = false }, password = true)
                    }
                    HbField("Neues Passwort bestätigen") {
                        HbTextField(value = confirm, onValueChange = { confirm = it; error = null; done = false }, password = true)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        HbButton("Passwort ändern", onClick = submit, icon = HbIcons.lock, enabled = canSubmit)
                        if (done) SavedHint("Geändert")
                    }
                    if (error != null) ErrorText(error!!)
                }
            }
            // Abmelden (#141): the only UI caller of AuthRepository.logout(). Confirm first
            // (an accidental tap shouldn't sign you out), then clear the encrypted JWT and let
            // the auth-state observer in MainActivity route back to the login screen.
            HbCard {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            "Abmelden",
                            style = HbType.rowTitle.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                            color = Hb.ink,
                        )
                        Text(
                            "Beendet die Sitzung auf diesem Gerät. Du musst dich danach erneut anmelden.",
                            style = HbType.small.copy(fontSize = 12.5.sp),
                            color = Hb.ink3,
                        )
                    }
                    HbButton(
                        "Abmelden",
                        onClick = { confirmLogout = true },
                        icon = HbIcons.logout,
                        variant = HbButtonVariant.Danger,
                    )
                }
            }
        }
    }

    if (confirmLogout) {
        HbConfirmDialog(
            message = "Du wirst abgemeldet und musst dich danach erneut anmelden.",
            confirmLabel = "Abmelden",
            onConfirm = {
                confirmLogout = false
                scope.launch {
                    authRepository.logout()
                    onLoggedOut()
                }
            },
            onDismiss = { confirmLogout = false },
        )
    }
}

@Composable
private fun NotificationsPage(configRepository: ConfigRepository, onBack: () -> Unit) {
    HbScreenScaffold(
        appBar = {
            HbAppBar(eyebrow = "Einstellungen", title = "Benachrichtigungen", leftIcon = HbIcons.chevronLeft, onLeft = onBack, bordered = true)
        },
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Spacer(Modifier.size(10.dp))
            // Morning briefing first (chronological), then the evening recap. Both are Telegram
            // digests with the same {time, enabled} contract — only the endpoint + copy differ.
            DigestTimeCard(
                title = "Morgen-Digest",
                hint = "Morgendliche Übersicht: heute fällig, überfällig, Inbox, Abwesenheiten und Kita-Schließtage.",
                placeholder = "07:00",
                load = configRepository::getMorningDigest,
                save = configRepository::updateMorningDigestTime,
            )
            DigestTimeCard(
                title = "Abend-Digest",
                hint = "Abendliche Zusammenfassung: heute erledigt, neue Inbox, morgen fällig.",
                placeholder = "20:00",
                load = configRepository::getDigest,
                save = configRepository::updateDigestTime,
            )
        }
    }
}

// One Telegram-digest time card (morning briefing or evening recap) — identical control,
// validation and persistence; only the heading/hint/endpoint differ. `enabled` reports whether
// Telegram is configured server-side; when not, an inactive note shows but the time stays editable.
@Composable
private fun DigestTimeCard(
    title: String,
    hint: String,
    placeholder: String,
    load: suspend () -> Result<DigestConfigResponse>,
    save: suspend (String) -> Result<String>,
) {
    val scope = rememberCoroutineScope()
    var time by remember { mutableStateOf("") }
    var enabled by remember { mutableStateOf(true) }
    var loaded by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        load().onSuccess { time = it.time; enabled = it.enabled }
        loaded = true
    }
    LaunchedEffect(saved) { if (saved) { delay(2500); saved = false } }

    val valid = time.matches(Regex("""\d{2}:\d{2}"""))
    val doSave = {
        if (loaded && valid && !saving) {
            saving = true
            error = null
            saved = false
            scope.launch {
                save(time)
                    .onSuccess { persisted -> time = persisted; saved = true }
                    .onFailure { e -> error = e.message ?: "Speichern fehlgeschlagen." }
                saving = false
            }
        }
        Unit
    }

    HbCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    title,
                    style = HbType.rowTitle.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                    color = Hb.ink,
                )
                Text(
                    hint,
                    style = HbType.small.copy(fontSize = 12.5.sp),
                    color = Hb.ink3,
                )
            }
            if (loaded && !enabled) {
                Text(
                    "Telegram ist nicht konfiguriert — der Digest ist derzeit inaktiv. Die Uhrzeit kannst du trotzdem setzen.",
                    style = HbType.small.copy(fontSize = 12.5.sp),
                    color = Hb.ink3,
                )
            }
            HbField("Uhrzeit (HH:MM)") {
                HbTextField(
                    value = time,
                    onValueChange = { time = it.take(5); error = null; saved = false },
                    placeholder = placeholder,
                    mono = true,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                HbButton("Speichern", onClick = doSave, enabled = loaded && valid && !saving)
                if (saved) SavedHint()
            }
            Text(
                "Änderungen greifen ab dem nächsten geplanten Lauf.",
                style = HbType.small.copy(fontSize = 12.sp),
                color = Hb.ink3,
            )
            if (error != null) ErrorText(error!!)
        }
    }
}

/** Small "saved" confirmation row (check + label), shared by the settings pages. */
@Composable
private fun SavedHint(label: String = "Gespeichert") {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        HbIcon(HbIcons.check, size = 16.dp, tint = Hb.ink3)
        Text(label, style = HbType.small, color = Hb.ink3)
    }
}

/** Inline error line, shared by the settings pages. */
@Composable
private fun ErrorText(message: String) {
    Text(message, style = HbType.small.copy(fontSize = 13.sp), color = Hb.danger)
}

@Composable
private fun ZeiterfassungPage(timeViewModel: TimeViewModel, onBack: () -> Unit) {
    val state by timeViewModel.uiState.collectAsStateWithLifecycle()
    var showTargets by remember { mutableStateOf(false) }

    // Active projects + archived ones that still carry a target, so an archived project's
    // Wochensoll stays editable — the same rule the tracker used before the move (#55).
    val targetProjects = state.projects.filter { p ->
        !p.archived || state.targets.any { it.projectId == p.id && (it.weeklyHours > 0 || it.isDefault) }
    }
    val configuredUsers = state.users.count { u ->
        state.targets.any { it.userId == u && (it.weeklyHours > 0 || it.isDefault) }
    }

    Box(Modifier.fillMaxSize()) {
        HbScreenScaffold(
            appBar = {
                HbAppBar(
                    eyebrow = "Einstellungen",
                    title = "Zeiterfassung",
                    leftIcon = HbIcons.chevronLeft,
                    onLeft = onBack,
                    bordered = true,
                )
            },
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp)) {
                Spacer(Modifier.size(10.dp))
                HbCard {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(
                                "Wochensoll",
                                style = HbType.rowTitle.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                                color = Hb.ink,
                            )
                            Text(
                                "Wochenstunden pro Person und Projekt. Urlaub, Krankheit und Feiertage werden dem Standard-Projekt gutgeschrieben.",
                                style = HbType.small.copy(fontSize = 12.5.sp),
                                color = Hb.ink3,
                            )
                        }
                        Text(
                            when {
                                state.projects.isEmpty() -> "Lege zuerst in der Zeiterfassung ein Projekt an."
                                configuredUsers == 0 -> "Noch kein Wochensoll konfiguriert."
                                else -> "Für $configuredUsers von ${state.users.size} Personen konfiguriert."
                            },
                            style = HbType.small.copy(fontSize = 12.5.sp),
                            color = Hb.ink3,
                        )
                        HbButton(
                            "Wochensoll bearbeiten",
                            onClick = { showTargets = true },
                            icon = HbIcons.edit,
                            enabled = state.projects.isNotEmpty(),
                        )
                    }
                }
            }
        }

        // Reuses the tracker's editor (now internal); central settings is its only entry point.
        if (showTargets) {
            TargetsSheet(
                users = state.users,
                projects = targetProjects,
                targets = state.targets,
                onSave = { changes -> timeViewModel.saveTargets(changes); showTargets = false },
                onDismiss = { showTargets = false },
            )
        }
    }
}

// The Abwesenheit window the settings PUT accepts — so the year stepper can never produce a
// year the backend would reject (mirrors web AbwesenheitSettings' YEAR_MIN/MAX, #99).
private const val ABS_YEAR_MIN = 2000
private const val ABS_YEAR_MAX = 2200

/**
 * Einstellungen → Abwesenheit (#101). The Familienkalender configuration relocated into the
 * central hub — pendant of the web's `AbwesenheitSettings`. Reuses the shared [AbwSettingsPanel]
 * (the same body the calendar's gear sheet shows) and brings its own year stepper, since the
 * per-person Kontingente/Übertrag are annual. The calendar screen keeps its gear too, so this
 * is an additional, discoverable entry point rather than a move.
 */
@Composable
private fun AbwesenheitPage(absenceViewModel: AbsenceViewModel, onBack: () -> Unit) {
    val state by absenceViewModel.uiState.collectAsStateWithLifecycle()
    val data = state.data
    val userIds = data.users
    var year by rememberSaveable { mutableStateOf(LocalDate.now().year) }

    // Honour per-user avatar-hue overrides like the calendar, so any colour cue stays consistent.
    val avatarHues = LocalAvatarHues.current
    val ctx = remember(data, year, userIds, avatarHues) { buildContext(data, year, userIds, avatarHues) }

    HbScreenScaffold(
        appBar = {
            HbAppBar(
                eyebrow = "Einstellungen",
                title = "Abwesenheit",
                leftIcon = HbIcons.chevronLeft,
                onLeft = onBack,
                bordered = true,
            )
        },
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp)) {
            Spacer(Modifier.size(10.dp))
            HbCard {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(
                                "Kontingente & Kalender",
                                style = HbType.rowTitle.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                                color = Hb.ink,
                            )
                            Text(
                                "Urlaubskontingent, Übertrag, Bundesland und Teilzeit pro Person; dazu " +
                                    "haushaltsweite Kita-Schließtage. Kontingent und Übertrag gelten pro Jahr.",
                                style = HbType.small.copy(fontSize = 12.5.sp),
                                color = Hb.ink3,
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            HbIconButton(
                                HbIcons.chevronLeft,
                                { year = (year - 1).coerceIn(ABS_YEAR_MIN, ABS_YEAR_MAX) },
                                iconSize = 18.dp,
                            )
                            Text("$year", style = HbType.mono(16.0, FontWeight.SemiBold), color = Hb.ink)
                            HbIconButton(
                                HbIcons.chevronRight,
                                { year = (year + 1).coerceIn(ABS_YEAR_MIN, ABS_YEAR_MAX) },
                                iconSize = 18.dp,
                            )
                        }
                    }
                    when {
                        state.isLoading && userIds.isEmpty() ->
                            Text("Lädt …", style = HbType.small.copy(fontSize = 12.5.sp), color = Hb.ink3)
                        userIds.isEmpty() ->
                            Text("Kalender konnte nicht geladen werden.", style = HbType.small.copy(fontSize = 12.5.sp), color = Hb.ink3)
                        else -> AbwSettingsPanel(ctx, data, userIds, year, absenceViewModel)
                    }
                }
            }
        }
    }
}
