package com.homebase.android.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.homebase.android.R
import com.homebase.android.data.model.DigestConfigResponse
import com.homebase.android.data.model.ProjectDto
import com.homebase.android.data.model.RecurringConfigResponse
import com.homebase.android.data.repository.AuthRepository
import com.homebase.android.data.repository.ConfigRepository
import com.homebase.android.ui.abwesenheit.AbsenceViewModel
import com.homebase.android.ui.abwesenheit.AbwSettingsPanel
import com.homebase.android.ui.abwesenheit.buildContext
import com.homebase.android.ui.components.HbAppBar
import com.homebase.android.ui.components.HbAvatar
import com.homebase.android.ui.components.HbBottomSheet
import com.homebase.android.ui.components.HbButton
import com.homebase.android.ui.components.HbButtonVariant
import com.homebase.android.ui.components.HbCard
import com.homebase.android.ui.components.HbCheck
import com.homebase.android.ui.components.HbConfirmDialog
import com.homebase.android.ui.components.HbField
import com.homebase.android.ui.components.HbIcon
import com.homebase.android.ui.components.HbIconButton
import com.homebase.android.ui.components.HbIcons
import com.homebase.android.ui.components.HbPill
import com.homebase.android.ui.components.HbRadiusSm
import com.homebase.android.ui.components.HbScreenScaffold
import com.homebase.android.ui.components.HbTextField
import com.homebase.android.ui.components.HbToast
import com.homebase.android.ui.components.LocalAvatarHues
import com.homebase.android.ui.components.displayName
import com.homebase.android.ui.theme.Hb
import com.homebase.android.ui.theme.HbType
import com.homebase.android.ui.time.TargetsSheet
import com.homebase.android.ui.time.TimeViewModel
import com.homebase.android.ui.util.FileShare
import com.homebase.android.ui.util.Format
import com.homebase.android.ui.util.LocaleManager
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
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
                eyebrow = stringResource(R.string.settings_eyebrow),
                title = stringResource(R.string.settings_overview),
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
                title = stringResource(R.string.settings_household),
                subtitle = stringResource(R.string.settings_household_sub),
                onClick = { onPick(SettingsSub.HOUSEHOLD) },
            )
            SettingsNavRow(
                icon = HbIcons.lock,
                title = stringResource(R.string.settings_account),
                subtitle = stringResource(R.string.settings_account_sub),
                onClick = { onPick(SettingsSub.KONTO) },
            )
            SettingsNavRow(
                icon = HbIcons.bell,
                title = stringResource(R.string.settings_notifications),
                subtitle = stringResource(R.string.settings_notifications_sub),
                onClick = { onPick(SettingsSub.NOTIFICATIONS) },
            )
            SettingsNavRow(
                icon = HbIcons.clock,
                title = stringResource(R.string.settings_time),
                subtitle = stringResource(R.string.settings_time_sub),
                onClick = { onPick(SettingsSub.ZEITERFASSUNG) },
            )
            SettingsNavRow(
                icon = HbIcons.calendar,
                title = stringResource(R.string.settings_absence),
                subtitle = stringResource(R.string.settings_absence_sub),
                onClick = { onPick(SettingsSub.ABWESENHEIT) },
            )
            // Language switcher (Issue #6) — flips the per-app locale (de/en) immediately and
            // persists it. Inline radio-style card so it needs no own subpage.
            LanguageCard()
        }
    }
}

/**
 * Sprache / Language switcher (Issue #6). Two options (Deutsch/English) wired to
 * [LocaleManager], which uses AppCompat's per-app locales API; selecting one recreates the
 * activity so the whole UI re-renders in the new language right away. Styled like the other
 * settings rows (icon + title + subtitle), with a trailing segmented control for the choice.
 */
