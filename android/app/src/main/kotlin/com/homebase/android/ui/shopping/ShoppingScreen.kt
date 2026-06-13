package com.homebase.android.ui.shopping

import androidx.compose.foundation.background
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.homebase.android.data.model.ShoppingItemDto
import com.homebase.android.data.model.ShoppingListDto
import com.homebase.android.ui.components.HbAppBar
import com.homebase.android.ui.components.HbAvatar
import com.homebase.android.ui.components.HbBottomSheet
import com.homebase.android.ui.components.HbButton
import com.homebase.android.ui.components.HbButtonVariant
import com.homebase.android.ui.components.HbCheck
import com.homebase.android.ui.components.HbEmpty
import com.homebase.android.ui.components.HbField
import com.homebase.android.ui.components.HbFab
import com.homebase.android.ui.components.HbIcon
import com.homebase.android.ui.components.HbIconButton
import com.homebase.android.ui.components.HbIcons
import com.homebase.android.ui.components.HbPill
import com.homebase.android.ui.components.HbRadius
import com.homebase.android.ui.components.HbQuickAdd
import com.homebase.android.ui.components.HbRow
import com.homebase.android.ui.components.HbScreenScaffold
import com.homebase.android.ui.components.HbTextField
import com.homebase.android.ui.components.bottomBorder
import com.homebase.android.ui.theme.Hb
import com.homebase.android.ui.theme.HbType

@Composable
fun ShoppingScreen(
    viewModel: ShoppingViewModel,
    currentUser: String?,
    onOpenDrawer: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    var addItemText by remember { mutableStateOf("") }
    var showNewListSheet by remember { mutableStateOf(false) }
    var showAddItemSheet by remember { mutableStateOf(false) }

    val visible = state.visibleItems
    val openItems = visible.filter { !it.checked }
    // Most-recently-checked first: ISO checkedAt sorts lexicographically = chronologically;
    // an item without a timestamp (legacy) sinks to the bottom.
    val checkedItems = visible
        .filter { it.checked }
        .sortedByDescending { it.checkedAt ?: "" }

    Box(Modifier.fillMaxSize()) {
        HbScreenScaffold(
            appBar = {
                HbAppBar(
                    eyebrow = "Einkaufsliste",
                    title = state.activeList?.name ?: "Einkauf",
                    onLeft = onOpenDrawer,
                    actions = { HbIconButton(HbIcons.more, {}) },
                )
            },
            fab = { HbFab(onClick = { showAddItemSheet = true }, label = "Artikel") },
        ) {
            // Full-bleed list-tabs strip
            ListTabs(
                lists = state.lists,
                items = state.items,
                activeListId = state.activeList?.id,
                onSelect = { viewModel.selectList(it) },
                onNewList = { showNewListSheet = true },
            )

            Column(Modifier.padding(horizontal = 18.dp)) {
                HbQuickAdd(
                    value = addItemText,
                    onValueChange = { addItemText = it },
                    onSubmit = {
                        viewModel.addItem(addItemText)
                        addItemText = ""
                    },
                    placeholder = "Artikel hinzufügen …",
                    leading = HbIcons.plus,
                )

                Spacer(Modifier.size(18.dp))

                if (state.visiblePendingCount > 0) {
                    SyncBanner(
                        count = state.visiblePendingCount,
                        onRetry = { viewModel.retryPending() },
                    )
                    Spacer(Modifier.size(12.dp))
                }

                if (visible.isEmpty()) {
                    HbEmpty(HbIcons.cart, "Liste ist leer", "Füge oben Artikel hinzu.")
                } else {
                    openItems.forEach { item ->
                        OpenItemRow(
                            item = item,
                            pending = state.isPending(item.id),
                            onToggle = { viewModel.toggleChecked(item) },
                        )
                    }

                    if (checkedItems.isNotEmpty()) {
                        Spacer(Modifier.size(24.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                "Im Wagen · ${checkedItems.size}".uppercase(),
                                style = HbType.sectionLabel,
                                color = Hb.ink3,
                                modifier = Modifier.padding(start = 2.dp),
                            )
                            Text(
                                "Abgehakte entfernen",
                                style = HbType.meta.copy(fontWeight = FontWeight.SemiBold),
                                color = Hb.ink3,
                                modifier = Modifier
                                    .clip(HbPill)
                                    .clickable { viewModel.clearChecked() }
                                    .padding(horizontal = 4.dp, vertical = 4.dp),
                            )
                        }
                        Spacer(Modifier.size(8.dp))
                        checkedItems.forEach { item ->
                            CheckedItemRow(
                                item = item,
                                pending = state.isPending(item.id),
                                onToggle = { viewModel.toggleChecked(item) },
                            )
                        }
                    }
                }
            }
        }

        if (showNewListSheet) {
            NewListSheet(
                onDismiss = { showNewListSheet = false },
                onCreate = { name ->
                    viewModel.createList(name)
                    showNewListSheet = false
                },
            )
        }

        if (showAddItemSheet) {
            AddItemSheet(
                onDismiss = { showAddItemSheet = false },
                onAdd = { name ->
                    viewModel.addItem(name)
                    showAddItemSheet = false
                },
            )
        }
    }
}

