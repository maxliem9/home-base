package com.homebase.routes

import com.homebase.model.AppConfigResponse
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.configRoutes(householdName: String) {
    get("/config") {
        call.respond(AppConfigResponse(householdName = householdName))
    }
}
