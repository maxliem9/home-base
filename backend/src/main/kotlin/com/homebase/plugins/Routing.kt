package com.homebase.plugins

import com.homebase.routes.authRoutes
import com.homebase.routes.configRoutes
import com.homebase.routes.healthRoutes
import com.homebase.routes.noteRoutes
import com.homebase.routes.recipeRoutes
import com.homebase.routes.shoppingRoutes
import com.homebase.routes.timeRoutes
import com.homebase.routes.todoRoutes
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.routing.*

fun Application.configureRouting() {
    val householdName = environment.config.propertyOrNull("app.householdName")?.getString() ?: "Mäxchen"
    routing {
        route("/api/v1") {
            healthRoutes()
            authRoutes()
            authenticate("auth-jwt") {
                configRoutes(householdName)
                todoRoutes()
                shoppingRoutes()
                noteRoutes()
                timeRoutes()
                recipeRoutes()
            }
        }
    }
}
