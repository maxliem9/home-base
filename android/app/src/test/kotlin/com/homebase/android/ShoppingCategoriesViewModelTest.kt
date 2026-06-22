package com.homebase.android

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.homebase.android.data.model.ShoppingCategoryDto
import com.homebase.android.data.model.ShoppingCategoryRuleDto
import com.homebase.android.data.model.UpdateShoppingCategoryRequest
import com.homebase.android.data.repository.ShoppingRepository
import com.homebase.android.data.websocket.ShoppingWebSocketClient
import com.homebase.android.ui.settings.ShoppingCategoriesViewModel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Tests for the Einkaufskategorien settings VM (#411): the catalog + rule fetch, the management
 * actions (create/update/delete category, reorder, rule upsert/delete) and the WS-driven refetch.
 *
 * Harness mirrors [ShoppingViewModelTest]'s backstop-hang avoidance: the VM is owned by a
 * [ViewModelStore] that is cleared inside the test body ([vmTest]) — that runs `onCleared()` →
 * `disconnectWebSocket()` and cancels `viewModelScope` (the forever-collecting WS coroutine) before
 * runTest's implicit final `advanceUntilIdle`, which would otherwise spin on it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ShoppingCategoriesViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var repository: ShoppingRepository
    private val wsEvents = MutableSharedFlow<ShoppingWebSocketClient.WsEvent>()

    private fun category(
        key: String,
        label: String = key,
        emoji: String = "🍎",
        sortOrder: Int = 0,
        isBuiltin: Boolean = false,
    ) = ShoppingCategoryDto(key = key, label = label, emoji = emoji, sortOrder = sortOrder, isBuiltin = isBuiltin)

    private fun rule(
        normalizedName: String,
        displayName: String = normalizedName,
        category: String = "OTHER",
        icon: String = "🛒",
    ) = ShoppingCategoryRuleDto(normalizedName = normalizedName, displayName = displayName, category = category, icon = icon)

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        repository = mockk(relaxed = true)
        every { repository.incomingEvents } returns wsEvents
        // init fetches both lists — default to empty unless a test overrides.
        coEvery { repository.getCategories() } returns Result.success(emptyList())
        coEvery { repository.getCategoryRules() } returns Result.success(emptyList())
    }

    private val vmStore = ViewModelStore()

    @After
    fun tearDown() {
        vmStore.clear()
        Dispatchers.resetMain()
    }

    /** runTest that always cancels the VM's coroutines before the implicit end-of-test drain. */
    private fun vmTest(body: suspend TestScope.() -> Unit) = runTest {
        try {
            body()
        } finally {
            vmStore.clear()
        }
    }

    private fun createVm(): ShoppingCategoriesViewModel {
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ShoppingCategoriesViewModel(repository = repository, token = "test-token") as T
        }
        return ViewModelProvider(vmStore, factory)[ShoppingCategoriesViewModel::class.java]
    }

    // --- Fetch -----------------------------------------------------------------------------------

    @Test
    fun `init fetches categories and rules into state`() = vmTest {
        coEvery { repository.getCategories() } returns Result.success(
            listOf(category("PRODUCE", "Obst & Gemüse", "🥦", 0, isBuiltin = true), category("GRILL", "Grillen", "🔥", 1)),
        )
        coEvery { repository.getCategoryRules() } returns Result.success(listOf(rule("milch", "Milch", "DAIRY", "🥛")))

        val vm = createVm()
        advanceUntilIdle()

        assertEquals(listOf("PRODUCE", "GRILL"), vm.uiState.value.categories.map { it.key })
        assertEquals(listOf("Milch"), vm.uiState.value.rules.map { it.displayName })
        assertEquals(false, vm.uiState.value.loading)
        assertNull(vm.uiState.value.error)
    }

    @Test
    fun `a failed categories fetch surfaces the error`() = vmTest {
        coEvery { repository.getCategories() } returns Result.failure(RuntimeException("Kaputt"))

        val vm = createVm()
        advanceUntilIdle()

        assertEquals("Kaputt", vm.uiState.value.error)
        assertEquals(false, vm.uiState.value.loading)
    }

    // --- Categories: create / update / delete / reorder ------------------------------------------

    @Test
    fun `saveCategory with no key creates and refetches`() = vmTest {
        coEvery { repository.createCategory(label = "Grillen", emoji = "🔥") } returns
            Result.success(category("GRILL", "Grillen", "🔥", 2))
        // Refetch after the create returns the new catalog.
        coEvery { repository.getCategories() } returnsMany listOf(
            Result.success(emptyList()),
            Result.success(listOf(category("GRILL", "Grillen", "🔥", 2))),
        )

        val vm = createVm()
        advanceUntilIdle()

        vm.saveCategory(key = null, label = "Grillen", emoji = "🔥")
        advanceUntilIdle()

        coVerify { repository.createCategory(label = "Grillen", emoji = "🔥") }
        assertEquals(listOf("GRILL"), vm.uiState.value.categories.map { it.key })
    }

    @Test
    fun `saveCategory with a key updates (label and emoji)`() = vmTest {
        val req = UpdateShoppingCategoryRequest(label = "Frisches", emoji = "🥬")
        coEvery { repository.updateCategory("PRODUCE", req) } returns
            Result.success(category("PRODUCE", "Frisches", "🥬", 0, isBuiltin = true))

        val vm = createVm()
        advanceUntilIdle()

        vm.saveCategory(key = "PRODUCE", label = "Frisches", emoji = "🥬")
        advanceUntilIdle()

        coVerify { repository.updateCategory("PRODUCE", req) }
    }

    @Test
    fun `saveCategory with a blank label no-ops`() = vmTest {
        val vm = createVm()
        advanceUntilIdle()

        vm.saveCategory(key = null, label = "   ", emoji = "🔥")
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.createCategory(any(), any(), any()) }
        coVerify(exactly = 0) { repository.updateCategory(any(), any()) }
    }

    @Test
    fun `deleteCategory deletes and refetches`() = vmTest {
        coEvery { repository.deleteCategory("GRILL") } returns Result.success(Unit)
        coEvery { repository.getCategories() } returnsMany listOf(
            Result.success(listOf(category("GRILL", "Grillen", "🔥", 1), category("OTHER", "Sonstiges", "❓", 9, isBuiltin = true))),
            Result.success(listOf(category("OTHER", "Sonstiges", "❓", 9, isBuiltin = true))),
        )

        val vm = createVm()
        advanceUntilIdle()
        assertEquals(2, vm.uiState.value.categories.size)

        vm.deleteCategory("GRILL")
        advanceUntilIdle()

        coVerify { repository.deleteCategory("GRILL") }
        assertEquals(listOf("OTHER"), vm.uiState.value.categories.map { it.key })
    }

    @Test
    fun `moveCategory swaps the two neighbours' sortOrder via PUT`() = vmTest {
        // Catalog: A(sortOrder=0), B(sortOrder=1). Moving A down (dir +1) must PUT A→1 and B→0.
        coEvery { repository.getCategories() } returns Result.success(
            listOf(category("A", "A", "🅰️", 0), category("B", "B", "🅱️", 1)),
        )
        coEvery { repository.updateCategory(any(), any()) } returns Result.success(category("A"))

        val vm = createVm()
        advanceUntilIdle()

        vm.moveCategory(index = 0, dir = 1)
        advanceUntilIdle()

        coVerify { repository.updateCategory("A", UpdateShoppingCategoryRequest(sortOrder = 1)) }
        coVerify { repository.updateCategory("B", UpdateShoppingCategoryRequest(sortOrder = 0)) }
    }

    @Test
    fun `moveCategory past the edge is a no-op`() = vmTest {
        coEvery { repository.getCategories() } returns Result.success(
            listOf(category("A", "A", "🅰️", 0), category("B", "B", "🅱️", 1)),
        )

        val vm = createVm()
        advanceUntilIdle()

        vm.moveCategory(index = 0, dir = -1) // already first → no neighbour above
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.updateCategory(any(), any()) }
    }

    // --- Rules: upsert / rename / delete --------------------------------------------------------

    @Test
    fun `saveRule upserts a new rule and refetches`() = vmTest {
        coEvery { repository.upsertCategoryRule(displayName = "Milch", category = "DAIRY", icon = "🥛") } returns
            Result.success(rule("milch", "Milch", "DAIRY", "🥛"))
        coEvery { repository.getCategoryRules() } returnsMany listOf(
            Result.success(emptyList()),
            Result.success(listOf(rule("milch", "Milch", "DAIRY", "🥛"))),
        )

        val vm = createVm()
        advanceUntilIdle()

        vm.saveRule(displayName = "Milch", category = "DAIRY", icon = "🥛")
        advanceUntilIdle()

        coVerify { repository.upsertCategoryRule(displayName = "Milch", category = "DAIRY", icon = "🥛") }
        // A pure add (no editingName) must NOT delete anything.
        coVerify(exactly = 0) { repository.deleteCategoryRule(any()) }
        assertEquals(listOf("Milch"), vm.uiState.value.rules.map { it.displayName })
    }

    @Test
    fun `saveRule that renames deletes the stale rule after the upsert`() = vmTest {
        coEvery { repository.upsertCategoryRule(displayName = "Vollmilch", category = "DAIRY", icon = "🥛") } returns
            Result.success(rule("vollmilch", "Vollmilch", "DAIRY", "🥛"))
        coEvery { repository.deleteCategoryRule("Milch") } returns Result.success(Unit)

        val vm = createVm()
        advanceUntilIdle()

        // Editing "Milch" → "Vollmilch": the upsert mints a new normalized key, so the old one is removed.
        vm.saveRule(displayName = "Vollmilch", category = "DAIRY", icon = "🥛", editingName = "Milch")
        advanceUntilIdle()

        coVerify { repository.upsertCategoryRule(displayName = "Vollmilch", category = "DAIRY", icon = "🥛") }
        coVerify { repository.deleteCategoryRule("Milch") }
    }

    @Test
    fun `saveRule that only changes the category keeps the same name and deletes nothing`() = vmTest {
        coEvery { repository.upsertCategoryRule(displayName = "Milch", category = "PRODUCE", icon = "🥛") } returns
            Result.success(rule("milch", "Milch", "PRODUCE", "🥛"))

        val vm = createVm()
        advanceUntilIdle()

        // Same display name (case-insensitive) → no stale-rule delete.
        vm.saveRule(displayName = "Milch", category = "PRODUCE", icon = "🥛", editingName = "milch")
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.deleteCategoryRule(any()) }
    }

    @Test
    fun `saveRule with a blank name no-ops`() = vmTest {
        val vm = createVm()
        advanceUntilIdle()

        vm.saveRule(displayName = "  ", category = "DAIRY", icon = "🥛")
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.upsertCategoryRule(any(), any(), any()) }
    }

    @Test
    fun `deleteRule deletes and refetches`() = vmTest {
        coEvery { repository.deleteCategoryRule("Milch") } returns Result.success(Unit)
        coEvery { repository.getCategoryRules() } returnsMany listOf(
            Result.success(listOf(rule("milch", "Milch", "DAIRY", "🥛"))),
            Result.success(emptyList()),
        )

        val vm = createVm()
        advanceUntilIdle()
        assertEquals(1, vm.uiState.value.rules.size)

        vm.deleteRule("Milch")
        advanceUntilIdle()

        coVerify { repository.deleteCategoryRule("Milch") }
        assertEquals(emptyList<String>(), vm.uiState.value.rules.map { it.displayName })
    }

    // --- WS-driven refetch ----------------------------------------------------------------------

    @Test
    fun `a category WS event refetches only the categories`() = vmTest {
        coEvery { repository.getCategories() } returnsMany listOf(
            Result.success(emptyList()),
            Result.success(listOf(category("GRILL", "Grillen", "🔥", 0))),
        )

        val vm = createVm()
        advanceUntilIdle()

        wsEvents.emit(ShoppingWebSocketClient.WsEvent.CategoryChanged)
        advanceUntilIdle()

        assertEquals(listOf("GRILL"), vm.uiState.value.categories.map { it.key })
        coVerify(atLeast = 2) { repository.getCategories() }
        // Rules were fetched only at init (the category event doesn't touch them).
        coVerify(exactly = 1) { repository.getCategoryRules() }
    }

    @Test
    fun `a rule WS event refetches only the rules`() = vmTest {
        coEvery { repository.getCategoryRules() } returnsMany listOf(
            Result.success(emptyList()),
            Result.success(listOf(rule("milch", "Milch", "DAIRY", "🥛"))),
        )

        val vm = createVm()
        advanceUntilIdle()

        wsEvents.emit(ShoppingWebSocketClient.WsEvent.CategoryRuleChanged)
        advanceUntilIdle()

        assertEquals(listOf("Milch"), vm.uiState.value.rules.map { it.displayName })
        coVerify(atLeast = 2) { repository.getCategoryRules() }
        coVerify(exactly = 1) { repository.getCategories() }
    }
}
