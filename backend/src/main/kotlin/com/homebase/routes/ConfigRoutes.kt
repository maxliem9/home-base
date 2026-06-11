package com.homebase.routes

import com.homebase.db.AppSettingsTable
import com.homebase.model.AppConfigResponse
import com.homebase.model.DigestConfigResponse
import com.homebase.model.ErrorResponse
import com.homebase.model.UpdateConfigRequest
import com.homebase.model.UpdateDigestRequest
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
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private const val HOUSEHOLD_NAME_KEY = "household_name"
private const val HOUSEHOLD_NAME_MAX = 60
private val HH_MM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/**
 * App-level config, shared by both users (#100; not owner-restricted, like the shared
 * calendar #127). Values live in the generic `app_settings` key/value table and fall back
 * to env defaults when unset, so a fresh DB behaves exactly as before. All endpoints sit
 * under auth-jwt (see configureRouting).
 *
 * - household name — the sidebar brand ([defaultHouseholdName] fallback).
 * - digest time — when the Telegram digest is sent ([digestDefaultTime] fallback). The
 *   scheduler re-reads this each cycle, so a change applies from the next run.
 *   [telegramEnabled] reports whether Telegram is configured at all; the time is editable
 *   regardless, ready for when it is.
 */
fun Route.configRoutes(defaultHouseholdName: String, digestDefaultTime: String, telegramEnabled: Boolean) {
    get("/config") {
        call.respond(AppConfigResponse(householdName = readSetting(HOUSEHOLD_NAME_KEY) ?: defaultHouseholdName))
    }

    put("/config") {
        val name = call.receive<UpdateConfigRequest>().householdName.trim()
        if (name.isEmpty() || name.length > HOUSEHOLD_NAME_MAX) {
            return@put call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse("INVALID_NAME", "householdName must be 1..$HOUSEHOLD_NAME_MAX characters"),
            )
        }
        upsertSetting(HOUSEHOLD_NAME_KEY, name)
        call.respond(AppConfigResponse(householdName = name))
    }

    get("/config/digest") {
        call.respond(
            DigestConfigResponse(
                time = readSetting(AppSettingsTable.DIGEST_TIME) ?: digestDefaultTime,
                enabled = telegramEnabled,
            ),
        )
    }

    put("/config/digest") {
        val parsed = runCatching { LocalTime.parse(call.receive<UpdateDigestRequest>().time.trim()) }.getOrNull()
            ?: return@put call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse("INVALID_TIME", "time must be a valid HH:mm"),
            )
        val normalized = parsed.format(HH_MM)
        upsertSetting(AppSettingsTable.DIGEST_TIME, normalized)
        call.respond(DigestConfigResponse(time = normalized, enabled = telegramEnabled))
    }
}

/** Reads one app_settings value by key, or null if unset. */
private fun readSetting(settingKey: String): String? = transaction {
    AppSettingsTable.selectAll().where { AppSettingsTable.key eq settingKey }
        .singleOrNull()?.get(AppSettingsTable.value)
}

/** Upserts one app_settings value (update-then-insert; the PK guards against duplicates). */
private fun upsertSetting(settingKey: String, settingValue: String) {
    transaction {
        val updated = AppSettingsTable.update({ AppSettingsTable.key eq settingKey }) { it[value] = settingValue }
        if (updated == 0) AppSettingsTable.insert {
            it[key] = settingKey
            it[value] = settingValue
        }
    }
}
