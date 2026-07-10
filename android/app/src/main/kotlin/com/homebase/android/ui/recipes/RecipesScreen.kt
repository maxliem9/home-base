package com.homebase.android.ui.recipes

import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.homebase.android.R
import com.homebase.android.data.model.CreateRecipeRequest
import com.homebase.android.data.model.IngredientDto
import com.homebase.android.data.model.IngredientInput
import com.homebase.android.data.model.RecipeDraftDto
import com.homebase.android.data.model.RecipeDto
import com.homebase.android.data.model.RecipeImageDto
import com.homebase.android.data.model.RecipeStepDto
import com.homebase.android.data.model.RecipeStepInput
import com.homebase.android.data.model.ShoppingLineInput
import com.homebase.android.data.model.ShoppingListDto
import com.homebase.android.ui.components.HbAppBar
import com.homebase.android.ui.components.HbBadge
import com.homebase.android.ui.components.HbBottomSheet
import com.homebase.android.ui.components.HbButton
import com.homebase.android.ui.components.HbButtonSize
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

// Recipe categories carried as their stable backend enum (#207) so the chips/filter are
// language-independent; the display label is resolved separately from string resources. DINNER
// is the canonical "Hauptgerichte"/"Mains"; LUNCH is intentionally omitted from the offered set
// (collapsed into DINNER by backend migration V17) but still labels correctly if a legacy row has
// it. The filter row prepends an "all" pseudo-category (null code).
private val CATEGORY_CODES = listOf("BREAKFAST", "DINNER", "SNACK", "DESSERT", "DRINK")

private fun totalTime(r: RecipeDto): Int = (r.prepTimeMinutes ?: 0) + (r.cookTimeMinutes ?: 0)

/** Canonical offered code for a recipe's category — folds the legacy LUNCH alias into DINNER so a
 *  not-yet-migrated row still matches the DINNER chip (parity with the old label-based filter). */
private fun canonicalCategory(code: String): String =
    code.uppercase().let { if (it == "LUNCH") "DINNER" else it }

/** Localized label for a recipe category code, resolved from resources (raw code as last resort). */
@Composable
private fun categoryLabel(code: String): String =
    Format.recipeCategoryLabelRes(code)?.let { stringResource(it) } ?: code

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

/** Editor-local draft of one ingredient row (issue #28). amount/unit are raw text, parsed on save. */
internal data class IngredientDraft(val name: String = "", val amount: String = "", val unit: String = "")

/** Editor-local draft of one section: an optional name + its ingredient rows. */
internal data class SectionDraft(val name: String = "", val ingredients: List<IngredientDraft> = listOf(IngredientDraft()))

/**
 * Build the editor's section drafts from a stored recipe (issue #28) — the structured-editor
 * counterpart of the detail view's [groupIngredientsBySection]. Each amount is shown as editable
 * text (via [Format.amount], dot-decimal so it parses back cleanly). An empty recipe yields a
 * single blank section so the editor always has one row to type into.
 */
internal fun sectionsFromIngredients(items: List<IngredientDto>): List<SectionDraft> {
    val groups = groupIngredientsBySection(items)
    if (groups.isEmpty()) return listOf(SectionDraft())
    return groups.map { (section, group) ->
        SectionDraft(
            name = section ?: "",
            ingredients = group.map { ing ->
                IngredientDraft(name = ing.name, amount = ing.amount?.let { Format.amount(it) } ?: "", unit = ing.unit ?: "")
            },
        )
    }
}

/**
 * Flatten the editor's sections back into [IngredientInput]s (issue #28), mirroring the web save:
 * drop blank-name rows, parse the amount (comma or dot; blank ⇒ null), trim unit/section to null.
 * Unlike the old free-text parser this keeps a **unit without an amount** and never mistakes a
 * **numeric-looking name** for an amount — the two cases the free-text round-trip lost.
 */
internal fun sectionsToIngredients(sections: List<SectionDraft>): List<IngredientInput> =
    sections.flatMap { sec ->
        val section = sec.name.trim().ifBlank { null }
        sec.ingredients
            .filter { it.name.isNotBlank() }
            .map { ing ->
                IngredientInput(
                    name = ing.name.trim(),
                    amount = ing.amount.trim().takeIf { it.isNotEmpty() }?.replace(',', '.')?.toDoubleOrNull(),
                    unit = ing.unit.trim().ifBlank { null },
                    section = section,
                )
            }
    }

/** Serialise stored steps back into the free-text editor format — one step per line. */
internal fun stepsToText(steps: List<RecipeStepDto>): String =
    steps.joinToString("\n") { it.description }

/**
 * Pre-filled initial state for the recipe editor (#460). Built either from an existing recipe
 * (edit) or from an imported [RecipeDraftDto] (URL import). Decouples the form sheet from the
 * source so a draft — which has no id/sortOrder/timestamps — can seed a brand-new recipe.
 */
