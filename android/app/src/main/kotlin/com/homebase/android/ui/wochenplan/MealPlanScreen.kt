package com.homebase.android.ui.wochenplan

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.homebase.android.R
import com.homebase.android.data.model.RecipeDto
import com.homebase.android.ui.components.HbAppBar
import com.homebase.android.ui.components.HbBottomSheet
import com.homebase.android.ui.components.HbButton
import com.homebase.android.ui.components.HbButtonSize
import com.homebase.android.ui.components.HbButtonVariant
import com.homebase.android.ui.components.HbCard
import com.homebase.android.ui.components.HbFab
import com.homebase.android.ui.components.HbIcon
import com.homebase.android.ui.components.HbIconButton
import com.homebase.android.ui.components.HbScreenScaffold
import com.homebase.android.ui.components.HbTextField
import com.homebase.android.ui.components.HbToast
import com.homebase.android.ui.components.HbIcons
import com.homebase.android.ui.components.HbRadiusSm
import com.homebase.android.ui.theme.Hb
import com.homebase.android.ui.theme.HbType
import com.homebase.android.ui.util.Format
import kotlinx.coroutines.delay
import java.time.LocalDate

private fun slotLabelRes(slot: String): Int = when (slot) {
    "BREAKFAST" -> R.string.meal_plan_slot_breakfast
    "LUNCH" -> R.string.meal_plan_slot_lunch
    else -> R.string.meal_plan_slot_dinner
}

@Composable
fun MealPlanScreen(
    viewModel: MealPlanViewModel,
    onOpenDrawer: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // (date, slot) currently being edited in the picker; null = picker closed.
    var picking by remember { mutableStateOf<Pair<String, String>?>(null) }
    var addingToShopping by remember { mutableStateOf(false) }
    var toastMsg by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(toastMsg) { if (toastMsg != null) { delay(2600); toastMsg = null } }
    LaunchedEffect(state.error) { if (state.error != null) { delay(3000); viewModel.clearError() } }

    HbScreenScaffold(
        appBar = {
            HbAppBar(
                title = stringResource(R.string.meal_plan_title),
                eyebrow = stringResource(R.string.meal_plan_eyebrow),
                onLeft = onOpenDrawer,
            )
        },
        fab = {
            if (state.entries.isNotEmpty()) {
                HbFab(
                    onClick = { addingToShopping = true },
                    label = stringResource(R.string.meal_plan_add_to_shopping),
                    icon = HbIcons.cart,
                )
            }
        },
        overlay = {
            picking?.let { (date, slot) ->
                RecipePickerSheet(
                    recipes = state.recipes,
                    currentRecipeId = state.entryFor(date, slot)?.recipeId,
                    dateLabel = Format.longWeekdayDate(LocalDate.parse(date)),
                    slotLabel = stringResource(slotLabelRes(slot)),
                    onPick = { recipeId -> viewModel.setSlot(date, slot, recipeId); picking = null },
                    onRemove = { viewModel.clearSlot(date, slot); picking = null },
                    onDismiss = { picking = null },
                )
            }
            if (addingToShopping) {
                AddToShoppingSheet(
                    state = state,
                    onAdd = { listId ->
                        addingToShopping = false
                        viewModel.addWeekToShopping(listId) { added, merged ->
                            val parts = buildList {
                                if (added > 0) add(context.getString(R.string.meal_plan_added, added))
                                if (merged > 0) add(context.getString(R.string.meal_plan_merged, merged))
                            }
                            toastMsg = if (parts.isEmpty()) context.getString(R.string.meal_plan_nothing_added)
                            else parts.joinToString(" · ")
                        }
                    },
                    onDismiss = { addingToShopping = false },
                )
            }
            val toast = state.error ?: toastMsg
            if (toast != null) HbToast(message = toast, icon = if (state.error != null) null else HbIcons.checkCircle)
        },
    ) {
        WeekNavRow(
            weekStart = state.weekStart,
            onPrev = viewModel::prevWeek,
            onNext = viewModel::nextWeek,
            onToday = viewModel::goToday,
        )
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.weekDates.forEach { day ->
                DayCard(
                    day = day,
                    entryFor = { slot -> state.entryFor(day.toString(), slot) },
                    onPick = { slot -> picking = day.toString() to slot },
                    onRemove = { slot -> viewModel.clearSlot(day.toString(), slot) },
                )
            }
        }
    }
}

@Composable
private fun WeekNavRow(weekStart: LocalDate, onPrev: () -> Unit, onNext: () -> Unit, onToday: () -> Unit) {
    val thisMonday = LocalDate.now().with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
    val rel = when (weekStart) {
        thisMonday -> stringResource(R.string.meal_plan_this_week)
        thisMonday.minusWeeks(1) -> stringResource(R.string.meal_plan_last_week)
        else -> null
    }
    val range = "${Format.shortDate(weekStart)} – ${Format.shortDate(weekStart.plusDays(6))}"
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        HbIconButton(HbIcons.chevronLeft, onPrev)
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            if (rel != null) Text(rel, style = HbType.eyebrow, color = Hb.accentInk)
            Text(range, style = HbType.body.copy(fontWeight = FontWeight.SemiBold), color = Hb.ink)
        }
        HbIconButton(HbIcons.chevronRight, onNext)
        HbButton(stringResource(R.string.meal_plan_today), onToday, variant = HbButtonVariant.Ghost, size = HbButtonSize.Sm)
    }
}

