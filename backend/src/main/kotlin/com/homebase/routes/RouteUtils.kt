package com.homebase.routes

import com.homebase.model.ErrorResponse
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
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