@Composable
private fun LanguageCard() {
    val current = LocaleManager.current()
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        HbIcon(HbIcons.users, size = 21.dp, tint = Hb.ink2)
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.settings_language),
                style = HbType.rowTitle.copy(fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold),
                color = Hb.ink,
            )
            Text(stringResource(R.string.settings_language_sub), style = HbType.small.copy(fontSize = 12.5.sp), color = Hb.ink3)
        }
        Row(
            Modifier.clip(HbPill).background(Hb.surface2, HbPill).padding(2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            LanguageChip(
                label = stringResource(R.string.settings_language_de),
                active = current == LocaleManager.Language.GERMAN,
                onClick = { LocaleManager.set(LocaleManager.Language.GERMAN) },
            )
            LanguageChip(
                label = stringResource(R.string.settings_language_en),
                active = current == LocaleManager.Language.ENGLISH,
                onClick = { LocaleManager.set(LocaleManager.Language.ENGLISH) },
            )
        }
    }
}

@Composable
private fun LanguageChip(label: String, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(HbPill)
            .background(if (active) Hb.accent else Color.Transparent, HbPill)
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = HbType.label.copy(fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold),
            color = if (active) Hb.onAccent else Hb.ink2,
        )
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

    val saveFailed = stringResource(R.string.common_save_failed)

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
                    .onFailure { e -> error = e.message ?: saveFailed }
                saving = false
            }
        }
    }

    HbScreenScaffold(
        appBar = {
            HbAppBar(
                eyebrow = stringResource(R.string.settings_eyebrow),
                title = stringResource(R.string.settings_household),
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
                            stringResource(R.string.settings_household_name),
                            style = HbType.rowTitle.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                            color = Hb.ink,
                        )
                        Text(
                            stringResource(R.string.settings_household_name_hint),
                            style = HbType.small.copy(fontSize = 12.5.sp),
                            color = Hb.ink3,
                        )
                    }
                    HbField(stringResource(R.string.common_field_name)) {
                        HbTextField(
                            value = name,
                            onValueChange = { name = it.take(60); saved = false; error = null },
                            placeholder = stringResource(R.string.settings_household_placeholder),
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        HbButton(stringResource(R.string.action_save), onClick = save, enabled = !saving && name.trim().isNotEmpty())
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

    val errPwMin = stringResource(R.string.settings_password_min)
    val errPwMismatch = stringResource(R.string.settings_password_mismatch)
    val errPwFailed = stringResource(R.string.settings_password_failed)

    LaunchedEffect(done) { if (done) { delay(3000); done = false } }

    val canSubmit = !saving && current.isNotEmpty() && next.isNotEmpty() && confirm.isNotEmpty()
    // Client-side checks mirror the web (length ≥ 8, confirm match) so the only server 400 is a
    // wrong current password — see AuthRepository.changePassword.
    val submit = {
        error = null
        done = false
        when {
            next.length < 8 -> error = errPwMin
            next != confirm -> error = errPwMismatch
            canSubmit -> {
                saving = true
                scope.launch {
                    authRepository.changePassword(current, next)
                        .onSuccess { current = ""; next = ""; confirm = ""; done = true }
                        .onFailure { e -> error = e.message ?: errPwFailed }
                    saving = false
                }
            }
        }
        Unit
    }

    HbScreenScaffold(
        appBar = {
            HbAppBar(eyebrow = stringResource(R.string.settings_eyebrow), title = stringResource(R.string.settings_account), leftIcon = HbIcons.chevronLeft, onLeft = onBack, bordered = true)
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
                                stringResource(R.string.settings_change_password),
                                style = HbType.rowTitle.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                                color = Hb.ink,
                            )
                            Text(
                                stringResource(R.string.settings_logged_in_as, displayName(currentUser)),
                                style = HbType.small.copy(fontSize = 12.5.sp),
                                color = Hb.ink3,
                            )
                        }
                    }
                    HbField(stringResource(R.string.settings_current_password)) {
                        HbTextField(value = current, onValueChange = { current = it; error = null; done = false }, password = true)
                    }
                    HbField(stringResource(R.string.settings_new_password)) {
                        HbTextField(value = next, onValueChange = { next = it; error = null; done = false }, password = true)
                    }
                    HbField(stringResource(R.string.settings_confirm_password)) {
                        HbTextField(value = confirm, onValueChange = { confirm = it; error = null; done = false }, password = true)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        HbButton(stringResource(R.string.settings_change_password), onClick = submit, icon = HbIcons.lock, enabled = canSubmit)
                        if (done) SavedHint(stringResource(R.string.settings_changed))
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
                            stringResource(R.string.settings_logout),
                            style = HbType.rowTitle.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                            color = Hb.ink,
                        )
                        Text(
                            stringResource(R.string.settings_logout_hint),
                            style = HbType.small.copy(fontSize = 12.5.sp),
                            color = Hb.ink3,
                        )
                    }
                    HbButton(
                        stringResource(R.string.settings_logout),
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
            message = stringResource(R.string.settings_logout_confirm),
            confirmLabel = stringResource(R.string.settings_logout),
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
            HbAppBar(eyebrow = stringResource(R.string.settings_eyebrow), title = stringResource(R.string.settings_notifications), leftIcon = HbIcons.chevronLeft, onLeft = onBack, bordered = true)
        },
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Spacer(Modifier.size(10.dp))
            // Morning briefing first (chronological), then the evening recap, then the recurring
            // safety-net — same order as the web's NotificationsSettings. The two digests share the
            // {time, enabled, sections} contract (only endpoint + copy differ, #189); the recurring
            // card is always-on, so just a time (#200).
            DigestCard(
                title = stringResource(R.string.settings_morning_digest),
                hint = stringResource(R.string.settings_morning_digest_hint),
                placeholder = "07:00",
                load = configRepository::getMorningDigest,
                save = configRepository::updateMorningDigest,
            )
            DigestCard(
                title = stringResource(R.string.settings_evening_digest),
                hint = stringResource(R.string.settings_evening_digest_hint),
                placeholder = "20:00",
                load = configRepository::getDigest,
                save = configRepository::updateDigest,
            )
            RecurringCard(configRepository = configRepository)
        }
    }
}

