package com.homebase.android

import com.homebase.android.data.model.ProjectDto
import com.homebase.android.data.model.SplitTimeEntryResponse
import com.homebase.android.data.model.TimeEntryDto
import com.homebase.android.data.model.TimeForecastDto
import com.homebase.android.data.model.UpdateTimeEntryRequest
import com.homebase.android.data.model.UserDto
import com.homebase.android.data.model.UserForecastDto
import com.homebase.android.data.model.WorkTargetDto
import com.homebase.android.data.cache.SnapshotStore
import com.homebase.android.data.repository.TimeRepository
import com.homebase.android.data.time.TimeSnapshot
import com.homebase.android.data.websocket.TimeWebSocketClient
import com.homebase.android.ui.time.TargetChange
import com.homebase.android.ui.time.TimeViewModel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TimeViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var repository: TimeRepository
    private val wsEvents = MutableSharedFlow<TimeWebSocketClient.WsEvent>()

    /** Captures the WS "(re)connected" callback the VM registers, so a test can fire it like a reconnect (#268). */
    private val onConnectedSlot = slot<() -> Unit>()
    private fun fireWsReconnect() = onConnectedSlot.captured.invoke()

    private fun project(id: String = "p1", name: String = "Arbeit", archived: Boolean = false) =
        ProjectDto(id = id, name = name, color = "#4F46E5", archived = archived, createdBy = "alice", createdAt = "2026-01-01T00:00:00Z")

    private fun entry(
        id: String = "e1",
        projectId: String = "p1",
        userId: String = "alice",
        stoppedAt: String? = "2026-06-03T09:00:00Z",
        durationSeconds: Long? = 3600,
    ) = TimeEntryDto(
        id = id, projectId = projectId, userId = userId,
        startedAt = "2026-06-03T08:00:00Z", stoppedAt = stoppedAt,
        description = null, durationSeconds = durationSeconds,
        createdAt = "2026-06-03T08:00:00Z", updatedAt = "2026-06-03T09:00:00Z",
    )

    private fun userForecast(
        userId: String = "alice",
        weekTargetSeconds: Long = 144_000, // 40h
        expectedEndAt: String? = null,
    ) = UserForecastDto(
        userId = userId,
        weeklyTargetHours = weekTargetSeconds / 3600.0,
        workdayCount = 5.0,
        weekTargetSeconds = weekTargetSeconds,
        weekRecordedSeconds = 0,
        weekCreditedSeconds = 0,
        weekRemainingSeconds = weekTargetSeconds,
        todayTargetSeconds = weekTargetSeconds / 5,
        todayRecordedSeconds = 0,
        todayRemainingSeconds = weekTargetSeconds / 5,
        expectedEndAt = expectedEndAt,
    )

    private fun forecast(users: List<UserForecastDto> = emptyList()) =
        TimeForecastDto(date = "2026-06-10", weekStart = "2026-06-08", users = users)

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        repository = mockk(relaxed = true)
        every { repository.incomingEvents } returns wsEvents
        every { repository.setWebSocketOnConnected(capture(onConnectedSlot)) } returns Unit
        coEvery { repository.getProjects() } returns Result.success(emptyList())
        coEvery { repository.getEntries() } returns Result.success(emptyList())
        // load() also fetches users; stub it so the relaxed mock doesn't
        // return a bad default that breaks every test at construction time.
        coEvery { repository.getUsers() } returns Result.success(emptyList<UserDto>())
        // #55 added forecast + targets fetches to load() — same reasoning.
        coEvery { repository.getForecast() } returns Result.success(forecast())
        coEvery { repository.getTargets() } returns Result.success(emptyList())
        // #31 added a credits fetch to load()/syncFromServer() — Result is a value class,
        // so an unstubbed relaxed default is a broken cast; stub the empty happy path.
        coEvery { repository.getCredits(any(), any()) } returns Result.success(emptyList())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createVm(username: String? = "alice", snapshotStore: SnapshotStore<TimeSnapshot>? = null) =
        TimeViewModel(repository, "test-token", username, snapshotStore = snapshotStore)

    /** In-memory [SnapshotStore] standing in for the SharedPreferences-backed read-cache (#520). */
    private class FakeSnapshotStore(var data: TimeSnapshot? = null) : SnapshotStore<TimeSnapshot> {
        override suspend fun load(): TimeSnapshot? = data
        override suspend fun save(snapshot: TimeSnapshot) { data = snapshot }
    }

    // --- Offline read-cache (#520) -------------------------------------------------------------

    @Test
    fun `cold start with no connection seeds the cached projects and entries`() = runTest {
        coEvery { repository.getProjects() } returns Result.failure(java.io.IOException("offline"))
        coEvery { repository.getEntries() } returns Result.failure(java.io.IOException("offline"))
        coEvery { repository.getForecast() } returns Result.failure(java.io.IOException("offline"))
        val cache = FakeSnapshotStore(TimeSnapshot(projects = listOf(project()), entries = listOf(entry())))

        val vm = createVm(snapshotStore = cache)
        advanceUntilIdle()

        assertEquals(listOf("p1"), vm.uiState.value.projects.map { it.id })
        assertEquals(listOf("e1"), vm.uiState.value.entries.map { it.id })
        assertFalse(vm.uiState.value.isLoading)
        assertNull("offline refresh over cached data is not surfaced as an error", vm.uiState.value.error)
    }

    @Test
    fun `seeded entries derive the running timer offline`() = runTest {
        coEvery { repository.getProjects() } returns Result.failure(java.io.IOException("offline"))
        coEvery { repository.getEntries() } returns Result.failure(java.io.IOException("offline"))
        // an OPEN entry (no stoppedAt) for the current user
        val open = entry(id = "run", userId = "alice", stoppedAt = null, durationSeconds = null)
        val cache = FakeSnapshotStore(TimeSnapshot(projects = listOf(project()), entries = listOf(open)))

        val vm = createVm(username = "alice", snapshotStore = cache)
        advanceUntilIdle()

        assertEquals("running timer recomputed from the cached open entry", "run", vm.uiState.value.running?.id)
    }

    @Test
    fun `a successful fetch wins over the cached snapshot`() = runTest {
        coEvery { repository.getProjects() } returns Result.success(listOf(project(id = "fresh")))
        coEvery { repository.getEntries() } returns Result.success(listOf(entry(id = "efresh")))
        val cache = FakeSnapshotStore(TimeSnapshot(projects = listOf(project(id = "stale")), entries = listOf(entry(id = "estale"))))

        val vm = createVm(snapshotStore = cache)
        advanceUntilIdle()

        assertEquals(listOf("fresh"), vm.uiState.value.projects.map { it.id })
        assertEquals(listOf("efresh"), vm.uiState.value.entries.map { it.id })
    }

    @Test
    fun `a successful load is mirrored into the cache`() = runTest {
        coEvery { repository.getProjects() } returns Result.success(listOf(project(id = "p1")))
        coEvery { repository.getEntries() } returns Result.success(listOf(entry(id = "e1")))
        val cache = FakeSnapshotStore()

        val vm = createVm(snapshotStore = cache)
        advanceUntilIdle()

        assertEquals(listOf("p1"), cache.data?.projects?.map { it.id })
        assertEquals(listOf("e1"), cache.data?.entries?.map { it.id })
    }

    @Test
    fun `an offline cold start does not overwrite the cache with an empty snapshot`() = runTest {
        coEvery { repository.getProjects() } returns Result.failure(java.io.IOException("offline"))
        coEvery { repository.getEntries() } returns Result.failure(java.io.IOException("offline"))
        coEvery { repository.getForecast() } returns Result.failure(java.io.IOException("offline"))
        val cached = TimeSnapshot(projects = listOf(project()), entries = listOf(entry()))
        val cache = FakeSnapshotStore(cached)

        val vm = createVm(snapshotStore = cache)
        advanceUntilIdle()

        assertEquals(cached.entries.map { it.id }, cache.data?.entries?.map { it.id })
    }

    @Test
    fun `initial load populates projects and entries`() = runTest {
        coEvery { repository.getProjects() } returns Result.success(listOf(project()))
        coEvery { repository.getEntries() } returns Result.success(listOf(entry()))

        val vm = createVm()
        advanceUntilIdle()

        assertFalse(vm.uiState.value.isLoading)
        assertEquals(1, vm.uiState.value.projects.size)
        assertEquals(1, vm.uiState.value.entries.size)
        assertNull(vm.uiState.value.running)
    }

    @Test
    fun `running timer is derived from open entry for this user`() = runTest {
        coEvery { repository.getEntries() } returns Result.success(
            listOf(entry(id = "open", stoppedAt = null, durationSeconds = null))
        )

        val vm = createVm()
        advanceUntilIdle()

        assertEquals("open", vm.uiState.value.running?.id)
    }

    @Test
    fun `open entry from other user is not treated as my running timer`() = runTest {
        coEvery { repository.getEntries() } returns Result.success(
            listOf(entry(id = "bobs", userId = "bob", stoppedAt = null, durationSeconds = null))
        )

        val vm = createVm(username = "alice")
        advanceUntilIdle()

        assertNull(vm.uiState.value.running)
    }

    @Test
    fun `startTimer upserts returned entry and sets running`() = runTest {
        val started = entry(id = "new", stoppedAt = null, durationSeconds = null)
        coEvery { repository.startTimer("p1", null) } returns Result.success(started)

        val vm = createVm()
        advanceUntilIdle()

        vm.startTimer("p1", null)
        advanceUntilIdle()

        assertEquals("new", vm.uiState.value.running?.id)
        assertEquals(1, vm.uiState.value.entries.size)
    }

    @Test
    fun `stopTimer clears running by updating the entry`() = runTest {
        val open = entry(id = "open", stoppedAt = null, durationSeconds = null)
        coEvery { repository.getEntries() } returns Result.success(listOf(open))
        coEvery { repository.stopTimer() } returns Result.success(open.copy(stoppedAt = "2026-06-03T09:00:00Z", durationSeconds = 3600))

        val vm = createVm()
        advanceUntilIdle()
        assertNotNull(vm.uiState.value.running)

        vm.stopTimer()
        advanceUntilIdle()

        assertNull(vm.uiState.value.running)
        assertEquals(1, vm.uiState.value.entries.size)
    }

    @Test
    fun `addProject appends created project`() = runTest {
        coEvery { repository.createProject("Garten", "#10B981") } returns Result.success(project(id = "p2", name = "Garten"))

        val vm = createVm()
        advanceUntilIdle()

        vm.addProject("Garten", "#10B981")
        advanceUntilIdle()

        assertEquals(1, vm.uiState.value.projects.size)
        assertEquals("Garten", vm.uiState.value.projects[0].name)
    }

    @Test
    fun `addProject with blank name does nothing`() = runTest {
        val vm = createVm()
        advanceUntilIdle()

        vm.addProject("   ", "#10B981")
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.createProject(any(), any()) }
    }

    @Test
    fun `deleteEntry removes it`() = runTest {
        coEvery { repository.getEntries() } returns Result.success(listOf(entry(id = "e1")))
        coEvery { repository.deleteEntry("e1") } returns Result.success(Unit)

        val vm = createVm()
        advanceUntilIdle()

        vm.deleteEntry("e1")
        advanceUntilIdle()

        assertTrue(vm.uiState.value.entries.isEmpty())
    }

    @Test
    fun `updateEntry replaces the edited entry in place`() = runTest {
        coEvery { repository.getEntries() } returns Result.success(listOf(entry(id = "e1")))
        val edited = entry(id = "e1", stoppedAt = "2026-06-03T10:00:00Z", durationSeconds = 7200)
        coEvery { repository.updateEntry(eq("e1"), any()) } returns Result.success(edited)

        val vm = createVm()
        advanceUntilIdle()

        vm.updateEntry("e1", UpdateTimeEntryRequest(stoppedAt = "2026-06-03T10:00:00Z"))
        advanceUntilIdle()

        assertEquals(1, vm.uiState.value.entries.size)
        assertEquals("2026-06-03T10:00:00Z", vm.uiState.value.entries[0].stoppedAt)
        assertNull(vm.uiState.value.error)
    }

    @Test
    fun `updateEntry of a running timer keeps it running and surfaces no error`() = runTest {
        val open = entry(id = "open", stoppedAt = null, durationSeconds = null)
        coEvery { repository.getEntries() } returns Result.success(listOf(open))
        val moved = open.copy(startedAt = "2026-06-03T07:30:00Z")
        coEvery { repository.updateEntry(eq("open"), any()) } returns Result.success(moved)

        val vm = createVm()
        advanceUntilIdle()

        vm.updateEntry("open", UpdateTimeEntryRequest(startedAt = "2026-06-03T07:30:00Z"))
        advanceUntilIdle()

        assertEquals("open", vm.uiState.value.running?.id)
        assertEquals("2026-06-03T07:30:00Z", vm.uiState.value.running?.startedAt)
    }

    @Test
    fun `updateEntry surfaces the failure message`() = runTest {
        coEvery { repository.getEntries() } returns Result.success(listOf(entry(id = "e1")))
        coEvery { repository.updateEntry(eq("e1"), any()) } returns
            Result.failure(IllegalStateException("Das Ende muss nach dem Start liegen."))

        val vm = createVm()
        advanceUntilIdle()

        vm.updateEntry("e1", UpdateTimeEntryRequest(stoppedAt = "2026-06-03T07:00:00Z"))
        advanceUntilIdle()

        assertEquals("Das Ende muss nach dem Start liegen.", vm.uiState.value.error)
    }

    @Test
    fun `setArchived updates project flag in place`() = runTest {
        coEvery { repository.getProjects() } returns Result.success(listOf(project(id = "p1")))
        coEvery { repository.setArchived("p1", true) } returns Result.success(project(id = "p1", archived = true))

        val vm = createVm()
        advanceUntilIdle()

        vm.setArchived("p1", true)
        advanceUntilIdle()

        assertTrue(vm.uiState.value.projects[0].archived)
        assertTrue(vm.uiState.value.activeProjects.isEmpty())
    }

    // --- Projekt-Verwaltung (#175): rename/recolour + CSV export ---

    @Test
    fun `updateProject replaces the renamed-recoloured project in place`() = runTest {
        coEvery { repository.getProjects() } returns Result.success(listOf(project(id = "p1", name = "Alt")))
        coEvery { repository.updateProject("p1", "Neu", "#3F7C8C") } returns
            Result.success(project(id = "p1", name = "Neu").copy(color = "#3F7C8C"))

        val vm = createVm()
        advanceUntilIdle()

        vm.updateProject("p1", "Neu", "#3F7C8C")
        advanceUntilIdle()

        assertEquals(1, vm.uiState.value.projects.size)
        assertEquals("Neu", vm.uiState.value.projects[0].name)
        assertEquals("#3F7C8C", vm.uiState.value.projects[0].color)
        assertNull(vm.uiState.value.error)
    }

    @Test
    fun `updateProject trims the name before sending`() = runTest {
        coEvery { repository.getProjects() } returns Result.success(listOf(project(id = "p1")))
        coEvery { repository.updateProject("p1", "Garten", "#5B9E7A") } returns
            Result.success(project(id = "p1", name = "Garten"))

        val vm = createVm()
        advanceUntilIdle()

        vm.updateProject("p1", "  Garten  ", "#5B9E7A")
        advanceUntilIdle()

        coVerify { repository.updateProject("p1", "Garten", "#5B9E7A") }
    }

    @Test
    fun `updateProject with blank name does nothing`() = runTest {
        val vm = createVm()
        advanceUntilIdle()

        vm.updateProject("p1", "   ", "#5B9E7A")
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.updateProject(any(), any(), any()) }
    }

    @Test
    fun `updateProject surfaces the failure message`() = runTest {
        coEvery { repository.getProjects() } returns Result.success(listOf(project(id = "p1")))
        coEvery { repository.updateProject(any(), any(), any()) } returns
            Result.failure(IllegalStateException("Projekt konnte nicht gespeichert werden."))

        val vm = createVm()
        advanceUntilIdle()

        vm.updateProject("p1", "Neu", "#3F7C8C")
        advanceUntilIdle()

        assertEquals("Projekt konnte nicht gespeichert werden.", vm.uiState.value.error)
    }

    @Test
    fun `exportCsv hands the repository result to the callback`() = runTest {
        val bytes = "Projekt;Nutzer\r\n".toByteArray()
        coEvery { repository.exportCsv("2026-06-01T00:00:00Z", "2026-06-30T23:59:59Z", "p1") } returns
            Result.success(bytes)

        val vm = createVm()
        advanceUntilIdle()

        var received: Result<ByteArray>? = null
        vm.exportCsv("2026-06-01T00:00:00Z", "2026-06-30T23:59:59Z", "p1") { received = it }
        advanceUntilIdle()

        assertTrue(received!!.isSuccess)
        assertArrayEquals(bytes, received!!.getOrNull())
        coVerify { repository.exportCsv("2026-06-01T00:00:00Z", "2026-06-30T23:59:59Z", "p1") }
    }

    @Test
    fun `exportCsv forwards null filters for an unfiltered export`() = runTest {
        coEvery { repository.exportCsv(null, null, null) } returns Result.success(ByteArray(0))

        val vm = createVm()
        advanceUntilIdle()

        var received: Result<ByteArray>? = null
        vm.exportCsv(null, null, null) { received = it }
        advanceUntilIdle()

        assertTrue(received!!.isSuccess)
        coVerify { repository.exportCsv(null, null, null) }
    }

    @Test
    fun `exportCsv propagates a failure to the callback`() = runTest {
        coEvery { repository.exportCsv(any(), any(), any()) } returns
            Result.failure(RuntimeException("Keine Verbindung"))

        val vm = createVm()
        advanceUntilIdle()

        var received: Result<ByteArray>? = null
        vm.exportCsv(null, null, null) { received = it }
        advanceUntilIdle()

        assertTrue(received!!.isFailure)
    }

    @Test
    fun `WS EntryCreated adds entry`() = runTest {
        val vm = createVm()
        advanceUntilIdle()

        wsEvents.emit(TimeWebSocketClient.WsEvent.EntryCreated(entry(id = "ws-1")))
        advanceUntilIdle()

        assertEquals(1, vm.uiState.value.entries.size)
        assertEquals("ws-1", vm.uiState.value.entries[0].id)
    }

    @Test
    fun `WS ProjectUpdated replaces project`() = runTest {
        coEvery { repository.getProjects() } returns Result.success(listOf(project(id = "p1", name = "Alt")))

        val vm = createVm()
        advanceUntilIdle()

        wsEvents.emit(TimeWebSocketClient.WsEvent.ProjectUpdated(project(id = "p1", name = "Neu")))
        advanceUntilIdle()

        assertEquals("Neu", vm.uiState.value.projects[0].name)
    }

    // --- Wochensoll & Forecast (#31/#55) ---

    @Test
    fun `initial load populates forecast and targets`() = runTest {
        coEvery { repository.getForecast() } returns Result.success(forecast(listOf(userForecast("alice"))))
        coEvery { repository.getTargets() } returns
            Result.success(listOf(WorkTargetDto("alice", "p1", 40.0, true)))

        val vm = createVm()
        advanceUntilIdle()

        assertEquals("2026-06-08", vm.uiState.value.forecast?.weekStart)
        assertEquals(1, vm.uiState.value.targets.size)
    }

    @Test
    fun `forecast and targets failures stay silent (non-critical reads)`() = runTest {
        coEvery { repository.getForecast() } returns Result.failure(RuntimeException("down"))
        coEvery { repository.getTargets() } returns Result.failure(RuntimeException("down"))

        val vm = createVm()
        advanceUntilIdle()

        assertNull(vm.uiState.value.error)
        assertNull(vm.uiState.value.forecast)
        assertTrue(vm.uiState.value.targets.isEmpty())
        assertTrue(vm.uiState.value.weekUsers.isEmpty())
    }

    @Test
    fun `weekUsers and forecastFor only include people with a weekly target`() = runTest {
        coEvery { repository.getForecast() } returns Result.success(
            forecast(listOf(userForecast("alice", weekTargetSeconds = 144_000), userForecast("bob", weekTargetSeconds = 0)))
        )

        val vm = createVm()
        advanceUntilIdle()

        assertEquals(listOf("alice"), vm.uiState.value.weekUsers.map { it.userId })
        assertNotNull(vm.uiState.value.forecastFor("alice"))
        assertNull(vm.uiState.value.forecastFor("bob"))
        assertNull(vm.uiState.value.forecastFor(null))
    }

    @Test
    fun `entry WS event reloads the forecast`() = runTest {
        val vm = createVm()
        advanceUntilIdle()
        coEvery { repository.getForecast() } returns
            Result.success(forecast(listOf(userForecast("alice", expectedEndAt = "2026-06-10T15:30:00Z"))))

        wsEvents.emit(TimeWebSocketClient.WsEvent.EntryCreated(entry(id = "ws-1", stoppedAt = null, durationSeconds = null)))
        advanceUntilIdle()

        assertEquals("2026-06-10T15:30:00Z", vm.uiState.value.forecastFor("alice")?.expectedEndAt)
    }

    @Test
    fun `TARGET_UPDATED refetches targets and forecast`() = runTest {
        val vm = createVm()
        advanceUntilIdle()
        val target = WorkTargetDto("alice", "p1", 38.0, true)
        coEvery { repository.getTargets() } returns Result.success(listOf(target))
        coEvery { repository.getForecast() } returns Result.success(forecast(listOf(userForecast("alice"))))

        wsEvents.emit(TimeWebSocketClient.WsEvent.TargetUpdated(target))
        advanceUntilIdle()

        assertEquals(listOf(target), vm.uiState.value.targets)
        assertEquals(1, vm.uiState.value.weekUsers.size)
    }

    @Test
    fun `stopTimer refreshes the forecast without waiting for the WS echo`() = runTest {
        val open = entry(id = "open", stoppedAt = null, durationSeconds = null)
        coEvery { repository.getEntries() } returns Result.success(listOf(open))
        coEvery { repository.stopTimer() } returns Result.success(open.copy(stoppedAt = "2026-06-03T09:00:00Z", durationSeconds = 3600))

        val vm = createVm()
        advanceUntilIdle()

        vm.stopTimer()
        advanceUntilIdle()

        // once in load(), once after the stop succeeded
        coVerify(exactly = 2) { repository.getForecast() }
    }

    // --- #268: re-sync the running timer on WS reconnect / app resume ---

    @Test
    fun `WS reconnect re-syncs and clears a timer stopped elsewhere`() = runTest {
        // The socket was dead when the web/another device stopped the timer, so the
        // ENTRY_UPDATED never arrived. load() still shows it running.
        val open = entry(id = "open", stoppedAt = null, durationSeconds = null)
        coEvery { repository.getEntries() } returns Result.success(listOf(open))

        val vm = createVm()
        advanceUntilIdle()
        assertEquals("open", vm.uiState.value.running?.id)

        // Meanwhile the server now reports it stopped; on reconnect we refetch entries.
        coEvery { repository.getEntries() } returns
            Result.success(listOf(open.copy(stoppedAt = "2026-06-03T09:00:00Z", durationSeconds = 3600)))
        fireWsReconnect()
        advanceUntilIdle()

        assertNull(vm.uiState.value.running)
    }

    @Test
    fun `WS reconnect refetches entries`() = runTest {
        // load() fetches entries once; each onConnected (re)sync refetches them again, so the
        // running timer always re-derives from the server's view (#268).
        val vm = createVm()
        advanceUntilIdle()
        coVerify(exactly = 1) { repository.getEntries() }

        fireWsReconnect()
        advanceUntilIdle()

        coVerify(exactly = 2) { repository.getEntries() }
    }

    @Test
    fun `ensureConnected re-syncs and clears a timer stopped while backgrounded`() = runTest {
        // Socket survived the background (so no reconnect/onConnected), but a stop happened
        // elsewhere. Resume must refetch regardless and clear the stale running timer.
        val open = entry(id = "open", stoppedAt = null, durationSeconds = null)
        coEvery { repository.getEntries() } returns Result.success(listOf(open))

        val vm = createVm()
        advanceUntilIdle()
        assertEquals("open", vm.uiState.value.running?.id)

        coEvery { repository.getEntries() } returns
            Result.success(listOf(open.copy(stoppedAt = "2026-06-03T09:00:00Z", durationSeconds = 3600)))
        vm.ensureConnected()
        advanceUntilIdle()

        assertNull(vm.uiState.value.running)
        coVerify { repository.ensureWebSocketConnected() }
    }

    @Test
    fun `saveTargets PUTs each change and refetches targets and forecast`() = runTest {
        coEvery { repository.upsertTarget("alice", "p1", 38.0, null) } returns
            Result.success(WorkTargetDto("alice", "p1", 38.0, false))
        coEvery { repository.upsertTarget("bob", "p1", null, true) } returns
            Result.success(WorkTargetDto("bob", "p1", 0.0, true))

        val vm = createVm()
        advanceUntilIdle()
        coEvery { repository.getTargets() } returns Result.success(
            listOf(WorkTargetDto("alice", "p1", 38.0, false), WorkTargetDto("bob", "p1", 0.0, true))
        )

        vm.saveTargets(
            listOf(
                TargetChange("alice", "p1", weeklyHours = 38.0),
                TargetChange("bob", "p1", isDefault = true),
            )
        )
        advanceUntilIdle()

        coVerify { repository.upsertTarget("alice", "p1", 38.0, null) }
        coVerify { repository.upsertTarget("bob", "p1", null, true) }
        assertEquals(2, vm.uiState.value.targets.size)
        assertNull(vm.uiState.value.error)
    }

    @Test
    fun `saveTargets stamps forecastAt on success`() = runTest {
        // forecastAt is already set after load(); saveTargets must refresh it so the
        // live-tick counter resets to the new snapshot (#64, same as load/refreshForecast).
        coEvery { repository.upsertTarget("alice", "p1", 38.0, null) } returns
            Result.success(WorkTargetDto("alice", "p1", 38.0, false))
        coEvery { repository.getTargets() } returns Result.success(
            listOf(WorkTargetDto("alice", "p1", 38.0, false))
        )
        coEvery { repository.getForecast() } returns Result.success(forecast(listOf(userForecast("alice"))))

        val vm = createVm()
        advanceUntilIdle()

        val before = vm.uiState.value.forecastAt
        assertNotNull(before)

        vm.saveTargets(listOf(TargetChange("alice", "p1", weeklyHours = 38.0)))
        advanceUntilIdle()

        val after = vm.uiState.value.forecastAt
        assertNotNull(after)
        // Stamp must not move backwards (test dispatcher uses real wall-clock, so equality is fine).
        assertFalse(after!!.isBefore(before!!))
        // ...and getForecast() must have run a second time (load + saveTargets) — proves the stamp
        // was actually refreshed by saveTargets, not merely preserved from load().
        coVerify(exactly = 2) { repository.getForecast() }
    }

    @Test
    fun `saveTargets with no changes does nothing`() = runTest {
        val vm = createVm()
        advanceUntilIdle()

        vm.saveTargets(emptyList())
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.upsertTarget(any(), any(), any(), any()) }
    }

    @Test
    fun `saveTargets failure surfaces German error`() = runTest {
        coEvery { repository.upsertTarget(any(), any(), any(), any()) } returns
            Result.failure(RuntimeException("boom"))

        val vm = createVm()
        advanceUntilIdle()

        vm.saveTargets(listOf(TargetChange("alice", "p1", weeklyHours = 10.0)))
        advanceUntilIdle()

        assertEquals("Wochensoll konnte nicht gespeichert werden.", vm.uiState.value.error)
    }

    // --- Live-Tick (#64): forecastAt snapshot timestamp ---

    @Test
    fun `successful forecast load stamps forecastAt`() = runTest {
        val vm = createVm()
        advanceUntilIdle()

        assertNotNull(vm.uiState.value.forecastAt)
    }

    @Test
    fun `failed forecast load leaves forecastAt unset`() = runTest {
        coEvery { repository.getForecast() } returns Result.failure(RuntimeException("down"))

        val vm = createVm()
        advanceUntilIdle()

        assertNull(vm.uiState.value.forecastAt)
    }

    @Test
    fun `forecast refresh after an entry change moves forecastAt forward`() = runTest {
        coEvery { repository.getEntries() } returns Result.success(listOf(entry(id = "e1")))
        coEvery { repository.deleteEntry("e1") } returns Result.success(Unit)

        val vm = createVm()
        advanceUntilIdle()
        val initial = vm.uiState.value.forecastAt
        assertNotNull(initial)

        vm.deleteEntry("e1")
        advanceUntilIdle()

        val refreshed = vm.uiState.value.forecastAt
        assertNotNull(refreshed)
        assertFalse(refreshed!!.isBefore(initial!!))
    }

    // --- Eintrag splitten (#66) ---

    @Test
    fun `splitEntry applies both halves from the response and refreshes the forecast`() = runTest {
        val original = entry(id = "e1") // 08:00–09:00
        coEvery { repository.getEntries() } returns Result.success(listOf(original))
        val first = original.copy(stoppedAt = "2026-06-03T08:30:00Z", durationSeconds = 1800)
        val second = original.copy(
            id = "e2",
            startedAt = "2026-06-03T08:40:00Z",
            durationSeconds = 1200,
        )
        coEvery { repository.splitEntry("e1", "2026-06-03T08:30:00Z", 10) } returns
            Result.success(SplitTimeEntryResponse(first = first, second = second))

        val vm = createVm()
        advanceUntilIdle()

        vm.splitEntry("e1", "2026-06-03T08:30:00Z", 10)
        advanceUntilIdle()

        // part one replaced in place, part two added — no WS echo needed
        assertEquals(2, vm.uiState.value.entries.size)
        assertEquals("2026-06-03T08:30:00Z", vm.uiState.value.entries.first { it.id == "e1" }.stoppedAt)
        assertEquals("2026-06-03T08:40:00Z", vm.uiState.value.entries.first { it.id == "e2" }.startedAt)
        assertNull(vm.uiState.value.error)
        // once in load(), once after the split succeeded
        coVerify(exactly = 2) { repository.getForecast() }
    }

    @Test
    fun `splitEntry failure surfaces the German error`() = runTest {
        coEvery { repository.getEntries() } returns Result.success(listOf(entry(id = "e1")))
        coEvery { repository.splitEntry(any(), any(), any()) } returns
            Result.failure(IllegalStateException("Laufende Timer können nicht gesplittet werden — erst stoppen."))

        val vm = createVm()
        advanceUntilIdle()

        vm.splitEntry("e1", "2026-06-03T08:30:00Z", null)
        advanceUntilIdle()

        assertEquals("Laufende Timer können nicht gesplittet werden — erst stoppen.", vm.uiState.value.error)
        assertEquals(1, vm.uiState.value.entries.size)
    }
}
