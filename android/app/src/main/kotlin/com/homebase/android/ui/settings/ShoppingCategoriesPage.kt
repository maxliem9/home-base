package com.homebase.android.ui.settings

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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.homebase.android.R
import com.homebase.android.data.model.ShoppingCategoryDto
import com.homebase.android.data.model.ShoppingCategoryRuleDto
import com.homebase.android.ui.components.HbAppBar
import com.homebase.android.ui.components.HbButton
import com.homebase.android.ui.components.HbButtonSize
import com.homebase.android.ui.components.HbButtonVariant
import com.homebase.android.ui.components.HbCard
import com.homebase.android.ui.components.HbConfirmDialog
import com.homebase.android.ui.components.HbField
import com.homebase.android.ui.components.HbIcon
import com.homebase.android.ui.components.HbIconButton
import com.homebase.android.ui.components.HbIcons
import com.homebase.android.ui.components.HbScreenScaffold
import com.homebase.android.ui.components.HbTextField
import com.homebase.android.ui.components.HbToast
import com.homebase.android.ui.shopping.DEFAULT_ITEM_ICON
import com.homebase.android.ui.shopping.OTHER_CATEGORY_KEY
import com.homebase.android.ui.shopping.categoryMeta
import com.homebase.android.ui.shopping.toGrocery
import com.homebase.android.ui.theme.Hb
import com.homebase.android.ui.theme.HbType

/**
 * Einstellungen → Einkaufskategorien (#411) — the Android mirror of the web's
 * `ShoppingCategoriesSettings`. Two cards: the editable category catalog (add / rename / change emoji
 * / reorder ↑↓ / delete — OTHER protected) and the auto-resolve rules (add / edit / delete:
 * displayName → category + emoji). Backed by [ShoppingCategoriesViewModel]; deletes go through a
 * confirm dialog.
 */
@Composable
internal fun ShoppingCategoriesPage(viewModel: ShoppingCategoriesViewModel, onBack: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Box(Modifier.fillMaxSize()) {
        HbScreenScaffold(
            appBar = {
                HbAppBar(
                    eyebrow = stringResource(R.string.settings_eyebrow),
                    title = stringResource(R.string.settings_shopping_cats),
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
                CategoriesCard(
                    categories = state.categories,
                    loading = state.loading,
                    onSave = viewModel::saveCategory,
                    onDelete = viewModel::deleteCategory,
                    onMove = viewModel::moveCategory,
                )
                RulesCard(
                    categories = state.categories,
                    rules = state.rules,
                    loading = state.loading,
                    onSave = viewModel::saveRule,
                    onDelete = viewModel::deleteRule,
                )
            }
        }

        state.error?.let { msg ->
            HbToast(
                message = msg,
                icon = HbIcons.x,
                actionLabel = stringResource(R.string.action_ok),
                onAction = { viewModel.clearError() },
            )
        }
    }
}

// --- Categories card -------------------------------------------------------

/** Draft for the inline add/edit category form. `key == null` → add; else edit. */
private data class CategoryDraft(val key: String?, val label: String, val emoji: String)

// internal so the per-list "Kategorien verwalten" sheet (#412, shopping screen) reuses this exact card.
// [title]/[hint] override the settings-page copy for the per-list context.
@Composable
internal fun CategoriesCard(
    categories: List<ShoppingCategoryDto>,
    loading: Boolean,
    onSave: (key: String?, label: String, emoji: String) -> Unit,
    onDelete: (key: String) -> Unit,
    onMove: (index: Int, dir: Int) -> Unit,
    title: String? = null,
    hint: String? = null,
) {
    var draft by remember { mutableStateOf<CategoryDraft?>(null) }
    var confirmDelete by remember { mutableStateOf<ShoppingCategoryDto?>(null) }
    // Resolve defaults unconditionally (Compose requires stable @Composable call counts).
    val titleText = title ?: stringResource(R.string.settings_shopping_cats_title)
    val hintText = hint ?: stringResource(R.string.settings_shopping_cats_hint)

    HbCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        titleText,
                        style = HbType.rowTitle.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                        color = Hb.ink,
                    )
                    Text(
                        hintText,
                        style = HbType.small.copy(fontSize = 12.5.sp),
                        color = Hb.ink3,
                    )
                }
                if (draft == null) {
                    HbButton(
                        stringResource(R.string.settings_shopping_cat_add),
                        onClick = { draft = CategoryDraft(key = null, label = "", emoji = "") },
                        icon = HbIcons.plus,
                        size = HbButtonSize.Sm,
                    )
                }
            }

            when {
                loading -> Text(stringResource(R.string.common_loading), style = HbType.small.copy(fontSize = 12.5.sp), color = Hb.ink3)
                categories.isEmpty() -> Text(stringResource(R.string.settings_shopping_cats_empty), style = HbType.small.copy(fontSize = 12.5.sp), color = Hb.ink3)
                else -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    categories.forEachIndexed { i, c ->
                        CategoryRow(
                            category = c,
                            isFirst = i == 0,
                            isLast = i == categories.lastIndex,
                            onMoveUp = { onMove(i, -1) },
                            onMoveDown = { onMove(i, 1) },
                            onEdit = { draft = CategoryDraft(key = c.key, label = c.label, emoji = c.emoji) },
                            onDelete = { confirmDelete = c },
                        )
                    }
                }
            }

            draft?.let { d ->
                CategoryEditor(
                    draft = d,
                    onChange = { draft = it },
                    onCancel = { draft = null },
                    onSave = {
                        onSave(d.key, d.label, d.emoji)
                        draft = null
                    },
                )
            }
        }
    }

    confirmDelete?.let { c ->
        HbConfirmDialog(
            message = stringResource(R.string.settings_shopping_cat_delete_body, c.label),
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = { onDelete(c.key); confirmDelete = null },
            onDismiss = { confirmDelete = null },
        )
    }
}

