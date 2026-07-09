package com.homebase.android

import com.homebase.android.data.cache.SnapshotStore
import com.homebase.android.data.familienkalender.CalendarSnapshot
import com.homebase.android.data.model.AbsenceStateDto
import com.homebase.android.data.model.CalendarEventDto
import com.homebase.android.data.model.MealPlanEntryDto
import com.homebase.android.data.model.TodoDto
import com.homebase.android.data.repository.CalendarRepository
import com.homebase.android.ui.familienkalender.FamilienkalenderViewModel
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

/**
 * Offline read-cache (#520) for the Familienkalender. The overlay is month-scoped: todos + the
 * absence-derived lists are date-bucketed (not month-scoped), while meals + events are fetched for the
 * visible month — so the cache only restores meals/events when the cached month matches.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FamilienkalenderViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var repository: CalendarRepository

    private fun todo(id: String, date: String?) = TodoDto(
        id = id, title = "T-$id", status = "PLANNED", dueDate = date, assignees = emptyList(),
        createdBy = "alice", createdAt = "2026-06-01T08:00:00Z", updatedAt = "2026-06-01T08:00:00Z",
    )

    private fun event(id: String, date: String) = CalendarEventDto(
        id = id, title = "E-$id", date = date, allDay = true, createdBy = "alice", createdAt = "2026-06-01T08:00:00Z",
    )

    private fun meal(id: String, date: String) = MealPlanEntryDto(
        id = id, date = date, slot = "DINNER", dishTitle = "M-$id", createdBy = "alice", createdAt = "2026-06-01T08:00:00Z",
    )

    /** The first-of-month the VM anchors to on a fresh launch (matches FamilienkalenderUiState default). */
    private val currentMonth = LocalDate.now().withDayOfMonth(1).toString()
    private val currentDay = LocalDate.now().withDayOfMonth(10).toString()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        repository = mockk(relaxed = true)
        // The VM collects five WS channels on init — stub them so the collectors park (never emit).
        every { repository.todoEvents } returns emptyFlow()
        every { repository.absenceEvents } returns emptyFlow()
        every { repository.mealPlanEvents } returns emptyFlow()
        every { repository.recipeEvents } returns emptyFlow()
        every { repository.eventEvents } returns emptyFlow()
        coEvery { repository.getTodos() } returns Result.success(emptyList())
        coEvery { repository.getAbsenceState() } returns Result.success(AbsenceStateDto())
        coEvery { repository.getMealPlan(any(), any()) } returns Result.success(emptyList())
        coEvery { repository.getEvents(any(), any()) } returns Result.success(emptyList())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** In-memory [SnapshotStore] standing in for the SharedPreferences-backed read-cache (#520). */
    private class FakeSnapshotStore(var data: CalendarSnapshot? = null) : SnapshotStore<CalendarSnapshot> {
        override suspend fun load(): CalendarSnapshot? = data
        override suspend fun save(snapshot: CalendarSnapshot) { data = snapshot }
    }

    private fun createVm(snapshotStore: SnapshotStore<CalendarSnapshot>? = null) =
        FamilienkalenderViewModel(repository, "test-token", snapshotStore = snapshotStore)

    @Test
    fun `cold start with no connection seeds the cached overlay for the current month`() = runTest {
        coEvery { repository.getTodos() } returns Result.failure(java.io.IOException("offline"))
        coEvery { repository.getAbsenceState() } returns Result.failure(java.io.IOException("offline"))
        coEvery { repository.getMealPlan(any(), any()) } returns Result.failure(java.io.IOException("offline"))
        coEvery { repository.getEvents(any(), any()) } returns Result.failure(java.io.IOException("offline"))
        val cache = FakeSnapshotStore(
            CalendarSnapshot(
                monthAnchor = currentMonth,
                todos = listOf(todo("t1", currentDay)),
                meals = listOf(meal("m1", currentDay)),
                events = listOf(event("e1", currentDay)),
            ),
        )

        val vm = createVm(snapshotStore = cache)
        advanceUntilIdle()

        assertEquals(listOf("t1"), vm.uiState.value.todos.map { it.id })
        assertEquals(listOf("m1"), vm.uiState.value.meals.map { it.id })
        assertEquals(listOf("e1"), vm.uiState.value.events.map { it.id })
        assertFalse(vm.uiState.value.isLoading)
        assertNull("offline refresh over cached data is not surfaced as an error", vm.uiState.value.error)
    }

    @Test
    fun `cached meals and events for a different month are not seeded`() = runTest {
        coEvery { repository.getTodos() } returns Result.failure(java.io.IOException("offline"))
        coEvery { repository.getMealPlan(any(), any()) } returns Result.failure(java.io.IOException("offline"))
        coEvery { repository.getEvents(any(), any()) } returns Result.failure(java.io.IOException("offline"))
        val lastMonth = LocalDate.parse(currentMonth).minusMonths(1)
        val cache = FakeSnapshotStore(
            CalendarSnapshot(
                monthAnchor = lastMonth.toString(),
                todos = listOf(todo("t1", currentDay)), // date-bucketed, not month-scoped
                meals = listOf(meal("m1", lastMonth.withDayOfMonth(10).toString())),
                events = listOf(event("e1", lastMonth.withDayOfMonth(10).toString())),
            ),
        )

        val vm = createVm(snapshotStore = cache)
        advanceUntilIdle()

        // Month-scoped meals/events for the wrong month must NOT seed...
        assertTrue("stale-month meals must not seed", vm.uiState.value.meals.isEmpty())
        assertTrue("stale-month events must not seed", vm.uiState.value.events.isEmpty())
        // ...but the (non-month-scoped) todos still seed.
        assertEquals(listOf("t1"), vm.uiState.value.todos.map { it.id })
    }

    @Test
    fun `a successful fetch wins over the cached overlay`() = runTest {
        coEvery { repository.getTodos() } returns Result.success(listOf(todo("fresh", currentDay)))
        val cache = FakeSnapshotStore(CalendarSnapshot(monthAnchor = currentMonth, todos = listOf(todo("stale", currentDay))))

        val vm = createVm(snapshotStore = cache)
        advanceUntilIdle()

        assertEquals(listOf("fresh"), vm.uiState.value.todos.map { it.id })
    }

    @Test
    fun `a successful load is mirrored into the cache`() = runTest {
        coEvery { repository.getTodos() } returns Result.success(listOf(todo("t1", currentDay)))
        coEvery { repository.getEvents(any(), any()) } returns Result.success(listOf(event("e1", currentDay)))
        val cache = FakeSnapshotStore()

        val vm = createVm(snapshotStore = cache)
        advanceUntilIdle()

        assertEquals(currentMonth, cache.data?.monthAnchor)
        assertEquals(listOf("t1"), cache.data?.todos?.map { it.id })
        assertEquals(listOf("e1"), cache.data?.events?.map { it.id })
    }

    @Test
    fun `an offline cold start does not overwrite the cache with an empty snapshot`() = runTest {
        coEvery { repository.getTodos() } returns Result.failure(java.io.IOException("offline"))
        coEvery { repository.getMealPlan(any(), any()) } returns Result.failure(java.io.IOException("offline"))
        coEvery { repository.getEvents(any(), any()) } returns Result.failure(java.io.IOException("offline"))
        val cached = CalendarSnapshot(monthAnchor = currentMonth, todos = listOf(todo("t1", currentDay)), events = listOf(event("e1", currentDay)))
        val cache = FakeSnapshotStore(cached)

        val vm = createVm(snapshotStore = cache)
        advanceUntilIdle()

        assertEquals(listOf("t1"), cache.data?.todos?.map { it.id })
        assertEquals(listOf("e1"), cache.data?.events?.map { it.id })
    }
}
