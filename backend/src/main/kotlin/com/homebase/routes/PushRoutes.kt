package com.homebase.routes

import com.homebase.db.PushSubscriptionsTable
import com.homebase.model.ErrorResponse
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.Instant
import java.util.UUID

@Serializable
data class VapidKeyResponse(val publicKey: String)

@Serializable
data class PushSubscriptionKeys(val p256dh: String, val auth: String)

@Serializable
data class PushSubscribeRequest(val endpoint: String, val keys: PushSubscriptionKeys)

@Serializable
data class PushUnsubscribeRequest(val endpoint: String)

/**
 * Browser Web Push endpoints (#429 Phase 2b). All under `authenticate("auth-jwt")`.
 *
 *  - GET    /push/vapid-public-key → the server's VAPID public key so the client can call
 *           `PushManager.subscribe({ applicationServerKey })`. 404 when web push is not configured
 *           (no VAPID keys) — the client then hides the enable control.
 *  - POST   /push/subscribe        → upsert a subscription (keyed on its endpoint). Idempotent: a
 *           browser re-subscribing with the same endpoint just refreshes its keys/owner.
 *  - DELETE /push/subscribe        → remove a subscription by endpoint (idempotent, 204).
 *
 * Subscriptions are household-wide for delivery (the reminder fans out to all), matching the
 * shared-chat digest model; the stored username records who registered it.
 *
 * [vapidPublicKey] is the configured key, or null when web push is dormant.
 */
fun Route.pushRoutes(vapidPublicKey: String?) {
    get("/push/vapid-public-key") {
        if (vapidPublicKey.isNullOrBlank()) {
            return@get call.respond(
                HttpStatusCode.NotFound,
                ErrorResponse("WEB_PUSH_DISABLED", "Web push is not configured on this server"),
            )
        }
        call.respond(VapidKeyResponse(vapidPublicKey))
    }

    post("/push/subscribe") {
        val req = call.receive<PushSubscribeRequest>()
        if (req.endpoint.isBlank() || req.keys.p256dh.isBlank() || req.keys.auth.isBlank()) {
            return@post call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse("INVALID_SUBSCRIPTION", "endpoint and keys (p256dh, auth) are required"),
            )
        }
        val username = call.username()
        upsertSubscription(req, username)
        call.respond(HttpStatusCode.NoContent)
    }

    delete("/push/subscribe") {
        val req = call.receive<PushUnsubscribeRequest>()
        transaction {
            PushSubscriptionsTable.deleteWhere { endpoint eq req.endpoint }
        }
        call.respond(HttpStatusCode.NoContent)
    }
}

/** Upserts a subscription on its unique endpoint (update-then-insert; the unique index guards races). */
private fun upsertSubscription(req: PushSubscribeRequest, username: String) {
    transaction {
        val updated = PushSubscriptionsTable.update({ PushSubscriptionsTable.endpoint eq req.endpoint }) {
            it[p256dh] = req.keys.p256dh
            it[auth] = req.keys.auth
            it[PushSubscriptionsTable.username] = username
        }
        if (updated == 0) {
            // Re-check inside the same transaction in case a concurrent insert won the race; only
            // insert when still absent. The unique index is the final backstop.
            val exists = PushSubscriptionsTable.selectAll()
                .where { PushSubscriptionsTable.endpoint eq req.endpoint }.any()
            if (!exists) PushSubscriptionsTable.insert {
                it[id] = UUID.randomUUID()
                it[endpoint] = req.endpoint
                it[p256dh] = req.keys.p256dh
                it[auth] = req.keys.auth
                it[PushSubscriptionsTable.username] = username
                it[createdAt] = Instant.now()
            }
        }
    }
}