@Composable
private fun CategoryRow(
    category: ShoppingCategoryDto,
    isFirst: Boolean,
    isLast: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(category.emoji, fontSize = 18.sp)
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(category.label, style = HbType.rowTitle, color = Hb.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (category.isBuiltin) {
                Text("· " + stringResource(R.string.settings_shopping_cat_builtin), style = HbType.meta, color = Hb.ink3)
            }
        }
        if (!isFirst) HbIconButton(HbIcons.chevronUp, onMoveUp, iconSize = 18.dp, contentDescription = stringResource(R.string.cd_move_up))
        if (!isLast) HbIconButton(HbIcons.chevronDown, onMoveDown, iconSize = 18.dp, contentDescription = stringResource(R.string.cd_move_down))
        HbIconButton(HbIcons.edit, onEdit, iconSize = 20.dp, contentDescription = stringResource(R.string.cd_edit))
        // OTHER is the protected fallback — its delete is hidden (backstopped server-side).
        if (category.key != OTHER_CATEGORY_KEY) {
            HbIconButton(HbIcons.trash, onDelete, tint = Hb.danger, iconSize = 20.dp, contentDescription = stringResource(R.string.cd_delete))
        }
    }
}

@Composable
private fun CategoryEditor(
    draft: CategoryDraft,
    onChange: (CategoryDraft) -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            stringResource(if (draft.key != null) R.string.settings_shopping_cat_edit else R.string.settings_shopping_cat_new).uppercase(),
            style = HbType.sectionLabel,
            color = Hb.ink3,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HbField(stringResource(R.string.settings_shopping_cat_emoji), modifier = Modifier.weight(0.4f)) {
                HbTextField(
                    value = draft.emoji,
                    onValueChange = { onChange(draft.copy(emoji = it)) },
                    placeholder = "🥦",
                )
            }
            HbField(stringResource(R.string.settings_shopping_cat_label), modifier = Modifier.weight(1f)) {
                HbTextField(
                    value = draft.label,
                    onValueChange = { onChange(draft.copy(label = it)) },
                    placeholder = stringResource(R.string.settings_shopping_cat_label_placeholder),
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HbButton(stringResource(R.string.action_cancel), onClick = onCancel, variant = HbButtonVariant.Secondary, size = HbButtonSize.Sm)
            HbButton(stringResource(R.string.action_save), onClick = onSave, icon = HbIcons.check, size = HbButtonSize.Sm, enabled = draft.label.isNotBlank())
        }
    }
}

// --- Rules card ------------------------------------------------------------

/** Draft for the inline add/edit rule form. [editingName] (set on edit) keeps the original display
 *  name so an edit that also renames can drop the stale, normalized-keyed rule. */
private data class RuleDraft(val displayName: String, val category: String, val icon: String, val editingName: String?)