// Section-id → localized label, mirroring web/src/i18n → settings.digestSections (#189). The
// backend's availableSections drives which rows show (in its display order); this only labels them,
// with a fallback to the raw id so a new server-side section never renders blank.
@Composable
private fun digestSectionLabel(id: String): String = when (id) {
    "evening_done_today" -> stringResource(R.string.digest_section_evening_done_today)
    "evening_new_inbox" -> stringResource(R.string.digest_section_evening_new_inbox)
    "evening_due_tomorrow" -> stringResource(R.string.digest_section_evening_due_tomorrow)
    "evening_absent_tomorrow" -> stringResource(R.string.digest_section_evening_absent_tomorrow)
    "evening_kita_tomorrow" -> stringResource(R.string.digest_section_evening_kita_tomorrow)
    "morning_due_today" -> stringResource(R.string.digest_section_morning_due_today)
    "morning_overdue" -> stringResource(R.string.digest_section_morning_overdue)
    "morning_inbox" -> stringResource(R.string.digest_section_morning_inbox)
    "morning_absent" -> stringResource(R.string.digest_section_morning_absent)
    "morning_kita" -> stringResource(R.string.digest_section_morning_kita)
    else -> id
}

/**
 * One Telegram-digest card (morning briefing or evening recap), the Android pendant of the web's
 * NotificationsSettings DigestCard (#189) — identical {time, enabled, sections} contract; only the
 * heading/hint/endpoint differ. On/off [enabled] toggles whether the digest sends; the section
 * checkbox group picks which content blocks it renders (driven by the backend's availableSections);
 * the time stays as before. `telegramConfigured` only drives the inactive hint — every control
 * stays editable regardless. Save sends all three fields in one PUT.
 */
