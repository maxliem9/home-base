package com.homebase.plugins

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.http.HttpHeaders
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
            // A browser can't set request headers on a WebSocket handshake or on an <img>/native
            // image load, so besides the standard Authorization header we accept the JWT two other
            // ways, in priority order:
            //   1. Authorization: Bearer …            — always preferred (REST + the Android WS client).
            //   2. Sec-WebSocket-Protocol: "bearer, <jwt>" — the web client passes the token as a
            //      WebSocket subprotocol so it rides in a handshake header, NOT the URL (no
            //      access-log / browser-history leak). See web/src/hooks/useWebSocket.ts.
            //   3. ?token=<jwt> query param           — last resort, still used by native image loads
            //      (Android Coil / <img>) that can set neither a header nor a subprotocol. Query-string
            //      tokens can leak into access logs, so this is intentionally the lowest-priority path.
            authHeader { call ->
                call.request.parseAuthorizationHeader()
                    ?: bearerFromWebSocketProtocol(call)
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

// The web WebSocket client opens `new WebSocket(url, ["bearer", token])`; the browser puts those
// values on the handshake's `Sec-WebSocket-Protocol` header as "bearer, <jwt>". Pull the token back
// out so the JWT never has to ride in the URL. A JWT only uses base64url chars + '.', all valid in a
// WebSocket subprotocol token, so a plain comma split is safe.
private fun bearerFromWebSocketProtocol(call: ApplicationCall): HttpAuthHeader? {
    val protocols = call.request.headers[HttpHeaders.SecWebSocketProtocol] ?: return null
    val parts = protocols.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    val marker = parts.indexOf("bearer")
    if (marker < 0) return null
    val token = parts.getOrNull(marker + 1)?.takeIf { it.isNotBlank() } ?: return null
    return HttpAuthHeader.Single("Bearer", token)
}
