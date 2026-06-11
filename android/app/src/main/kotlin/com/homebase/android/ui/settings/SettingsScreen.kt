package com.homebase.android.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import com.homebase.android.data.repository.AuthRepository
import com.homebase.android.data.repository.ConfigRepository
import com.homebase.android.ui.components.HbAppBar
import com.homebase.android.ui.components.HbAvatar
import com.homebase.android.ui.components.HbButton
import com.homebase.android.ui.components.HbCard
import com.homebase.android.ui.components.HbField
import com.homebase.android.ui.components.HbIcon
import com.homebase.android.ui.components.HbIcons
import com.homebase.android.ui.components.HbRadiusSm
import com.homebase.android.ui.components.HbScreenScaffold
import com.homebase.android.ui.components.HbTextField
import com.homebase.android.ui.components.displayName
import com.homebase.android.ui.theme.Hb
import com.homebase.android.ui.theme.HbType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Zentrale Einstellungen (#101) — the Android pendant of the web settings hub
 * (web/src/components/settings). A dedicated screen reached from the drawer's account-corner
 * gear, split into per-domain subpages reached from a list (a phone-friendly take on the web's
 * left nav rail). Haushalt, Konto und Benachrichtigungen sind umgesetzt; die Zeiterfassungs-
 * Verlagerung und (sobald die Web-Hälfte landet) Abwesenheit folgen. The list is built to grow.
 */

private enum class SettingsSub { HOUSEHOLD, KONTO, NOTIFICATIONS }

@Composable
fun SettingsScreen(
    configRepository: ConfigRepository,
    authRepository: AuthRepository,
    currentUser: String?,
    householdName: String,
    onHouseholdRenamed: (String) -> Unit,
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
            onBack = { sub = null },
        )
        SettingsSub.NOTIFICATIONS -> NotificationsPage(
            configRepository = configRepository,
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
                subtitle = "Telegram-Digest-Uhrzeit",
                onClick = { onPick(SettingsSub.NOTIFICATIONS) },
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
private fun KontoPage(authRepository: AuthRepository, currentUser: String?, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var current by remember { mutableStateOf("") }
    var next by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

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
        }
    }
}

@Composable
private fun NotificationsPage(configRepository: ConfigRepository, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var time by remember { mutableStateOf("") }
    var enabled by remember { mutableStateOf(true) }
    var loaded by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        configRepository.getDigest().onSuccess { time = it.time; enabled = it.enabled }
        loaded = true
    }
    LaunchedEffect(saved) { if (saved) { delay(2500); saved = false } }

    val valid = time.matches(Regex("""\d{2}:\d{2}"""))
    val save = {
        if (loaded && valid && !saving) {
            saving = true
            error = null
            saved = false
            scope.launch {
                configRepository.updateDigestTime(time)
                    .onSuccess { persisted -> time = persisted; saved = true }
                    .onFailure { e -> error = e.message ?: "Speichern fehlgeschlagen." }
                saving = false
            }
        }
        Unit
    }

    HbScreenScaffold(
        appBar = {
            HbAppBar(eyebrow = "Einstellungen", title = "Benachrichtigungen", leftIcon = HbIcons.chevronLeft, onLeft = onBack, bordered = true)
        },
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp)) {
            Spacer(Modifier.size(10.dp))
            HbCard {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            "Täglicher Telegram-Digest",
                            style = HbType.rowTitle.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                            color = Hb.ink,
                        )
                        Text(
                            "Uhrzeit, zu der die tägliche Zusammenfassung gesendet wird.",
                            style = HbType.small.copy(fontSize = 12.5.sp),
                            color = Hb.ink3,
                        )
                    }
                    if (loaded && !enabled) {
                        Text(
                            "Telegram ist serverseitig nicht konfiguriert — die Uhrzeit ist trotzdem editierbar.",
                            style = HbType.small.copy(fontSize = 12.5.sp),
                            color = Hb.ink3,
                        )
                    }
                    HbField("Uhrzeit (HH:MM)") {
                        HbTextField(
                            value = time,
                            onValueChange = { time = it.take(5); error = null; saved = false },
                            placeholder = "20:00",
                            mono = true,
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        HbButton("Speichern", onClick = save, enabled = loaded && valid && !saving)
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
