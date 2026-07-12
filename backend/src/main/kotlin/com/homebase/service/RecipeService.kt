package com.homebase.service

import com.homebase.db.IngredientsTable
import com.homebase.db.RecipeImagesTable
import com.homebase.db.RecipeStepsTable
import com.homebase.db.RecipesTable
import com.homebase.db.dbQuery
import com.homebase.model.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * Owns the recipes domain's persistence (issue #565, following the TodoService pattern of #546):
 * recipe CRUD with embedded ingredients/steps (replaced wholesale on update) and the DB side of the
 * single cover image. Methods are `suspend` + `dbQuery {}` (#549).
 *
 * The route keeps HTTP validation, the servings scaling, the Markdown/PDF export rendering, the
 * SSRF-guarded URL import (no DB) and the cover-image file-I/O + multipart; broadcasts stay in the
 * route. Recipes are household-shared, so there is no per-recipe visibility/ownership gate.
 */
class RecipeService {

    /** Metadata of an already-stored cover upload the route hands to [setCoverImage]. */
    class StoredUpload(val id: UUID, val storedName: String, val originalName: String, val contentType: String, val size: Long)

    /** The updated recipe after setting a new cover, plus the previous cover file(s) to delete. */
    class CoverSetOutcome(val recipe: RecipeDto, val oldFiles: List<String>)

    /** What the route needs to stream a cover download. */
    class ImageDownload(val filename: String, val contentType: String, val originalName: String)

    /** A removed cover's on-disk filename plus the recipe to broadcast. */
    class ImageDeleteOutcome(val filename: String, val recipe: RecipeDto)

    /** A deleted recipe plus the cover image filenames to clean off disk afterwards. */
    class DeleteOutcome(val recipe: RecipeDto, val files: List<String>)

    // ---- Recipes ---------------------------------------------------------

    /** [categoryFilter] is already validated/uppercased by the caller (null = all). */
    suspend fun list(categoryFilter: String?): List<RecipeDto> = dbQuery {
        RecipesTable.selectAll()
            .apply { if (categoryFilter != null) andWhere { RecipesTable.category eq categoryFilter } }
            .orderBy(RecipesTable.updatedAt, SortOrder.DESC)
            .map { it.toRecipeDto() }
    }

    /** null = recipe not found (→ 404). Scaling is applied by the caller. */
    suspend fun get(id: UUID): RecipeDto? = dbQuery {
        RecipesTable.selectAll().where { RecipesTable.id eq id }.singleOrNull()?.toRecipeDto()
    }

    /** Caller has validated the request. */
    suspend fun create(req: CreateRecipeRequest, username: String): RecipeDto = dbQuery {
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

    /** null = recipe not found (→ 404). Caller has validated the request. */
    suspend fun update(id: UUID, req: UpdateRecipeRequest): RecipeDto? = dbQuery {
        RecipesTable.selectAll().where { RecipesTable.id eq id }.singleOrNull() ?: return@dbQuery null

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

    /** null = recipe not found (→ 404). */
    suspend fun delete(id: UUID): DeleteOutcome? = dbQuery {
        val existing = RecipesTable.selectAll().where { RecipesTable.id eq id }.singleOrNull()
            ?: return@dbQuery null
        val dto = existing.toRecipeDto()
        // Capture the image filenames before the cascade removes their rows so we can clean up the
        // files on disk afterwards.
        val files = RecipeImagesTable.selectAll().where { RecipeImagesTable.recipeId eq id }
            .map { it[RecipeImagesTable.filename] }
        IngredientsTable.deleteWhere { IngredientsTable.recipeId eq id }
        RecipeStepsTable.deleteWhere { RecipeStepsTable.recipeId eq id }
        RecipesTable.deleteWhere { RecipesTable.id eq id }
        DeleteOutcome(dto, files)
    }

    // ---- Cover image -----------------------------------------------------

    suspend fun exists(id: UUID): Boolean = dbQuery {
        RecipesTable.selectAll().where { RecipesTable.id eq id }.singleOrNull() != null
    }

    /** Sets (replacing any previous) the cover image row. null = recipe vanished (→ 404, undo file). */
    suspend fun setCoverImage(recipeId: UUID, username: String, upload: StoredUpload): CoverSetOutcome? = dbQuery {
        RecipesTable.selectAll().where { RecipesTable.id eq recipeId }.singleOrNull() ?: return@dbQuery null
        // single cover image: drop the previous one (its file is removed after commit)
        val oldFiles = RecipeImagesTable.selectAll().where { RecipeImagesTable.recipeId eq recipeId }
            .map { it[RecipeImagesTable.filename] }
        RecipeImagesTable.deleteWhere { RecipeImagesTable.recipeId eq recipeId }
        RecipeImagesTable.insert {
            it[RecipeImagesTable.id] = upload.id
            it[RecipeImagesTable.recipeId] = recipeId
            it[filename] = upload.storedName
            it[RecipeImagesTable.originalName] = upload.originalName
            it[RecipeImagesTable.contentType] = upload.contentType
            it[sizeBytes] = upload.size
            it[createdBy] = username
            it[createdAt] = Instant.now()
        }
        RecipesTable.update({ RecipesTable.id eq recipeId }) { it[updatedAt] = Instant.now() }
        CoverSetOutcome(RecipesTable.selectAll().where { RecipesTable.id eq recipeId }.single().toRecipeDto(), oldFiles)
    }

    /** null = image row not found (→ 404). */
    suspend fun imageForDownload(recipeId: UUID, imageId: UUID): ImageDownload? = dbQuery {
        val row = RecipeImagesTable.selectAll()
            .where { (RecipeImagesTable.id eq imageId) and (RecipeImagesTable.recipeId eq recipeId) }
            .singleOrNull() ?: return@dbQuery null
        ImageDownload(row[RecipeImagesTable.filename], row[RecipeImagesTable.contentType], row[RecipeImagesTable.originalName])
    }

    /** null = recipe or image not found (→ 404). */
    suspend fun deleteCoverImage(recipeId: UUID, imageId: UUID): ImageDeleteOutcome? = dbQuery {
        RecipesTable.selectAll().where { RecipesTable.id eq recipeId }.singleOrNull() ?: return@dbQuery null
        val image = RecipeImagesTable.selectAll()
            .where { (RecipeImagesTable.id eq imageId) and (RecipeImagesTable.recipeId eq recipeId) }
            .singleOrNull() ?: return@dbQuery null
        val filename = image[RecipeImagesTable.filename]
        RecipeImagesTable.deleteWhere { (RecipeImagesTable.id eq imageId) and (RecipeImagesTable.recipeId eq recipeId) }
        RecipesTable.update({ RecipesTable.id eq recipeId }) { it[updatedAt] = Instant.now() }
        ImageDeleteOutcome(filename, RecipesTable.selectAll().where { RecipesTable.id eq recipeId }.single().toRecipeDto())
    }
}

// ---- Persistence helpers (run inside a transaction) --------------------------------------------

// Ingredient order is taken from list position.
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

// Step numbers are 1-based list positions.
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

// Loads the recipe with its ingredients + steps + cover image. Must be called inside a transaction.
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
                sortOrder = it[IngredientsTable.sortOrder],
            )
        }
    val steps = RecipeStepsTable.selectAll()
        .where { RecipeStepsTable.recipeId eq recipeId }
        .orderBy(RecipeStepsTable.stepNumber, SortOrder.ASC)
        .map {
            RecipeStepDto(
                id = it[RecipeStepsTable.id].toString(),
                stepNumber = it[RecipeStepsTable.stepNumber],
                description = it[RecipeStepsTable.description],
            )
        }
    val image = RecipeImagesTable.selectAll()
        .where { RecipeImagesTable.recipeId eq recipeId }
        .firstOrNull()
        ?.let {
            RecipeImageDto(
                id = it[RecipeImagesTable.id].toString(),
                recipeId = it[RecipeImagesTable.recipeId].toString(),
                originalName = it[RecipeImagesTable.originalName],
                contentType = it[RecipeImagesTable.contentType],
                sizeBytes = it[RecipeImagesTable.sizeBytes],
                createdBy = it[RecipeImagesTable.createdBy],
                createdAt = it[RecipeImagesTable.createdAt].toString(),
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
        image = image,
        createdBy = this[RecipesTable.createdBy],
        createdAt = this[RecipesTable.createdAt].toString(),
        updatedAt = this[RecipesTable.updatedAt].toString(),
    )
}
