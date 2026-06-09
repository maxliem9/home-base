package com.homebase.android.ui.recipes

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.homebase.android.data.model.CreateRecipeRequest
import com.homebase.android.data.model.IngredientDto
import com.homebase.android.data.model.IngredientInput
import com.homebase.android.data.model.RecipeDto
import com.homebase.android.data.model.RecipeStepDto
import com.homebase.android.data.model.RecipeStepInput
import com.homebase.android.data.model.ShoppingLineInput
import com.homebase.android.data.model.ShoppingListDto
import com.homebase.android.ui.components.HbAppBar
import com.homebase.android.ui.components.HbBadge
import com.homebase.android.ui.components.HbBottomSheet
import com.homebase.android.ui.components.HbButton
import com.homebase.android.ui.components.HbButtonVariant
import com.homebase.android.ui.components.HbCard
import com.homebase.android.ui.components.HbCheck
import com.homebase.android.ui.components.HbDotSep
import com.homebase.android.ui.components.HbEmpty
import com.homebase.android.ui.components.HbField
import com.homebase.android.ui.components.HbFab
import com.homebase.android.ui.components.HbIcon
import com.homebase.android.ui.components.HbIconButton
import com.homebase.android.ui.components.HbIcons
import com.homebase.android.ui.components.HbPickText
import com.homebase.android.ui.components.HbPill
import com.homebase.android.ui.components.HbRadiusSm
import com.homebase.android.ui.components.HbScreenScaffold
import com.homebase.android.ui.components.HbSectionLabel
import com.homebase.android.ui.components.HbTagChip
import com.homebase.android.ui.components.HbTextField
import com.homebase.android.ui.components.HbToast
import com.homebase.android.ui.components.HbTone
import com.homebase.android.ui.shopping.ShoppingViewModel
import com.homebase.android.ui.theme.Hb
import com.homebase.android.ui.theme.HbType
import com.homebase.android.ui.util.FileShare
import com.homebase.android.ui.util.Format

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

private val CATEGORIES = listOf("Alle", "Frühstück", "Hauptgerichte", "Snack", "Dessert", "Getränk")

/** Short units recognised when parsing a "200 g Mehl" ingredient line. */
//
// ACHTUNG — bewusst gespiegelt: Diese Liste ist absichtlich identisch mit der KNOWN_UNITS-Liste
// (und der parseQty/parseIngredientLine-Heuristik) auf der Backend-Seite:
//   backend/src/main/kotlin/com/homebase/routes/ShoppingRoutes.kt
// Es gibt keinen Mechanismus, der das erzwingt — wer hier eine Einheit ergänzt/entfernt oder die
// Parse-Heuristik ändert, MUSS die andere Datei mitziehen, sonst driften Android (Rezept-Freitext)
// und Backend (Shopping-Merge) still auseinander. Web hat keinen Freitext-Parser. Siehe Issue #103.
private val KNOWN_UNITS = setOf(
    "g", "kg", "mg", "ml", "l", "el", "tl", "stk", "stück", "prise",
    "bund", "dose", "pkg", "pck", "tasse", "cup", "msp",
)

private fun totalTime(r: RecipeDto): Int = (r.prepTimeMinutes ?: 0) + (r.cookTimeMinutes ?: 0)

/**
 * Group ingredients into consecutive runs sharing the same section label (issue #123).
 * Ingredients arrive ordered by sortOrder (authoring order), so consecutive grouping
 * faithfully reconstructs the sections. A blank/absent section becomes the header-less
 * top group (null first).
 */
internal fun groupIngredientsBySection(items: List<IngredientDto>): List<Pair<String?, List<IngredientDto>>> {
    val groups = mutableListOf<Pair<String?, MutableList<IngredientDto>>>()
    for (ing in items) {
        val sec = ing.section?.trim()?.takeIf { it.isNotEmpty() }
        val last = groups.lastOrNull()
        if (last != null && last.first == sec) last.second.add(ing)
        else groups.add(sec to mutableListOf(ing))
    }
    return groups.map { it.first to it.second.toList() }
}

