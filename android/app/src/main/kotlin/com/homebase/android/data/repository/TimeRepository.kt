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
        runCatching { api.updateTimeEntry(id, request) }

    suspend fun deleteEntry(id: String): Result<Unit> = runCatching { api.deleteTimeEntry(id) }

    fun connectWebSocket(token: String) = wsClient.connect(token)
    fun ensureWebSocketConnected() = wsClient.ensureConnected()
    fun disconnectWebSocket() = wsClient.disconnect()
}
