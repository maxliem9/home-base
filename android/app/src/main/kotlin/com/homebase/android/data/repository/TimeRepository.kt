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
import com.homebase.android.data.model.UpsertWorkTargetRequest
import com.homebase.android.data.model.UserDto
import com.homebase.android.data.model.StopTimerRequest
import com.homebase.android.data.model.TimeEntryDto
import com.homebase.android.data.model.UpdateTimeEntryRequest
import com.homebase.android.data.model.WorkTargetDto
import com.homebase.android.data.websocket.TimeWebSocketClient
import kotlinx.coroutines.flow.Flow
import org.json.JSONObject
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

    suspend fun setArchived(id: String, archived: Boolean): Result<ProjectDto> =
        apiCatching { api.archiveProject(id, ArchiveProjectRequest(archived)) }

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
        apiCatching(mapHttpError = ::germanTimeError) { api.updateTimeEntry(id, request) }

    suspend fun deleteEntry(id: String): Result<Unit> = apiCatching { api.deleteTimeEntry(id) }

    /**
     * Split a completed entry at [splitAt] with an optional untracked break (#66) —
     * both halves come back in one response (part one keeps the id).
     */
    suspend fun splitEntry(id: String, splitAt: String, breakMinutes: Int?): Result<SplitTimeEntryResponse> =
        apiCatching(mapHttpError = ::germanSplitError) { api.splitTimeEntry(id, SplitTimeEntryRequest(splitAt, breakMinutes)) }

    // --- Wochensoll & Forecast (#31 / #55) ---

    suspend fun getForecast(): Result<TimeForecastDto> = apiCatching { api.getTimeForecast() }

    suspend fun getTargets(): Result<List<WorkTargetDto>> = apiCatching { api.getWorkTargets() }

    /** Household-shared upsert: `userId` is the target person, not the caller. */
    suspend fun upsertTarget(
        userId: String,
        projectId: String,
        weeklyHours: Double? = null,
        isDefault: Boolean? = null,
    ): Result<WorkTargetDto> =
        apiCatching { api.upsertWorkTarget(userId, projectId, UpsertWorkTargetRequest(weeklyHours, isDefault)) }

    fun connectWebSocket(token: String) = wsClient.connect(token)
    fun ensureWebSocketConnected() = wsClient.ensureConnected()
    fun disconnectWebSocket() = wsClient.disconnect()

    /** Map a failed entry-update response to German text via its ErrorResponse.code. */
    private fun germanTimeError(e: HttpException): String = when (errorCodeOf(e)) {
        "PROJECT_ARCHIVED" -> "Das Projekt ist archiviert."
        "INVALID_RANGE" -> "Das Ende muss nach dem Start liegen."
        "INVALID_DATE" -> "Ungültiges Datum."
        "NOT_FOUND" -> "Eintrag nicht gefunden – bitte neu laden."
        else -> "Konnte nicht gespeichert werden."
    }

    /** Same for a failed split (#66) — wording mirrors the web errors map (de.ts). */
    private fun germanSplitError(e: HttpException): String = when (errorCodeOf(e)) {
        "ENTRY_RUNNING" -> "Laufende Timer können nicht gesplittet werden — erst stoppen."
        "INVALID_RANGE" -> "Das Ende muss nach dem Start liegen."
        "INVALID_DATE" -> "Ungültiges Datum."
        "NOT_FOUND" -> "Eintrag nicht gefunden – bitte neu laden."
        else -> "Eintrag konnte nicht gesplittet werden."
    }

    private fun errorCodeOf(e: HttpException): String? = runCatching {
        e.response()?.errorBody()?.string()
            ?.let { JSONObject(it).optString("code").ifBlank { null } }
    }.getOrNull()
}
