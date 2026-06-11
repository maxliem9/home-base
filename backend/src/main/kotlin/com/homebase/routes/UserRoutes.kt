package com.homebase.routes

import com.homebase.db.UsersTable
import com.homebase.model.ChangePasswordRequest
import com.homebase.model.ErrorResponse
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
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

// Minimum length for a new password (#100). bcrypt has no practical maximum (long inputs
// are SHA-512 pre-hashed, see Passwords), so only a floor is enforced here.
private const val MIN_PASSWORD_LENGTH = 8

/**
 * The household members. HomeBase has 2 fixed users seeded from SEED_USERS; their
 * usernames are configurable, so clients can't hard-code "the other user". This list
 * lets a client resolve the partner — e.g. to start/stop their timer (#142) or render
 * an avatar — without scraping it out of unrelated payloads.
 */
fun Route.userRoutes() {
    get("/users") {
        val users = transaction {
            UsersTable.selectAll()
                .orderBy(UsersTable.username, SortOrder.ASC)
                .map { UserDto(it[UsersTable.username]) }
        }
        call.respond(users)
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
        val username = call.username()
        val storedHash = transaction {
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
        transaction {
            UsersTable.update({ UsersTable.username eq username }) { it[passwordHash] = newHash }
        }
        call.respond(HttpStatusCode.NoContent)
    }
}
