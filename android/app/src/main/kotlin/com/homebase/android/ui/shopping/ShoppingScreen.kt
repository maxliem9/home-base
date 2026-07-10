package com.homebase.android.ui.shopping

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.homebase.android.R
import com.homebase.android.data.model.ShoppingCategoryDto
import com.homebase.android.data.model.ShoppingCategoryRuleDto
import com.homebase.android.data.model.ShoppingItemDto
import com.homebase.android.data.model.ShoppingListDto
import com.homebase.android.data.model.ShoppingSuggestion
import com.homebase.android.data.model.ShoppingTemplateDto
import com.homebase.android.ui.components.HbAppBar
import com.homebase.android.ui.components.HbBottomSheet
import com.homebase.android.ui.components.HbButton
import com.homebase.android.ui.components.HbButtonSize
import com.homebase.android.ui.components.HbButtonVariant
import com.homebase.android.ui.settings.CategoriesCard
import com.homebase.android.ui.settings.RulesCard
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
    var showManageCats by remember { mutableStateOf(false) } // per-list category manager (#412)
    var editingItem by remember { mutableStateOf<ShoppingItemDto?>(null) }
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
                    actions = {
                        // List/tile view toggle (#446) — shows the icon of the *other* view.
                        HbIconButton(
                            if (state.tileView) HbIcons.list else HbIcons.grid,
                            { viewModel.setTileView(!state.tileView) },
                            contentDescription = stringResource(R.string.cd_toggle_view),
                        )
                        // The "more" button opens the saved-templates manager (#215).
                        HbIconButton(HbIcons.more, { showTemplatesSheet = true }, contentDescription = stringResource(R.string.cd_more_actions))
                    },
                )
            },
            fab = { HbFab(onClick = { showAddItemSheet = true }, label = stringResource(R.string.shopping_fab)) },
            onRefresh = { viewModel.refresh() },
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
                ShoppingQuickAddSection(
                    value = addItemText,
                    onValueChange = { addItemText = it },
                    onAdd = { name ->
                        viewModel.addItem(name)
                        addItemText = ""
                    },
                    suggestions = state.suggestions,
                    categories = state.categories,
                    placeholder = stringResource(R.string.shopping_quick_add),
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
                    groupByCategory(openItems, state.categories).forEach { (category, catItems) ->
                        CategorySectionHeader(category, catItems.size)
                        if (state.tileView) {
                            ShoppingTileGrid(
                                items = catItems,
                                isPending = { state.isPending(it) },
                                onToggle = { viewModel.toggleChecked(it) },
                                onEdit = { editingItem = it },
                            )
                        } else {
                            catItems.forEach { item ->
                                OpenItemRow(
                                    item = item,
                                    pending = state.isPending(item.id),
                                    categories = state.categories,
                                    onToggle = { viewModel.toggleChecked(item) },
                                    onMove = { viewModel.moveItemCategory(item, it) },
                                    onEdit = { editingItem = item },
                                )
                            }
                        }
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
                        if (state.tileView) {
                            ShoppingTileGrid(
                                items = checkedItems,
                                done = true,
                                isPending = { state.isPending(it) },
                                onToggle = { viewModel.toggleChecked(it) },
                            )
                        } else {
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

                // Per-list options (#412): own-category toggle + manager, at the foot of the list.
                state.activeList?.let { active ->
                    Spacer(Modifier.size(28.dp))
                    OwnCategoriesFooter(
                        on = active.ownCategories,
                        onToggle = { viewModel.toggleOwnCategories(it) },
                        onManage = {
                            viewModel.loadManageCategories()
                            viewModel.loadManageRules() // #501: also load the list's own rules for the sheet
                            showManageCats = true
                        },
                    )
                    Spacer(Modifier.size(12.dp))
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

        editingItem?.let { item ->
            EditItemSheet(
                item = item,
                onDismiss = { editingItem = null },
                onSave = { name, quantity, note, icon ->
                    viewModel.updateItemDetails(item, name, quantity, note, icon)
                    editingItem = null
                },
            )
        }

        // Per-list category manager (#412): reuses the Settings CategoriesCard, scoped to this list.
        val activeForManage = state.activeList
        if (showManageCats && activeForManage != null && activeForManage.ownCategories) {
            ManageCategoriesSheet(
                listName = activeForManage.name,
                categories = state.manageCategories,
                rules = state.manageRules,
                onSave = { key, label, emoji -> viewModel.saveManageCategory(key, label, emoji) },
                onDelete = { viewModel.deleteManageCategory(it) },
                onMove = { index, dir -> viewModel.moveManageCategory(index, dir) },
                onSaveRule = { displayName, category, icon, editingName -> viewModel.saveManageRule(editingName, displayName, category, icon) },
                onDeleteRule = { viewModel.deleteManageRule(it) },
                onDismiss = { showManageCats = false },
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
            .then(if (active) Modifier.accentUnderline(Hb.accent) else Modifier)
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

/**
 * 2dp accent underline drawn at the bottom edge (overlapping the strip's hairline). [color] is
 * passed in (resolved by the composable caller) since drawBehind is a non-composable DrawScope and
 * the `Hb.*` tokens are now @Composable getters (#244).
 */
private fun Modifier.accentUnderline(color: Color): Modifier = drawBehind {
    val w = 2.dp.toPx()
    val y = size.height - w / 2f
    drawLine(color, Offset(0f, y), Offset(size.width, y), w)
}

// ---------------------------------------------------------------------------
// Item rows
// ---------------------------------------------------------------------------

/** Row title block (#447): name + inline quantity + an optional note line below. */
@Composable
private fun RowScope.ItemNameBlock(item: ShoppingItemDto, done: Boolean) {
    val parts = ShoppingQuantity.displayParts(item)
    Column(Modifier.weight(1f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                parts.title,
                style = HbType.rowTitle.copy(textDecoration = if (done) TextDecoration.LineThrough else null),
                color = if (done) Hb.ink3 else Hb.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            parts.detail?.let {
                Text(it, style = HbType.meta, color = Hb.ink3, modifier = Modifier.padding(start = 8.dp))
            }
        }
        item.note?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = HbType.meta, color = Hb.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun OpenItemRow(
    item: ShoppingItemDto,
    pending: Boolean,
    categories: List<GroceryCategory>,
    onToggle: () -> Unit,
    onMove: (String) -> Unit,
    onEdit: () -> Unit,
) {
    HbRow {
        HbCheck(checked = false, onCheckedChange = onToggle)
        ShoppingItemIcon(item)
        ItemNameBlock(item, done = false)
        if (pending) SyncBadge()
        // Trailing actions cluster tightly (their 44dp hit boxes already carry the visual gap);
        // the wide row spacing (13dp) is only for the checkbox/icon/name lead-in.
        Row(
            horizontalArrangement = Arrangement.spacedBy((-4).dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HbIconButton(HbIcons.edit, onEdit, contentDescription = stringResource(R.string.cd_edit))
            CategoryMoveMenu(current = item.category, categories = categories, onPick = onMove)
        }
    }
}

@Composable
private fun CheckedItemRow(item: ShoppingItemDto, pending: Boolean, onToggle: () -> Unit) {
    HbRow {
        HbCheck(checked = true, onCheckedChange = onToggle)
        ShoppingItemIcon(item, muted = true)
        ItemNameBlock(item, done = true)
        if (pending) SyncBadge()
    }
}

/** Tile grid for a category's items (#446): a 3-column wrapping grid of [ShoppingTile]s. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ShoppingTileGrid(
    items: List<ShoppingItemDto>,
    done: Boolean = false,
    isPending: (String) -> Boolean,
    onToggle: (ShoppingItemDto) -> Unit,
    onEdit: (ShoppingItemDto) -> Unit = {},
) {
    FlowRow(
        Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        maxItemsInEachRow = 3,
    ) {
        items.forEach { item ->
            ShoppingTile(
                item = item,
                done = done,
                pending = isPending(item.id),
                onToggle = { onToggle(item) },
                onEdit = { onEdit(item) },
                modifier = Modifier.weight(1f),
            )
        }
        // Pad the last row so 1–2 trailing tiles keep the column width (don't stretch).
        val remainder = items.size % 3
        if (remainder != 0) repeat(3 - remainder) { Spacer(Modifier.weight(1f)) }
    }
}

/** A single Bring-style tile: big designed icon + name; tapping toggles the check-off (#446). */
@Composable
private fun ShoppingTile(
    item: ShoppingItemDto,
    done: Boolean,
    pending: Boolean,
    onToggle: () -> Unit,
    onEdit: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val parts = ShoppingQuantity.displayParts(item)
    Box(modifier) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .border(1.dp, Hb.line, RoundedCornerShape(16.dp))
                .background(Hb.surface)
                .clickable { onToggle() }
                .padding(vertical = 12.dp, horizontal = 6.dp)
                .alpha(if (done) 0.65f else 1f),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier.size(52.dp).clip(RoundedCornerShape(14.dp)).background(Hb.surface2),
                contentAlignment = Alignment.Center,
            ) {
                ShoppingItemIcon(item, muted = done, size = 38.dp)
            }
            Spacer(Modifier.size(6.dp))
            Text(
                parts.title,
                style = HbType.meta.copy(
                    fontWeight = FontWeight.SemiBold,
                    textDecoration = if (done) TextDecoration.LineThrough else null,
                ),
                color = if (done) Hb.ink3 else Hb.ink,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            parts.detail?.let {
                Text(it, style = HbType.meta, color = Hb.ink3, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            item.note?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = HbType.meta, color = Hb.ink3, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (done) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(5.dp).size(18.dp).clip(CircleShape).background(Hb.accent),
                contentAlignment = Alignment.Center,
            ) {
                HbIcon(HbIcons.check, size = 11.dp, tint = Hb.onAccent)
            }
        } else if (pending) {
            Box(Modifier.align(Alignment.TopEnd).padding(7.dp)) { SyncBadge() }
        } else {
            // Edit affordance (#447): a small pencil in the corner. A compact hit box (not the 44dp
            // HbIconButton) so it doesn't swallow check-off taps across the tile's top-right corner.
            Box(
                Modifier.align(Alignment.TopEnd).padding(4.dp).size(28.dp).clip(CircleShape).clickable { onEdit() },
                contentAlignment = Alignment.Center,
            ) {
                HbIcon(HbIcons.edit, size = 15.dp, tint = Hb.ink3)
            }
        }
    }
}

/** Single rendering seam for an item's icon (#443): designed SVG via Coil, emoji as the fallback. */
@Composable
private fun ShoppingItemIcon(item: ShoppingItemDto, muted: Boolean = false, size: Dp = 30.dp) {
    SvgIcon(ShoppingIcons.assetForItem(item), fallbackEmoji = item.icon, size = size, muted = muted)
}

/** Category header/menu icon: designed SVG with the catalog emoji as fallback. */
@Composable
private fun CategoryIconView(category: GroceryCategory, size: Dp = 22.dp) {
    SvgIcon(ShoppingIcons.assetForCategory(category.key), fallbackEmoji = category.emoji, size = size)
}

/** Render a bundled SVG asset (Coil) at [size]; falls back to the emoji if there's no asset. */
@Composable
private fun SvgIcon(assetUri: String?, fallbackEmoji: String?, size: Dp, muted: Boolean = false) {
    val mod = Modifier.size(size).then(if (muted) Modifier.alpha(0.65f) else Modifier)
    if (assetUri == null) {
        Box(mod, contentAlignment = Alignment.Center) {
            Text(fallbackEmoji?.ifBlank { DEFAULT_ITEM_ICON } ?: DEFAULT_ITEM_ICON, fontSize = (size.value * 0.72f).sp)
        }
        return
    }
    AsyncImage(
        model = assetUri,
        contentDescription = null,
        modifier = mod,
        colorFilter = if (muted) ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0.45f) }) else null,
    )
}

/** Category section header: emoji + label + open-item count, in fixed shopping-route order. */
@Composable
private fun CategorySectionHeader(category: GroceryCategory, count: Int) {
    Row(
        Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CategoryIconView(category)
        Text(
            category.label,
            style = HbType.rowTitle.copy(fontWeight = FontWeight.Bold),
            color = Hb.ink,
            modifier = Modifier.weight(1f),
        )
        Text(count.toString(), style = HbType.meta, color = Hb.ink3)
    }
}

/** "In Kategorie verschieben" trigger + dropdown of the (editable, #411) categories. */
@Composable
private fun CategoryMoveMenu(current: String?, categories: List<GroceryCategory>, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        HbIconButton(HbIcons.tag, { open = true }, contentDescription = stringResource(R.string.cd_move_to_category))
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            categories.forEach { c ->
                DropdownMenuItem(
                    text = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            CategoryIconView(c, size = 20.dp)
                            Text(c.label, style = HbType.body, color = if (c.key == current) Hb.accent else Hb.ink)
                        }
                    },
                    onClick = { open = false; onPick(c.key) },
                )
            }
        }
    }
}

/**
 * Quick-add pill plus a "most used" autocomplete (#389): as the user types, matching suggestions
 * (prefix first, then substring; preloaded count-desc) appear below; tapping one adds it. The panel
 * is rendered inline below the pill so the text field keeps focus (no popup focus juggling).
 */
@Composable
private fun ShoppingQuickAddSection(
    value: String,
    onValueChange: (String) -> Unit,
    onAdd: (String) -> Unit,
    suggestions: List<ShoppingSuggestion>,
    categories: List<GroceryCategory>,
    placeholder: String,
) {
    HbQuickAdd(
        value = value,
        onValueChange = onValueChange,
        onSubmit = { onAdd(value) },
        placeholder = placeholder,
        leading = HbIcons.plus,
    )
    val q = value.trim().lowercase()
    val matches = remember(q, suggestions) {
        if (q.isEmpty()) {
            emptyList()
        } else {
            val pre = suggestions.filter { it.name.lowercase().startsWith(q) }
            val sub = suggestions.filter { !it.name.lowercase().startsWith(q) && it.name.lowercase().contains(q) }
            (pre + sub).take(6)
        }
    }
    if (matches.isNotEmpty()) {
        Spacer(Modifier.size(8.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .clip(HbRadius)
                .background(Hb.surface, HbRadius)
                .border(1.dp, Hb.line, HbRadius)
                .padding(5.dp),
        ) {
            Row(
                Modifier.padding(start = 11.dp, top = 7.dp, bottom = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                HbIcon(HbIcons.sparkle, size = 13.dp, tint = Hb.accent)
                Text(stringResource(R.string.shopping_suggestions_hint).uppercase(), style = HbType.sectionLabel, color = Hb.ink3)
            }
            matches.forEach { s ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(HbRadiusSm)
                        .clickable { onAdd(s.name) }
                        .padding(horizontal = 11.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SvgIcon(ShoppingIcons.assetForName(s.name, s.category), fallbackEmoji = s.icon, size = 24.dp)
                    Text(s.name, style = HbType.rowTitle, color = Hb.ink, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(categoryMeta(s.category, categories).label, style = HbType.meta, color = Hb.ink3, maxLines = 1)
                    Text("${s.count}×", style = HbType.meta, color = Hb.ink3)
                }
            }
        }
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

// Per-list own-categories footer (#412): a small on/off toggle + label + "manage" button, mirroring
// the web ShoppingView footer. Shown under the item list for the active list.
@Composable
private fun OwnCategoriesFooter(on: Boolean, onToggle: (Boolean) -> Unit, onManage: () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OwnCategoriesToggle(on = on, onClick = { onToggle(!on) })
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.shopping_own_categories), style = HbType.rowTitle, color = Hb.ink)
            Text(
                stringResource(R.string.shopping_own_categories_hint),
                style = HbType.small.copy(fontSize = 12.5.sp),
                color = Hb.ink3,
            )
        }
        if (on) {
            HbButton(
                stringResource(R.string.shopping_manage_categories),
                onClick = onManage,
                variant = HbButtonVariant.Secondary,
                size = HbButtonSize.Sm,
            )
        }
    }
}

/** Small pill on/off toggle (mirrors the Abwesenheit ToggleSwitch) for the own-categories switch. */
@Composable
private fun OwnCategoriesToggle(on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(width = 46.dp, height = 27.dp)
            .clip(HbPill)
            .background(if (on) Hb.accent else Hb.surface3)
            .clickable { onClick() }
            .padding(3.dp),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(Modifier.size(21.dp).clip(HbPill).background(Color.White))
    }
}

// Per-list "Kategorien verwalten" sheet (#412): reuses the Settings CategoriesCard, scoped to the
// active list's own set. „Sonstiges" (shared OTHER) is managed household-wide and not shown here.
@Composable
private fun ManageCategoriesSheet(
    listName: String,
    categories: List<ShoppingCategoryDto>,
    rules: List<ShoppingCategoryRuleDto>,
    onSave: (String?, String, String) -> Unit,
    onDelete: (String) -> Unit,
    onMove: (Int, Int) -> Unit,
    onSaveRule: (displayName: String, category: String, icon: String, editingName: String?) -> Unit,
    onDeleteRule: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    HbBottomSheet(
        onDismiss = onDismiss,
        title = stringResource(R.string.shopping_manage_categories_title, listName),
    ) {
        CategoriesCard(
            categories = categories,
            loading = false,
            onSave = onSave,
            onDelete = onDelete,
            onMove = onMove,
            title = stringResource(R.string.shopping_own_categories_card_title),
            hint = stringResource(R.string.shopping_own_categories_card_hint),
        )
        Spacer(Modifier.size(16.dp))
        // #501: the list's own auto-resolve rules, reusing the Settings RulesCard scoped to this list.
        RulesCard(
            categories = categories,
            rules = rules,
            loading = false,
            onSave = onSaveRule,
            onDelete = onDeleteRule,
            title = stringResource(R.string.shopping_own_rules_card_title),
            hint = stringResource(R.string.shopping_own_rules_card_hint),
        )
    }
}

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

/** Edit an item's name + free-text quantity + note (#447) + per-item icon override (#508). Empty
 *  quantity/note are sent as "" to clear; the icon is sent only when a new one is picked. */
@Composable
private fun EditItemSheet(
    item: ShoppingItemDto,
    onDismiss: () -> Unit,
    onSave: (name: String, quantity: String, note: String, icon: String?) -> Unit,
) {
    var name by remember { mutableStateOf(item.name) }
    var quantity by remember { mutableStateOf(item.quantity ?: "") }
    var note by remember { mutableStateOf(item.note ?: "") }
    // iconKey: null = untouched, "" = clear the override, else the chosen svg-basename (#508/#511).
    var iconKey by remember { mutableStateOf<String?>(null) }
    var showPicker by remember { mutableStateOf(false) }
    HbBottomSheet(
        onDismiss = onDismiss,
        title = stringResource(R.string.shopping_edit_item_title),
        footer = {
            HbButton(
                stringResource(R.string.action_cancel),
                onClick = onDismiss,
                variant = HbButtonVariant.Secondary,
                modifier = Modifier.weight(1f),
            )
            HbButton(
                stringResource(R.string.action_save),
                onClick = { onSave(name, quantity, note, iconKey) },
                variant = HbButtonVariant.Primary,
                enabled = name.isNotBlank(),
                modifier = Modifier.weight(1f),
            )
        },
    ) {
        HbField(stringResource(R.string.common_field_name)) {
            HbTextField(value = name, onValueChange = { name = it })
        }
        // Icon override (#508 — web parity #442): preview the current/chosen icon, open the picker, and
        // reset to auto-resolution (#511). '' clears; the preview then falls back to the name-based icon.
        HbField(stringResource(R.string.shopping_field_icon)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val previewItem = if (iconKey != null) item.copy(icon = iconKey?.ifEmpty { null }) else item
                ShoppingItemIcon(previewItem, size = 34.dp)
                HbButton(
                    stringResource(R.string.shopping_choose_icon),
                    onClick = { showPicker = true },
                    variant = HbButtonVariant.Secondary,
                    size = HbButtonSize.Sm,
                    icon = HbIcons.grid,
                )
                val effectiveIcon = if (iconKey != null) iconKey?.ifEmpty { null } else item.icon
                if (ShoppingIcons.isItemIconKey(effectiveIcon)) {
                    HbButton(
                        stringResource(R.string.shopping_reset_icon),
                        onClick = { iconKey = "" },
                        variant = HbButtonVariant.Ghost,
                        size = HbButtonSize.Sm,
                        icon = HbIcons.x,
                    )
                }
            }
        }
        HbField(stringResource(R.string.shopping_field_quantity)) {
            HbTextField(
                value = quantity,
                onValueChange = { quantity = it },
                placeholder = stringResource(R.string.shopping_quantity_placeholder),
            )
        }
        HbField(stringResource(R.string.shopping_field_note)) {
            HbTextField(
                value = note,
                onValueChange = { note = it },
                placeholder = stringResource(R.string.shopping_note_placeholder),
            )
        }
    }
    if (showPicker) {
        IconPickerSheet(
            current = iconKey ?: item.icon,
            onPick = { iconKey = it; showPicker = false },
            onDismiss = { showPicker = false },
        )
    }
}

