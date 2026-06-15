package com.homebase.routes

import com.homebase.db.MealPlanEntriesTable
import com.homebase.db.RecipesTable
import com.homebase.model.*
import com.homebase.ws.WsSessionManager
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import com.homebase.plugins.appJson
import kotlinx.serialization.encodeToString
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

private const val MEAL_PLAN_WS_CHANNEL = "meal-plan"

// The three weekly-grid meal slots. Deliberately independent of the recipe categories
// (which dropped LUNCH in V17): any recipe may be planned into any slot. See #218.
private val MEAL_SLOTS = setOf("BREAKFAST", "LUNCH", "DINNER")

// Guard rail: a single range query may span at most ~one year (inclusive), so a stray/huge
// range can't pull the whole table. The weekly view only ever asks for 7 days.
private const val MAX_RANGE_DAYS = 370

fun Route.mealPlanRoutes() {
    suspend fun notify() =
        WsSessionManager.broadcast(MEAL_PLAN_WS_CHANNEL, appJson.encodeToString(MealPlanWsMessage("MEAL_PLAN_CHANGED")))

    route("/meal-plan") {

        // Entries within an inclusive [from, to] date range (the weekly view fetches Mon..Sun).
        // The recipe title/category are joined in so the grid renders without a second fetch.
        get {
            val from = call.request.queryParameters["from"]?.let { parseDate(it) } ?: return@get call.invalidRange()
            val to = call.request.queryParameters["to"]?.let { parseDate(it) } ?: return@get call.invalidRange()
            if (from.isAfter(to)) return@get call.invalidRange()
            if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_RANGE_DAYS) {
                return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("RANGE_TOO_LARGE", "range must not exceed $MAX_RANGE_DAYS days"))
            }
            val entries = transaction {
                (MealPlanEntriesTable innerJoin RecipesTable).selectAll()
                    .where { (MealPlanEntriesTable.date greaterEq from) and (MealPlanEntriesTable.date lessEq to) }
                    .orderBy(MealPlanEntriesTable.date to SortOrder.ASC, MealPlanEntriesTable.slot to SortOrder.ASC)
                    .map { it.toMealPlanDto() }
            }
            call.respond(entries)
        }

        // Set (or replace) the recipe planned for a (date, slot). Idempotent — one entry per slot.
        put("/{date}/{slot}") {
            val username = call.username()
            val day = call.parameters["date"]?.let { parseDate(it) } ?: return@put call.invalidDate()
            val slot = call.parameters["slot"]?.uppercase()
            if (slot == null || slot !in MEAL_SLOTS) return@put call.invalidSlot()
            val req = call.receive<SetMealPlanRequest>()
            val recipeId = runCatching { UUID.fromString(req.recipeId) }.getOrNull()
                ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_ID", "recipeId must be a valid UUID"))
            if (req.servings != null && req.servings < 1) {
                return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_SERVINGS", "servings must be >= 1"))
            }

            val dto = transaction {
                if (RecipesTable.selectAll().where { RecipesTable.id eq recipeId }.empty()) return@transaction null
                MealPlanEntriesTable.deleteWhere { (MealPlanEntriesTable.date eq day) and (MealPlanEntriesTable.slot eq slot) }
                val id = UUID.randomUUID()
                MealPlanEntriesTable.insert {
                    it[MealPlanEntriesTable.id] = id
                    it[MealPlanEntriesTable.date] = day
                    it[MealPlanEntriesTable.slot] = slot
                    it[MealPlanEntriesTable.recipeId] = recipeId
                    it[servings] = req.servings
                    it[createdBy] = username
                    it[createdAt] = Instant.now()
                }
                (MealPlanEntriesTable innerJoin RecipesTable).selectAll()
                    .where { MealPlanEntriesTable.id eq id }.single().toMealPlanDto()
            } ?: return@put call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Recipe not found"))

            notify()
            call.respond(dto)
        }

        // Clear the recipe planned for a (date, slot). Idempotent (204 even if nothing was set).
        delete("/{date}/{slot}") {
            val day = call.parameters["date"]?.let { parseDate(it) } ?: return@delete call.invalidDate()
            val slot = call.parameters["slot"]?.uppercase()
            if (slot == null || slot !in MEAL_SLOTS) return@delete call.invalidSlot()

            transaction {
                MealPlanEntriesTable.deleteWhere { (MealPlanEntriesTable.date eq day) and (MealPlanEntriesTable.slot eq slot) }
            }
            notify()
            call.respond(HttpStatusCode.NoContent)
        }
    }

    webSocket("/ws/meal-plan") {
        WsSessionManager.add(MEAL_PLAN_WS_CHANNEL, this)
        try {
            for (frame in incoming) {
                if (frame is Frame.Close) break
            }
        } finally {
            WsSessionManager.remove(MEAL_PLAN_WS_CHANNEL, this)
        }
    }
}

// Must be selected from (MealPlanEntriesTable innerJoin RecipesTable) so the recipe columns load.
private fun ResultRow.toMealPlanDto() = MealPlanEntryDto(
    id = this[MealPlanEntriesTable.id].toString(),
    date = this[MealPlanEntriesTable.date].toString(),
    slot = this[MealPlanEntriesTable.slot],
    recipeId = this[MealPlanEntriesTable.recipeId].toString(),
    recipeTitle = this[RecipesTable.title],
    recipeCategory = this[RecipesTable.category],
    servings = this[MealPlanEntriesTable.servings],
    createdBy = this[MealPlanEntriesTable.createdBy],
    createdAt = this[MealPlanEntriesTable.createdAt].toString(),
)

private fun parseDate(value: String): LocalDate? = runCatching { LocalDate.parse(value) }.getOrNull()

private suspend fun ApplicationCall.invalidDate() =
    respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_DATE", "date must be in YYYY-MM-DD format"))

private suspend fun ApplicationCall.invalidRange() =
    respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_RANGE", "from and to must be YYYY-MM-DD with from <= to"))

private suspend fun ApplicationCall.invalidSlot() =
    respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_SLOT", "slot must be one of $MEAL_SLOTS"))
