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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.homebase.android.R
import com.homebase.android.data.model.ShoppingItemDto
import com.homebase.android.data.model.ShoppingListDto
import com.homebase.android.data.model.ShoppingTemplateDto
import com.homebase.android.ui.components.HbAppBar
import com.homebase.android.ui.components.HbAvatar
import com.homebase.android.ui.components.HbBottomSheet
import com.homebase.android.ui.components.HbButton
import com.homebase.android.ui.components.HbButtonVariant
import com.homebase.android.ui.components.HbCheck
import com.homebase.android.ui.components.HbConfirmDialog
import com.homebase.android.ui.components.HbEmpty
import com.homebase.android.ui.components.HbField
import com.homebase.android.ui.components.HbFab
import com.homebase.android.ui.components.HbIcon
import com.homebase.android.ui.components.HbIconButton
import com.homebase.android.ui.components.HbIcons
import com.homebase.android.ui.components.HbPill
import com.homebase.android.ui.components.HbRadius
import com.homebase.android.ui.components.HbRadiusSm
import com.homebase.android.ui.components.HbQuickAdd
import com.homebase.android.ui.components.HbRow
import com.homebase.android.ui.components.HbScreenScaffold
import com.homebase.android.ui.components.HbSectionLabel
import com.homebase.android.ui.components.HbTextField
import com.homebase.android.ui.components.HbToast
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
    val context = LocalContext.current

    var addItemText by remember { mutableStateOf("") }
    var showNewListSheet by remember { mutableStateOf(false) }
    var showAddItemSheet by remember { mutableStateOf(false) }
    // Template flows (#215): the list/manage sheet, an in-flight create-or-edit form, an apply
    // selection sheet (for a chosen template), a pending delete confirm, and the add-result toast.
    var showTemplatesSheet by remember { mutableStateOf(false) }
    var editingTemplate by remember { mutableStateOf<TemplateEdit?>(null) }
    var applyingTemplate by remember { mutableStateOf<ShoppingTemplateDto?>(null) }
    var deletingTemplate by remember { mutableStateOf<ShoppingTemplateDto?>(null) }
    var toastMsg by remember { mutableStateOf<String?>(null) }

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
                    eyebrow = stringResource(R.string.shopping_eyebrow),
                    title = state.activeList?.name ?: stringResource(R.string.shopping_title_fallback),
                    onLeft = onOpenDrawer,
                    // The "more" button opens the saved-templates manager (#215).
                    actions = { HbIconButton(HbIcons.list, { showTemplatesSheet = true }) },
                )
            },
            fab = { HbFab(onClick = { showAddItemSheet = true }, label = stringResource(R.string.shopping_fab)) },
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
                    placeholder = stringResource(R.string.shopping_quick_add),
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
                    HbEmpty(
                        HbIcons.cart,
                        stringResource(R.string.shopping_empty_title),
                        stringResource(R.string.shopping_empty_hint),
                    )
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
                                stringResource(R.string.shopping_in_cart, checkedItems.size).uppercase(),
                                style = HbType.sectionLabel,
                                color = Hb.ink3,
                                modifier = Modifier.padding(start = 2.dp),
                            )
                            Text(
                                stringResource(R.string.shopping_clear_checked),
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

        // --- Templates (#215) ---

        if (showTemplatesSheet) {
            TemplatesSheet(
                templates = state.templates,
                onDismiss = { showTemplatesSheet = false },
                onNew = { editingTemplate = TemplateEdit.New },
                onApply = { applyingTemplate = it },
                onEdit = { editingTemplate = TemplateEdit.Existing(it) },
                onDelete = { deletingTemplate = it },
            )
        }

        editingTemplate?.let { edit ->
            TemplateFormSheet(
                existing = (edit as? TemplateEdit.Existing)?.template,
                onDismiss = { editingTemplate = null },
                onSave = { name, itemNames ->
                    when (edit) {
                        is TemplateEdit.Existing ->
                            viewModel.updateTemplate(edit.template.id, name, itemNames) { editingTemplate = null }
                        TemplateEdit.New ->
                            viewModel.createTemplate(name, itemNames) { editingTemplate = null }
                    }
                },
            )
        }

        applyingTemplate?.let { template ->
            ApplyTemplateSheet(
                template = template,
                onDismiss = { applyingTemplate = null },
                onConfirm = { names ->
                    applyingTemplate = null
                    showTemplatesSheet = false
                    viewModel.applyTemplate(names) { added, merged ->
                        toastMsg = addToast(context, added, merged)
                    }
                },
            )
        }

        deletingTemplate?.let { template ->
            HbConfirmDialog(
                message = stringResource(R.string.shopping_template_delete_confirm, template.name),
                confirmLabel = stringResource(R.string.action_delete),
                onConfirm = {
                    viewModel.deleteTemplate(template.id)
                    deletingTemplate = null
                },
                onDismiss = { deletingTemplate = null },
            )
        }

        toastMsg?.let { msg -> HbToast(message = msg) }
        LaunchedEffect(toastMsg) {
            if (toastMsg != null) {
                kotlinx.coroutines.delay(2600)
                toastMsg = null
            }
        }
    }
}

