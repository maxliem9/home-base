package com.homebase.android

import com.homebase.android.data.model.BatchAddShoppingResponse
import com.homebase.android.data.model.IngredientDto
import com.homebase.android.data.model.MealPlanEntryDto
import com.homebase.android.data.model.RecipeDto
import com.homebase.android.data.model.ShoppingLineInput
import com.homebase.android.data.model.ShoppingListDto
import com.homebase.android.data.repository.MealPlanRepository
import com.homebase.android.data.websocket.MealPlanWebSocketClient
import com.homebase.android.data.websocket.RecipeWebSocketClient
import com.homebase.android.ui.wochenplan.MealPlanViewModel
import io.mockk.CapturingSlot
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MealPlanViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var repository: MealPlanRepository
    private val mealPlanEvents = MutableSharedFlow<MealPlanWebSocketClient.WsEvent>()
    private val recipeEvents = MutableSharedFlow<RecipeWebSocketClient.WsEvent>()

    private fun ingredient(name: String, amount: Double?, unit: String?, order: Int) =
        IngredientDto(id = "i-$name", name = name, amount = amount, unit = unit, sortOrder = order)

    private fun recipe(id: String, title: String, ingredients: List<IngredientDto>) = RecipeDto(
        id = id, title = title, servings = 2, category = "DINNER",
        ingredients = ingredients, createdBy = "alice",
        createdAt = "2026-01-01T00:00:00Z", updatedAt = "2026-01-01T00:00:00Z",
    )

    private fun entry(date: String, slot: String, recipeId: String, title: String) = MealPlanEntryDto(
        id = "m-$date-$slot", date = date, slot = slot, recipeId = recipeId,
        recipeTitle = title, recipeCategory = "DINNER", createdBy = "alice", createdAt = "2026-01-01T00:00:00Z",
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        repository = mockk(relaxed = true)
        every { repository.mealPlanEvents } returns mealPlanEvents
        every { repository.recipeEvents } returns recipeEvents
        coEvery { repository.getMealPlan(any(), any()) } returns Result.success(emptyList())
        coEvery { repository.getRecipes() } returns Result.success(emptyList())
        coEvery { repository.getShoppingLists() } returns Result.success(emptyList())
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun createVm() = MealPlanViewModel(repository, "test-token")

    @Test
    fun `initial load populates entries, recipes and lists`() = runTest {
        val r = recipe("r1", "Lasagne", listOf(ingredient("Mehl", 200.0, "g", 0)))
        coEvery { repository.getRecipes() } returns Result.success(listOf(r))
        coEvery { repository.getShoppingLists() } returns Result.success(listOf(ShoppingListDto("sl1", "Wocheneinkauf", "alice", "2026-01-01T00:00:00Z")))
        coEvery { repository.getMealPlan(any(), any()) } returns Result.success(listOf(entry("2026-06-15", "DINNER", "r1", "Lasagne")))

        val vm = createVm()
        advanceUntilIdle()

        val s = vm.uiState.value
        assertEquals(1, s.recipes.size)
        assertEquals(1, s.shoppingLists.size)
        assertEquals(1, s.entries.size)
        assertEquals("Lasagne", s.entryFor("2026-06-15", "DINNER")?.recipeTitle)
        assertTrue(!s.isLoading)
    }

    @Test
    fun `setSlot upserts the returned entry into state`() = runTest {
        val planned = entry("2026-06-16", "DINNER", "r1", "Lasagne")
        coEvery { repository.setMealSlot("2026-06-16", "DINNER", "r1") } returns Result.success(planned)

        val vm = createVm()
        advanceUntilIdle()
        vm.setSlot("2026-06-16", "DINNER", "r1")
        advanceUntilIdle()

        assertEquals("Lasagne", vm.uiState.value.entryFor("2026-06-16", "DINNER")?.recipeTitle)
    }

    @Test
    fun `clearSlot optimistically removes the entry`() = runTest {
        coEvery { repository.getMealPlan(any(), any()) } returns Result.success(listOf(entry("2026-06-15", "DINNER", "r1", "Lasagne")))
        coEvery { repository.deleteMealSlot(any(), any()) } returns Result.success(Unit)

        val vm = createVm()
        advanceUntilIdle()
        assertEquals(1, vm.uiState.value.entries.size)

        vm.clearSlot("2026-06-15", "DINNER")
        advanceUntilIdle()

        assertTrue(vm.uiState.value.entries.isEmpty())
    }

    @Test
    fun `addWeekToShopping aggregates planned-recipe ingredients in 1x portions`() = runTest {
        val r = recipe("r1", "Lasagne", listOf(ingredient("Nudelplatten", 250.0, "g", 0), ingredient("Hackfleisch", 500.0, "g", 1)))
        coEvery { repository.getRecipes() } returns Result.success(listOf(r))
        coEvery { repository.getMealPlan(any(), any()) } returns Result.success(listOf(entry("2026-06-15", "DINNER", "r1", "Lasagne")))
        val lines: CapturingSlot<List<ShoppingLineInput>> = slot()
        coEvery { repository.addToShopping(any(), capture(lines)) } returns Result.success(BatchAddShoppingResponse(added = 2, merged = 0, skipped = 0))

        val vm = createVm()
        advanceUntilIdle()

        var reported: Pair<Int, Int>? = null
        vm.addWeekToShopping("sl1") { added, merged -> reported = added to merged }
        advanceUntilIdle()

        coVerify { repository.addToShopping("sl1", any()) }
        assertEquals(listOf("Nudelplatten", "Hackfleisch"), lines.captured.map { it.name })
        assertEquals(250.0, lines.captured.first().amount!!, 0.001)
        assertEquals(2 to 0, reported)
    }
}
