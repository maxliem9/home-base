package com.homebase.plugins

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.config.*
import io.ktor.http.auth.HttpAuthHeader

fun Application.configureAuthentication(config: ApplicationConfig) {
    val secret = config.property("jwt.secret").getString()
    val issuer = config.property("jwt.issuer").getString()
    val audience = config.property("jwt.audience").getString()
    val realm = config.property("jwt.realm").getString()

    install(Authentication) {
        jwt("auth-jwt") {
            this.realm = realm
            // Browsers can't set headers on a WebSocket handshake, so we also accept the JWT
            // via a `?token=` query parameter as a fallback. The Authorization header always
            // takes precedence. Tradeoff: query-string tokens can leak into access logs and
            // browser history, which is acceptable for this private 2-user hub but should only
            // be relied upon for the /ws/* upgrade endpoints.
            authHeader { call ->
                call.request.parseAuthorizationHeader()
                    ?: call.request.queryParameters["token"]
                        ?.takeIf { it.isNotBlank() }
                        ?.let { HttpAuthHeader.Single("Bearer", it) }
            }
            verifier(
                JWT.require(Algorithm.HMAC256(secret))
                    .withAudience(audience)
                    .withIssuer(issuer)
                    .build()
            )
            validate { credential ->
                if (credential.payload.getClaim("username").asString() != null)
                    JWTPrincipal(credential.payload)
                else null
            }
        }
    }
}