@Composable
private fun DigestCard(
    title: String,
    hint: String,
    placeholder: String,
    load: suspend () -> Result<DigestConfigResponse>,
    save: suspend (time: String, enabled: Boolean, sections: List<String>) -> Result<DigestConfigResponse>,
) {
    val scope = rememberCoroutineScope()
    var time by remember { mutableStateOf("") }
    var enabled by remember { mutableStateOf(true) }
    var telegramConfigured by remember { mutableStateOf(true) }
    var available by remember { mutableStateOf<List<String>>(emptyList()) }
    // Selected section ids; serialised back in `available` order on save so the payload is stable.
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var loaded by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val saveFailed = stringResource(R.string.common_save_failed)

    // Enable editing only after the GET lands, so a late response can't clobber freshly-typed
    // values (same guard as the household-name + web digest cards).
    LaunchedEffect(Unit) {
        load().onSuccess {
            time = it.time
            enabled = it.enabled
            telegramConfigured = it.telegramConfigured
            available = it.availableSections
            selected = it.sections.toSet()
        }
        loaded = true
    }
    LaunchedEffect(saved) { if (saved) { delay(2500); saved = false } }

    val dirty = { error = null; saved = false }
    val valid = time.matches(Regex("""\d{2}:\d{2}"""))
    val doSave = {
        if (loaded && valid && !saving) {
            saving = true
            error = null
            saved = false
            // Persist in the backend's display order so the stored value stays stable + readable.
            val sections = available.filter { it in selected }
            scope.launch {
                save(time, enabled, sections)
                    .onSuccess { cfg ->
                        time = cfg.time
                        enabled = cfg.enabled
                        selected = cfg.sections.toSet()
                        saved = true
                    }
                    .onFailure { e -> error = e.message ?: saveFailed }
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
            if (loaded && !telegramConfigured) {
                Text(
                    stringResource(R.string.settings_telegram_inactive),
                    style = HbType.small.copy(fontSize = 12.5.sp),
                    color = Hb.ink3,
                )
            }

            // On/off toggle (#189): a deselected digest skips sending entirely; the rest stays editable.
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(HbRadiusSm)
                    .clickable(enabled = loaded) { enabled = !enabled; dirty() }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                HbCheck(checked = enabled, onCheckedChange = { if (loaded) { enabled = !enabled; dirty() } }, size = 22.dp)
                Text(stringResource(R.string.settings_digest_active), style = HbType.body.copy(fontSize = 14.sp), color = Hb.ink)
            }

            HbField(stringResource(R.string.settings_time_hhmm)) {
                HbTextField(
                    value = time,
                    onValueChange = { time = it.take(5); dirty() },
                    placeholder = placeholder,
                    mono = true,
                )
            }

            // Per-section checkbox group (#189): which content blocks this digest renders.
            if (available.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        stringResource(R.string.settings_digest_sections),
                        style = HbType.small.copy(fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold),
                        color = Hb.ink2,
                    )
                    Text(
                        stringResource(R.string.settings_digest_sections_hint),
                        style = HbType.small.copy(fontSize = 12.sp),
                        color = Hb.ink3,
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    available.forEach { id ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(HbRadiusSm)
                                .clickable(enabled = loaded) { toggleSection(id, selected) { selected = it }; dirty() }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            HbCheck(
                                checked = id in selected,
                                onCheckedChange = { if (loaded) { toggleSection(id, selected) { selected = it }; dirty() } },
                                size = 22.dp,
                            )
                            Text(
                                digestSectionLabel(id),
                                style = HbType.body.copy(fontSize = 14.sp),
                                color = Hb.ink,
                            )
                        }
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                HbButton(stringResource(R.string.action_save), onClick = doSave, enabled = loaded && valid && !saving)
                if (saved) SavedHint()
            }
            Text(
                stringResource(R.string.settings_changes_next_run),
                style = HbType.small.copy(fontSize = 12.sp),
                color = Hb.ink3,
            )
            if (error != null) ErrorText(error!!)
        }
    }
}