/**
 * Serialise stored ingredients back into the free-text editor format — the inverse of
 * [parseIngredients]. Groups by section (via [groupIngredientsBySection]), prefixes each named
 * group with a `# <section>` header line, and renders every ingredient as `<amount> <unit> <name>`
 * (omitting any missing part). Used to pre-fill the editor when editing an existing recipe so its
 * sections survive the round-trip instead of collapsing into the header-less top group (issue #11).
 *
 * The unit is only emitted together with an amount: the free-text format can't represent a
 * unit-without-amount unambiguously (a leading "g Mehl" would parse back as the name "g Mehl"),
 * so such a — in practice non-existent — ingredient keeps its name and drops the stray unit.
 */
internal fun ingredientsToText(items: List<IngredientDto>): String {
    val lines = mutableListOf<String>()
    for ((section, group) in groupIngredientsBySection(items)) {
        if (section != null) lines += "# $section"
        for (ing in group) {
            val prefix = ing.amount?.let { amount ->
                listOfNotNull(Format.amount(amount), ing.unit?.trim()?.takeIf { it.isNotEmpty() })
                    .joinToString(" ")
            }
            lines += if (prefix.isNullOrEmpty()) ing.name else "$prefix ${ing.name}"
        }
    }
    return lines.joinToString("\n")
}

/** Serialise stored steps back into the free-text editor format — one step per line. */
internal fun stepsToText(steps: List<RecipeStepDto>): String =
    steps.joinToString("\n") { it.description }

/** Map a German category chip label back to the backend enum value. */
private fun categoryLabelToEnum(label: String): String = when (label) {
    "Frühstück" -> "BREAKFAST"
    "Hauptgerichte" -> "DINNER"
    "Snack" -> "SNACK"
    "Dessert" -> "DESSERT"
    "Getränk" -> "DRINK"
    else -> "DINNER"
}

// ---------------------------------------------------------------------------
// Striped placeholder band ("Foto folgt")
// ---------------------------------------------------------------------------

/**
 * Repeating diagonal-stripe brush (~135°) alternating the recipe's warm dark/light band
 * tones, mirroring the CSS `repeating-linear-gradient(135deg, …)`. The gradient runs along
 * a short vector and tiles via [TileMode.Repeated]; the (1,1) direction yields the 135° look.
 */
private fun stripeBrush(hue: Double, periodPx: Float): Brush {
    val dark = Hb.recipeBandDark(hue)
    val light = Hb.recipeBandLight(hue)
    return Brush.linearGradient(
        0.0f to dark,
        0.5f to dark,
        0.5f to light,
        1.0f to light,
        start = Offset(0f, 0f),
        end = Offset(periodPx, periodPx),
        tileMode = TileMode.Repeated,
    )
}

// ---------------------------------------------------------------------------
// Top-level screen — toggles between the list and a full-screen detail page.
// ---------------------------------------------------------------------------

@Composable
fun RecipesScreen(
    viewModel: RecipesViewModel,
    shoppingViewModel: ShoppingViewModel,
    onOpenDrawer: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    var selectedId by remember { mutableStateOf<String?>(null) }

    // Keep the detail page in sync with WS updates; if the recipe vanishes, fall back to list.
    val selected = selectedId?.let { id -> state.recipes.firstOrNull { it.id == id } }
    if (selectedId != null && selected == null) {
        LaunchedEffect(selectedId) { selectedId = null }
    }

    if (selected != null) {
        RecipeDetailPage(
            recipe = selected,
            onBack = { selectedId = null },
            onDelete = { viewModel.deleteRecipe(selected.id) { selectedId = null } },
            shoppingViewModel = shoppingViewModel,
            viewModel = viewModel,
        )
    } else {
        RecipeListPage(
            state = state,
            onOpen = { selectedId = it.id },
            onOpenDrawer = onOpenDrawer,
            onSave = { request -> viewModel.saveRecipe(null, request) { saved -> selectedId = saved.id } },
        )
    }
}

