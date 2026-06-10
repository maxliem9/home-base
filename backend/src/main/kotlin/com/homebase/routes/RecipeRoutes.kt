package com.homebase.routes

import com.homebase.db.IngredientsTable
import com.homebase.db.RecipeStepsTable
import com.homebase.db.RecipesTable
import com.homebase.model.*
import com.homebase.ws.WsSessionManager
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

private const val RECIPES_WS_CHANNEL = "recipes"
// LUNCH was dropped (collapsed into DINNER) — see migration V17. Clients only offer these five.
private val VALID_CATEGORIES = setOf("BREAKFAST", "DINNER", "SNACK", "DESSERT", "DRINK")

fun Route.recipeRoutes() {
    val json = Json { ignoreUnknownKeys = true }

    route("/recipes") {
        // List recipes, optionally filtered by ?category=. Newest first.
        get {
            val categoryFilter = call.request.queryParameters["category"]?.uppercase()
            if (categoryFilter != null && categoryFilter !in VALID_CATEGORIES) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_CATEGORY", "unknown category"))
                return@get
            }
            val recipes = transaction {
                RecipesTable.selectAll()
                    .apply { if (categoryFilter != null) andWhere { RecipesTable.category eq categoryFilter } }
                    .orderBy(RecipesTable.updatedAt, SortOrder.DESC)
                    .map { it.toRecipeDto() }
            }
            call.respond(recipes)
        }

        // Detail incl. ingredients + steps. Optional ?servings=N scales ingredient amounts.
        get("/{id}") {
            val id = call.uuidParam() ?: return@get
            val servingsParam = call.request.queryParameters["servings"]
            val targetServings = servingsParam?.toIntOrNull()
            if (servingsParam != null && (targetServings == null || targetServings < 1)) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_RECIPE", "servings must be >= 1"))
                return@get
            }
            val recipe = transaction {
                RecipesTable.selectAll().where { RecipesTable.id eq id }.singleOrNull()?.toRecipeDto()
            }
            if (recipe == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Recipe not found"))
                return@get
            }
            call.respond(if (targetServings != null) recipe.scaledTo(targetServings) else recipe)
        }

        // Download a single recipe as Markdown (?format=md, default) or PDF (?format=pdf).
        // Optional ?servings=N scales amounts exactly like the detail endpoint.
        get("/{id}/export") {
            val id = call.uuidParam() ?: return@get
            val format = (call.request.queryParameters["format"] ?: "md").lowercase()
            if (format != "md" && format != "pdf") {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_FORMAT", "format must be 'md' or 'pdf'"))
                return@get
            }
            val servingsParam = call.request.queryParameters["servings"]
            val targetServings = servingsParam?.toIntOrNull()
            if (servingsParam != null && (targetServings == null || targetServings < 1)) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_RECIPE", "servings must be >= 1"))
                return@get
            }
            val recipe = transaction {
                RecipesTable.selectAll().where { RecipesTable.id eq id }.singleOrNull()?.toRecipeDto()
            }
            if (recipe == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Recipe not found"))
                return@get
            }
            val scaled = if (targetServings != null) recipe.scaledTo(targetServings) else recipe
            val slug = recipeSlug(scaled.title)

            if (format == "pdf") {
                call.attachmentHeader("rezept_$slug.pdf")
                call.respondBytes(buildRecipePdf(scaled), ContentType.Application.Pdf)
            } else {
                call.attachmentHeader("rezept_$slug.md")
                call.respondText(buildRecipeMarkdown(scaled), ContentType.parse("text/markdown; charset=UTF-8"))
            }
        }

        post {
            val username = call.username()
            val req = call.receive<CreateRecipeRequest>()

            val validation = validate(
                title = req.title,
                category = req.category,
                servings = req.servings,
                prepTimeMinutes = req.prepTimeMinutes,
                cookTimeMinutes = req.cookTimeMinutes,
                ingredients = req.ingredients
            )
            if (validation != null) {
                call.respond(HttpStatusCode.BadRequest, validation)
                return@post
            }

            val recipe = transaction {
                val id = UUID.randomUUID()
                val now = Instant.now()
                RecipesTable.insert {
                    it[RecipesTable.id] = id
                    it[title] = req.title.trim()
                    it[description] = req.description
                    it[servings] = req.servings ?: 1
                    it[prepTimeMinutes] = req.prepTimeMinutes
                    it[cookTimeMinutes] = req.cookTimeMinutes
                    it[category] = req.category.uppercase()
                    it[createdBy] = username
                    it[createdAt] = now
                    it[updatedAt] = now
                }
                insertIngredients(id, req.ingredients)
                insertSteps(id, req.steps)
                RecipesTable.selectAll().where { RecipesTable.id eq id }.single().toRecipeDto()
            }

            WsSessionManager.broadcast(RECIPES_WS_CHANNEL, json.encodeToString(RecipeWsMessage("RECIPE_CREATED", recipe)))
            call.respond(HttpStatusCode.Created, recipe)
        }

        put("/{id}") {
            val id = call.uuidParam() ?: return@put
            val req = call.receive<UpdateRecipeRequest>()

            val validation = validate(
                title = req.title,
                category = req.category,
                servings = req.servings,
                prepTimeMinutes = req.prepTimeMinutes,
                cookTimeMinutes = req.cookTimeMinutes,
                ingredients = req.ingredients
            )
            if (validation != null) {
                call.respond(HttpStatusCode.BadRequest, validation)
                return@put
            }

            val recipe = transaction {
                RecipesTable.selectAll().where { RecipesTable.id eq id }.singleOrNull()
                    ?: return@transaction null

                RecipesTable.update({ RecipesTable.id eq id }) {
                    req.title?.let { v -> it[title] = v.trim() }
                    req.description?.let { v -> it[description] = v }
                    req.servings?.let { v -> it[servings] = v }
                    req.prepTimeMinutes?.let { v -> it[prepTimeMinutes] = v }
                    req.cookTimeMinutes?.let { v -> it[cookTimeMinutes] = v }
                    req.category?.let { v -> it[category] = v.uppercase() }
                    it[updatedAt] = Instant.now()
                }
                // Ingredients / steps are owned by the recipe: when supplied, replace wholesale.
                req.ingredients?.let { items ->
                    IngredientsTable.deleteWhere { IngredientsTable.recipeId eq id }
                    insertIngredients(id, items)
                }
                req.steps?.let { steps ->
                    RecipeStepsTable.deleteWhere { RecipeStepsTable.recipeId eq id }
                    insertSteps(id, steps)
                }
                RecipesTable.selectAll().where { RecipesTable.id eq id }.single().toRecipeDto()
            }

            if (recipe == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Recipe not found"))
                return@put
            }

            WsSessionManager.broadcast(RECIPES_WS_CHANNEL, json.encodeToString(RecipeWsMessage("RECIPE_UPDATED", recipe)))
            call.respond(recipe)
        }

        delete("/{id}") {
            val id = call.uuidParam() ?: return@delete
            val deleted = transaction {
                val existing = RecipesTable.selectAll().where { RecipesTable.id eq id }.singleOrNull()
                    ?: return@transaction null
                val dto = existing.toRecipeDto()
                IngredientsTable.deleteWhere { IngredientsTable.recipeId eq id }
                RecipeStepsTable.deleteWhere { RecipeStepsTable.recipeId eq id }
                RecipesTable.deleteWhere { RecipesTable.id eq id }
                dto
            }
            if (deleted == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Recipe not found"))
                return@delete
            }
            WsSessionManager.broadcast(RECIPES_WS_CHANNEL, json.encodeToString(RecipeWsMessage("RECIPE_DELETED", deleted)))
            call.respond(HttpStatusCode.NoContent)
        }
    }

    webSocket("/ws/recipes") {
        WsSessionManager.add(RECIPES_WS_CHANNEL, this)
        try {
            for (frame in incoming) {
                if (frame is Frame.Close) break
            }
        } finally {
            WsSessionManager.remove(RECIPES_WS_CHANNEL, this)
        }
    }
}