/** Flips one section id in/out of [current], handing the new set to [onChange]. */
private fun toggleSection(id: String, current: Set<String>, onChange: (Set<String>) -> Unit) {
    onChange(if (id in current) current - id else current + id)
}

/**
 * Recurring-todo safety-net run time (#200) — the Android pendant of the web's NotificationsSettings
 * RecurringCard. The scheduler is always-on (it rolls overdue, still-open recurring todos forward to
 * the current period), so — unlike the digests — there's no enabled flag or section group, just a
 * validated HH:mm time. Same load-then-enable guard, HH:mm validation and persistence as the digest
 * cards. Labels mirror web/src/i18n/de.ts → settings.recurring*.
 */
@Composable
private fun RecurringCard(configRepository: ConfigRepository) {
    val scope = rememberCoroutineScope()
    var time by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val saveFailed = stringResource(R.string.common_save_failed)

    // Enable editing only after the GET lands, so a late response can't clobber a freshly-typed
    // value (same guard as the household-name + digest cards).
    LaunchedEffect(Unit) {
        configRepository.getRecurring().onSuccess { time = it.time }
        loaded = true
    }
    LaunchedEffect(saved) { if (saved) { delay(2500); saved = false } }

    val dirty = { error = null; saved = false }
    val valid = time.matches(Regex("""\d{2}:\d{2}"""))
    val doSave = {
        if (loaded && valid && !saving) {
            saving = true
            error = null
            saved = false
            scope.launch {
                configRepository.updateRecurring(time)
                    .onSuccess { cfg -> time = cfg.time; saved = true }
                    .onFailure { e -> error = e.message ?: saveFailed }
                saving = false
            }
        }
        Unit
    }

    HbCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    stringResource(R.string.settings_recurring_title),
                    style = HbType.rowTitle.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                    color = Hb.ink,
                )
                Text(
                    stringResource(R.string.settings_recurring_hint),
                    style = HbType.small.copy(fontSize = 12.5.sp),
                    color = Hb.ink3,
                )
            }

            HbField(stringResource(R.string.settings_time_hhmm)) {
                HbTextField(
                    value = time,
                    onValueChange = { time = it.take(5); dirty() },
                    placeholder = "00:30",
                    mono = true,
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                HbButton(stringResource(R.string.action_save), onClick = doSave, enabled = loaded && valid && !saving)
                if (saved) SavedHint()
            }
            Text(
                stringResource(R.string.settings_changes_next_run),
                style = HbType.small.copy(fontSize = 12.sp),
                color = Hb.ink3,
            )
            if (error != null) ErrorText(error!!)
        }
    }
}

