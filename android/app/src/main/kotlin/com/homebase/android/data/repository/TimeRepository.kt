package com.homebase.android.data.repository

import com.homebase.android.data.api.HomeBaseApi
import com.homebase.android.data.model.ArchiveProjectRequest
import com.homebase.android.data.model.CreateProjectRequest
import com.homebase.android.data.model.CreateTimeEntryRequest
import com.homebase.android.data.model.ProjectDto
import com.homebase.android.data.model.SplitTimeEntryRequest
import com.homebase.android.data.model.SplitTimeEntryResponse
import com.homebase.android.data.model.StartTimerRequest
import com.homebase.android.data.model.TimeForecastDto
import com.homebase.android.data.model.CreateTargetPeriodRequest
import com.homebase.android.data.model.UpsertWorkTargetRequest
import com.homebase.android.data.model.UserDto
import com.homebase.android.data.model.StopTimerRequest
import com.homebase.android.data.model.TimeCreditDto
import com.homebase.android.data.model.TimeEntryDto
import com.homebase.android.data.model.UpdateProjectRequest
import com.homebase.android.data.model.UpdateTimeEntryRequest
import com.homebase.android.data.model.WorkTargetDto
import com.homebase.android.data.websocket.TimeWebSocketClient
import kotlinx.coroutines.flow.Flow
import kotlin.coroutines.cancellation.CancellationException
import retrofit2.HttpException

