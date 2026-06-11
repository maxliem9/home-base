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
import com.homebase.android.data.repository.ConfigRepository
import com.homebase.android.ui.components.HbAppBar
import com.homebase.android.ui.components.HbButton
import com.homebase.android.ui.components.HbCard
import com.homebase.android.ui.components.HbField
import com.homebase.android.ui.components.HbIcon
import com.homebase.android.ui.components.HbIcons
import com.homebase.android.ui.components.HbRadiusSm
import com.homebase.android.ui.components.HbScreenScaffold
import com.homebase.android.ui.components.HbTextField
import com.homebase.android.ui.theme.Hb
import com.homebase.android.ui.theme.HbType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Zentrale Einstellungen (#101) — the Android pendant of the web settings hub
 * (web/src/components/settings). A dedicated screen reached from the drawer's account-corner
 * gear, split into per-domain subpages reached from a list (a phone-friendly take on the web's
 * left nav rail). Only the Haushalt subpage ships in this first PR; Zeiterfassung / Konto /
 * Abwesenheit follow as their web halves land. The list is built to grow.
 */

private enum class SettingsSub { HOUSEHOLD }

@Composable
fun SettingsScreen(
    configRepository: ConfigRepository,
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
                        if (saved) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                HbIcon(HbIcons.check, size = 16.dp, tint = Hb.ink3)
                                Text("Gespeichert", style = HbType.small, color = Hb.ink3)
                            }
                        }
                    }
                    if (error != null) {
                        Text(error!!, style = HbType.small.copy(fontSize = 13.sp), color = Hb.danger)
                    }
                }
            }
        }
    }
}