/** Sets `Content-Disposition: attachment; filename="…"` so the browser downloads the body. */
private fun ApplicationCall.attachmentHeader(filename: String) {
    response.header(
        HttpHeaders.ContentDisposition,
        ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, filename).toString(),
    )
}

private fun validate(
    title: String? = null,
    category: String? = null,
    servings: Int? = null,
    prepTimeMinutes: Int? = null,
    cookTimeMinutes: Int? = null,
    ingredients: List<IngredientInput>? = null
): ErrorResponse? = when {
    title != null && title.isBlank() -> ErrorResponse("INVALID_RECIPE", "title must not be blank")
    category != null && category.uppercase() !in VALID_CATEGORIES -> ErrorResponse("INVALID_CATEGORY", "unknown category")
    servings != null && servings < 1 -> ErrorResponse("INVALID_RECIPE", "servings must be >= 1")
    prepTimeMinutes != null && prepTimeMinutes < 0 -> ErrorResponse("INVALID_RECIPE", "prepTimeMinutes must be >= 0")
    cookTimeMinutes != null && cookTimeMinutes < 0 -> ErrorResponse("INVALID_RECIPE", "cookTimeMinutes must be >= 0")
    ingredients?.any { it.amount != null && it.amount < 0.0 } == true ->
        ErrorResponse("INVALID_INGREDIENT", "ingredient amount must be >= 0")
    else -> null
}

