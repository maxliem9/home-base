package com.homebase.routes

import com.homebase.db.UsersTable
import com.homebase.model.ErrorResponse
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.response.*
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import java.util.UUID

/**
 * Parses the given path parameter as a UUID. If it is missing or malformed the call is
 * answered with 400 and null is returned, so handlers don't surface an unhandled 500.
 *
 * Usage: `val id = call.uuidParam() ?: return@put`
 */
suspend fun ApplicationCall.uuidParam(name: String = "id"): UUID? {
    val uuid = parameters[name]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
    if (uuid == null) {
        respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_ID", "$name must be a valid UUID"))
    }
    return uuid
}

/**
 * The authenticated caller's username, read from the JWT "username" claim.
 *
 * Routes calling this must sit under `authenticate("auth-jwt")` (the principal is
 * asserted non-null). The claim itself is validated to be present at login
 * (see `configureAuthentication`); the `?:` guard fails loudly rather than
 * smuggling a null into the non-null return type if that ever changes.
 */
fun ApplicationCall.username(): String =
    principal<JWTPrincipal>()!!.payload.getClaim("username").asString()
        ?: error("JWT is missing the required \"username\" claim")

/**
 * Whether a username exists in the household (the `users` table). Must be called
 * inside a `transaction { }`. Shared by routes that act on another user — the
 * calendar and shared timers.
 */
fun userExists(username: String): Boolean =
    !UsersTable.selectAll().where { UsersTable.username eq username }.empty()
