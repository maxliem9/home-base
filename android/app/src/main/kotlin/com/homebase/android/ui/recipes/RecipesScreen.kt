package com.homebase.android.ui.recipes

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.homebase.android.data.model.CreateRecipeRequest
import com.homebase.android.data.model.IngredientInput
import com.homebase.android.data.model.RecipeDto
import com.homebase.android.data.model.RecipeStepInput
import com.homebase.android.ui.components.HbAppBar
import com.homebase.android.ui.components.HbBadge
import com.homebase.android.ui.components.HbBottomSheet
import com.homebase.android.ui.components.HbButton
import com.homebase.android.ui.components.HbButtonVariant
import com.homebase.android.ui.components.HbCard
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
import com.homebase.android.ui.util.Format

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

private val CATEGORIES = listOf("Alle", "Frühstück", "Hauptgerichte", "Snack", "Dessert", "Getränk")

/** Short units recognised when parsing a "200 g Mehl" ingredient line. */
private val KNOWN_UNITS = setOf(
    "g", "kg", "mg", "ml", "l", "el", "tl", "stk", "stück", "prise",
    "bund", "dose", "pkg", "pck", "tasse", "cup", "msp",
)

private fun totalTime(r: RecipeDto): Int = (r.prepTimeMinutes ?: 0) + (r.cookTimeMinutes ?: 0)

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
            NewRecipeSheet(
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
) {
    BackHandler(onBack = onBack)

    var toastCount by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(toastCount) {
        if (toastCount != null) {
            kotlinx.coroutines.delay(2600)
            toastCount = null
        }
    }

    val hue = Format.recipeHue(recipe.id)

    HbScreenScaffold(
        appBar = {
            HbAppBar(
                title = "Rezept",
                titleSm = true,
                bordered = true,
                leftIcon = HbIcons.chevronLeft,
                onLeft = onBack,
                actions = { HbIconButton(HbIcons.more, {}) },
            )
        },
        overlay = {
            toastCount?.let { count ->
                HbToast(
                    message = "$count Zutaten zur Einkaufsliste hinzugefügt",
                    actionLabel = "Ansehen",
                    onAction = {},
                )
            }
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

            // Ingredients
            if (recipe.ingredients.isNotEmpty()) {
                HbSectionLabel("Zutaten")
                recipe.ingredients.forEachIndexed { i, ing ->
                    IngredientRow(
                        amountUnit = "${ing.amount?.let { Format.amount(it) } ?: ""} ${ing.unit ?: ""}".trim(),
                        name = ing.name,
                        divider = i < recipe.ingredients.lastIndex,
                    )
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
                    onClick = {
                        shoppingViewModel.addItemsToFirstList(
                            recipe.ingredients.map { it.name },
                        ) { added -> toastCount = added }
                    },
                    variant = HbButtonVariant.Primary,
                    icon = HbIcons.cart,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.size(28.dp))
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
// New-recipe sheet
// ---------------------------------------------------------------------------

@Composable
private fun NewRecipeSheet(onDismiss: () -> Unit, onSave: (CreateRecipeRequest) -> Unit) {
    var title by remember { mutableStateOf("") }
    var categoryLabel by remember { mutableStateOf("Hauptgerichte") }
    var servings by remember { mutableStateOf("") }
    var prep by remember { mutableStateOf("") }
    var cook by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var ingredientsText by remember { mutableStateOf("") }
    var stepsText by remember { mutableStateOf("") }

    val catChips = listOf("Frühstück", "Hauptgerichte", "Snack", "Dessert", "Getränk")

    HbBottomSheet(
        onDismiss = onDismiss,
        title = "Neues Rezept",
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
                "Eine pro Zeile, z. B. „200 g Mehl“",
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

/** Parse a free-text textarea into [IngredientInput]s — one ingredient per non-blank line. */
private fun parseIngredients(text: String): List<IngredientInput> =
    text.lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { parseIngredientLine(it) }

/**
 * Parse a single ingredient line. A leading numeric token (comma decimals allowed) becomes the
 * amount; a following short token recognised as a unit becomes the unit; the rest is the name.
 * Lines without a leading number become a name-only ingredient.
 */
private fun parseIngredientLine(line: String): IngredientInput {
    val tokens = line.split(Regex("\\s+")).filter { it.isNotBlank() }
    if (tokens.isEmpty()) return IngredientInput(name = line)

    val amount = tokens[0].replace(',', '.').toDoubleOrNull()
        ?: return IngredientInput(name = line)

    var idx = 1
    var unit: String? = null
    if (idx < tokens.size) {
        val candidate = tokens[idx]
        val isUnit = candidate.lowercase() in KNOWN_UNITS ||
            (candidate.length <= 4 && candidate.any { it.isLetter() } && candidate.none { it.isDigit() })
        if (isUnit && idx < tokens.size - 1) {
            unit = candidate
            idx++
        }
    }

    val name = tokens.drop(idx).joinToString(" ")
    return if (name.isBlank()) {
        IngredientInput(name = line)
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
