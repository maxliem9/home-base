package com.homebase.android.data.repository

import com.homebase.android.data.api.HomeBaseApi
import com.homebase.android.data.model.AbsenceStateDto
import com.homebase.android.data.model.CalendarEventDto
import com.homebase.android.data.model.CalendarFeedConfigResponse
import com.homebase.android.data.model.MealPlanEntryDto
import com.homebase.android.data.model.TodoDto
import com.homebase.android.data.model.UpdateCalendarFeedRequest
import com.homebase.android.data.websocket.AbsenceWebSocketClient
import com.homebase.android.data.websocket.EventWebSocketClient
import com.homebase.android.data.websocket.MealPlanWebSocketClient
import com.homebase.android.data.websocket.RecipeWebSocketClient
import com.homebase.android.data.websocket.TodoWebSocketClient
import kotlinx.coroutines.flow.Flow

/**
 * Read-only data layer for the Familienkalender month view (#435). Pure read aggregation over the
 * existing endpoints — no new schema: due todos, the absence snapshot (absences/kita), the meal-plan
 * range, and calendar events (#434). Owns its own dedicated WebSocket clients for every channel it
 * overlays (todos / absence / meal-plan / events) plus a recipes client — a recipe delete cascades
 * meal-plan rows away server-side but only broadcasts on the "recipes" channel (see MealPlanRepository),
 * so the calendar must watch it too. Each client is a separate instance from the feature screens'
 * (Aufgaben/Abwesenheit/Wochenplan), so connect/disconnect lifecycles don't collide.
 */
class CalendarRepository(
    private val api: HomeBaseApi,
    private val todoWsClient: TodoWebSocketClient,
    private val absenceWsClient: AbsenceWebSocketClient,
    private val mealPlanWsClient: MealPlanWebSocketClient,
    private val recipeWsClient: RecipeWebSocketClient,
    private val eventWsClient: EventWebSocketClient,
) {
    val todoEvents: Flow<TodoWebSocketClient.WsEvent> = todoWsClient.events
    val absenceEvents: Flow<AbsenceWebSocketClient.WsEvent> = absenceWsClient.events
    val mealPlanEvents: Flow<MealPlanWebSocketClient.WsEvent> = mealPlanWsClient.events
    val recipeEvents: Flow<RecipeWebSocketClient.WsEvent> = recipeWsClient.events
    val eventEvents: Flow<EventWebSocketClient.WsEvent> = eventWsClient.events

    // Todos + the absence snapshot are whole-collection reads (small, already visibility-filtered
    // server-side); only meals + events are range-scoped.
    suspend fun getTodos(): Result<List<TodoDto>> = apiCatching { api.getTodos() }

    suspend fun getAbsenceState(): Result<AbsenceStateDto> = apiCatching { api.getAbsenceState() }

    suspend fun getMealPlan(from: String, to: String): Result<List<MealPlanEntryDto>> =
        apiCatching { api.getMealPlan(from, to) }

    suspend fun getEvents(from: String, to: String): Result<List<CalendarEventDto>> =
        apiCatching { api.getEvents(from, to) }

    // --- iCal subscription feed config (#427/#488) --------------------------------------------
    // The caller's personal feed carries only the categories they pick here. Unset server-side =
    // all (back-compat). Thematically the calendar's own config, so it lives with the read
    // aggregation rather than in ConfigRepository — the Familienkalender VM already owns this repo.

    /** The caller's current feed category selection + the full available list. Falls back gracefully. */
    suspend fun getCalendarFeedConfig(): Result<CalendarFeedConfigResponse> =
        apiCatching { api.getCalendarFeed() }

    /**
     * Replace the caller's feed category selection (PUT /config/calendar-feed). Sends the full set;
     * an empty list means "nothing". The only 400 is an unknown section id — impossible from the UI,
     * which only ever toggles ids from availableSections — so it's mapped to a generic German message.
     */
    suspend fun updateCalendarFeedConfig(sections: List<String>): Result<CalendarFeedConfigResponse> =
        apiCatching(mapHttpError = {
            if (it.code() == 400) "Ungültige Auswahl." else "Konnte nicht gespeichert werden."
        }) { api.updateCalendarFeed(UpdateCalendarFeedRequest(sections)) }

    fun connectWebSocket(token: String) {
        todoWsClient.connect(token)
        absenceWsClient.connect(token)
        mealPlanWsClient.connect(token)
        recipeWsClient.connect(token)
        eventWsClient.connect(token)
    }

    fun ensureWebSocketConnected() {
        todoWsClient.ensureConnected()
        absenceWsClient.ensureConnected()
        mealPlanWsClient.ensureConnected()
        recipeWsClient.ensureConnected()
        eventWsClient.ensureConnected()
    }

    fun disconnectWebSocket() {
        todoWsClient.disconnect()
        absenceWsClient.disconnect()
        mealPlanWsClient.disconnect()
        recipeWsClient.disconnect()
        eventWsClient.disconnect()
    }
}
