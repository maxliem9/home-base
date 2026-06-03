package com.homebase.android.ui.recipes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.homebase.android.data.model.CreateRecipeRequest
import com.homebase.android.data.model.IngredientInput
import com.homebase.android.data.model.RecipeDto
import com.homebase.android.data.model.RecipeStepInput
import kotlin.math.round

// Category labels + icons used across the recipe screens.
private val CATEGORIES = listOf(
    "BREAKFAST" to ("🥐" to "Frühstück"),
    "LUNCH" to ("🍽️" to "Mittag"),
    "DINNER" to ("🍝" to "Abend"),
    "SNACK" to ("🥨" to "Snack"),
    "DESSERT" to ("🍰" to "Dessert"),
    "DRINK" to ("🍹" to "Getränk"),
)

private fun categoryLabel(c: String) = CATEGORIES.firstOrNull { it.first == c }?.second?.second ?: c
private fun categoryIcon(c: String) = CATEGORIES.firstOrNull { it.first == c }?.second?.first ?: "🍴"
private fun totalTime(r: RecipeDto) = (r.prepTimeMinutes ?: 0) + (r.cookTimeMinutes ?: 0)
private fun fmtAmount(n: Double): String {
    val rounded = round(n * 100.0) / 100.0
    return if (rounded == rounded.toLong().toDouble()) rounded.toLong().toString() else rounded.toString()
}

private sealed interface Route {
    data object List : Route
    data class Detail(val id: String) : Route
    data class Editor(val recipe: RecipeDto?) : Route
}

@Composable
fun RecipesScreen(viewModel: RecipesViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var route by remember { mutableStateOf<Route>(Route.List) }

    // resolve a Detail route against the current list so it stays in sync with WS updates
    val detail = (route as? Route.Detail)?.let { d -> uiState.recipes.firstOrNull { it.id == d.id } }

    when (val r = route) {
        is Route.List -> RecipeListScreen(
            uiState = uiState,
            onSelectCategory = viewModel::setCategoryFilter,
            onOpen = { route = Route.Detail(it.id) },
            onCreate = { route = Route.Editor(null) },
            onClearError = viewModel::clearError,
        )
        is Route.Detail -> {
            if (detail == null) {
                // recipe vanished (e.g. deleted on the other client) — fall back to list
                LaunchedEffect(r.id) { route = Route.List }
            } else {
                RecipeDetailScreen(
                    recipe = detail,
                    onBack = { route = Route.List },
                    onEdit = { route = Route.Editor(detail) },
                    onDelete = { viewModel.deleteRecipe(detail.id) { route = Route.List } },
                )
            }
        }
        is Route.Editor -> RecipeEditorScreen(
            recipe = r.recipe,
            onBack = { route = if (r.recipe != null) Route.Detail(r.recipe.id) else Route.List },
            onSave = { request ->
                viewModel.saveRecipe(r.recipe?.id, request) { saved -> route = Route.Detail(saved.id) }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecipeListScreen(
    uiState: RecipesUiState,
    onSelectCategory: (String?) -> Unit,
    onOpen: (RecipeDto) -> Unit,
    onCreate: () -> Unit,
    onClearError: () -> Unit,
) {
    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("Rezepte") },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                )
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        FilterChip(
                            selected = uiState.categoryFilter == null,
                            onClick = { onSelectCategory(null) },
                            label = { Text("Alle") },
                        )
                    }
                    items(CATEGORIES) { (id, meta) ->
                        FilterChip(
                            selected = uiState.categoryFilter == id,
                            onClick = { onSelectCategory(id) },
                            label = { Text("${meta.first} ${meta.second}") },
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onCreate) {
                Text("+", style = MaterialTheme.typography.headlineMedium)
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                uiState.isLoading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                uiState.recipes.isEmpty() -> Text(
                    if (uiState.categoryFilter == null) "Noch keine Rezepte — nutze + zum Erstellen."
                    else "Keine Rezepte in dieser Kategorie.",
                    modifier = Modifier.align(Alignment.Center),
                    style = MaterialTheme.typography.bodyLarge,
                )
                else -> LazyColumn(
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(uiState.recipes, key = { it.id }) { recipe ->
                        RecipeCard(recipe = recipe, onClick = { onOpen(recipe) })
                    }
                }
            }
        }
    }

    uiState.error?.let { msg ->
        AlertDialog(
            onDismissRequest = onClearError,
            confirmButton = { TextButton(onClick = onClearError) { Text("OK") } },
            title = { Text("Fehler") },
            text = { Text(msg) },
        )
    }
}

