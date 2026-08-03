package com.homebase.routes

import com.homebase.AppVersion
import com.homebase.model.VersionResponse
import io.ktor.server.response.*
import io.ktor.server.routing.*

/**
 * GET /version (#626) — welche Backend-Version läuft hier gerade.
 *
 * Bewusst hinter `auth-jwt` registriert (siehe plugins/Routing.kt): die Clients zeigen den Wert
 * in den Einstellungen, also erst nach dem Login — unauthentifiziertes Fingerprinting der
 * Deployment-Version bleibt so aus. `/health` bleibt davon unberührt (Container-Healthcheck).
 */
fun Route.versionRoutes() {
    get("/version") {
        call.respond(VersionResponse(version = AppVersion.version, commit = AppVersion.commit))
    }
}