/** What the template form is editing: a brand-new template, or an existing one to update. */
private sealed interface TemplateEdit {
    data object New : TemplateEdit
    data class Existing(val template: ShoppingTemplateDto) : TemplateEdit
}

/** Add-result toast text (mirrors the recipe→shopping helper): N added / merged / nothing. */
private fun addToast(context: android.content.Context, added: Int, merged: Int): String = when {
    added == 0 && merged == 0 -> context.getString(R.string.recipe_toast_nothing)
    merged == 0 -> context.resources.getQuantityString(R.plurals.recipe_toast_added, added, added)
    added == 0 -> context.getString(R.string.recipe_toast_merged, merged)
    else -> context.getString(R.string.recipe_toast_added_merged, added, merged)
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
        Text(stringResource(R.string.shopping_new_list_tab), style = HbType.label.copy(fontSize = 14.5.sp), color = Hb.accentInk)
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
            pluralStringResource(R.plurals.shopping_sync_banner, count, count),
            style = HbType.small,
            color = Hb.accentInk,
            modifier = Modifier.weight(1f),
        )
        Text(
            stringResource(R.string.action_now),
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
    // Re-entry guard mirroring the web `submitRef`: a double-tap on "Erstellen" otherwise fires the
    // create twice before the sheet dismisses → a duplicate list (#191). Latched on the first press
    // so the second tap hits a disabled button (the VM-level single-flight in `createList` is the
    // backstop). Blank names are blocked too (the VM already no-ops, this just disables the button).
    var submitting by remember { mutableStateOf(false) }
    HbBottomSheet(
        onDismiss = onDismiss,
        title = stringResource(R.string.shopping_new_list_title),
        footer = {
            HbButton(
                stringResource(R.string.action_cancel),
                onClick = onDismiss,
                variant = HbButtonVariant.Secondary,
                modifier = Modifier.weight(1f),
            )
            HbButton(
                stringResource(R.string.action_create),
                onClick = {
                    if (submitting || name.isBlank()) return@HbButton
                    submitting = true
                    onCreate(name)
                },
                variant = HbButtonVariant.Primary,
                enabled = !submitting && name.isNotBlank(),
                modifier = Modifier.weight(1f),
            )
        },
    ) {
        HbField(stringResource(R.string.common_field_name)) {
            HbTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = stringResource(R.string.shopping_list_name_placeholder),
            )
        }
        Text(
            stringResource(R.string.shopping_lists_shared_hint),
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
        title = stringResource(R.string.shopping_add_item_title),
        footer = {
            HbButton(
                stringResource(R.string.action_cancel),
                onClick = onDismiss,
                variant = HbButtonVariant.Secondary,
                modifier = Modifier.weight(1f),
            )
            HbButton(
                stringResource(R.string.action_add),
                onClick = { onAdd(name) },
                variant = HbButtonVariant.Primary,
                modifier = Modifier.weight(1f),
            )
        },
    ) {
        HbField(stringResource(R.string.common_field_name)) {
            HbTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = stringResource(R.string.shopping_item_name_placeholder),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Template sheets (named standard lists, #215)
// ---------------------------------------------------------------------------

/** Manage saved templates: pick one to add, edit, or delete; or create a new one. */
@Composable
private fun TemplatesSheet(
    templates: List<ShoppingTemplateDto>,
    onDismiss: () -> Unit,
    onNew: () -> Unit,
    onApply: (ShoppingTemplateDto) -> Unit,
    onEdit: (ShoppingTemplateDto) -> Unit,
    onDelete: (ShoppingTemplateDto) -> Unit,
) {
    HbBottomSheet(
        onDismiss = onDismiss,
        title = stringResource(R.string.shopping_templates_title),
        footer = {
            HbButton(
                stringResource(R.string.shopping_template_new),
                onClick = onNew,
                variant = HbButtonVariant.Primary,
                icon = HbIcons.plus,
                modifier = Modifier.weight(1f),
            )
        },
    ) {
        if (templates.isEmpty()) {
            HbEmpty(
                HbIcons.list,
                stringResource(R.string.shopping_templates_empty_title),
                stringResource(R.string.shopping_templates_empty_hint),
            )
        } else {
            Text(
                stringResource(R.string.shopping_templates_subtitle),
                style = HbType.small.copy(fontSize = 12.5.sp),
                color = Hb.ink3,
            )
            templates.forEach { template ->
                TemplateRow(
                    template = template,
                    onApply = { onApply(template) },
                    onEdit = { onEdit(template) },
                    onDelete = { onDelete(template) },
                )
            }
        }
    }
}

@Composable
private fun TemplateRow(
    template: ShoppingTemplateDto,
    onApply: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    HbRow {
        // Tapping the name area = open the add-to-cart selection (the primary action); the cart
        // button repeats it as an explicit affordance, with edit/delete alongside.
        Column(
            Modifier
                .weight(1f)
                .clip(HbRadiusSm)
                .clickable { onApply() }
                .padding(vertical = 2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(template.name, style = HbType.rowTitle, color = Hb.ink)
            Text(
                if (template.items.isEmpty()) stringResource(R.string.shopping_template_no_items)
                else pluralStringResource(R.plurals.shopping_template_item_count, template.items.size, template.items.size),
                style = HbType.meta,
                color = Hb.ink3,
            )
        }
        HbIconButton(HbIcons.cart, onApply, iconSize = 20.dp, tint = Hb.accentInk)
        HbIconButton(HbIcons.edit, onEdit, iconSize = 20.dp)
        HbIconButton(HbIcons.trash, onDelete, iconSize = 20.dp)
    }
}

/** Create or edit a template: a name + an editable list of item-name fields. */
@Composable
private fun TemplateFormSheet(
    existing: ShoppingTemplateDto?,
    onDismiss: () -> Unit,
    onSave: (name: String, itemNames: List<String>) -> Unit,
) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    // One trailing blank row so there's always an empty field to type the next item into.
    val items = remember {
        mutableStateListOf<String>().apply {
            existing?.items?.forEach { add(it.name) }
            add("")
        }
    }
    var submitting by remember { mutableStateOf(false) }

    HbBottomSheet(
        onDismiss = onDismiss,
        title = stringResource(
            if (existing == null) R.string.shopping_template_new_title else R.string.shopping_template_edit_title,
        ),
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
                    if (submitting || name.isBlank()) return@HbButton
                    submitting = true
                    onSave(name.trim(), items.map { it.trim() }.filter { it.isNotBlank() })
                },
                variant = HbButtonVariant.Primary,
                enabled = !submitting && name.isNotBlank(),
                modifier = Modifier.weight(1f),
            )
        },
    ) {
        HbField(stringResource(R.string.shopping_template_name)) {
            HbTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = stringResource(R.string.shopping_template_name_placeholder),
            )
        }

        HbSectionLabel(stringResource(R.string.shopping_template_items))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items.forEachIndexed { i, value ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    HbTextField(
                        value = value,
                        onValueChange = { items[i] = it },
                        placeholder = stringResource(R.string.shopping_template_item_placeholder),
                        modifier = Modifier.weight(1f),
                    )
                    // Keep at least one (trailing) row; removing the last typed row is allowed.
                    if (items.size > 1) {
                        HbIconButton(HbIcons.x, { items.removeAt(i) }, iconSize = 18.dp)
                    }
                }
            }
        }
        Text(
            stringResource(R.string.shopping_template_add_item),
            style = HbType.meta.copy(fontWeight = FontWeight.SemiBold),
            color = Hb.accentInk,
            modifier = Modifier
                .clip(HbPill)
                .clickable { items.add("") }
                .padding(horizontal = 6.dp, vertical = 6.dp),
        )
    }
}