// ---------------------------------------------------------------------------
// List-tabs strip (full-bleed, horizontally scrollable)
// ---------------------------------------------------------------------------

@Composable
private fun ListTabs(
    lists: List<ShoppingListDto>,
    items: List<ShoppingItemDto>,
    activeListId: String?,
    onSelect: (String) -> Unit,
    onNewList: () -> Unit,
) {
    val firstListId = lists.firstOrNull()?.id
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .bottomBorder(Hb.lineSoft),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Spacer(Modifier.width(18.dp))
        lists.forEach { list ->
            // The `listId == null` term is the lists-first safety net (#181): list-less items are no
            // longer created and are migrated into the first list, but a best-effort miss still counts
            // on the first tab (mirrors visibleItems) so its badge matches what that tab shows.
            val openCount = items.count { item ->
                !item.checked && (item.listId == list.id || (list.id == firstListId && item.listId == null))
            }
            ListTab(
                label = list.name,
                count = openCount,
                active = list.id == activeListId,
                onClick = { onSelect(list.id) },
            )
        }
        NewListTab(onClick = onNewList)
        Spacer(Modifier.width(18.dp))
    }
}

@Composable
private fun ListTab(label: String, count: Int, active: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .clickable { onClick() }
            .then(if (active) Modifier.accentUnderline() else Modifier)
            .padding(horizontal = 13.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            label,
            style = HbType.label.copy(fontSize = 14.5.sp),
            color = if (active) Hb.ink else Hb.ink3,
        )
        Box(
            Modifier
                .heightIn(min = 19.dp)
                .clip(HbPill)
                .background(if (active) Hb.accentSoft else Hb.surface3, HbPill)
                .padding(horizontal = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                count.toString(),
                style = HbType.mono.copy(fontSize = 11.5.sp, fontWeight = FontWeight.Bold),
                color = if (active) Hb.accentInk else Hb.ink2,
            )
        }
    }
}

