package com.homebase.plugins

import com.homebase.model.ErrorResponse
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*

fun Application.configureStatusPages() {
    install(StatusPages) {
        // Ein syntaktisch kaputter Request-Body (malformed JSON) lässt `call.receive<T>()`
        // eine Ktor-BadRequestException werfen. Ohne expliziten Handler fiele die in den
        // generischen Throwable-Zweig → 500; hier 400 mit INVALID_BODY. StatusPages dispatcht
        // auf den spezifischsten registrierten Typ, daher steht dieser vor IllegalArgumentException
        // (BadRequestException ist zwar dessen Subtyp, aber wir wollen die eigene Fehlerantwort).
        exception<BadRequestException> { call, _ ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_BODY", "Malformed request body"))
        }
        exception<IllegalArgumentException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("BAD_REQUEST", cause.message ?: "Bad request"))
        }
        exception<Throwable> { call, cause ->
            call.application.log.error("Unhandled exception", cause)
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("INTERNAL_ERROR", "An internal error occurred"))
        }
    }
}