// ---------------------------------------------------------------------------
// List page
// ---------------------------------------------------------------------------

@Composable
private fun RecipeListPage(
    state: RecipesUiState,
    onOpen: (RecipeDto) -> Unit,
    onOpenDrawer: () -> Unit,
    onSave: (CreateRecipeRequest) -> Unit,
) {
    var selectedCat by remember { mutableStateOf("Alle") }
    var showNewSheet by remember { mutableStateOf(false) }

    val recipes = if (selectedCat == "Alle") {
        state.recipes
    } else {
        state.recipes.filter { Format.recipeCategoryLabel(it.category) == selectedCat }
    }

    Box(Modifier.fillMaxSize()) {
        HbScreenScaffold(
            appBar = {
                HbAppBar(
                    eyebrow = "${state.recipes.size} Rezepte",
                    title = "Rezepte",
                    onLeft = onOpenDrawer,
                    actions = { HbIconButton(HbIcons.search, {}) },
                )
            },
            fab = { HbFab(onClick = { showNewSheet = true }, label = "Rezept") },
        ) {
            // Full-bleed category chip row
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.width(18.dp))
                CATEGORIES.forEach { cat ->
                    HbTagChip(
                        text = cat,
                        active = selectedCat == cat,
                        onClick = { selectedCat = cat },
                    )
                }
                Spacer(Modifier.width(18.dp))
            }

            Spacer(Modifier.size(18.dp))

            if (recipes.isEmpty()) {
                HbEmpty(
                    HbIcons.chef,
                    "Keine Rezepte",
                    if (selectedCat == "Alle") {
                        "Lege dein erstes Rezept an."
                    } else {
                        "In „$selectedCat“ ist noch nichts.\nLege ein neues Rezept an."
                    },
                )
            } else {
                RecipeGrid(recipes = recipes, onOpen = onOpen)
            }
        }

        if (showNewSheet) {
            RecipeFormSheet(
                existing = null,
                onDismiss = { showNewSheet = false },
                onSave = { request ->
                    showNewSheet = false
                    onSave(request)
                },
            )
        }
    }
}