/** Searchable grid of the designed item icons (#508, web parity #442). Picking one writes its svg
 *  basename as the item's icon override; the search matches the English key and the German names. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IconPickerSheet(
    current: String?,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val matches = remember(query) {
        ShoppingIcons.itemIconChoices.filter { ShoppingIcons.iconMatchesQuery(it.key, query) }
    }
    // Focus the search on open (web parity: the IconPicker's field autofocuses).
    val searchFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { searchFocus.requestFocus() }
    HbBottomSheet(
        onDismiss = onDismiss,
        title = stringResource(R.string.shopping_choose_icon),
        full = true,
    ) {
        HbTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = stringResource(R.string.shopping_icon_search),
            modifier = Modifier.focusRequester(searchFocus),
        )
        if (matches.isEmpty()) {
            Text(
                stringResource(R.string.shopping_icon_no_match),
                style = HbType.small,
                color = Hb.ink3,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        } else {
            FlowRow(
                Modifier.fillMaxWidth().padding(top = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                maxItemsInEachRow = 5,
            ) {
                matches.forEach { choice ->
                    IconPickerCell(
                        choice = choice,
                        selected = choice.key == current,
                        onClick = { onPick(choice.key) },
                        modifier = Modifier.weight(1f),
                    )
                }
                // Pad the last row so 1–4 trailing cells keep the column width (don't stretch).
                val remainder = matches.size % 5
                if (remainder != 0) repeat(5 - remainder) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** A single icon tile in the picker grid: the designed SVG, highlighted when it's the current pick. */
@Composable
private fun IconPickerCell(
    choice: IconChoice,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isSelected = selected
    Box(
        modifier
            .clip(HbRadiusSm)
            .background(if (selected) Hb.accentSoft else Hb.surface, HbRadiusSm)
            .border(
                if (selected) 1.5.dp else 1.dp,
                if (selected) Hb.accent else Hb.line,
                HbRadiusSm,
            )
            .clickable(onClick = onClick)
            .semantics { contentDescription = choice.key; this.selected = isSelected }
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        SvgIcon(choice.assetUri, fallbackEmoji = null, size = 30.dp)
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
        HbIconButton(HbIcons.cart, onApply, iconSize = 20.dp, tint = Hb.accentInk, contentDescription = stringResource(R.string.cd_add_to_shopping))
        HbIconButton(HbIcons.edit, onEdit, iconSize = 20.dp, contentDescription = stringResource(R.string.cd_edit))
        HbIconButton(HbIcons.trash, onDelete, iconSize = 20.dp, contentDescription = stringResource(R.string.cd_delete))
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
                        HbIconButton(HbIcons.x, { items.removeAt(i) }, iconSize = 18.dp, contentDescription = stringResource(R.string.cd_remove))
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
