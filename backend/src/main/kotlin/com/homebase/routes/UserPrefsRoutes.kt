package com.homebase.routes

import com.homebase.db.UserPrefsTable
import com.homebase.model.ErrorResponse
import com.homebase.model.UpdateUserPrefRequest
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

// Mirrors the column widths in V22 / UserPrefsTable.
private const val KEY_MAX = 64

// TEXT column, but a generous cap keeps a single pref from being abused as bulk
// storage. The current consumer (theme) stores a handful of characters; this only
// needs to be roomy enough for plausible future JSON-ish prefs.
private const val VALUE_MAX = 4096

/**
 * Generic PER-USER key/value preferences (#100, Phase 2). Personal, NOT shared:
 * the rows are keyed by the authenticated caller's username, and a user only ever
 * reads/writes their own — in deliberate contrast to the shared household calendars
 * (absence/time) and the shared app config (/config). All routes sit under
 * `authenticate("auth-jwt")` (see configureRouting); the owner is always
 * `call.username()`, never a path/body parameter, so one user can't touch another's.
 *
 * - GET  /user-prefs       → the caller's prefs as a flat { key: value } map
 *                            (empty object when none set). New keys surface here
 *                            without a model change.
 * - PUT  /user-prefs/{key} → upsert one pref ({ value }); returns the full updated
 *                            map (like /config, so the client can resync in one read).
 *
 * First consumer: the UI theme (key 'theme', values light|dark|system). The endpoint
 * is value-agnostic on purpose — validation of allowed values for a given key is the
 * consumer's concern (the client coerces an unknown theme to a safe default).
 */
fun Route.userPrefsRoutes() {
    get("/user-prefs") {
        call.respond(readPrefs(call.username()))
    }

    put("/user-prefs/{key}") {
        val key = call.parameters["key"]?.trim().orEmpty()
        if (key.isEmpty() || key.length > KEY_MAX) {
            return@put call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse("INVALID_KEY", "key must be 1..$KEY_MAX characters"),
            )
        }
        val value = call.receive<UpdateUserPrefRequest>().value
        if (value.length > VALUE_MAX) {
            return@put call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse("VALUE_TOO_LONG", "value must be at most $VALUE_MAX characters"),
            )
        }
        val username = call.username()
        upsertPref(username, key, value)
        call.respond(readPrefs(username))
    }
}

/** All of one user's prefs as a flat key→value map (empty when none). */
private fun readPrefs(username: String): Map<String, String> = transaction {
    UserPrefsTable.selectAll().where { UserPrefsTable.userId eq username }
        .associate { it[UserPrefsTable.key] to it[UserPrefsTable.value] }
}

/** Upserts one (user, key) pref (update-then-insert; the composite PK guards duplicates). */
private fun upsertPref(username: String, prefKey: String, prefValue: String) {
    transaction {
        val updated = UserPrefsTable.update({
            (UserPrefsTable.userId eq username) and (UserPrefsTable.key eq prefKey)
        }) { it[value] = prefValue }
        if (updated == 0) UserPrefsTable.insert {
            it[userId] = username
            it[key] = prefKey
            it[value] = prefValue
        }
    }
}