@Composable
private fun ColumnScope.RecipeGrid(recipes: List<RecipeDto>, onOpen: (RecipeDto) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        recipes.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                row.forEach { recipe ->
                    Box(Modifier.weight(1f)) {
                        RecipeCard(recipe = recipe, onClick = { onOpen(recipe) })
                    }
                }
                // keep a single trailing card half-width
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun RecipeCard(recipe: RecipeDto, onClick: () -> Unit) {
    val hue = Format.recipeHue(recipe.id)
    HbCard(modifier = Modifier.fillMaxWidth().clickable { onClick() }, pad = false) {
        Column(Modifier.fillMaxWidth()) {
            // Placeholder image band
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(104.dp)
                    .background(stripeBrush(hue, 31f)),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.align(Alignment.TopStart).padding(9.dp),
                ) { HbBadge(Format.recipeCategoryLabel(recipe.category), tone = HbTone.Neutral) }
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    HbIcon(HbIcons.chef, size = 26.dp, tint = Hb.recipeBandInk(hue))
                    Text(
                        "FOTO FOLGT",
                        style = HbType.mono.copy(fontSize = 10.sp, letterSpacing = 0.04.em),
                        color = Hb.recipeBandInk(hue).copy(alpha = 0.75f),
                    )
                }
            }
            // Body
            Column(
                Modifier.padding(start = 14.dp, end = 14.dp, top = 13.dp, bottom = 15.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(
                    recipe.title,
                    style = TextStyle(fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold, lineHeight = 19.sp),
                    color = Hb.ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!recipe.description.isNullOrBlank()) {
                    Text(
                        recipe.description,
                        style = TextStyle(fontSize = 12.5.sp, lineHeight = 17.sp),
                        color = Hb.ink3,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    HbIcon(HbIcons.clock, size = 14.dp, tint = Hb.ink2)
                    Text(
                        "${totalTime(recipe)} Min",
                        style = HbType.small.copy(fontWeight = FontWeight.Medium),
                        color = Hb.ink2,
                    )
                    HbDotSep()
                    HbIcon(HbIcons.users, size = 14.dp, tint = Hb.ink2)
                    Text(
                        "${recipe.servings}",
                        style = HbType.small.copy(fontWeight = FontWeight.Medium),
                        color = Hb.ink2,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Detail page (full-screen)
// ---------------------------------------------------------------------------

@Composable
private fun RecipeDetailPage(
    recipe: RecipeDto,
    onBack: () -> Unit,
    onDelete: () -> Unit,
    shoppingViewModel: ShoppingViewModel,
    viewModel: RecipesViewModel,
) {
    BackHandler(onBack = onBack)

    val context = LocalContext.current
    val shoppingState by shoppingViewModel.uiState.collectAsStateWithLifecycle()
    var showPicker by remember { mutableStateOf(false) }
    var showEdit by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var toastMsg by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(toastMsg) {
        if (toastMsg != null) {
            kotlinx.coroutines.delay(2600)
            toastMsg = null
        }
    }

    // Fetch the export bytes, then hand them to the system share sheet as a cached file.
    val export: (String) -> Unit = { format ->
        val (ext, mime) = if (format == "pdf") "pdf" to "application/pdf" else "md" to "text/markdown"
        viewModel.exportRecipe(recipe.id, format) { result ->
            result
                .onSuccess { bytes -> FileShare.share(context, "rezept_${FileShare.slug(recipe.title)}.$ext", mime, bytes) }
                .onFailure { toastMsg = "Export fehlgeschlagen" }
        }
    }

    val hue = Format.recipeHue(recipe.id)

    Box(Modifier.fillMaxSize()) {
    HbScreenScaffold(
        appBar = {
            HbAppBar(
                title = "Rezept",
                titleSm = true,
                bordered = true,
                leftIcon = HbIcons.chevronLeft,
                onLeft = onBack,
                actions = {
                    Box {
                        HbIconButton(HbIcons.more, { menuOpen = true })
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Bearbeiten", style = HbType.body, color = Hb.ink) },
                                onClick = { menuOpen = false; showEdit = true },
                            )
                            DropdownMenuItem(
                                text = { Text("Als Markdown", style = HbType.body, color = Hb.ink) },
                                onClick = { menuOpen = false; export("md") },
                            )
                            DropdownMenuItem(
                                text = { Text("Als PDF", style = HbType.body, color = Hb.ink) },
                                onClick = { menuOpen = false; export("pdf") },
                            )
                        }
                    }
                },
            )
        },
        overlay = {
            toastMsg?.let { msg -> HbToast(message = msg) }
        },
    ) {
        // Full-bleed hero band
        Box(
            Modifier
                .fillMaxWidth()
                .height(188.dp)
                .background(stripeBrush(hue, 37f)),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.align(Alignment.TopStart).padding(12.dp)) {
                HbBadge(Format.recipeCategoryLabel(recipe.category), tone = HbTone.Neutral)
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                HbIcon(HbIcons.chef, size = 34.dp, tint = Hb.recipeBandInk(hue))
                Text(
                    "FOTO FOLGT",
                    style = HbType.mono.copy(fontSize = 10.sp, letterSpacing = 0.04.em),
                    color = Hb.recipeBandInk(hue).copy(alpha = 0.75f),
                )
            }
        }

        Column(Modifier.padding(horizontal = 18.dp)) {
            Spacer(Modifier.size(18.dp))
            Text(recipe.title, style = HbType.docTitle, color = Hb.ink)
            if (!recipe.description.isNullOrBlank()) {
                Spacer(Modifier.size(8.dp))
                Text(
                    recipe.description,
                    style = TextStyle(fontSize = 14.5.sp, lineHeight = 22.sp),
                    color = Hb.ink3,
                )
            }

            // Fact tiles
            Spacer(Modifier.size(18.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FactTile("${recipe.servings}", "Portionen", Modifier.weight(1f))
                FactTile("${recipe.prepTimeMinutes ?: 0}", "Vorb. Min", Modifier.weight(1f))
                FactTile("${recipe.cookTimeMinutes ?: 0}", "Koch Min", Modifier.weight(1f))
                FactTile("${totalTime(recipe)}", "Gesamt", Modifier.weight(1f))
            }
            Spacer(Modifier.size(18.dp))

            // Ingredients (optionally grouped into named sections — issue #123)
            if (recipe.ingredients.isNotEmpty()) {
                HbSectionLabel("Zutaten")
                groupIngredientsBySection(recipe.ingredients).forEach { group ->
                    val section = group.first
                    val items = group.second
                    if (section != null) IngredientSectionHeader(section)
                    items.forEachIndexed { i, ing ->
                        IngredientRow(
                            amountUnit = "${ing.amount?.let { Format.amount(it) } ?: ""} ${ing.unit ?: ""}".trim(),
                            name = ing.name,
                            divider = i < items.lastIndex,
                        )
                    }
                }
                Spacer(Modifier.size(22.dp))
            }

            // Steps
            if (recipe.steps.isNotEmpty()) {
                HbSectionLabel("Zubereitung")
                Column(verticalArrangement = Arrangement.spacedBy(15.dp)) {
                    recipe.steps.forEach { step ->
                        StepRow(number = step.stepNumber, description = step.description)
                    }
                }
                Spacer(Modifier.size(26.dp))
            }

            // Footer actions
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HbButton(
                    "Löschen",
                    onClick = onDelete,
                    variant = HbButtonVariant.Danger,
                    icon = HbIcons.trash,
                )
                HbButton(
                    "Zutaten zur Liste",
                    onClick = { showPicker = true },
                    variant = HbButtonVariant.Primary,
                    icon = HbIcons.cart,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.size(28.dp))
        }
    }

        if (showPicker) {
            AddToShoppingSheet(
                recipe = recipe,
                lists = shoppingState.lists,
                onDismiss = { showPicker = false },
                onConfirm = { listId, lines ->
                    showPicker = false
                    shoppingViewModel.addIngredients(listId, lines) { added, merged ->
                        toastMsg = addToast(added, merged)
                    }
                },
            )
        }

        if (showEdit) {
            RecipeFormSheet(
                existing = recipe,
                onDismiss = { showEdit = false },
                onSave = { request ->
                    showEdit = false
                    viewModel.saveRecipe(recipe.id, request)
                },
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
            .padding(horizontal = 8.dp, vertical = 11.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            value,
            style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold, lineHeight = 18.sp),
            color = Hb.ink,
        )
        Text(label, style = HbType.small.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium), color = Hb.ink3)
    }
}

@Composable
private fun IngredientSectionHeader(text: String) {
    Text(
        text,
        style = HbType.small.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
        color = Hb.ink2,
        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
    )
}

@Composable
private fun IngredientRow(amountUnit: String, name: String, divider: Boolean) {
    Column {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                amountUnit,
                style = HbType.mono.copy(fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold),
                color = Hb.accentInk,
                modifier = Modifier.widthIn(min = 70.dp),
            )
            Text(
                name,
                style = TextStyle(fontSize = 14.5.sp, lineHeight = 20.sp),
                color = Hb.ink,
                modifier = Modifier.weight(1f),
            )
        }
        if (divider) Box(Modifier.fillMaxWidth().height(1.dp).background(Hb.lineSoft))
    }
}

@Composable
private fun StepRow(number: Int, description: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(13.dp), verticalAlignment = Alignment.Top) {
        Box(
            Modifier.size(26.dp).clip(HbPill).background(Hb.accentSoft, HbPill),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "$number",
                style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold),
                color = Hb.accentInk,
            )
        }
        Text(
            description,
            style = TextStyle(fontSize = 14.5.sp, lineHeight = 22.sp),
            color = Hb.ink,
            modifier = Modifier.weight(1f),
        )
    }
}