class TimeRepository(
    private val api: HomeBaseApi,
    private val wsClient: TimeWebSocketClient,
) {
    val incomingEvents: Flow<TimeWebSocketClient.WsEvent> = wsClient.events

    suspend fun getProjects(): Result<List<ProjectDto>> = apiCatching { api.getProjects() }

    /** Household members — used to resolve "the other user" for shared timers. */
    suspend fun getUsers(): Result<List<UserDto>> = apiCatching { api.getUsers() }

    suspend fun createProject(name: String, color: String): Result<ProjectDto> =
        apiCatching { api.createProject(CreateProjectRequest(name, color)) }

    /** Rename / recolour a project (PUT). Both fields are always sent (web parity). */
    suspend fun updateProject(id: String, name: String, color: String): Result<ProjectDto> =
        apiCatching(mapHttpError = ::projectError) { api.updateProject(id, UpdateProjectRequest(name, color)) }

    suspend fun setArchived(id: String, archived: Boolean): Result<ProjectDto> =
        apiCatching(mapHttpError = ::projectError) { api.archiveProject(id, ArchiveProjectRequest(archived)) }

    suspend fun getEntries(): Result<List<TimeEntryDto>> = apiCatching { api.getTimeEntries() }

    /** `userId` starts the timer on behalf of the partner; null → self. */
    suspend fun startTimer(projectId: String, description: String?, userId: String? = null): Result<TimeEntryDto> =
        apiCatching { api.startTimer(StartTimerRequest(projectId, description, userId)) }

    /** `userId` stops the partner's timer; null → own timer. */
    suspend fun stopTimer(userId: String? = null): Result<TimeEntryDto> =
        apiCatching { api.stopTimer(StopTimerRequest(userId)) }

    /** `userId` records the entry for the partner (shared household); null → self. */
    suspend fun createEntry(
        projectId: String,
        startedAt: String,
        stoppedAt: String,
        description: String?,
        userId: String? = null,
    ): Result<TimeEntryDto> =
        apiCatching { api.createTimeEntry(CreateTimeEntryRequest(projectId, startedAt, stoppedAt, description, userId)) }

    // Surface the backend's ErrorResponse.code as German text instead of a
    // raw "HTTP 409" so the edit sheet's failure toast is understandable.
    suspend fun updateEntry(id: String, request: UpdateTimeEntryRequest): Result<TimeEntryDto> =
        apiCatching(mapHttpError = ::timeError) { api.updateTimeEntry(id, request) }

    suspend fun deleteEntry(id: String): Result<Unit> = apiCatching { api.deleteTimeEntry(id) }

    /**
     * Split an entry — completed or running (#634) — at [splitAt] with an optional untracked break (#66);
     * both halves come back in one response (part one keeps the id).
     */
    suspend fun splitEntry(id: String, splitAt: String, breakMinutes: Int?): Result<SplitTimeEntryResponse> =
        apiCatching(mapHttpError = ::splitError) { api.splitTimeEntry(id, SplitTimeEntryRequest(splitAt, breakMinutes)) }

    // --- Wochensoll & Forecast (#31 / #55) ---

    suspend fun getForecast(): Result<TimeForecastDto> = apiCatching { api.getTimeForecast() }

    /** Absence/holiday work credits over [from]..[to] (YYYY-MM-DD, both inclusive, #31). */
    suspend fun getCredits(from: String, to: String): Result<List<TimeCreditDto>> =
        apiCatching { api.getTimeCredits(from, to) }

    suspend fun getTargets(): Result<List<WorkTargetDto>> = apiCatching { api.getWorkTargets() }

    /** Household-shared upsert: `userId` is the target person, not the caller. */
    suspend fun upsertTarget(
        userId: String,
        projectId: String,
        weeklyHours: Double? = null,
        isDefault: Boolean? = null,
        validFrom: String? = null,
    ): Result<WorkTargetDto> =
        apiCatching { api.upsertWorkTarget(userId, projectId, UpsertWorkTargetRequest(weeklyHours, isDefault, validFrom)) }

    /**
     * Schedule a new Wochensoll period (#31), seeded server-side from the effective one.
     * A 409 (this person already has the period) is tolerated: the household-wide create
     * loops over both people and one may already have it.
     */
    suspend fun createTargetPeriod(userId: String, validFrom: String): Result<Unit> =
        tolerating(409) { api.createTargetPeriod(userId, CreateTargetPeriodRequest(validFrom)) }

    /**
     * Delete a whole Wochensoll period for a person. A 404 (no such period) is tolerated:
     * only one person may have configured the period the household-wide delete targets.
     */
    suspend fun deleteTargetPeriod(userId: String, validFrom: String): Result<Unit> =
        tolerating(404) { api.deleteTargetPeriod(userId, validFrom) }

    /** apiCatching that also maps an HTTP [tolerated] status to success (period create/delete). */
    private suspend fun tolerating(tolerated: Int, block: suspend () -> Unit): Result<Unit> = try {
        block()
        Result.success(Unit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: HttpException) {
        if (e.code() == tolerated) Result.success(Unit) else Result.failure(mapApiError(e))
    } catch (e: Throwable) {
        Result.failure(mapApiError(e))
    }

    /**
     * Download the server-rendered CSV export of completed entries as raw bytes
     * (the JWT travels in the auth header, like every other call). Optional
     * date-range / project filters mirror the entry list; the screen turns the
     * bytes into a cached file + system share-sheet (it owns the Android Context).
     */
    suspend fun exportCsv(from: String?, to: String?, projectId: String?): Result<ByteArray> =
        apiCatching { api.exportTimeCsv(from, to, projectId).use { it.bytes() } }

    fun connectWebSocket(token: String) = wsClient.connect(token)
    fun ensureWebSocketConnected() = wsClient.ensureConnected()
    fun disconnectWebSocket() = wsClient.disconnect()

    /**
     * Register a "socket (re)connected, server reachable" callback (#268). The ViewModel uses it to
     * re-sync entries/forecast after a drop, so a timer stopped elsewhere while we were offline (and
     * whose ENTRY_UPDATED we missed) clears instead of showing stale. Mirrors the shopping channel.
     */
    fun setWebSocketOnConnected(onConnected: (() -> Unit)?) {
        wsClient.onConnected = onConnected
    }

    /** Map a failed entry-update response to a typed [AppError] via its ErrorResponse.code. */
    private fun timeError(e: HttpException): AppError = when (errorCodeOf(e)) {
        "PROJECT_ARCHIVED" -> AppError.TIME_PROJECT_ARCHIVED
        "INVALID_RANGE" -> AppError.TIME_INVALID_RANGE
        "INVALID_DATE" -> AppError.INVALID_DATE
        "NOT_FOUND" -> AppError.TIME_ENTRY_NOT_FOUND
        else -> AppError.SAVE_FAILED
    }

    /** Map a failed project create/update/archive to a typed [AppError]. */
    private fun projectError(e: HttpException): AppError = when (errorCodeOf(e)) {
        "INVALID_PROJECT" -> AppError.NAME_REQUIRED
        "INVALID_COLOR" -> AppError.INVALID_COLOR
        "NOT_FOUND" -> AppError.PROJECT_NOT_FOUND
        else -> AppError.PROJECT_SAVE_FAILED
    }

    /** Same for a failed split (#66). */
    private fun splitError(e: HttpException): AppError = when (errorCodeOf(e)) {
        "INVALID_RANGE" -> AppError.TIME_INVALID_RANGE
        "INVALID_DATE" -> AppError.INVALID_DATE
        "NOT_FOUND" -> AppError.TIME_ENTRY_NOT_FOUND
        else -> AppError.SPLIT_FAILED
    }
}
