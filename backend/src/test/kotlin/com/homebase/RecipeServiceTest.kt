package com.homebase

import com.homebase.db.IngredientsTable
import com.homebase.db.RecipeImagesTable
import com.homebase.db.RecipeStepsTable
import com.homebase.db.RecipesTable
import com.homebase.model.CreateRecipeRequest
import com.homebase.model.IngredientInput
import com.homebase.model.RecipeStepInput
import com.homebase.model.UpdateRecipeRequest
import com.homebase.service.RecipeService
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [RecipeService] (issue #565): recipe CRUD with embedded ingredients/steps
 * (replaced wholesale on update) and the cover-image DB path — without an HTTP layer. The full HTTP +
 * file-I/O + import contract stays covered by RecipeRouteTest / RecipeImageRouteTest.
 */
class RecipeServiceTest {

    private val service = RecipeService()

    @BeforeTest
    fun setup() {
        Database.connect(
            url = "jdbc:h2:mem:recipeservice_test_${System.nanoTime()};DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
            driver = "org.h2.Driver",
        )
        transaction { SchemaUtils.create(RecipesTable, IngredientsTable, RecipeStepsTable, RecipeImagesTable) }
    }

    private suspend fun sampleCreate() = service.create(
        CreateRecipeRequest(
            title = "Lasagne",
            category = "dinner",
            servings = 4,
            ingredients = listOf(IngredientInput(name = "Mehl", amount = 200.0, unit = "g"), IngredientInput(name = "Ei", amount = 2.0)),
            steps = listOf(RecipeStepInput(description = "Teig kneten"), RecipeStepInput(description = "Backen")),
        ),
        username = "alice",
    )

    @Test
    fun `create embeds ingredients and steps in order and normalises category`() = runBlocking {
        val r = sampleCreate()
        assertEquals("DINNER", r.category)
        assertEquals(listOf("Mehl", "Ei"), r.ingredients.map { it.name })
        assertEquals(listOf(1, 2), r.steps.map { it.stepNumber })
    }

    @Test
    fun `get returns the recipe, unknown id is null`() = runBlocking {
        val r = sampleCreate()
        assertEquals(r.id, service.get(UUID.fromString(r.id))?.id)
        assertNull(service.get(UUID.randomUUID()))
    }

    @Test
    fun `update replaces the ingredient list wholesale`() = runBlocking {
        val r = sampleCreate()
        val updated = service.update(
            UUID.fromString(r.id),
            UpdateRecipeRequest(ingredients = listOf(IngredientInput(name = "Tomate", amount = 3.0))),
        )
        assertTrue(updated != null)
        assertEquals(listOf("Tomate"), updated.ingredients.map { it.name })
        // steps were not supplied → left untouched
        assertEquals(2, updated.steps.size)
    }

    @Test
    fun `delete removes the recipe and reports its cover files`() = runBlocking {
        val r = sampleCreate()
        val id = UUID.fromString(r.id)
        service.setCoverImage(id, "alice", RecipeService.StoredUpload(UUID.randomUUID(), "cover.jpg", "l.jpg", "image/jpeg", 1L))

        val outcome = service.delete(id)
        assertEquals(listOf("cover.jpg"), outcome?.files)
        assertNull(service.get(id))
    }

    @Test
    fun `setting a cover replaces the previous one and reports the old file`() = runBlocking {
        val r = sampleCreate()
        val id = UUID.fromString(r.id)
        service.setCoverImage(id, "alice", RecipeService.StoredUpload(UUID.randomUUID(), "old.jpg", "a.jpg", "image/jpeg", 1L))

        val second = service.setCoverImage(id, "alice", RecipeService.StoredUpload(UUID.randomUUID(), "new.jpg", "b.jpg", "image/jpeg", 2L))
        assertEquals(listOf("old.jpg"), second?.oldFiles)
        assertEquals("new.jpg", service.imageForDownload(id, UUID.fromString(second!!.recipe.image!!.id))?.filename)
    }
}
