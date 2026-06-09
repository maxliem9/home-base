package com.homebase.android.data.repository

import com.homebase.android.data.api.HomeBaseApi
import com.homebase.android.data.model.ArchiveProjectRequest
import com.homebase.android.data.model.CreateProjectRequest
import com.homebase.android.data.model.CreateTimeEntryRequest
import com.homebase.android.data.model.ProjectDto
import com.homebase.android.data.model.StartTimerRequest
import com.homebase.android.data.model.UserDto
import com.homebase.android.data.model.StopTimerRequest
import com.homebase.android.data.model.TimeEntryDto
import com.homebase.android.data.model.UpdateTimeEntryRequest
import com.homebase.android.data.websocket.TimeWebSocketClient
import kotlinx.coroutines.flow.Flow
import org.json.JSONObject
import retrofit2.HttpException

class TimeRepository(
    private val api: HomeBaseApi,
    private val wsClient: TimeWebSocketClient,
) {
    val incomingEvents: Flow<TimeWebSocketClient.WsEvent> = wsClient.events

    suspend fun getProjects(): Result<List<ProjectDto>> = runCatching { api.getProjects() }

    /** Household members — used to resolve "the other user" for shared timers (#142). */
    suspend fun getUsers(): Result<List<UserDto>> = runCatching { api.getUsers() }

    suspend fun createProject(name: String, color: String): Result<ProjectDto> =
        runCatching { api.createProject(CreateProjectRequest(name, color)) }

    suspend fun setArchived(id: String, archived: Boolean): Result<ProjectDto> =
        runCatching { api.archiveProject(id, ArchiveProjectRequest(archived)) }

    suspend fun getEntries(): Result<List<TimeEntryDto>> = runCatching { api.getTimeEntries() }

    /** `userId` starts the timer on behalf of the partner (#142); null → self. */
    suspend fun startTimer(projectId: String, description: String?, userId: String? = null): Result<TimeEntryDto> =
        runCatching { api.startTimer(StartTimerRequest(projectId, description, userId)) }

    /** `userId` stops the partner's timer (#142); null → own timer. */
    suspend fun stopTimer(userId: String? = null): Result<TimeEntryDto> =
        runCatching { api.stopTimer(StopTimerRequest(userId)) }

    suspend fun createEntry(
        projectId: String,
        startedAt: String,
        stoppedAt: String,
        description: String?,
    ): Result<TimeEntryDto> =
        runCatching { api.createTimeEntry(CreateTimeEntryRequest(projectId, startedAt, stoppedAt, description)) }

    suspend fun updateEntry(id: String, request: UpdateTimeEntryRequest): Result<TimeEntryDto> =
        runCatching {
            try {
                api.updateTimeEntry(id, request)
            } catch (e: HttpException) {
                // Surface the backend's ErrorResponse.code as German text instead of a
                // raw "HTTP 409" so the edit sheet's failure toast is understandable.
                throw IllegalStateException(germanTimeError(e), e)
            }
        }

    suspend fun deleteEntry(id: String): Result<Unit> = runCatching { api.deleteTimeEntry(id) }

    fun connectWebSocket(token: String) = wsClient.connect(token)
    fun ensureWebSocketConnected() = wsClient.ensureConnected()
    fun disconnectWebSocket() = wsClient.disconnect()

    /** Map a failed entry-update response to German text via its ErrorResponse.code. */
    private fun germanTimeError(e: HttpException): String {
        val code = runCatching {
            e.response()?.errorBody()?.string()
                ?.let { JSONObject(it).optString("code").ifBlank { null } }
        }.getOrNull()
        return when (code) {
            "PROJECT_ARCHIVED" -> "Das Projekt ist archiviert."
            "INVALID_RANGE" -> "Das Ende muss nach dem Start liegen."
            "INVALID_DATE" -> "Ungültiges Datum."
            "NOT_FOUND" -> "Eintrag nicht gefunden – bitte neu laden."
            else -> "Konnte nicht gespeichert werden."
        }
    }
}
