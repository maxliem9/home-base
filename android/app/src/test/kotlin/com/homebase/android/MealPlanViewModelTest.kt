package com.homebase.android

import com.homebase.android.data.model.BatchAddShoppingResponse
import com.homebase.android.data.model.IngredientDto
import com.homebase.android.data.model.MealPlanEntryDto
import com.homebase.android.data.model.RecipeDto
import com.homebase.android.data.model.ShoppingLineInput
import com.homebase.android.data.model.ShoppingListDto
import com.homebase.android.data.cache.SnapshotStore
import com.homebase.android.data.repository.MealPlanRepository
import com.homebase.android.data.websocket.MealPlanWebSocketClient
import com.homebase.android.data.websocket.RecipeWebSocketClient
import com.homebase.android.data.wochenplan.MealPlanSnapshot
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

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

    private fun entry(date: String, slot: String, recipeId: String, title: String, servings: Int? = null) = MealPlanEntryDto(
        id = "m-$date-$slot", date = date, slot = slot, recipeId = recipeId,
        recipeTitle = title, recipeCategory = "DINNER", servings = servings, createdBy = "alice", createdAt = "2026-01-01T00:00:00Z",
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

    private fun createVm(snapshotStore: SnapshotStore<MealPlanSnapshot>? = null) =
        MealPlanViewModel(repository, "test-token", snapshotStore = snapshotStore)

    /** In-memory [SnapshotStore] standing in for the SharedPreferences-backed read-cache (#520). */
    private class FakeSnapshotStore(var data: MealPlanSnapshot? = null) : SnapshotStore<MealPlanSnapshot> {
        override suspend fun load(): MealPlanSnapshot? = data
        override suspend fun save(snapshot: MealPlanSnapshot) { data = snapshot }
    }

    /** The Monday the VM anchors to on a fresh launch (matches MealPlanUiState.weekStart's default). */
    private val currentMonday = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString()

    // --- Offline read-cache (#520) -------------------------------------------------------------

    @Test
    fun `cold start with no connection seeds the cached plan for the current week`() = runTest {
        coEvery { repository.getMealPlan(any(), any()) } returns Result.failure(java.io.IOException("offline"))
        coEvery { repository.getRecipes() } returns Result.failure(java.io.IOException("offline"))
        coEvery { repository.getShoppingLists() } returns Result.failure(java.io.IOException("offline"))
        val cache = FakeSnapshotStore(
            MealPlanSnapshot(
                weekStart = currentMonday,
                entries = listOf(entry(currentMonday, "DINNER", "r1", "Lasagne")),
                recipes = listOf(recipe("r1", "Lasagne", emptyList())),
            ),
        )

        val vm = createVm(snapshotStore = cache)
        advanceUntilIdle()

        assertEquals(listOf("Lasagne"), vm.uiState.value.entries.map { it.recipeTitle })
        assertEquals(listOf("Lasagne"), vm.uiState.value.recipes.map { it.title })
        assertFalse(vm.uiState.value.isLoading)
        assertNull("offline refresh over cached data is not surfaced as an error", vm.uiState.value.error)
    }

    @Test
    fun `cached entries for a different week are not seeded`() = runTest {
        coEvery { repository.getMealPlan(any(), any()) } returns Result.failure(java.io.IOException("offline"))
        coEvery { repository.getRecipes() } returns Result.failure(java.io.IOException("offline"))
        val lastWeekMonday = LocalDate.parse(currentMonday).minusWeeks(1).toString()
        val cache = FakeSnapshotStore(
            MealPlanSnapshot(
                weekStart = lastWeekMonday, // stale week
                entries = listOf(entry(lastWeekMonday, "DINNER", "r1", "AltesGericht")),
                recipes = listOf(recipe("r1", "Lasagne", emptyList())),
            ),
        )

        val vm = createVm(snapshotStore = cache)
        advanceUntilIdle()

        // Week-scoped: the other week's entries must NOT appear for the current week...
        assertTrue("stale-week entries must not seed", vm.uiState.value.entries.isEmpty())
        // ...but the week-independent recipes still seed.
        assertEquals(listOf("Lasagne"), vm.uiState.value.recipes.map { it.title })
    }

    @Test
    fun `a successful entries fetch wins over the cached plan`() = runTest {
        coEvery { repository.getMealPlan(any(), any()) } returns Result.success(listOf(entry(currentMonday, "DINNER", "r2", "Frisch")))
        val cache = FakeSnapshotStore(
            MealPlanSnapshot(weekStart = currentMonday, entries = listOf(entry(currentMonday, "DINNER", "r1", "STALE"))),
        )

        val vm = createVm(snapshotStore = cache)
        advanceUntilIdle()

        assertEquals(listOf("Frisch"), vm.uiState.value.entries.map { it.recipeTitle })
    }

    @Test
    fun `a successful load is mirrored into the cache`() = runTest {
        coEvery { repository.getMealPlan(any(), any()) } returns Result.success(listOf(entry(currentMonday, "DINNER", "r1", "Lasagne")))
        coEvery { repository.getRecipes() } returns Result.success(listOf(recipe("r1", "Lasagne", emptyList())))
        val cache = FakeSnapshotStore()

        val vm = createVm(snapshotStore = cache)
        advanceUntilIdle()

        assertEquals(currentMonday, cache.data?.weekStart)
        assertEquals(listOf("Lasagne"), cache.data?.entries?.map { it.recipeTitle })
        assertEquals(listOf("Lasagne"), cache.data?.recipes?.map { it.title })
    }

    @Test
    fun `an offline cold start does not overwrite the cache with a new-week empty snapshot`() = runTest {
        coEvery { repository.getMealPlan(any(), any()) } returns Result.failure(java.io.IOException("offline"))
        coEvery { repository.getRecipes() } returns Result.failure(java.io.IOException("offline"))
        val cache = FakeSnapshotStore(
            MealPlanSnapshot(weekStart = currentMonday, entries = listOf(entry(currentMonday, "DINNER", "r1", "Lasagne")), recipes = listOf(recipe("r1", "Lasagne", emptyList()))),
        )

        val vm = createVm(snapshotStore = cache)
        advanceUntilIdle()

        assertEquals("cached current-week entries survive an offline launch", listOf("Lasagne"), cache.data?.entries?.map { it.recipeTitle })
    }

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
        coEvery { repository.setMealSlot("2026-06-16", "DINNER", "r1", null, null) } returns Result.success(planned)

        val vm = createVm()
        advanceUntilIdle()
        vm.setSlot("2026-06-16", "DINNER", "r1", null, null)
        advanceUntilIdle()

        assertEquals("Lasagne", vm.uiState.value.entryFor("2026-06-16", "DINNER")?.recipeTitle)
    }

    @Test
    fun `setSlot with free text upserts a recipe-less dish entry (#293)`() = runTest {
        val dish = MealPlanEntryDto(
            id = "m-free", date = "2026-06-16", slot = "LUNCH", recipeId = null,
            recipeTitle = null, recipeCategory = null, dishTitle = "Pizza bestellen",
            servings = null, createdBy = "alice", createdAt = "2026-01-01T00:00:00Z",
        )
        coEvery { repository.setMealSlot("2026-06-16", "LUNCH", null, "Pizza bestellen", null) } returns Result.success(dish)

        val vm = createVm()
        advanceUntilIdle()
        vm.setSlot("2026-06-16", "LUNCH", null, "Pizza bestellen", null)
        advanceUntilIdle()

        val e = vm.uiState.value.entryFor("2026-06-16", "LUNCH")
        assertEquals("Pizza bestellen", e?.dishTitle)
        assertEquals(null, e?.recipeId)
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
    fun `clearSlot reloads from the server when the delete fails`() = runTest {
        coEvery { repository.getMealPlan(any(), any()) } returns Result.success(listOf(entry("2026-06-15", "DINNER", "r1", "Lasagne")))
        coEvery { repository.deleteMealSlot(any(), any()) } returns Result.failure(RuntimeException("offline"))

        val vm = createVm()
        advanceUntilIdle()
        assertEquals(1, vm.uiState.value.entries.size)

        vm.clearSlot("2026-06-15", "DINNER")
        advanceUntilIdle()

        // optimistic removal is reconciled back from the server reload, and an error surfaces
        assertEquals(1, vm.uiState.value.entries.size)
        assertTrue(vm.uiState.value.error != null)
    }

    @Test
    fun `meal-plan WS event reloads the visible week`() = runTest {
        val vm = createVm()
        advanceUntilIdle()

        mealPlanEvents.emit(MealPlanWebSocketClient.WsEvent.Changed)
        advanceUntilIdle()

        coVerify(atLeast = 2) { repository.getMealPlan(any(), any()) }
    }

    @Test
    fun `recipe WS event reloads recipes and the week (catches cascade deletes)`() = runTest {
        val vm = createVm()
        advanceUntilIdle()

        recipeEvents.emit(RecipeWebSocketClient.WsEvent.RecipeDeleted(recipe("r1", "Lasagne", emptyList())))
        advanceUntilIdle()

        coVerify(atLeast = 2) { repository.getRecipes() }
        coVerify(atLeast = 2) { repository.getMealPlan(any(), any()) }
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

    @Test
    fun `addWeekToShopping scales ingredient amounts by the chosen portions`() = runTest {
        // recipe authored for 2 servings; entry plans 4 → ×2
        val r = recipe("r1", "Lasagne", listOf(ingredient("Nudelplatten", 250.0, "g", 0), ingredient("Hackfleisch", 500.0, "g", 1)))
        coEvery { repository.getRecipes() } returns Result.success(listOf(r))
        coEvery { repository.getMealPlan(any(), any()) } returns Result.success(listOf(entry("2026-06-15", "DINNER", "r1", "Lasagne", servings = 4)))
        val lines: CapturingSlot<List<ShoppingLineInput>> = slot()
        coEvery { repository.addToShopping(any(), capture(lines)) } returns Result.success(BatchAddShoppingResponse(added = 2, merged = 0, skipped = 0))

        val vm = createVm()
        advanceUntilIdle()
        vm.addWeekToShopping("sl1") { _, _ -> }
        advanceUntilIdle()

        assertEquals(500.0, lines.captured.first { it.name == "Nudelplatten" }.amount!!, 0.001)
        assertEquals(1000.0, lines.captured.first { it.name == "Hackfleisch" }.amount!!, 0.001)
    }
}