@Composable
private fun NewListTab(onClick: () -> Unit) {
    Row(
        Modifier
            .clickable { onClick() }
            .padding(horizontal = 13.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HbIcon(HbIcons.plus, size = 16.dp, tint = Hb.accentInk)
        Text("Neue Liste", style = HbType.label.copy(fontSize = 14.5.sp), color = Hb.accentInk)
    }
}

/** 2dp accent underline drawn at the bottom edge (overlapping the strip's hairline). */
private fun Modifier.accentUnderline(): Modifier = drawBehind {
    val w = 2.dp.toPx()
    val y = size.height - w / 2f
    drawLine(Hb.accent, Offset(0f, y), Offset(size.width, y), w)
}

// ---------------------------------------------------------------------------
// Item rows
// ---------------------------------------------------------------------------

@Composable
private fun OpenItemRow(item: ShoppingItemDto, pending: Boolean, onToggle: () -> Unit) {
    HbRow {
        HbCheck(checked = false, onCheckedChange = onToggle)
        Text(
            item.name,
            style = HbType.rowTitle,
            color = Hb.ink,
            modifier = Modifier.weight(1f),
        )
        if (pending) SyncBadge()
        HbAvatar(item.createdBy, size = 24.dp)
    }
}

@Composable
private fun CheckedItemRow(item: ShoppingItemDto, pending: Boolean, onToggle: () -> Unit) {
    HbRow {
        HbCheck(checked = true, onCheckedChange = onToggle)
        Text(
            item.name,
            style = HbType.rowTitle.copy(textDecoration = TextDecoration.LineThrough),
            color = Hb.ink3,
            modifier = Modifier.weight(1f),
        )
        if (pending) SyncBadge()
        HbAvatar(item.createdBy, size = 24.dp)
    }
}

// ---------------------------------------------------------------------------
// Offline check-off sync UI (issue #170)
// ---------------------------------------------------------------------------

/** Small per-item "not synced yet" marker — the offline check-off is queued and will retry. */
@Composable
private fun SyncBadge() {
    Box(
        Modifier
            .size(22.dp)
            .clip(HbPill)
            .background(Hb.accentSoft, HbPill),
        contentAlignment = Alignment.Center,
    ) {
        HbIcon(HbIcons.repeat, size = 13.dp, tint = Hb.accentInk)
    }
}

/** Collective banner shown while any visible check-off is still queued; offers a manual retry. */
@Composable
private fun SyncBanner(count: Int, onRetry: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(HbRadius)
            .background(Hb.accentSoft, HbRadius)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        HbIcon(HbIcons.repeat, size = 16.dp, tint = Hb.accentInk)
        Text(
            if (count == 1) "1 Abhakung wird synchronisiert …" else "$count Abhakungen werden synchronisiert …",
            style = HbType.small,
            color = Hb.accentInk,
            modifier = Modifier.weight(1f),
        )
        Text(
            "Jetzt",
            style = HbType.meta.copy(fontWeight = FontWeight.SemiBold),
            color = Hb.accentInk,
            modifier = Modifier
                .clip(HbPill)
                .clickable { onRetry() }
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

// ---------------------------------------------------------------------------
// Sheets
// ---------------------------------------------------------------------------

@Composable
private fun NewListSheet(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    HbBottomSheet(
        onDismiss = onDismiss,
        title = "Neue Liste",
        footer = {
            HbButton(
                "Abbrechen",
                onClick = onDismiss,
                variant = HbButtonVariant.Secondary,
                modifier = Modifier.weight(1f),
            )
            HbButton(
                "Erstellen",
                onClick = { onCreate(name) },
                variant = HbButtonVariant.Primary,
                modifier = Modifier.weight(1f),
            )
        },
    ) {
        HbField("Name") {
            HbTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = "z. B. Drogerie",
            )
        }
        Text(
            "Alle Einkaufslisten sind geteilt.",
            style = HbType.small.copy(fontSize = 12.5.sp),
            color = Hb.ink3,
        )
    }
}

@Composable
private fun AddItemSheet(onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    HbBottomSheet(
        onDismiss = onDismiss,
        title = "Artikel",
        footer = {
            HbButton(
                "Abbrechen",
                onClick = onDismiss,
                variant = HbButtonVariant.Secondary,
                modifier = Modifier.weight(1f),
            )
            HbButton(
                "Hinzufügen",
                onClick = { onAdd(name) },
                variant = HbButtonVariant.Primary,
                modifier = Modifier.weight(1f),
            )
        },
    ) {
        HbField("Name") {
            HbTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = "z. B. Tomaten",
            )
        }
    }
}
