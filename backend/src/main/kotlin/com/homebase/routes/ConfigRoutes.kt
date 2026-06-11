package com.homebase.routes

import com.homebase.db.AppSettingsTable
import com.homebase.model.AppConfigResponse
import com.homebase.model.ErrorResponse
import com.homebase.model.UpdateConfigRequest
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

private const val HOUSEHOLD_NAME_KEY = "household_name"
private const val HOUSEHOLD_NAME_MAX = 60

/**
 * App-level config. The household name (sidebar brand) is editable and shared by
 * both users (#100): it lives in the generic `app_settings` key/value table and
 * falls back to the HOUSEHOLD_NAME env default ([defaultHouseholdName]) when unset,
 * so a fresh DB behaves exactly as before. Both endpoints sit under auth-jwt
 * (see configureRouting); editing is intentionally not owner-restricted — like the
 * shared calendar (#127), either household member may change it.
 */
fun Route.configRoutes(defaultHouseholdName: String) {
    get("/config") {
        val stored = transaction {
            AppSettingsTable.selectAll()
                .where { AppSettingsTable.key eq HOUSEHOLD_NAME_KEY }
                .singleOrNull()?.get(AppSettingsTable.value)
        }
        call.respond(AppConfigResponse(householdName = stored ?: defaultHouseholdName))
    }

    put("/config") {
        val name = call.receive<UpdateConfigRequest>().householdName.trim()
        if (name.isEmpty() || name.length > HOUSEHOLD_NAME_MAX) {
            return@put call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse("INVALID_NAME", "householdName must be 1..$HOUSEHOLD_NAME_MAX characters"),
            )
        }
        transaction {
            val updated = AppSettingsTable.update({ AppSettingsTable.key eq HOUSEHOLD_NAME_KEY }) {
                it[value] = name
            }
            if (updated == 0) AppSettingsTable.insert {
                it[key] = HOUSEHOLD_NAME_KEY
                it[value] = name
            }
        }
        call.respond(AppConfigResponse(householdName = name))
    }
}