internal data class RecipeFormPrefill(
    val title: String = "",
    val categoryCode: String = "DINNER",
    val servings: String = "",
    val prep: String = "",
    val cook: String = "",
    val description: String = "",
    val sections: List<SectionDraft> = listOf(SectionDraft()),
    val stepsText: String = "",
) {
    companion object {
        /** Editor state for editing an existing recipe (empty/new when [recipe] is null). */
        fun of(recipe: RecipeDto?): RecipeFormPrefill {
            if (recipe == null) return RecipeFormPrefill()
            return RecipeFormPrefill(
                title = recipe.title,
                categoryCode = canonicalCategory(recipe.category).takeIf { it in CATEGORY_CODES } ?: "DINNER",
                servings = recipe.servings.toString(),
                prep = recipe.prepTimeMinutes?.toString() ?: "",
                cook = recipe.cookTimeMinutes?.toString() ?: "",
                description = recipe.description ?: "",
                sections = sectionsFromIngredients(recipe.ingredients),
                stepsText = stepsToText(recipe.steps),
            )
        }

        /**
         * Editor state pre-filled from an imported draft (#460) — always a NEW recipe (no id), so
         * the user reviews and saves via the normal create flow. Best-effort fields the page didn't
         * provide stay blank. The category is folded into the offered set (legacy LUNCH → DINNER,
         * anything unknown → DINNER) so the chip row always has a valid selection.
         */
        fun ofImport(draft: RecipeDraftDto): RecipeFormPrefill = RecipeFormPrefill(
            title = draft.title,
            categoryCode = canonicalCategory(draft.category).takeIf { it in CATEGORY_CODES } ?: "DINNER",
            servings = draft.servings?.toString() ?: "",
            prep = draft.prepTimeMinutes?.toString() ?: "",
            cook = draft.cookTimeMinutes?.toString() ?: "",
            description = draft.description ?: "",
            sections = sectionsFromInputs(draft.ingredients),
            stepsText = draft.steps.joinToString("\n") { it.description },
        )
    }
}

/**
 * Build editor section drafts from imported [IngredientInput]s (#460) — the import counterpart of
 * [sectionsFromIngredients], which works on stored [IngredientDto]s. Groups consecutive runs that
 * share a section label (authoring order), shows each amount as editable dot-decimal text, and
 * yields a single blank section when the draft has no ingredients so the editor always has a row.
 */
internal fun sectionsFromInputs(items: List<IngredientInput>): List<SectionDraft> {
    if (items.isEmpty()) return listOf(SectionDraft())
    val groups = mutableListOf<Pair<String?, MutableList<IngredientInput>>>()
    for (ing in items) {
        val sec = ing.section?.trim()?.takeIf { it.isNotEmpty() }
        val last = groups.lastOrNull()
        if (last != null && last.first == sec) last.second.add(ing)
        else groups.add(sec to mutableListOf(ing))
    }
    return groups.map { (section, group) ->
        SectionDraft(
            name = section ?: "",
            ingredients = group.map { ing ->
                IngredientDraft(name = ing.name, amount = ing.amount?.let { Format.amount(it) } ?: "", unit = ing.unit ?: "")
            },
        )
    }
}

// ---------------------------------------------------------------------------
// Striped placeholder band ("Foto folgt")
// ---------------------------------------------------------------------------

/**
 * Repeating diagonal-stripe brush (~135°) alternating the recipe's warm dark/light band
 * tones, mirroring the CSS `repeating-linear-gradient(135deg, …)`. The gradient runs along
 * a short vector and tiles via [TileMode.Repeated]; the (1,1) direction yields the 135° look.
 */
