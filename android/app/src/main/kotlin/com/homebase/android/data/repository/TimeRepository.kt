package com.homebase.android.data.repository

import com.homebase.android.data.api.HomeBaseApi
import com.homebase.android.data.model.ArchiveProjectRequest
import com.homebase.android.data.model.CreateProjectRequest
import com.homebase.android.data.model.CreateTimeEntryRequest
import com.homebase.android.data.model.ProjectDto
import com.homebase.android.data.model.StartTimerRequest
import com.homebase.android.data.model.TimeEntryDto
import com.homebase.android.data.model.UpdateTimeEntryRequest
import com.homebase.android.data.websocket.TimeWebSocketClient
import kotlinx.coroutines.flow.Flow
import retrofit2.HttpException

class TimeRepository(
    private val api: HomeBaseApi,
    private val wsClient: TimeWebSocketClient,
) {
    val incomingEvents: Flow<TimeWebSocketClient.WsEvent> = wsClient.events

    suspend fun getProjects(): Result<List<ProjectDto>> = runCatching { api.getProjects() }

    suspend fun createProject(name: String, color: String): Result<ProjectDto> =
        runCatching { api.createProject(CreateProjectRequest(name, color)) }

    suspend fun setArchived(id: String, archived: Boolean): Result<ProjectDto> =
        runCatching { api.archiveProject(id, ArchiveProjectRequest(archived)) }

    suspend fun getEntries(): Result<List<TimeEntryDto>> = runCatching { api.getTimeEntries() }

    /** Returns the caller's running timer, or null when none is active (404). */
    suspend fun getRunning(): Result<TimeEntryDto?> = runCatching {
        try {
            api.getRunningTimer()
        } catch (e: HttpException) {
            if (e.code() == 404) null else throw e
        }
    }

    suspend fun startTimer(projectId: String, description: String?): Result<TimeEntryDto> =
        runCatching { api.startTimer(StartTimerRequest(projectId, description)) }

    suspend fun stopTimer(): Result<TimeEntryDto> = runCatching { api.stopTimer() }

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
    fun disconnectWebSocket() = wsClient.disconnect()
}