/** Small "saved" confirmation row (check + label), shared by the settings pages. */
@Composable
private fun SavedHint(label: String = stringResource(R.string.settings_saved)) {
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

/**
 * Einstellungen → Zeiterfassung (#175). The Android pendant of the web's `TimeSettings`:
 * project management (rename/recolour/archive + "Archivierte anzeigen"), the Wochensoll
 * editor, and a CSV export with an optional date-range/project filter. Mirrors the web's
 * card grouping and labels. Project create stays on the tracker screen (start-a-timer flow);
 * this page owns the management actions that have no other entry point on Android.
 */
@Composable
private fun ZeiterfassungPage(timeViewModel: TimeViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val state by timeViewModel.uiState.collectAsStateWithLifecycle()
    var showTargets by remember { mutableStateOf(false) }
    var editProject by remember { mutableStateOf<ProjectDto?>(null) }
    var showArchived by remember { mutableStateOf(false) }
    var showExport by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(toast) { if (toast != null) { delay(3500); toast = null } }

    // Active projects + archived ones that still carry a target, so an archived project's
    // Wochensoll stays editable — the same rule the tracker used before the move (#55).
    val targetProjects = state.projects.filter { p ->
        !p.archived || state.targets.any { it.projectId == p.id && (it.weeklyHours > 0 || it.isDefault) }
    }
    val configuredUsers = state.users.count { u ->
        state.targets.any { it.userId == u && (it.weeklyHours > 0 || it.isDefault) }
    }

    val activeProjects = state.projects.filter { !it.archived }
    val hasArchived = state.projects.any { it.archived }
    val shownProjects = if (showArchived) state.projects else activeProjects

    // Captured for the export callback, which runs outside composition.
    val csvChooser = stringResource(R.string.settings_csv_chooser)
    val exportFailed = stringResource(R.string.settings_export_failed)

    Box(Modifier.fillMaxSize()) {
        HbScreenScaffold(
            appBar = {
                HbAppBar(
                    eyebrow = stringResource(R.string.settings_eyebrow),
                    title = stringResource(R.string.settings_time),
                    leftIcon = HbIcons.chevronLeft,
                    onLeft = onBack,
                    bordered = true,
                )
            },
        ) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Spacer(Modifier.size(10.dp))

                // --- Projekt-Verwaltung (mirrors web t.settings.projectsTitle/projectsHint) ---
                HbCard {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(
                                stringResource(R.string.settings_projects),
                                style = HbType.rowTitle.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                                color = Hb.ink,
                            )
                            Text(
                                stringResource(R.string.settings_projects_hint),
                                style = HbType.small.copy(fontSize = 12.5.sp),
                                color = Hb.ink3,
                            )
                        }
                        if (state.projects.isEmpty()) {
                            Text(
                                if (state.isLoading) stringResource(R.string.common_loading) else stringResource(R.string.settings_no_projects),
                                style = HbType.small.copy(fontSize = 12.5.sp),
                                color = Hb.ink3,
                            )
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                shownProjects.forEach { p ->
                                    ProjectRow(
                                        project = p,
                                        onEdit = { editProject = p },
                                        onToggleArchive = { timeViewModel.setArchived(p.id, !p.archived) },
                                    )
                                }
                            }
                            if (hasArchived) {
                                Text(
                                    if (showArchived) stringResource(R.string.settings_hide_archived) else stringResource(R.string.settings_show_archived),
                                    style = HbType.small.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                                    color = Hb.accentInk,
                                    modifier = Modifier
                                        .clip(HbRadiusSm)
                                        .clickable { showArchived = !showArchived }
                                        .padding(vertical = 4.dp, horizontal = 2.dp),
                                )
                            }
                        }
                    }
                }

                // --- Wochensoll ---
                HbCard {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(
                                stringResource(R.string.settings_weektargets),
                                style = HbType.rowTitle.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                                color = Hb.ink,
                            )
                            Text(
                                stringResource(R.string.settings_weektargets_hint),
                                style = HbType.small.copy(fontSize = 12.5.sp),
                                color = Hb.ink3,
                            )
                        }
                        Text(
                            when {
                                state.projects.isEmpty() -> stringResource(R.string.settings_weektargets_no_project)
                                configuredUsers == 0 -> stringResource(R.string.settings_weektargets_none)
                                else -> stringResource(R.string.settings_weektargets_configured, configuredUsers, state.users.size)
                            },
                            style = HbType.small.copy(fontSize = 12.5.sp),
                            color = Hb.ink3,
                        )
                        HbButton(
                            stringResource(R.string.settings_weektargets_edit),
                            onClick = { showTargets = true },
                            icon = HbIcons.edit,
                            enabled = state.projects.isNotEmpty(),
                        )
                    }
                }

                // --- CSV-Export (mirrors web t.time.exportCsv / t.settings.exportOpen) ---
                HbCard {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(
                                stringResource(R.string.settings_csv_export),
                                style = HbType.rowTitle.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                                color = Hb.ink,
                            )
                            Text(
                                stringResource(R.string.settings_csv_export_hint),
                                style = HbType.small.copy(fontSize = 12.5.sp),
                                color = Hb.ink3,
                            )
                        }
                        HbButton(
                            stringResource(R.string.settings_csv_export_button),
                            onClick = { showExport = true },
                            icon = HbIcons.send,
                            variant = HbButtonVariant.Secondary,
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

        editProject?.let { p ->
            ProjectEditSheet(
                project = p,
                onSave = { name, hex ->
                    timeViewModel.updateProject(p.id, name, hex)
                    editProject = null
                },
                onDismiss = { editProject = null },
            )
        }

        if (showExport) {
            ExportSheet(
                projects = state.projects,
                onExport = { from, to, projectId ->
                    showExport = false
                    timeViewModel.exportCsv(from, to, projectId) { result ->
                        result
                            .onSuccess { bytes ->
                                FileShare.share(
                                    context,
                                    "zeiterfassung_export.csv",
                                    "text/csv",
                                    bytes,
                                    chooserTitle = csvChooser,
                                )
                            }
                            .onFailure { e -> toast = e.message ?: exportFailed }
                    }
                },
                onDismiss = { showExport = false },
            )
        }

        // Project mutations surface their failure via the shared ViewModel error channel;
        // export failures (separate callback path) use this local toast.
        (toast ?: state.error)?.let { msg ->
            HbToast(
                message = msg,
                icon = HbIcons.x,
                actionLabel = stringResource(R.string.action_ok),
                onAction = { toast = null; timeViewModel.clearError() },
            )
        }
    }
}