private fun stripeBrush(hue: Double, periodPx: Float, darkTheme: Boolean): Brush {
    val dark = Hb.recipeBandDark(hue, darkTheme)
    val light = Hb.recipeBandLight(hue, darkTheme)
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
            onImport = viewModel::importRecipe,
            imageUrl = viewModel::imageUrl,
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
    onImport: (String, (Result<RecipeDraftDto>) -> Unit) -> Unit,
    imageUrl: (RecipeImageDto) -> String,
) {
    // null = "all"; otherwise the selected backend enum code (language-independent filter, #207).
    var selectedCode by remember { mutableStateOf<String?>(null) }
    var showNewSheet by remember { mutableStateOf(false) }
    var showImport by remember { mutableStateOf(false) }
    // When an import succeeds we open the editor pre-filled with the returned draft (#460).
    var importPrefill by remember { mutableStateOf<RecipeFormPrefill?>(null) }

    val recipes = selectedCode?.let { code ->
        state.recipes.filter { canonicalCategory(it.category) == code }
    } ?: state.recipes

    Box(Modifier.fillMaxSize()) {
        HbScreenScaffold(
            appBar = {
                HbAppBar(
                    eyebrow = stringResource(R.string.recipe_count_eyebrow, state.recipes.size),
                    title = stringResource(R.string.recipe_title),
                    onLeft = onOpenDrawer,
                    actions = {
                        // "Aus URL importieren" (#460) — paste a recipe URL, the backend extracts a
                        // draft and the editor opens pre-filled.
                        HbIconButton(HbIcons.link, { showImport = true }, contentDescription = stringResource(R.string.cd_recipe_import))
                        HbIconButton(HbIcons.search, {}, contentDescription = stringResource(R.string.cd_search))
                    },
                )
            },
            fab = { HbFab(onClick = { showNewSheet = true }, label = stringResource(R.string.recipe_fab)) },
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
                // "All" pseudo-chip (null code) first, then one chip per category code.
                HbTagChip(
                    text = stringResource(R.string.recipe_cat_all),
                    active = selectedCode == null,
                    onClick = { selectedCode = null },
                )
                CATEGORY_CODES.forEach { code ->
                    HbTagChip(
                        text = categoryLabel(code),
                        active = selectedCode == code,
                        onClick = { selectedCode = code },
                    )
                }
                Spacer(Modifier.width(18.dp))
            }

            Spacer(Modifier.size(18.dp))

            if (recipes.isEmpty()) {
                HbEmpty(
                    HbIcons.chef,
                    stringResource(R.string.recipe_empty_title),
                    selectedCode?.let { stringResource(R.string.recipe_empty_category, categoryLabel(it)) }
                        ?: stringResource(R.string.recipe_empty_all),
                )
            } else {
                RecipeGrid(recipes = recipes, onOpen = onOpen, imageUrl = imageUrl)
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

        if (showImport) {
            ImportFromUrlSheet(
                onImport = onImport,
                onDismiss = { showImport = false },
                onImported = { draft ->
                    showImport = false
                    importPrefill = RecipeFormPrefill.ofImport(draft)
                },
            )
        }

        // After a successful import the editor opens pre-filled (#460); saving creates a new recipe.
        importPrefill?.let { prefill ->
            RecipeFormSheet(
                existing = null,
                prefill = prefill,
                onDismiss = { importPrefill = null },
                onSave = { request ->
                    importPrefill = null
                    onSave(request)
                },
            )
        }
    }
}

// ---------------------------------------------------------------------------
// "Aus URL importieren" sheet (#460) — one URL field; posts to /recipes/import.
// ---------------------------------------------------------------------------

/**
 * A short, focused bottom sheet with a single URL field (#460). Posting the URL hits the backend
 * import endpoint; on success the caller opens the editor pre-filled (the user reviews and saves
 * normally — nothing is persisted by the import). A failure (no recipe data / rejected URL / server
 * error) shows the repository's German message inline and keeps the sheet open so the user can retry.
 */
@Composable
private fun ImportFromUrlSheet(
    onImport: (String, (Result<RecipeDraftDto>) -> Unit) -> Unit,
    onDismiss: () -> Unit,
    onImported: (RecipeDraftDto) -> Unit,
) {
    var url by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val submit = {
        val trimmed = url.trim()
        if (trimmed.isNotEmpty() && !busy) {
            busy = true
            error = null
            onImport(trimmed) { result ->
                result
                    .onSuccess { draft -> onImported(draft) }
                    .onFailure { e ->
                        busy = false
                        error = e.message
                    }
            }
        }
    }

    HbBottomSheet(
        onDismiss = onDismiss,
        title = stringResource(R.string.recipe_import_title),
        footer = {
            HbButton(
                stringResource(R.string.action_cancel),
                onClick = onDismiss,
                variant = HbButtonVariant.Secondary,
                modifier = Modifier.weight(1f),
            )
            HbButton(
                if (busy) stringResource(R.string.recipe_importing) else stringResource(R.string.recipe_import_action),
                onClick = submit,
                variant = HbButtonVariant.Primary,
                enabled = url.isNotBlank() && !busy,
                modifier = Modifier.weight(1f),
            )
        },
    ) {
        Text(
            stringResource(R.string.recipe_import_hint),
            style = HbType.body,
            color = Hb.ink3,
        )
        Spacer(Modifier.size(14.dp))
        HbField(stringResource(R.string.recipe_import_url_label)) {
            HbTextField(
                value = url,
                onValueChange = { url = it },
                placeholder = "https://…",
            )
        }
        error?.let { msg ->
            Spacer(Modifier.size(10.dp))
            Text(
                msg,
                style = HbType.small.copy(fontWeight = FontWeight.Medium),
                color = Hb.danger,
            )
        }
    }
}

@Composable
private fun ColumnScope.RecipeGrid(
    recipes: List<RecipeDto>,
    onOpen: (RecipeDto) -> Unit,
    imageUrl: (RecipeImageDto) -> String,
) {
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
                        RecipeCard(recipe = recipe, onClick = { onOpen(recipe) }, imageUrl = imageUrl)
                    }
                }
                // keep a single trailing card half-width
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun RecipeCard(recipe: RecipeDto, onClick: () -> Unit, imageUrl: (RecipeImageDto) -> String) {
    val hue = Format.recipeHue(recipe.id)
    val darkTheme = Hb.isDark
    val coverImage = recipe.image
    HbCard(modifier = Modifier.fillMaxWidth().clickable { onClick() }, pad = false) {
        Column(Modifier.fillMaxWidth()) {
            // Cover image band — the recipe's cover image, or a striped "Foto folgt" placeholder
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(104.dp)
                    .background(stripeBrush(hue, 31f, darkTheme)),
                contentAlignment = Alignment.Center,
            ) {
                if (coverImage != null) {
                    AsyncImage(
                        model = imageUrl(coverImage),
                        contentDescription = recipe.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        HbIcon(HbIcons.chef, size = 26.dp, tint = Hb.recipeBandInk(hue, darkTheme))
                        Text(
                            stringResource(R.string.recipe_photo_placeholder),
                            style = HbType.mono.copy(fontSize = 10.sp, letterSpacing = 0.04.em),
                            color = Hb.recipeBandInk(hue, darkTheme).copy(alpha = 0.75f),
                        )
                    }
                }
                Box(
                    Modifier.align(Alignment.TopStart).padding(9.dp),
                ) { HbBadge(categoryLabel(recipe.category), tone = HbTone.Neutral) }
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
                        stringResource(R.string.recipe_minutes, totalTime(recipe)),
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
    var lightbox by remember { mutableStateOf<String?>(null) }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            val resolver = context.contentResolver
            val type = resolver.getType(uri) ?: "image/jpeg"
            val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            } ?: "image"
            val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
            if (bytes != null) viewModel.uploadImage(recipe.id, bytes, name, type)
        }
    }
    LaunchedEffect(toastMsg) {
        if (toastMsg != null) {
            kotlinx.coroutines.delay(2600)
            toastMsg = null
        }
    }

    // Portions stepper (parity with web `RecipeDetail`): drives both the displayed ingredient
    // amounts and the export. Keyed on the recipe id so opening another recipe starts at its base.
    val baseServings = recipe.servings.coerceAtLeast(1)
    var servings by remember(recipe.id) { mutableStateOf(baseServings) }
    val factor = servings.toDouble() / baseServings.toDouble()

    // Captured here because the export callback below runs outside composition.
    val recipeShareChooser = stringResource(R.string.recipe_share_chooser)
    val recipeExportFailed = stringResource(R.string.recipe_export_failed)

    // Fetch the export bytes, then hand them to the system share sheet as a cached file.
    // The chosen servings travel along (omitted at base, like web) so the file matches the view.
    val export: (String) -> Unit = { format ->
        val (ext, mime) = if (format == "pdf") "pdf" to "application/pdf" else "md" to "text/markdown"
        viewModel.exportRecipe(recipe.id, format, servings.takeIf { it != baseServings }) { result ->
            result
                .onSuccess { bytes -> FileShare.share(context, "rezept_${FileShare.slug(recipe.title)}.$ext", mime, bytes, chooserTitle = recipeShareChooser) }
                .onFailure { toastMsg = recipeExportFailed }
        }
    }

    val hue = Format.recipeHue(recipe.id)
    val darkTheme = Hb.isDark

    Box(Modifier.fillMaxSize()) {
    HbScreenScaffold(
        appBar = {
            HbAppBar(
                title = stringResource(R.string.recipe_detail_title),
                titleSm = true,
                bordered = true,
                leftIcon = HbIcons.chevronLeft,
                onLeft = onBack,
                actions = {
                    Box {
                        HbIconButton(HbIcons.more, { menuOpen = true }, contentDescription = stringResource(R.string.cd_more_actions))
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.recipe_edit), style = HbType.body, color = Hb.ink) },
                                onClick = { menuOpen = false; showEdit = true },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.recipe_export_md), style = HbType.body, color = Hb.ink) },
                                onClick = { menuOpen = false; export("md") },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.recipe_export_pdf), style = HbType.body, color = Hb.ink) },
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
        // Full-bleed hero band — the cover image (tap to enlarge) or a striped placeholder
        val heroImage = recipe.image
        Box(
            Modifier
                .fillMaxWidth()
                .height(188.dp)
                .background(stripeBrush(hue, 37f, darkTheme))
                .then(if (heroImage != null) Modifier.clickable { lightbox = viewModel.imageUrl(heroImage) } else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            if (heroImage != null) {
                AsyncImage(
                    model = viewModel.imageUrl(heroImage),
                    contentDescription = recipe.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    HbIcon(HbIcons.chef, size = 34.dp, tint = Hb.recipeBandInk(hue, darkTheme))
                    Text(
                        stringResource(R.string.recipe_photo_placeholder),
                        style = HbType.mono.copy(fontSize = 10.sp, letterSpacing = 0.04.em),
                        color = Hb.recipeBandInk(hue, darkTheme).copy(alpha = 0.75f),
                    )
                }
            }
            Box(Modifier.align(Alignment.TopStart).padding(12.dp)) {
                HbBadge(categoryLabel(recipe.category), tone = HbTone.Neutral)
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

            // Portions stepper + fact tiles
            Spacer(Modifier.size(18.dp))
            HbField(stringResource(R.string.recipe_field_servings)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    StepButton(HbIcons.minus) { if (servings > 1) servings-- }
                    Text(
                        "$servings",
                        style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold),
                        color = Hb.ink,
                    )
                    StepButton(HbIcons.plus) { servings++ }
                    if (factor != 1.0) {
                        Text(
                            stringResource(R.string.recipe_amount_factor, Format.amount(factor)),
                            style = HbType.small.copy(fontSize = 12.5.sp),
                            color = Hb.ink3,
                        )
                    }
                }
            }
            Spacer(Modifier.size(14.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FactTile("${recipe.prepTimeMinutes ?: 0}", stringResource(R.string.recipe_fact_prep), Modifier.weight(1f))
                FactTile("${recipe.cookTimeMinutes ?: 0}", stringResource(R.string.recipe_fact_cook), Modifier.weight(1f))
                FactTile("${totalTime(recipe)}", stringResource(R.string.recipe_fact_total), Modifier.weight(1f))
            }
            Spacer(Modifier.size(18.dp))

            // Cover image controls: add / replace / remove (the image is the hero band above).
            RecipeImageControls(
                hasImage = recipe.image != null,
                onAddOrReplace = { imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                onRemove = { recipe.image?.let { viewModel.removeImage(recipe.id, it.id) } },
            )
            Spacer(Modifier.size(22.dp))

            // Ingredients (optionally grouped into named sections — issue #123)
            if (recipe.ingredients.isNotEmpty()) {
                HbSectionLabel(stringResource(R.string.recipe_section_ingredients))
                groupIngredientsBySection(recipe.ingredients).forEach { group ->
                    val section = group.first
                    val items = group.second
                    if (section != null) IngredientSectionHeader(section)
                    items.forEachIndexed { i, ing ->
                        IngredientRow(
                            amountUnit = "${ing.amount?.let { Format.amount(it * factor) } ?: ""} ${ing.unit ?: ""}".trim(),
                            name = ing.name,
                            divider = i < items.lastIndex,
                        )
                    }
                }
                Spacer(Modifier.size(22.dp))
            }

            // Steps
            if (recipe.steps.isNotEmpty()) {
                HbSectionLabel(stringResource(R.string.recipe_section_steps))
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
                    stringResource(R.string.action_delete),
                    onClick = onDelete,
                    variant = HbButtonVariant.Danger,
                    icon = HbIcons.trash,
                )
                HbButton(
                    stringResource(R.string.recipe_add_to_list),
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
                        toastMsg = addToast(context, added, merged)
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

        lightbox?.let { url -> RecipeImageLightbox(url = url, onDismiss = { lightbox = null }) }
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
// Cover image controls (add / replace / remove). The image itself shows as the hero band.
// ---------------------------------------------------------------------------

@Composable
private fun RecipeImageControls(
    hasImage: Boolean,
    onAddOrReplace: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            stringResource(R.string.recipe_image),
            style = HbType.meta.copy(fontWeight = FontWeight.SemiBold),
            color = Hb.ink2,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (hasImage) {
                HbButton(
                    stringResource(R.string.action_remove),
                    onClick = onRemove,
                    variant = HbButtonVariant.Ghost,
                    size = HbButtonSize.Sm,
                    icon = HbIcons.trash,
                )
            }
            HbButton(
                if (hasImage) stringResource(R.string.recipe_image_change) else stringResource(R.string.recipe_image_add),
                onClick = onAddOrReplace,
                variant = HbButtonVariant.Secondary,
                size = HbButtonSize.Sm,
                icon = HbIcons.plus,
            )
        }
    }
}

@Composable
private fun RecipeImageLightbox(url: String, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.85f))
                .clickable { onDismiss() },
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// "Zutaten zur Liste" picker — pick servings, ingredients and a target list
// ---------------------------------------------------------------------------

/**
 * Toast copy after a batch add, e.g. "3 hinzugefügt · 1 zusammengeführt". Takes a [Context]
 * (not @Composable) because it runs inside the addIngredients callback, outside composition.
 */
private fun addToast(context: android.content.Context, added: Int, merged: Int): String = when {
    added == 0 && merged == 0 -> context.getString(R.string.recipe_toast_nothing)
    merged == 0 -> context.resources.getQuantityString(R.plurals.recipe_toast_added, added, added)
    added == 0 -> context.getString(R.string.recipe_toast_merged, merged)
    else -> context.getString(R.string.recipe_toast_added_merged, added, merged)
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
        title = stringResource(R.string.recipe_add_to_list_title),
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
            Text(stringResource(R.string.recipe_create_list_first), style = HbType.body, color = Hb.ink3)
        } else {
            // Target list — only offer a choice when there's more than one.
            if (lists.size > 1) {
                HbField(stringResource(R.string.recipe_field_list)) {
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
            HbField(stringResource(R.string.recipe_field_servings)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    StepButton(HbIcons.minus) { if (servings > 1) servings-- }
                    Text("$servings", style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold), color = Hb.ink)
                    StepButton(HbIcons.plus) { servings++ }
                    if (factor != 1.0) {
                        Text(
                            stringResource(R.string.recipe_amount_factor, Format.amount(factor)),
                            style = HbType.small.copy(fontSize = 12.5.sp),
                            color = Hb.ink3,
                        )
                    }
                }
            }

            // Ingredient checklist (all on by default — untick the staples you keep at home).
            HbSectionLabel(stringResource(R.string.recipe_section_ingredients))
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
    // Pre-filled initial fields (#460): defaults to the existing recipe (edit) or a blank new
    // recipe; a URL import passes a draft-derived prefill so the editor opens with the imported
    // data while still saving as a brand-new recipe (existing stays null).
    prefill: RecipeFormPrefill = RecipeFormPrefill.of(existing),
) {
    var title by remember { mutableStateOf(prefill.title) }
    // Carry the stable backend enum code (#207); the chip labels are localized on render. The
    // prefill already normalized the code into the offered set (legacy LUNCH → DINNER, default DINNER).
    var categoryCode by remember { mutableStateOf(prefill.categoryCode) }
    var servings by remember { mutableStateOf(prefill.servings) }
    var prep by remember { mutableStateOf(prefill.prep) }
    var cook by remember { mutableStateOf(prefill.cook) }
    var description by remember { mutableStateOf(prefill.description) }
    var sections by remember { mutableStateOf(prefill.sections) }
    // Section-name fields appear once sections are in play; sticky for the editor's lifetime so
    // clearing a name mid-edit doesn't make the field vanish (mirrors web `sectionsShown`).
    var sectionsShown by remember { mutableStateOf(sections.size > 1 || sections.any { it.name.isNotBlank() }) }
    var stepsText by remember { mutableStateOf(prefill.stepsText) }

    // Free-text bulk editor for ingredients (paste a list, one per line; "# Name" opens a section).
    // The structured `sections` stay the source of truth — text edits are parsed back into them
    // live, so save + toggling to the list view need no reconciliation (mirrors web).
    var pasteMode by remember { mutableStateOf(false) }
    var ingredientsText by remember { mutableStateOf("") }

    fun mutateSection(si: Int, f: (SectionDraft) -> SectionDraft) {
        sections = sections.mapIndexed { i, s -> if (i == si) f(s) else s }
    }
    fun mutateIngredient(si: Int, ii: Int, f: (IngredientDraft) -> IngredientDraft) =
        mutateSection(si) { s -> s.copy(ingredients = s.ingredients.mapIndexed { i, ing -> if (i == ii) f(ing) else ing }) }

    HbBottomSheet(
        onDismiss = onDismiss,
        title = if (existing == null) stringResource(R.string.recipe_new_title) else stringResource(R.string.recipe_edit_title),
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
                    onSave(
                        CreateRecipeRequest(
                            title = title.trim(),
                            description = description.trim().ifBlank { null },
                            servings = servings.toIntOrNull(),
                            prepTimeMinutes = prep.toIntOrNull(),
                            cookTimeMinutes = cook.toIntOrNull(),
                            category = categoryCode,
                            ingredients = sectionsToIngredients(sections),
                            steps = parseSteps(stepsText),
                        ),
                    )
                },
                variant = HbButtonVariant.Primary,
                modifier = Modifier.weight(1f),
            )
        },
    ) {
        HbField(stringResource(R.string.common_field_title)) {
            HbTextField(value = title, onValueChange = { title = it }, placeholder = stringResource(R.string.recipe_title_placeholder))
        }

        HbField(stringResource(R.string.recipe_field_category)) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CATEGORY_CODES.forEach { code ->
                    HbPickText(
                        text = categoryLabel(code),
                        active = categoryCode == code,
                        onClick = { categoryCode = code },
                    )
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HbField(stringResource(R.string.recipe_field_servings), Modifier.weight(1f)) {
                HbTextField(value = servings, onValueChange = { servings = it.filter(Char::isDigit) }, placeholder = stringResource(R.string.recipe_servings_placeholder))
            }
            HbField(stringResource(R.string.recipe_field_prep), Modifier.weight(1f)) {
                HbTextField(value = prep, onValueChange = { prep = it.filter(Char::isDigit) }, placeholder = stringResource(R.string.recipe_prep_placeholder))
            }
            HbField(stringResource(R.string.recipe_field_cook), Modifier.weight(1f)) {
                HbTextField(value = cook, onValueChange = { cook = it.filter(Char::isDigit) }, placeholder = stringResource(R.string.recipe_cook_placeholder))
            }
        }

        HbField(stringResource(R.string.recipe_field_description)) {
            HbTextField(
                value = description,
                onValueChange = { description = it },
                placeholder = stringResource(R.string.recipe_description_placeholder),
                singleLine = false,
                minLines = 2,
            )
        }

        HbField(stringResource(R.string.recipe_field_ingredients)) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // toggle: structured rows ⇄ free-text bulk entry (paste a whole list at once)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    AddRowLink(if (pasteMode) stringResource(R.string.recipe_as_list) else stringResource(R.string.recipe_as_text)) {
                        if (pasteMode) {
                            sectionsShown = sectionsShown || sections.size > 1 || sections.any { it.name.isNotBlank() }
                            pasteMode = false
                        } else {
                            ingredientsText = ingredientsTextFromSections(sections)
                            pasteMode = true
                        }
                    }
                }
                if (pasteMode) {
                    HbTextField(
                        value = ingredientsText,
                        onValueChange = { v -> ingredientsText = v; sections = sectionsFromText(v) },
                        placeholder = stringResource(R.string.recipe_paste_placeholder),
                        singleLine = false,
                        minLines = 6,
                        mono = true,
                    )
                    Text(
                        stringResource(R.string.recipe_paste_hint),
                        style = HbType.small.copy(fontSize = 12.sp),
                        color = Hb.ink3,
                    )
                } else {
                    val multiSection = sections.size > 1
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        sections.forEachIndexed { si, section ->
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                // Section name only shows once sections are in play (sticky, see sectionsShown).
                                if (sectionsShown) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        Box(Modifier.weight(1f)) {
                                            HbTextField(
                                                value = section.name,
                                                onValueChange = { v -> mutateSection(si) { it.copy(name = v) } },
                                                placeholder = stringResource(R.string.recipe_section_placeholder),
                                            )
                                        }
                                        // can't remove the last section
                                        if (multiSection) {
                                            HbIconButton(
                                                HbIcons.x,
                                                { sections = sections.filterIndexed { i, _ -> i != si } },
                                                iconSize = 18.dp,
                                                contentDescription = stringResource(R.string.cd_remove),
                                            )
                                        }
                                    }
                                }
                                section.ingredients.forEachIndexed { ii, ing ->
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        Box(Modifier.weight(1f)) {
                                            HbTextField(
                                                value = ing.name,
                                                onValueChange = { v -> mutateIngredient(si, ii) { it.copy(name = v) } },
                                                placeholder = stringResource(R.string.recipe_ingredient_placeholder),
                                            )
                                        }
                                        Box(Modifier.width(56.dp)) {
                                            HbTextField(
                                                value = ing.amount,
                                                onValueChange = { v -> mutateIngredient(si, ii) { it.copy(amount = v) } },
                                                placeholder = stringResource(R.string.recipe_amount_placeholder),
                                                mono = true,
                                            )
                                        }
                                        Box(Modifier.width(60.dp)) {
                                            HbTextField(
                                                value = ing.unit,
                                                onValueChange = { v -> mutateIngredient(si, ii) { it.copy(unit = v) } },
                                                placeholder = stringResource(R.string.recipe_unit_placeholder),
                                            )
                                        }
                                        HbIconButton(
                                            HbIcons.x,
                                            {
                                                mutateSection(si) {
                                                    it.copy(ingredients = it.ingredients.filterIndexed { i, _ -> i != ii })
                                                }
                                            },
                                            iconSize = 18.dp,
                                            contentDescription = stringResource(R.string.cd_remove),
                                        )
                                    }
                                }
                                AddRowLink(stringResource(R.string.recipe_add_ingredient)) {
                                    mutateSection(si) { it.copy(ingredients = it.ingredients + IngredientDraft()) }
                                }
                            }
                        }
                        AddRowLink(stringResource(R.string.recipe_add_section)) {
                            sectionsShown = true
                            sections = sections + SectionDraft()
                        }
                    }
                }
            }
        }

        HbField(stringResource(R.string.recipe_field_steps)) {
            HbTextField(
                value = stepsText,
                onValueChange = { stepsText = it },
                placeholder = stringResource(R.string.recipe_steps_placeholder),
                singleLine = false,
                minLines = 3,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Parsing helpers
// ---------------------------------------------------------------------------

// --- Free-text ("paste") ingredient editor — counterpart to the structured rows -------------
// One ingredient per line ("200 g Mehl"); a "# Name" line opens a named section. Parsing is
// best-effort and only treats the first token after a leading amount as a unit when it's a
// known unit, so "3 Eier" keeps "Eier" as the name (not the unit). Mirrors the web parser.

private val KNOWN_UNITS = setOf(
    "g", "kg", "mg", "ml", "cl", "dl", "l", "el", "tl", "msp", "prise", "prisen", "stück", "stk", "st",
    "dose", "dosen", "pkg", "packung", "päckchen", "bund", "zehe", "zehen", "scheibe", "scheiben",
    "tasse", "tassen", "becher", "glas", "cm", "mm", "kugel", "kugeln", "blatt", "blätter",
)

// A leading number / fraction / range becomes the amount (#166); otherwise the whole line stays
// the name (never silently stores a wrong number). Fractions → decimal (1/2 → 0.5), ranges → LOWER
// bound (1-2 → 1), mixed numbers ("1 1/2" → 1.5). MUST stay identical to the web parser in
// web/src/components/recipeIngredients.ts — same rules and same 3-decimal rounding.

// Splits the leading whitespace-delimited token off the line: group 1 = first token, group 2 = rest.
private val FIRST_TOKEN = Regex("""^(\S+)\s+(.*)$""")
private val RANGE = Regex("""^([0-9]+(?:[.,][0-9]+)?)-([0-9]+(?:[.,][0-9]+)?)$""")
private val FRACTION = Regex("""^([0-9]+(?:[.,][0-9]+)?)/([0-9]+(?:[.,][0-9]+)?)$""")
private val PLAIN_NUMBER = Regex("""^[0-9]+(?:[.,][0-9]+)?$""")
// A "b/c" fraction at the start of the rest, for the mixed-number form "a b/c": groups b, c, tail.
private val FRACTION_NEXT = Regex("""^([0-9]+)/([0-9]+)(?:\s+(.*))?$""")
private val INTEGER = Regex("""^[0-9]+$""")

// Format a decimal to at most 3 places, trailing zeros stripped ("0.5", "1.5", "1", "0.333").
// 3 places keeps 1/3, 2/3 etc. honest. Rounds via integer math (Math.round(n*1000)) so it is
// BIT-IDENTICAL to the web parser, which does the same — Java "%.3f" and JS toFixed disagree on
// 4th-decimal ties (e.g. 3/80). Amounts are always >= 0 here, so the sign is never an issue.
private fun formatAmount(n: Double): String {
    val k = Math.round(n * 1000.0)
    val whole = k / 1000
    val frac = (k % 1000).toString().padStart(3, '0').trimEnd('0')
    return if (frac.isEmpty()) "$whole" else "$whole.$frac"
}

private fun numOf(s: String): Double? = s.replace(',', '.').toDoubleOrNull()

// Parse a leading amount TOKEN into a normalized decimal string, or null if it isn't a clean
// number / fraction / range. The mixed-number form "a b/c" spans two tokens (handled by the caller).
private fun parseAmountToken(tok: String): String? {
    RANGE.matchEntire(tok)?.let { m -> // range a-b → lower bound
        return numOf(m.groupValues[1])?.let { formatAmount(it) }
    }
    FRACTION.matchEntire(tok)?.let { m -> // fraction a/b → decimal (b must be non-zero)
        val a = numOf(m.groupValues[1])
        val b = numOf(m.groupValues[2])
        return if (a != null && b != null && b != 0.0) formatAmount(a / b) else null
    }
    // plain number (keep as typed, only normalize the decimal comma)
    return if (PLAIN_NUMBER.matches(tok)) tok.replace(',', '.') else null
}

internal fun parseIngredientLine(line: String): IngredientDraft {
    val trimmed = line.trim()
    val m = FIRST_TOKEN.matchEntire(trimmed) ?: return IngredientDraft(name = trimmed)
    val first = m.groupValues[1]
    var rest = m.groupValues[2].trim()
    var amount = parseAmountToken(first) ?: return IngredientDraft(name = trimmed)

    // mixed number: a leading integer followed by a "b/c" fraction ("1 1/2" → 1.5). Only when the
    // first token was a bare integer (not itself a fraction/range) and the next token is a fraction.
    if (INTEGER.matches(first)) {
        FRACTION_NEXT.matchEntire(rest)?.let { fn ->
            val b = fn.groupValues[2].toDouble()
            if (b != 0.0) {
                amount = formatAmount(first.toDouble() + fn.groupValues[1].toDouble() / b)
                rest = fn.groupValues[3].trim()
            }
        }
    }
    if (rest.isEmpty()) return IngredientDraft(name = "", amount = amount)

    val parts = rest.split(Regex("\\s+"))
    val unitKey = parts.firstOrNull()?.removeSuffix(".")?.lowercase()
    return if (parts.size > 1 && unitKey != null && unitKey in KNOWN_UNITS) {
        IngredientDraft(name = parts.drop(1).joinToString(" "), amount = amount, unit = parts[0])
    } else {
        IngredientDraft(name = rest, amount = amount)
    }
}

/** Parse the free-text block into section drafts; "# Name" lines open a named section. */
internal fun sectionsFromText(text: String): List<SectionDraft> {
    val names = mutableListOf<String>()
    val itemLists = mutableListOf<MutableList<IngredientDraft>>()
    for (raw in text.lines()) {
        val line = raw.trim()
        if (line.isEmpty()) continue
        if (line.startsWith("#")) {
            names.add(line.trimStart('#').trim())
            itemLists.add(mutableListOf())
        } else {
            if (itemLists.isEmpty()) { names.add(""); itemLists.add(mutableListOf()) }
            itemLists.last().add(parseIngredientLine(line))
        }
    }
    if (itemLists.isEmpty()) return listOf(SectionDraft())
    return names.indices.map { SectionDraft(names[it], itemLists[it].toList()) }
}

/** Structured sections → the editable text block (named sections become "# name" headers). */
internal fun ingredientsTextFromSections(sections: List<SectionDraft>): String {
    val out = mutableListOf<String>()
    for (sec in sections) {
        if (sec.name.isNotBlank()) out.add("# ${sec.name.trim()}")
        for (ing in sec.ingredients) {
            val line = listOf(ing.amount.trim(), ing.unit.trim(), ing.name.trim())
                .filter { it.isNotEmpty() }.joinToString(" ")
            if (line.isNotEmpty()) out.add(line)
        }
    }
    return out.joinToString("\n")
}

/** Parse a free-text textarea into [RecipeStepInput]s — one step per non-blank line. */
private fun parseSteps(text: String): List<RecipeStepInput> =
    text.lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { RecipeStepInput(it) }

/** A text "+ …" affordance styled like the web `hb-addrow` link (issue #28). */
@Composable
private fun AddRowLink(text: String, onClick: () -> Unit) {
    Text(
        text,
        style = HbType.small.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
        color = Hb.accentInk,
        modifier = Modifier
            .clip(HbRadiusSm)
            .clickable { onClick() }
            .padding(vertical = 6.dp, horizontal = 2.dp),
    )
}
