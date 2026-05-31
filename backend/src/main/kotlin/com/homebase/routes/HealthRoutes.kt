package com.homebase.routes

import com.homebase.model.HealthResponse
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.healthRoutes() {
    get("/health") {
        call.respond(HealthResponse("ok"))
    }
}