/** One project row in the management list: colour dot, name (archived suffix), edit + archive toggle. */
@Composable
private fun ProjectRow(project: ProjectDto, onEdit: () -> Unit, onToggleArchive: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Box(Modifier.size(11.dp).clip(RoundedCornerShape(4.dp)).background(Format.parseColor(project.color)))
        Text(
            if (project.archived) stringResource(R.string.settings_project_archived_suffix, project.name) else project.name,
            style = HbType.rowTitle.copy(fontSize = 14.5.sp),
            color = if (project.archived) Hb.ink3 else Hb.ink,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        HbIconButton(HbIcons.edit, onEdit, iconSize = 18.dp, tint = Hb.ink2)
        HbIconButton(
            HbIcons.archive,
            onToggleArchive,
            iconSize = 18.dp,
            tint = if (project.archived) Hb.accentInk else Hb.ink2,
        )
    }
}

/**
 * Rename / recolour an existing project (#175) — the create flow stays on the tracker.
 * Mirrors the tracker's NewProjectSheet (name field + swatch picker); the current colour
 * is matched against the palette so a custom (seed) colour still shows a sensible default.
 */
@Composable
private fun ProjectEditSheet(project: ProjectDto, onSave: (String, String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(project.name) }
    var selected by remember {
        mutableStateOf(
            Hb.projectSwatches.firstOrNull { hexOf(it).equals(project.color, ignoreCase = true) }
                ?: Hb.projectSwatches.first(),
        )
    }

    HbBottomSheet(
        onDismiss = onDismiss,
        title = stringResource(R.string.settings_project_edit_title),
        footer = {
            HbButton(
                stringResource(R.string.action_cancel),
                onClick = onDismiss,
                variant = HbButtonVariant.Secondary,
                modifier = Modifier.weight(1f),
            )
            HbButton(
                stringResource(R.string.action_save),
                onClick = { if (name.isNotBlank()) onSave(name.trim(), hexOf(selected)) },
                variant = HbButtonVariant.Primary,
                enabled = name.isNotBlank(),
                modifier = Modifier.weight(1f),
            )
        },
    ) {
        HbField(stringResource(R.string.common_field_name)) {
            HbTextField(value = name, onValueChange = { name = it }, placeholder = stringResource(R.string.time_project_name_placeholder))
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
                                },
                            )
                            .clickable { selected = color },
                    )
                }
            }
        }
    }
}