// ---------------------------------------------------------------------------
// "Zutaten zur Liste" picker — pick servings, ingredients and a target list
// ---------------------------------------------------------------------------

/** Toast copy after a batch add, e.g. "3 hinzugefügt · 1 zusammengeführt". */
private fun addToast(added: Int, merged: Int): String = when {
    added == 0 && merged == 0 -> "Nichts hinzugefügt"
    merged == 0 -> "$added ${if (added == 1) "Zutat" else "Zutaten"} hinzugefügt"
    added == 0 -> "$merged zusammengeführt"
    else -> "$added hinzugefügt · $merged zusammengeführt"
}

@Composable
private fun AddToShoppingSheet(
    recipe: RecipeDto,
    lists: List<ShoppingListDto>,
    onDismiss: () -> Unit,
    onConfirm: (listId: String, lines: List<ShoppingLineInput>) -> Unit,
) {
    val baseServings = recipe.servings.coerceAtLeast(1)
    var servings by remember { mutableStateOf(baseServings) }
    var selected by remember { mutableStateOf(recipe.ingredients.map { true }) }
    var listId by remember { mutableStateOf(lists.firstOrNull()?.id) }
    var menuOpen by remember { mutableStateOf(false) }

    val factor = servings.toDouble() / baseServings.toDouble()
    val count = selected.count { it }
    val selectedList = lists.firstOrNull { it.id == listId } ?: lists.firstOrNull()

    HbBottomSheet(
        onDismiss = onDismiss,
        title = "Zutaten zur Liste",
        footer = {
            HbButton(
                "Abbrechen",
                onClick = onDismiss,
                variant = HbButtonVariant.Secondary,
                modifier = Modifier.weight(1f),
            )
            HbButton(
                if (count > 0) "$count hinzufügen" else "Hinzufügen",
                onClick = {
                    val targetId = selectedList?.id
                    val lines = recipe.ingredients
                        .filterIndexed { i, _ -> selected.getOrElse(i) { false } }
                        .map { ing ->
                            ShoppingLineInput(
                                name = ing.name,
                                amount = ing.amount?.let { Math.round(it * factor * 1000.0) / 1000.0 },
                                unit = ing.unit,
                            )
                        }
                    if (targetId != null && lines.isNotEmpty()) onConfirm(targetId, lines)
                },
                variant = HbButtonVariant.Primary,
                icon = HbIcons.cart,
                enabled = count > 0 && selectedList != null,
                modifier = Modifier.weight(1f),
            )
        },
    ) {
        if (lists.isEmpty()) {
            Text("Lege zuerst eine Einkaufsliste an.", style = HbType.body, color = Hb.ink3)
        } else {
            // Target list — only offer a choice when there's more than one.
            if (lists.size > 1) {
                HbField("Liste") {
                    Box {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(HbRadiusSm)
                                .background(Hb.surface, HbRadiusSm)
                                .border(1.dp, Hb.line, HbRadiusSm)
                                .clickable { menuOpen = true }
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                selectedList?.name ?: "—",
                                style = HbType.body,
                                color = Hb.ink,
                                modifier = Modifier.weight(1f),
                            )
                            HbIcon(HbIcons.chevronDown, size = 18.dp, tint = Hb.ink3)
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            lists.forEach { l ->
                                DropdownMenuItem(
                                    text = { Text(l.name, style = HbType.body, color = Hb.ink) },
                                    onClick = { listId = l.id; menuOpen = false },
                                )
                            }
                        }
                    }
                }
            }

            // Servings — drives the amount scaling.
            HbField("Portionen") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    StepButton(HbIcons.minus) { if (servings > 1) servings-- }
                    Text("$servings", style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold), color = Hb.ink)
                    StepButton(HbIcons.plus) { servings++ }
                    if (factor != 1.0) {
                        Text(
                            "Mengen ×${Format.amount(factor)}",
                            style = HbType.small.copy(fontSize = 12.5.sp),
                            color = Hb.ink3,
                        )
                    }
                }
            }

            // Ingredient checklist (all on by default — untick the staples you keep at home).
            HbSectionLabel("Zutaten")
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                recipe.ingredients.forEachIndexed { i, ing ->
                    val amountUnit = "${ing.amount?.let { Format.amount(it * factor) } ?: ""} ${ing.unit ?: ""}".trim()
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
                            amountUnit.ifBlank { "·" },
                            style = HbType.mono.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                            color = Hb.accentInk,
                            modifier = Modifier.widthIn(min = 64.dp),
                        )
                        Text(
                            ing.name,
                            style = TextStyle(fontSize = 14.5.sp),
                            color = Hb.ink,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StepButton(icon: ImageVector, onClick: () -> Unit) {
    Box(
        Modifier
            .size(34.dp)
            .clip(HbPill)
            .background(Hb.surface2, HbPill)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) { HbIcon(icon, size = 18.dp, tint = Hb.ink2) }
}

// ---------------------------------------------------------------------------
// Recipe form sheet — shared by "new recipe" and "edit recipe" (issue #11)
// ---------------------------------------------------------------------------

@Composable
private fun RecipeFormSheet(
    existing: RecipeDto?,
    onDismiss: () -> Unit,
    onSave: (CreateRecipeRequest) -> Unit,
) {
    val catChips = listOf("Frühstück", "Hauptgerichte", "Snack", "Dessert", "Getränk")

    var title by remember { mutableStateOf(existing?.title ?: "") }
    var categoryLabel by remember {
        mutableStateOf(
            existing?.let { Format.recipeCategoryLabel(it.category) }?.takeIf { it in catChips }
                ?: "Hauptgerichte",
        )
    }
    var servings by remember { mutableStateOf(existing?.servings?.toString() ?: "") }
    var prep by remember { mutableStateOf(existing?.prepTimeMinutes?.toString() ?: "") }
    var cook by remember { mutableStateOf(existing?.cookTimeMinutes?.toString() ?: "") }
    var description by remember { mutableStateOf(existing?.description ?: "") }
    var ingredientsText by remember { mutableStateOf(existing?.let { ingredientsToText(it.ingredients) } ?: "") }
    var stepsText by remember { mutableStateOf(existing?.let { stepsToText(it.steps) } ?: "") }

    HbBottomSheet(
        onDismiss = onDismiss,
        title = if (existing == null) "Neues Rezept" else "Rezept bearbeiten",
        full = true,
        footer = {
            HbButton(
                "Abbrechen",
                onClick = onDismiss,
                variant = HbButtonVariant.Secondary,
                modifier = Modifier.weight(1f),
            )
            HbButton(
                "Speichern",
                onClick = {
                    onSave(
                        CreateRecipeRequest(
                            title = title.trim(),
                            description = description.trim().ifBlank { null },
                            servings = servings.toIntOrNull(),
                            prepTimeMinutes = prep.toIntOrNull(),
                            cookTimeMinutes = cook.toIntOrNull(),
                            category = categoryLabelToEnum(categoryLabel),
                            ingredients = parseIngredients(ingredientsText),
                            steps = parseSteps(stepsText),
                        ),
                    )
                },
                variant = HbButtonVariant.Primary,
                modifier = Modifier.weight(1f),
            )
        },
    ) {
        HbField("Titel") {
            HbTextField(value = title, onValueChange = { title = it }, placeholder = "z. B. Ofengemüse")
        }

        HbField("Kategorie") {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                catChips.forEach { label ->
                    HbPickText(
                        text = label,
                        active = categoryLabel == label,
                        onClick = { categoryLabel = label },
                    )
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HbField("Portionen", Modifier.weight(1f)) {
                HbTextField(value = servings, onValueChange = { servings = it.filter(Char::isDigit) }, placeholder = "4")
            }
            HbField("Vorb.", Modifier.weight(1f)) {
                HbTextField(value = prep, onValueChange = { prep = it.filter(Char::isDigit) }, placeholder = "15")
            }
            HbField("Kochen", Modifier.weight(1f)) {
                HbTextField(value = cook, onValueChange = { cook = it.filter(Char::isDigit) }, placeholder = "30")
            }
        }

        HbField("Beschreibung") {
            HbTextField(
                value = description,
                onValueChange = { description = it },
                placeholder = "Kurz beschreiben …",
                singleLine = false,
                minLines = 2,
            )
        }

        HbField("Zutaten") {
            HbTextField(
                value = ingredientsText,
                onValueChange = { ingredientsText = it },
                placeholder = "Eine pro Zeile, z. B. „200 g Mehl“",
                singleLine = false,
                minLines = 3,
                mono = true,
            )
            Text(
                "Eine pro Zeile, z. B. „200 g Mehl“. „# Boden“ beginnt einen Abschnitt.",
                style = HbType.small.copy(fontSize = 12.sp),
                color = Hb.ink3,
                modifier = Modifier.padding(top = 4.dp, start = 2.dp),
            )
        }

        HbField("Schritte") {
            HbTextField(
                value = stepsText,
                onValueChange = { stepsText = it },
                placeholder = "Ein Schritt pro Zeile …",
                singleLine = false,
                minLines = 3,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Parsing helpers
// ---------------------------------------------------------------------------

/**
 * Parse a free-text textarea into [IngredientInput]s — one ingredient per non-blank line.
 * A line beginning with "#" starts a named section (e.g. "# Boden"); every ingredient after it
 * carries that section until the next "#"-line. A bare "#" resets to the header-less top group.
 * Mirrors the web editor's structured sections (issue #123).
 */
internal fun parseIngredients(text: String): List<IngredientInput> {
    val result = mutableListOf<IngredientInput>()
    var section: String? = null
    for (raw in text.lines()) {
        val line = raw.trim()
        if (line.isEmpty()) continue
        if (line.startsWith("#")) {
            section = line.removePrefix("#").trim().takeIf { it.isNotEmpty() }
            continue
        }
        result += parseIngredientLine(line).copy(section = section)
    }
    return result
}

/**
 * Parse a single ingredient line. A leading numeric token (comma decimals allowed) becomes the
 * amount; a following token that is a known unit (KNOWN_UNITS) becomes the unit; the rest is the
 * name. Lines without a leading number become a name-only ingredient.
 *
 * Only KNOWN_UNITS count as a unit — earlier we also treated any short letter-only token as one,
 * which swallowed the first word of a multi-word name ("2 rote Paprika" → unit="rote") and mis-parsed
 * the ingredient. The whitelist is enough for our short-unit notation. Mirrors the backend
 * parseQty fix. See issues #92 / #47.
 *
 * Diese Heuristik ist bewusst mit `parseQty` auf der Backend-Seite gespiegelt
 * (ShoppingRoutes.kt) — Änderungen hier dort mitziehen. Siehe Issue #103.
 */
internal fun parseIngredientLine(line: String): IngredientInput {
    val trimmed = line.trim()
    val tokens = trimmed.split(Regex("\\s+")).filter { it.isNotBlank() }
    if (tokens.isEmpty()) return IngredientInput(name = trimmed)

    val amount = tokens[0].replace(',', '.').toDoubleOrNull()
        ?: return IngredientInput(name = trimmed)

    var idx = 1
    var unit: String? = null
    if (idx < tokens.size) {
        val candidate = tokens[idx]
        if (candidate.lowercase() in KNOWN_UNITS && idx < tokens.size - 1) {
            unit = candidate
            idx++
        }
    }

    val name = tokens.drop(idx).joinToString(" ")
    return if (name.isBlank()) {
        IngredientInput(name = trimmed)
    } else {
        IngredientInput(name = name, amount = amount, unit = unit)
    }
}

/** Parse a free-text textarea into [RecipeStepInput]s — one step per non-blank line. */
private fun parseSteps(text: String): List<RecipeStepInput> =
    text.lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { RecipeStepInput(it) }