/** Apply a template: checkbox per item (all on by default), add the chosen names to the active list. */
@Composable
private fun ApplyTemplateSheet(
    template: ShoppingTemplateDto,
    onDismiss: () -> Unit,
    onConfirm: (names: List<String>) -> Unit,
) {
    var selected by remember { mutableStateOf(template.items.map { true }) }
    val count = selected.count { it }

    HbBottomSheet(
        onDismiss = onDismiss,
        title = stringResource(R.string.shopping_template_apply_title),
        footer = {
            HbButton(
                stringResource(R.string.action_cancel),
                onClick = onDismiss,
                variant = HbButtonVariant.Secondary,
                modifier = Modifier.weight(1f),
            )
            HbButton(
                if (count > 0) stringResource(R.string.recipe_add_n, count) else stringResource(R.string.action_add),
                onClick = {
                    val names = template.items
                        .filterIndexed { i, _ -> selected.getOrElse(i) { false } }
                        .map { it.name }
                    if (names.isNotEmpty()) onConfirm(names)
                },
                variant = HbButtonVariant.Primary,
                icon = HbIcons.cart,
                enabled = count > 0,
                modifier = Modifier.weight(1f),
            )
        },
    ) {
        if (template.items.isEmpty()) {
            Text(stringResource(R.string.shopping_template_apply_empty), style = HbType.body, color = Hb.ink3)
        } else {
            Text(template.name, style = HbType.cardTitle, color = Hb.ink2)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                template.items.forEachIndexed { i, item ->
                    val toggle = { selected = selected.mapIndexed { j, v -> if (j == i) !v else v } }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { toggle() }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        HbCheck(checked = selected.getOrElse(i) { false }, onCheckedChange = toggle, size = 22.dp)
                        Text(
                            item.name,
                            style = HbType.rowTitle,
                            color = Hb.ink,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}