/**
 * CSV export filter (#175): optional from/to dates + project, mirroring the web ExportModal.
 * Dates are turned into the day's local start/end and sent as ISO-8601 instants (the same
 * conversion the web does); an empty form exports every completed entry. Archived projects
 * stay selectable so their history can still be exported.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExportSheet(
    projects: List<ProjectDto>,
    onExport: (from: String?, to: String?, projectId: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val zone = remember { ZoneId.systemDefault() }
    var from by remember { mutableStateOf<LocalDate?>(null) }
    var to by remember { mutableStateOf<LocalDate?>(null) }
    var projectId by remember { mutableStateOf("") }

    val projectName = projects.firstOrNull { it.id == projectId }?.name ?: stringResource(R.string.settings_all_projects)
    val allProjectsLabel = stringResource(R.string.settings_all_projects)

    HbBottomSheet(
        onDismiss = onDismiss,
        title = stringResource(R.string.settings_export_title),
        footer = {
            HbButton(
                stringResource(R.string.action_cancel),
                onClick = onDismiss,
                variant = HbButtonVariant.Secondary,
                modifier = Modifier.weight(1f),
            )
            HbButton(
                stringResource(R.string.action_export),
                onClick = {
                    onExport(
                        from?.atStartOfDay(zone)?.toInstant()?.toString(),
                        to?.plusDays(1)?.atStartOfDay(zone)?.minusNanos(1_000_000)?.toInstant()?.toString(),
                        projectId.takeIf { it.isNotEmpty() },
                    )
                },
                variant = HbButtonVariant.Primary,
                modifier = Modifier.weight(1f),
            )
        },
    ) {
        Text(
            stringResource(R.string.settings_csv_export_hint),
            style = HbType.small.copy(fontSize = 12.5.sp),
            color = Hb.ink3,
        )
        HbField(stringResource(R.string.settings_field_from)) { DateFilterField(from) { from = it } }
        HbField(stringResource(R.string.settings_field_to)) { DateFilterField(to) { to = it } }
        HbField(stringResource(R.string.settings_field_project)) {
            SettingsSelectField(
                value = projectName,
                options = listOf(allProjectsLabel to "") + projects.map { it.name to it.id },
                onSelect = { projectId = it },
            )
        }
    }
}

/** Optional date field for the export filter: shows dd.MM.yyyy or a placeholder; clearable. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateFilterField(value: LocalDate?, onChange: (LocalDate?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clip(HbRadiusSm).background(Hb.surface, HbRadiusSm)
            .border(1.dp, Hb.line, HbRadiusSm).clickable { open = true }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            value?.let { "%02d.%02d.%04d".format(it.dayOfMonth, it.monthValue, it.year) } ?: stringResource(R.string.settings_date_any),
            style = HbType.body.copy(fontSize = 14.sp),
            color = if (value != null) Hb.ink else Hb.ink3,
            modifier = Modifier.weight(1f),
        )
        if (value != null) HbIconButton(HbIcons.x, { onChange(null) }, iconSize = 16.dp, tint = Hb.ink3)
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

/** Dropdown select (label/value pairs) for the settings sheets — same look as the tracker's. */
@Composable
private fun SettingsSelectField(value: String, options: List<Pair<String, String>>, onSelect: (String) -> Unit) {
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

/** Hex string of a Compose colour (`#RRGGBB`), matching the tracker's `hexOf` and the API contract. */
private fun hexOf(color: Color): String = "#%06X".format(0xFFFFFF and color.toArgb())

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
                eyebrow = stringResource(R.string.settings_eyebrow),
                title = stringResource(R.string.settings_absence),
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
                                stringResource(R.string.settings_absence_quotas),
                                style = HbType.rowTitle.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                                color = Hb.ink,
                            )
                            Text(
                                stringResource(R.string.settings_absence_quotas_hint),
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
                            Text(stringResource(R.string.common_loading), style = HbType.small.copy(fontSize = 12.5.sp), color = Hb.ink3)
                        userIds.isEmpty() ->
                            Text(stringResource(R.string.absence_load_failed), style = HbType.small.copy(fontSize = 12.5.sp), color = Hb.ink3)
                        else -> AbwSettingsPanel(ctx, data, userIds, year, absenceViewModel)
                    }
                }
            }
        }
    }
}
