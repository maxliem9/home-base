package com.homebase.android.data.repository

import com.homebase.android.data.api.HomeBaseApi
import com.homebase.android.data.model.AbsenceStateDto
import com.homebase.android.data.model.BatchAbsenceRequest
import com.homebase.android.data.model.CreateCustomHolidayRequest
import com.homebase.android.data.model.CreateKitaRangeRequest
import com.homebase.android.data.model.CreateKitaRequest
import com.homebase.android.data.model.CreatePartTimeRequest
import com.homebase.android.data.model.SetAbsenceRequest
import com.homebase.android.data.model.UpdateAbsSettingsRequest
import com.homebase.android.data.model.UpdateCustomHolidayRequest
import com.homebase.android.data.model.UpdateKitaRequest
import com.homebase.android.data.model.UpdatePartTimeRequest
import com.homebase.android.data.websocket.AbsenceWebSocketClient
import kotlinx.coroutines.flow.Flow

/**
 * Absence planner persistence. The backend is pure storage; every mutation returns
 * a thin DTO, but the planner always re-reads the whole snapshot afterwards, so the
 * mutators here surface success/failure only and the ViewModel reloads on success.
 */
class AbsenceRepository(
    private val api: HomeBaseApi,
    private val wsClient: AbsenceWebSocketClient,
) {
    val incomingEvents: Flow<AbsenceWebSocketClient.WsEvent> = wsClient.events

    suspend fun getState(): Result<AbsenceStateDto> = apiCatching { api.getAbsenceState() }

    suspend fun setAbsence(userId: String, date: String, type: String, half: String?): Result<Unit> =
        apiCatching { api.setAbsence(SetAbsenceRequest(userId, date, type, half)) }

    suspend fun clearAbsence(userId: String, date: String): Result<Unit> =
        apiCatching { api.clearAbsence(userId, date) }

    /** Apply [type] (or clear, when null) on an explicit list of dates. */
    suspend fun batchAbsence(userId: String, type: String?, half: String?, dates: List<String>): Result<Unit> =
        apiCatching { api.batchAbsence(BatchAbsenceRequest(userId, type, half, dates)) }

    suspend fun addPartTime(userId: String, weekday: Int, start: String, end: String?): Result<Unit> =
        apiCatching { api.createPartTime(CreatePartTimeRequest(userId, weekday, start, end)) }

    suspend fun updatePartTime(id: String, weekday: Int, start: String, end: String?): Result<Unit> =
        apiCatching { api.updatePartTime(id, UpdatePartTimeRequest(weekday, start, end)) }

    suspend fun removePartTime(id: String): Result<Unit> =
        apiCatching { api.deletePartTime(id) }

    suspend fun addKita(date: String, label: String?): Result<Unit> =
        apiCatching { api.createKita(CreateKitaRequest(date, label)) }

    suspend fun addKitaRange(from: String, to: String, label: String?): Result<Unit> =
        apiCatching { api.createKitaRange(CreateKitaRangeRequest(from, to, label)) }

    suspend fun updateKita(id: String, date: String?, label: String?): Result<Unit> =
        apiCatching { api.updateKita(id, UpdateKitaRequest(date, label)) }

    suspend fun removeKita(id: String): Result<Unit> =
        apiCatching { api.deleteKita(id) }

    suspend fun addCustomHoliday(month: Int, day: Int, half: Boolean, label: String?): Result<Unit> =
        apiCatching { api.createCustomHoliday(CreateCustomHolidayRequest(month, day, half, label)) }

    suspend fun updateCustomHoliday(id: String, month: Int?, day: Int?, half: Boolean?, label: String?): Result<Unit> =
        apiCatching { api.updateCustomHoliday(id, UpdateCustomHolidayRequest(month, day, half, label)) }

    suspend fun removeCustomHoliday(id: String): Result<Unit> =
        apiCatching { api.deleteCustomHoliday(id) }

    suspend fun updateSettings(userId: String, year: Int, request: UpdateAbsSettingsRequest): Result<Unit> =
        apiCatching { api.updateAbsSettings(userId, year, request) }

    fun connectWebSocket(token: String) = wsClient.connect(token)
    fun ensureWebSocketConnected() = wsClient.ensureConnected()
    fun disconnectWebSocket() = wsClient.disconnect()
}
