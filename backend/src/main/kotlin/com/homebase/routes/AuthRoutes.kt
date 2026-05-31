package com.homebase.routes

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.homebase.model.ErrorResponse
import com.homebase.model.LoginRequest
import com.homebase.model.TokenResponse
import com.homebase.db.UsersTable
import com.homebase.security.sha256
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.*

fun Route.authRoutes() {
    post("/auth/login") {
        val request = call.receive<LoginRequest>()
        val config = call.application.environment.config

        val user = transaction {
            UsersTable.selectAll().where { UsersTable.username eq request.username }
                .singleOrNull()
        } ?: run {
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse("INVALID_CREDENTIALS", "Invalid username or password"))
            return@post
        }

        val hash = sha256(request.password)
        if (hash != user[UsersTable.passwordHash]) {
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse("INVALID_CREDENTIALS", "Invalid username or password"))
            return@post
        }

        val secret = config.property("jwt.secret").getString()
        val issuer = config.property("jwt.issuer").getString()
        val audience = config.property("jwt.audience").getString()

        val token = JWT.create()
            .withAudience(audience)
            .withIssuer(issuer)
            .withClaim("username", request.username)
            .withExpiresAt(Date(System.currentTimeMillis() + 30L * 24 * 60 * 60 * 1000))
            .sign(Algorithm.HMAC256(secret))

        call.respond(TokenResponse(token))
    }
}
