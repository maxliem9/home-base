package com.homebase.android.data.repository

import com.homebase.android.data.api.HomeBaseApi
import com.homebase.android.data.model.BatchAddShoppingResponse
import com.homebase.android.data.model.MealPlanEntryDto
import com.homebase.android.data.model.RecipeDto
import com.homebase.android.data.model.SetMealPlanRequest
import com.homebase.android.data.model.ShoppingLineInput
import com.homebase.android.data.model.ShoppingListDto
import com.homebase.android.data.websocket.MealPlanWebSocketClient
import com.homebase.android.data.websocket.RecipeWebSocketClient
import kotlinx.coroutines.flow.Flow

/**
 * Wochenplan data (#250). Owns its own meal-plan WebSocket AND a dedicated recipe WebSocket: a recipe
 * delete cascades the plan rows away on the server but only broadcasts on the "recipes" channel, so
 * the planner must watch both (the recipe client is a separate instance from the Recipes feature's, so
 * connect/disconnect lifecycles don't collide). Recipe list + shopping reads are proxied through the
 * shared [api] so the ViewModel needs only this one repository.
 */
class MealPlanRepository(
    private val api: HomeBaseApi,
    private val mealPlanWsClient: MealPlanWebSocketClient,
    private val recipeWsClient: RecipeWebSocketClient,
) {
    /** A meal-plan mutation happened — reload the visible range. */
    val mealPlanEvents: Flow<MealPlanWebSocketClient.WsEvent> = mealPlanWsClient.events

    /** A recipe changed/was deleted — reload recipes (and the range, to catch cascade deletes). */
    val recipeEvents: Flow<RecipeWebSocketClient.WsEvent> = recipeWsClient.events

    suspend fun getMealPlan(from: String, to: String): Result<List<MealPlanEntryDto>> =
        apiCatching { api.getMealPlan(from, to) }

    /** Set a slot to EITHER a recipe OR a free-text dish (#293) — pass exactly one of recipeId/dishTitle. */
    suspend fun setMealSlot(date: String, slot: String, recipeId: String?, dishTitle: String?, servings: Int?): Result<MealPlanEntryDto> =
        apiCatching { api.setMealSlot(date, slot, SetMealPlanRequest(recipeId = recipeId, dishTitle = dishTitle, servings = servings)) }

    suspend fun deleteMealSlot(date: String, slot: String): Result<Unit> =
        apiCatching { api.deleteMealSlot(date, slot) }

    /** Full recipe list (with ingredients) — for the picker and the shopping aggregation. */
    suspend fun getRecipes(): Result<List<RecipeDto>> = apiCatching { api.getRecipes(null) }

    suspend fun getShoppingLists(): Result<List<ShoppingListDto>> = apiCatching { api.getShoppingLists() }

    suspend fun addToShopping(listId: String?, lines: List<ShoppingLineInput>): Result<BatchAddShoppingResponse> =
        apiCatching { api.batchAddShoppingItems(com.homebase.android.data.model.BatchAddShoppingRequest(listId, lines)) }

    fun connectWebSocket(token: String) {
        mealPlanWsClient.connect(token)
        recipeWsClient.connect(token)
    }

    fun ensureWebSocketConnected() {
        mealPlanWsClient.ensureConnected()
        recipeWsClient.ensureConnected()
    }

    fun disconnectWebSocket() {
        mealPlanWsClient.disconnect()
        recipeWsClient.disconnect()
    }
}