@Composable
private fun RulesCard(
    categories: List<ShoppingCategoryDto>,
    rules: List<ShoppingCategoryRuleDto>,
    loading: Boolean,
    onSave: (displayName: String, category: String, icon: String, editingName: String?) -> Unit,
    onDelete: (displayName: String) -> Unit,
) {
    var draft by remember { mutableStateOf<RuleDraft?>(null) }
    var confirmDelete by remember { mutableStateOf<ShoppingCategoryRuleDto?>(null) }

    HbCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        stringResource(R.string.settings_shopping_rules_title),
                        style = HbType.rowTitle.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                        color = Hb.ink,
                    )
                    Text(
                        stringResource(R.string.settings_shopping_rules_hint),
                        style = HbType.small.copy(fontSize = 12.5.sp),
                        color = Hb.ink3,
                    )
                }
                if (draft == null) {
                    HbButton(
                        stringResource(R.string.settings_shopping_rule_add),
                        onClick = {
                            // Default the category to OTHER (or the first category) when adding.
                            val def = categories.firstOrNull { it.key == OTHER_CATEGORY_KEY }?.key
                                ?: categories.firstOrNull()?.key ?: OTHER_CATEGORY_KEY
                            draft = RuleDraft(displayName = "", category = def, icon = "", editingName = null)
                        },
                        icon = HbIcons.plus,
                        size = HbButtonSize.Sm,
                        enabled = categories.isNotEmpty(),
                    )
                }
            }

            when {
                loading -> Text(stringResource(R.string.common_loading), style = HbType.small.copy(fontSize = 12.5.sp), color = Hb.ink3)
                rules.isEmpty() -> Text(stringResource(R.string.settings_shopping_rules_empty), style = HbType.small.copy(fontSize = 12.5.sp), color = Hb.ink3)
                else -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    rules.forEach { r ->
                        RuleRow(
                            rule = r,
                            categories = categories,
                            onEdit = { draft = RuleDraft(displayName = r.displayName, category = r.category, icon = r.icon, editingName = r.displayName) },
                            onDelete = { confirmDelete = r },
                        )
                    }
                }
            }

            draft?.let { d ->
                RuleEditor(
                    draft = d,
                    categories = categories,
                    onChange = { draft = it },
                    onCancel = { draft = null },
                    onSave = {
                        onSave(d.displayName, d.category, d.icon, d.editingName)
                        draft = null
                    },
                )
            }
        }
    }

    confirmDelete?.let { r ->
        HbConfirmDialog(
            message = stringResource(R.string.settings_shopping_rule_delete_body, r.displayName),
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = { onDelete(r.displayName); confirmDelete = null },
            onDismiss = { confirmDelete = null },
        )
    }
}

@Composable
private fun RuleRow(
    rule: ShoppingCategoryRuleDto,
    categories: List<ShoppingCategoryDto>,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val meta = categoryMeta(rule.category, categories.map { it.toGrocery() })
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(rule.icon.ifBlank { DEFAULT_ITEM_ICON }, fontSize = 18.sp)
        Column(Modifier.weight(1f)) {
            Text(rule.displayName, style = HbType.rowTitle, color = Hb.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${meta.emoji} ${meta.label}", style = HbType.meta, color = Hb.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        HbIconButton(HbIcons.edit, onEdit, iconSize = 20.dp, contentDescription = stringResource(R.string.cd_edit))
        HbIconButton(HbIcons.trash, onDelete, tint = Hb.danger, iconSize = 20.dp, contentDescription = stringResource(R.string.cd_delete))
    }
}

@Composable
private fun RuleEditor(
    draft: RuleDraft,
    categories: List<ShoppingCategoryDto>,
    onChange: (RuleDraft) -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            stringResource(if (draft.editingName != null) R.string.settings_shopping_rule_edit else R.string.settings_shopping_rule_new).uppercase(),
            style = HbType.sectionLabel,
            color = Hb.ink3,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HbField(stringResource(R.string.settings_shopping_rule_emoji), modifier = Modifier.weight(0.45f)) {
                HbTextField(
                    value = draft.icon,
                    onValueChange = { onChange(draft.copy(icon = it)) },
                    placeholder = DEFAULT_ITEM_ICON,
                )
            }
            HbField(stringResource(R.string.settings_shopping_rule_name), modifier = Modifier.weight(1f)) {
                HbTextField(
                    value = draft.displayName,
                    onValueChange = { onChange(draft.copy(displayName = it)) },
                    placeholder = stringResource(R.string.settings_shopping_rule_name_placeholder),
                )
            }
        }
        HbField(stringResource(R.string.settings_shopping_rule_category)) {
            CategorySelect(
                categories = categories,
                selected = draft.category,
                onSelect = { onChange(draft.copy(category = it)) },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HbButton(stringResource(R.string.action_cancel), onClick = onCancel, variant = HbButtonVariant.Secondary, size = HbButtonSize.Sm)
            HbButton(
                stringResource(R.string.action_save),
                onClick = onSave,
                icon = HbIcons.check,
                size = HbButtonSize.Sm,
                enabled = draft.displayName.isNotBlank() && draft.category.isNotBlank(),
            )
        }
    }
}

/** Category picker for the rule editor: a tap-to-open dropdown of all categories (emoji + label). */
@Composable
private fun CategorySelect(
    categories: List<ShoppingCategoryDto>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val current = categories.firstOrNull { it.key == selected }
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .border(1.dp, Hb.line, RoundedCornerShape(10.dp))
                .clickable { open = true }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(current?.emoji ?: "", fontSize = 16.sp)
            Text(
                current?.label ?: "",
                style = HbType.body,
                color = Hb.ink,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            HbIcon(HbIcons.chevronDown, size = 18.dp, tint = Hb.ink3)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            categories.forEach { c ->
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(c.emoji, fontSize = 16.sp)
                            Text(c.label, style = HbType.body, color = if (c.key == selected) Hb.accent else Hb.ink)
                        }
                    },
                    onClick = { open = false; onSelect(c.key) },
                )
            }
        }
    }
}