// Must be called inside a transaction. Ingredient order is taken from list position.
private fun insertIngredients(recipeId: UUID, items: List<IngredientInput>) {
    items.filter { it.name.isNotBlank() }.forEachIndexed { index, ing ->
        IngredientsTable.insert {
            it[id] = UUID.randomUUID()
            it[IngredientsTable.recipeId] = recipeId
            it[name] = ing.name.trim()
            it[amount] = ing.amount?.let { a -> BigDecimal.valueOf(a) }
            it[unit] = ing.unit?.takeIf { u -> u.isNotBlank() }
            it[section] = ing.section?.trim()?.takeIf { s -> s.isNotBlank() }
            it[sortOrder] = index
        }
    }
}

// Must be called inside a transaction. Step numbers are 1-based list positions.
private fun insertSteps(recipeId: UUID, steps: List<RecipeStepInput>) {
    steps.filter { it.description.isNotBlank() }.forEachIndexed { index, step ->
        RecipeStepsTable.insert {
            it[id] = UUID.randomUUID()
            it[RecipeStepsTable.recipeId] = recipeId
            it[stepNumber] = index + 1
            it[description] = step.description.trim()
        }
    }
}

/** Scales ingredient amounts so the recipe yields [targetServings] portions. */
private fun RecipeDto.scaledTo(targetServings: Int): RecipeDto {
    if (targetServings < 1 || targetServings == servings || servings < 1) return this
    val factor = targetServings.toDouble() / servings.toDouble()
    return copy(
        servings = targetServings,
        ingredients = ingredients.map { ing ->
            ing.copy(amount = ing.amount?.let { Math.round(it * factor * 1000.0) / 1000.0 })
        }
    )
}

// Loads the recipe with its ingredients + steps. Must be called inside a transaction.
private fun ResultRow.toRecipeDto(): RecipeDto {
    val recipeId = this[RecipesTable.id]
    val ingredients = IngredientsTable.selectAll()
        .where { IngredientsTable.recipeId eq recipeId }
        .orderBy(IngredientsTable.sortOrder, SortOrder.ASC)
        .map {
            IngredientDto(
                id = it[IngredientsTable.id].toString(),
                name = it[IngredientsTable.name],
                amount = it[IngredientsTable.amount]?.toDouble(),
                unit = it[IngredientsTable.unit],
                section = it[IngredientsTable.section],
                sortOrder = it[IngredientsTable.sortOrder]
            )
        }
    val steps = RecipeStepsTable.selectAll()
        .where { RecipeStepsTable.recipeId eq recipeId }
        .orderBy(RecipeStepsTable.stepNumber, SortOrder.ASC)
        .map {
            RecipeStepDto(
                id = it[RecipeStepsTable.id].toString(),
                stepNumber = it[RecipeStepsTable.stepNumber],
                description = it[RecipeStepsTable.description]
            )
        }
    return RecipeDto(
        id = recipeId.toString(),
        title = this[RecipesTable.title],
        description = this[RecipesTable.description],
        servings = this[RecipesTable.servings],
        prepTimeMinutes = this[RecipesTable.prepTimeMinutes],
        cookTimeMinutes = this[RecipesTable.cookTimeMinutes],
        category = this[RecipesTable.category],
        ingredients = ingredients,
        steps = steps,
        createdBy = this[RecipesTable.createdBy],
        createdAt = this[RecipesTable.createdAt].toString(),
        updatedAt = this[RecipesTable.updatedAt].toString()
    )
}
