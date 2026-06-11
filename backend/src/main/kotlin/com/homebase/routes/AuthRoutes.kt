package com.homebase.routes

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.homebase.model.ErrorResponse
import com.homebase.model.LoginRequest
import com.homebase.model.TokenResponse
import com.homebase.db.UsersTable
import com.homebase.security.LoginThrottler
import com.homebase.security.Passwords
import com.homebase.security.clientKey
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.*

fun Route.authRoutes(throttler: LoginThrottler, trustedProxyCount: Int) {
    post("/auth/login") {
        val log = call.application.log
        // Throttle by source before touching the body or the DB: once a client IP has failed too
        // often it is locked out (429) without any password check, so this reveals nothing about
        // which accounts exist and sheds the load cheaply. See issue #8.
        val key = clientKey(call, trustedProxyCount)
        val retryAfter = throttler.retryAfterSeconds(key)
        if (retryAfter > 0) {
            log.debug("Login attempt blocked for ip=$key — still locked, retry-after=${retryAfter}s")
            call.response.header(HttpHeaders.RetryAfter, retryAfter.toString())
            call.respond(
                HttpStatusCode.TooManyRequests,
                ErrorResponse("TOO_MANY_ATTEMPTS", "Too many login attempts. Please try again later."),
            )
            return@post
        }

        val request = call.receive<LoginRequest>()
        val config = call.application.environment.config

        val user = transaction {
            UsersTable.selectAll().where { UsersTable.username eq request.username }
                .singleOrNull()
        } ?: run {
            // Unknown user: still run one bcrypt verification (against a dummy hash) so this
            // path takes about as long as a wrong-password attempt against a real user. Skipping
            // it would make missing usernames answer faster and leak which accounts exist
            // (username enumeration via timing). See issue #71.
            Passwords.verifyDummy(request.password)
            val result = throttler.recordFailure(key)
            if (result.newLockoutSet) {
                log.warn("Login lockout set for ip=$key — failures=${result.failures}, locked-for=${result.lockoutSeconds}s")
            }
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse("INVALID_CREDENTIALS", "Invalid username or password"))
            return@post
        }

        if (!Passwords.verify(request.password, user[UsersTable.passwordHash])) {
            val result = throttler.recordFailure(key)
            if (result.newLockoutSet) {
                log.warn("Login lockout set for ip=$key — failures=${result.failures}, locked-for=${result.lockoutSeconds}s")
            }
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse("INVALID_CREDENTIALS", "Invalid username or password"))
            return@post
        }

        throttler.recordSuccess(key)

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
