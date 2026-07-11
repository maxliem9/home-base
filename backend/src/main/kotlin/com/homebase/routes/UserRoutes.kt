package com.homebase.routes

import com.homebase.db.dbQuery
import com.homebase.db.UsersTable
import com.homebase.model.ChangePasswordRequest
import com.homebase.model.ErrorResponse
import com.homebase.model.SetAvatarColorRequest
import com.homebase.model.UserDto
import com.homebase.security.Passwords
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update

// Minimum length for a new password (#100). bcrypt has no practical maximum (long inputs
// are SHA-512 pre-hashed, see Passwords), so only a floor is enforced here.
private const val MIN_PASSWORD_LENGTH = 8

// Valid avatar hue range, matching the OKLCH hue wheel and the DB CHECK in V23 (Teil
// von #100). Anything outside is rejected; null is allowed (clears to automatic/derived).
private val AVATAR_HUE_RANGE = 0..359

/**
 * The household members. HomeBase has 2 fixed users seeded from SEED_USERS; their
 * usernames are configurable, so clients can't hard-code "the other user". This list
 * lets a client resolve the partner — e.g. to start/stop their timer or render
 * an avatar — without scraping it out of unrelated payloads.
 */
fun Route.userRoutes() {
    get("/users") {
        val users = dbQuery {
            UsersTable.selectAll()
                .orderBy(UsersTable.username, SortOrder.ASC)
                .map { UserDto(it[UsersTable.username], it[UsersTable.avatarHue]) }
        }
        call.respond(users)
    }

    // Set the authenticated user's own avatar hue (Teil von #100). hue null clears it back
    // to automatic (client derives from the username hash, #160). The colour is exposed via
    // the household-visible roster (GET /users), so the partner sees it on their next refetch
    // — avatar colour is deliberately personal here (own-only via /users/me), unlike the
    // shared calendars; household-shared editing could be a later extension.
    put("/users/me/avatar-color") {
        val req = call.receive<SetAvatarColorRequest>()
        if (req.hue != null && req.hue !in AVATAR_HUE_RANGE) {
            return@put call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse("INVALID_HUE", "hue must be between ${AVATAR_HUE_RANGE.first} and ${AVATAR_HUE_RANGE.last}"),
            )
        }
        val username = call.username()
        val updated = dbQuery {
            UsersTable.update({ UsersTable.username eq username }) { it[avatarHue] = req.hue }
        }
        if (updated == 0) {
            return@put call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "user not found"))
        }
        call.respond(HttpStatusCode.NoContent)
    }

    // Change the authenticated user's own password (#100). Verifies the current password
    // first, so a stolen-but-unused session can't silently lock the owner out. bcrypt
    // verify/hash run OUTSIDE the transaction (like login) to avoid holding a DB connection
    // during the deliberately-slow KDF. JWTs are stateless (30-day expiry) and stay valid —
    // a password change does not revoke existing tokens (acceptable for a 2-user household).
    put("/users/me/password") {
        val req = call.receive<ChangePasswordRequest>()
        if (req.newPassword.length < MIN_PASSWORD_LENGTH) {
            return@put call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse("WEAK_PASSWORD", "newPassword must be at least $MIN_PASSWORD_LENGTH characters"),
            )
        }
        // Reject a no-op change up front: it's a plain string compare of the two request
        // fields (independent of the stored hash), so no DB read or bcrypt work is needed,
        // and the caller already supplies both values — this leaks nothing.
        if (req.newPassword == req.currentPassword) {
            return@put call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse("PASSWORD_UNCHANGED", "newPassword must differ from the current password"),
            )
        }
        val username = call.username()
        val storedHash = dbQuery {
            UsersTable.selectAll().where { UsersTable.username eq username }
                .singleOrNull()?.get(UsersTable.passwordHash)
        } ?: return@put call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "user not found"))

        if (!Passwords.verify(req.currentPassword, storedHash)) {
            return@put call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse("INVALID_PASSWORD", "current password is incorrect"),
            )
        }

        val newHash = Passwords.hash(req.newPassword)
        dbQuery {
            UsersTable.update({ UsersTable.username eq username }) { it[passwordHash] = newHash }
        }
        call.respond(HttpStatusCode.NoContent)
    }
}
