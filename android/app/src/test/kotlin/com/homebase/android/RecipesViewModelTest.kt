package com.homebase.android

import com.homebase.android.data.model.RecipeDto
import com.homebase.android.data.repository.RecipesRepository
import com.homebase.android.data.websocket.RecipeWebSocketClient
import com.homebase.android.ui.recipes.RecipesViewModel
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
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

    private fun createVm() = RecipesViewModel(repository, "test-token")

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