@Composable
private fun RecipeCard(recipe: RecipeDto, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(categoryIcon(recipe.category), style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    recipe.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                val info = buildList {
                    add(categoryLabel(recipe.category))
                    if (totalTime(recipe) > 0) add("⏱️ ${totalTime(recipe)} Min")
                    add("🍽️ ${recipe.servings} Port.")
                }.joinToString("  ·  ")
                Text(
                    info,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecipeDetailScreen(
    recipe: RecipeDto,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    // Portions are scaled live on the client; the base recipe stays untouched.
    var servings by remember(recipe.id) { mutableStateOf(recipe.servings) }
    val factor = if (recipe.servings > 0) servings.toDouble() / recipe.servings.toDouble() else 1.0

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(recipe.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                },
                actions = {
                    TextButton(onClick = onEdit) { Text("Bearbeiten") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Text("${categoryIcon(recipe.category)} ${categoryLabel(recipe.category)}", style = MaterialTheme.typography.labelLarge)

            if (!recipe.description.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(recipe.description, style = MaterialTheme.typography.bodyMedium)
            }

            Spacer(Modifier.height(8.dp))
            val times = buildList {
                recipe.prepTimeMinutes?.let { add("Vorbereitung: $it Min") }
                recipe.cookTimeMinutes?.let { add("Kochzeit: $it Min") }
            }
            if (times.isNotEmpty()) {
                Text(times.joinToString("  ·  "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            // Servings stepper
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Portionen", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                OutlinedIconButton(onClick = { if (servings > 1) servings-- }) { Text("−") }
                Text(
                    servings.toString(),
                    Modifier.widthIn(min = 40.dp).padding(horizontal = 8.dp),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                OutlinedIconButton(onClick = { servings++ }) { Text("+") }
            }

            if (recipe.ingredients.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text("Zutaten", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                recipe.ingredients.forEach { ing ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        Text(ing.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        ing.amount?.let { amount ->
                            Text(
                                "${fmtAmount(amount * factor)} ${ing.unit ?: ""}".trim(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            if (recipe.steps.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text("Zubereitung", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                recipe.steps.forEach { step ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text(
                            "${step.stepNumber}.",
                            Modifier.width(28.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(step.description, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            OutlinedButton(
                onClick = onDelete,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Text("Rezept löschen")
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private data class IngredientDraft(val name: String, val amount: String, val unit: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecipeEditorScreen(
    recipe: RecipeDto?,
    onBack: () -> Unit,
    onSave: (CreateRecipeRequest) -> Unit,
) {
    var title by remember { mutableStateOf(recipe?.title ?: "") }
    var description by remember { mutableStateOf(recipe?.description ?: "") }
    var category by remember { mutableStateOf(recipe?.category ?: "DINNER") }
    var servings by remember { mutableStateOf((recipe?.servings ?: 2).toString()) }
    var prep by remember { mutableStateOf(recipe?.prepTimeMinutes?.toString() ?: "") }
    var cook by remember { mutableStateOf(recipe?.cookTimeMinutes?.toString() ?: "") }
    var ingredients by remember {
        mutableStateOf(
            recipe?.ingredients?.map { IngredientDraft(it.name, it.amount?.let(::fmtAmount) ?: "", it.unit ?: "") }
                ?.ifEmpty { listOf(IngredientDraft("", "", "")) }
                ?: listOf(IngredientDraft("", "", "")),
        )
    }
    var steps by remember {
        mutableStateOf(
            recipe?.steps?.map { it.description }?.ifEmpty { listOf("") } ?: listOf(""),
        )
    }
    var categoryMenu by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (recipe == null) "Neues Rezept" else "Rezept bearbeiten") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                },
                actions = {
                    TextButton(
                        enabled = title.isNotBlank(),
                        onClick = {
                            onSave(
                                CreateRecipeRequest(
                                    title = title.trim(),
                                    description = description.trim().ifBlank { null },
                                    servings = servings.toIntOrNull() ?: 1,
                                    prepTimeMinutes = prep.toIntOrNull(),
                                    cookTimeMinutes = cook.toIntOrNull(),
                                    category = category,
                                    ingredients = ingredients
                                        .filter { it.name.isNotBlank() }
                                        .map {
                                            IngredientInput(
                                                name = it.name.trim(),
                                                amount = it.amount.replace(',', '.').toDoubleOrNull(),
                                                unit = it.unit.trim().ifBlank { null },
                                            )
                                        },
                                    steps = steps.filter { it.isNotBlank() }.map { RecipeStepInput(it.trim()) },
                                ),
                            )
                        },
                    ) { Text("Speichern") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Titel") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text("Beschreibung") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))

            // Category dropdown
            ExposedDropdownMenuBox(expanded = categoryMenu, onExpandedChange = { categoryMenu = it }) {
                OutlinedTextField(
                    value = categoryLabel(category),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Kategorie") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = categoryMenu) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(),
                )
                ExposedDropdownMenu(expanded = categoryMenu, onDismissRequest = { categoryMenu = false }) {
                    CATEGORIES.forEach { (id, meta) ->
                        DropdownMenuItem(
                            text = { Text("${meta.first} ${meta.second}") },
                            onClick = { category = id; categoryMenu = false },
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = servings,
                    onValueChange = { servings = it.filter(Char::isDigit) },
                    label = { Text("Portionen") },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = prep,
                    onValueChange = { prep = it.filter(Char::isDigit) },
                    label = { Text("Vorb. Min") },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = cook,
                    onValueChange = { cook = it.filter(Char::isDigit) },
                    label = { Text("Koch. Min") },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }

            // Ingredients
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Zutaten", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { ingredients = ingredients + IngredientDraft("", "", "") }) { Text("+ Zutat") }
            }
            ingredients.forEachIndexed { idx, ing ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = ing.name,
                        onValueChange = { v -> ingredients = ingredients.mapIndexed { i, x -> if (i == idx) x.copy(name = v) else x } },
                        label = { Text("Zutat") },
                        singleLine = true,
                        modifier = Modifier.weight(2f),
                    )
                    Spacer(Modifier.width(4.dp))
                    OutlinedTextField(
                        value = ing.amount,
                        onValueChange = { v -> ingredients = ingredients.mapIndexed { i, x -> if (i == idx) x.copy(amount = v) else x } },
                        label = { Text("Menge") },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(4.dp))
                    OutlinedTextField(
                        value = ing.unit,
                        onValueChange = { v -> ingredients = ingredients.mapIndexed { i, x -> if (i == idx) x.copy(unit = v) else x } },
                        label = { Text("Einh.") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { ingredients = ingredients.filterIndexed { i, _ -> i != idx } }) {
                        Text("✕", color = MaterialTheme.colorScheme.error)
                    }
                }
                Spacer(Modifier.height(4.dp))
            }

            // Steps
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Zubereitung", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { steps = steps + "" }) { Text("+ Schritt") }
            }
            steps.forEachIndexed { idx, step ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${idx + 1}.",
                        Modifier.width(28.dp),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    OutlinedTextField(
                        value = step,
                        onValueChange = { v -> steps = steps.mapIndexed { i, x -> if (i == idx) v else x } },
                        label = { Text("Schritt ${idx + 1}") },
                        minLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { steps = steps.filterIndexed { i, _ -> i != idx } }) {
                        Text("✕", color = MaterialTheme.colorScheme.error)
                    }
                }
                Spacer(Modifier.height(4.dp))
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}