@Composable
private fun DayCard(
    day: LocalDate,
    entryFor: (String) -> com.homebase.android.data.model.MealPlanEntryDto?,
    onPick: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    val today = day == LocalDate.now()
    HbCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                Format.longWeekdayDate(day),
                style = HbType.body.copy(fontWeight = FontWeight.Bold),
                color = if (today) Hb.accentInk else Hb.ink,
            )
            MEAL_SLOTS.forEach { slot ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        stringResource(slotLabelRes(slot)),
                        style = HbType.meta,
                        color = Hb.ink2,
                        modifier = Modifier.width(72.dp),
                    )
                    val entry = entryFor(slot)
                    if (entry != null) {
                        Row(
                            Modifier
                                .weight(1f)
                                .clip(HbRadiusSm)
                                .background(Hb.accentSoft, HbRadiusSm)
                                .clickable { onPick(slot) }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                entry.recipeTitle,
                                style = HbType.body.copy(fontWeight = FontWeight.Medium),
                                color = Hb.ink,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        HbIconButton(HbIcons.x, { onRemove(slot) }, iconSize = 18.dp)
                    } else {
                        Row(
                            Modifier
                                .weight(1f)
                                .clip(HbRadiusSm)
                                .border(1.dp, Hb.line, HbRadiusSm)
                                .clickable { onPick(slot) }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(7.dp),
                        ) {
                            HbIcon(HbIcons.plus, size = 16.dp, tint = Hb.ink3)
                            Text(stringResource(R.string.meal_plan_add_meal), style = HbType.body, color = Hb.ink3)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecipePickerSheet(
    recipes: List<RecipeDto>,
    currentRecipeId: String?,
    dateLabel: String,
    slotLabel: String,
    onPick: (String) -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val needle = query.trim().lowercase()
    val filtered = if (needle.isEmpty()) recipes else recipes.filter { it.title.lowercase().contains(needle) }

    HbBottomSheet(
        onDismiss = onDismiss,
        title = "$dateLabel · $slotLabel",
        footer = if (currentRecipeId != null) {
            {
                HbButton(stringResource(R.string.action_cancel), onDismiss, variant = HbButtonVariant.Secondary, modifier = Modifier.weight(1f))
                HbButton(stringResource(R.string.meal_plan_remove), onRemove, variant = HbButtonVariant.Danger, icon = HbIcons.trash, modifier = Modifier.weight(1f))
            }
        } else null,
    ) {
        if (recipes.isEmpty()) {
            Text(stringResource(R.string.meal_plan_pick_empty), style = HbType.body, color = Hb.ink2)
        } else {
            HbTextField(value = query, onValueChange = { query = it }, placeholder = stringResource(R.string.meal_plan_pick_search))
            if (filtered.isEmpty()) {
                Text(stringResource(R.string.meal_plan_pick_no_match), style = HbType.body, color = Hb.ink2)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    filtered.forEach { r ->
                        val selected = r.id == currentRecipeId
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(HbRadiusSm)
                                .background(if (selected) Hb.accentSoft else Hb.surface, HbRadiusSm)
                                .border(1.dp, if (selected) Hb.accent else Hb.lineSoft, HbRadiusSm)
                                .clickable { onPick(r.id) }
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(r.title, style = HbType.body, color = Hb.ink, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Format.recipeCategoryLabelRes(r.category)?.let {
                                Text(stringResource(it), style = HbType.small, color = Hb.ink3)
                            }
                            if (selected) HbIcon(HbIcons.check, size = 18.dp, tint = Hb.accent)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AddToShoppingSheet(
    state: MealPlanUiState,
    onAdd: (listId: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val byId = remember(state.recipes) { state.recipes.associateBy { it.id } }
    val itemCount = state.entries.sumOf { byId[it.recipeId]?.ingredients?.size ?: 0 }
    val dishCount = state.entries.size
    var listId by remember(state.shoppingLists) { mutableStateOf(state.shoppingLists.firstOrNull()?.id) }

    HbBottomSheet(
        onDismiss = onDismiss,
        title = stringResource(R.string.meal_plan_add_to_shopping_title),
        footer = {
            HbButton(stringResource(R.string.action_cancel), onDismiss, variant = HbButtonVariant.Secondary, modifier = Modifier.weight(1f))
            HbButton(
                stringResource(R.string.meal_plan_add_confirm),
                onClick = { listId?.let(onAdd) },
                variant = HbButtonVariant.Primary,
                icon = HbIcons.cart,
                enabled = listId != null && itemCount > 0,
                modifier = Modifier.weight(1f),
            )
        },
    ) {
        if (state.shoppingLists.isEmpty()) {
            Text(stringResource(R.string.meal_plan_no_list), style = HbType.body, color = Hb.ink2)
        } else {
            Text(
                stringResource(R.string.meal_plan_shop_summary, itemCount, dishCount),
                style = HbType.body,
                color = Hb.ink2,
            )
            if (state.shoppingLists.size > 1) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    state.shoppingLists.forEach { list ->
                        val selected = list.id == listId
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(HbRadiusSm)
                                .background(if (selected) Hb.accentSoft else Hb.surface, HbRadiusSm)
                                .border(1.dp, if (selected) Hb.accent else Hb.lineSoft, HbRadiusSm)
                                .clickable { listId = list.id }
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(list.name, style = HbType.body, color = Hb.ink, modifier = Modifier.weight(1f))
                            if (selected) HbIcon(HbIcons.check, size = 18.dp, tint = Hb.accent)
                        }
                    }
                }
            }
        }
    }
}

