package com.homebase.android

import com.homebase.android.data.cache.SnapshotStore
import com.homebase.android.data.model.RecipeDto
import com.homebase.android.data.recipes.RecipesSnapshot
import com.homebase.android.data.repository.RecipesRepository
import com.homebase.android.data.websocket.RecipeWebSocketClient
import com.homebase.android.ui.recipes.RecipesViewModel
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
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
class RecipesViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var repository: RecipesRepository
    private val wsEvents = MutableSharedFlow<RecipeWebSocketClient.WsEvent>()

    private fun recipe(id: String = "r1", title: String = "Lasagne", category: String = "DINNER") = RecipeDto(
        id = id,
        title = title,
        servings = 2,
        category = category,
        createdBy = "alice",
        createdAt = "2026-01-01T00:00:00Z",
        updatedAt = "2026-01-01T00:00:00Z",
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        repository = mockk(relaxed = true)
        every { repository.incomingEvents } returns wsEvents
        coEvery { repository.getRecipes(null) } returns Result.success(emptyList())
        coEvery { repository.getRecipes("DINNER") } returns Result.success(emptyList())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** In-memory [SnapshotStore] standing in for the SharedPreferences-backed read-cache (#520). */
    private class FakeSnapshotStore(var data: RecipesSnapshot? = null) : SnapshotStore<RecipesSnapshot> {
        override suspend fun load(): RecipesSnapshot? = data
        override suspend fun save(snapshot: RecipesSnapshot) { data = snapshot }
    }

    private fun createVm(snapshotStore: SnapshotStore<RecipesSnapshot>? = null) =
        RecipesViewModel(repository, "test-token", snapshotStore = snapshotStore)

    // --- Offline read-cache (#520) -------------------------------------------------------------

    @Test
    fun `cold start with no connection seeds the cached recipes`() = runTest {
        coEvery { repository.getRecipes(null) } returns Result.failure(java.io.IOException("offline"))
        val cache = FakeSnapshotStore(RecipesSnapshot(recipes = listOf(recipe(id = "r1", title = "Lasagne"))))

        val vm = createVm(snapshotStore = cache)
        advanceUntilIdle()

        assertEquals(listOf("Lasagne"), vm.uiState.value.recipes.map { it.title })
        assertFalse(vm.uiState.value.isLoading)
        assertNull("offline refresh over cached data is not surfaced as an error", vm.uiState.value.error)
    }

    @Test
    fun `a successful fetch wins over the cached snapshot`() = runTest {
        coEvery { repository.getRecipes(null) } returns Result.success(listOf(recipe(id = "r2", title = "Frisch")))
        val cache = FakeSnapshotStore(RecipesSnapshot(recipes = listOf(recipe(id = "r1", title = "STALE"))))

        val vm = createVm(snapshotStore = cache)
        advanceUntilIdle()

        assertEquals(listOf("Frisch"), vm.uiState.value.recipes.map { it.title })
    }

    @Test
    fun `a successful load is mirrored into the cache`() = runTest {
        coEvery { repository.getRecipes(null) } returns Result.success(listOf(recipe(id = "r1", title = "Lasagne")))
        val cache = FakeSnapshotStore()

        val vm = createVm(snapshotStore = cache)
        advanceUntilIdle()

        assertEquals(listOf("Lasagne"), cache.data?.recipes?.map { it.title })
    }

    @Test
    fun `a filtered (category) result does not overwrite the cache`() = runTest {
        coEvery { repository.getRecipes(null) } returns Result.success(listOf(recipe(id = "r1", title = "Lasagne", category = "DINNER"), recipe(id = "r2", title = "Pfannkuchen", category = "BREAKFAST")))
        coEvery { repository.getRecipes("DINNER") } returns Result.success(listOf(recipe(id = "r1", title = "Lasagne", category = "DINNER")))
        val cache = FakeSnapshotStore()

        val vm = createVm(snapshotStore = cache)
        advanceUntilIdle()
        assertEquals(listOf("Lasagne", "Pfannkuchen"), cache.data?.recipes?.map { it.title })

        vm.setCategoryFilter("DINNER")
        advanceUntilIdle()

        assertEquals(listOf("Lasagne"), vm.uiState.value.recipes.map { it.title })
        assertEquals("filtered result must not poison the cache", listOf("Lasagne", "Pfannkuchen"), cache.data?.recipes?.map { it.title })
    }

    @Test
    fun `an offline cold start does not overwrite the cache with an empty snapshot`() = runTest {
        coEvery { repository.getRecipes(null) } returns Result.failure(java.io.IOException("offline"))
        val cached = RecipesSnapshot(recipes = listOf(recipe(id = "r1")))
        val cache = FakeSnapshotStore(cached)

        val vm = createVm(snapshotStore = cache)
        advanceUntilIdle()

        assertEquals(cached.recipes.map { it.id }, cache.data?.recipes?.map { it.id })
    }

    @Test
    fun `WS RecipeCreated ignores recipes outside active category filter`() = runTest {
        val vm = createVm()
        advanceUntilIdle()

        vm.setCategoryFilter("DINNER")
        advanceUntilIdle()

        wsEvents.emit(RecipeWebSocketClient.WsEvent.RecipeCreated(recipe(id = "b1", category = "BREAKFAST")))
        advanceUntilIdle()

        assertTrue(vm.uiState.value.recipes.isEmpty())
    }

    @Test
    fun `WS RecipeUpdated removes recipe that no longer matches active category filter`() = runTest {
        coEvery { repository.getRecipes("DINNER") } returns Result.success(listOf(recipe(id = "d1", category = "DINNER")))

        val vm = createVm()
        advanceUntilIdle()

        vm.setCategoryFilter("DINNER")
        advanceUntilIdle()
        assertEquals(1, vm.uiState.value.recipes.size)

        wsEvents.emit(RecipeWebSocketClient.WsEvent.RecipeUpdated(recipe(id = "d1", category = "BREAKFAST")))
        advanceUntilIdle()

        assertTrue(vm.uiState.value.recipes.isEmpty())
    }
}
