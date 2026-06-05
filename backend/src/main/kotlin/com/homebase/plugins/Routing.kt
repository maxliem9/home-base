package com.homebase.plugins

import com.homebase.routes.NoteImageConfig
import com.homebase.routes.absenceRoutes
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
import java.nio.file.Paths

fun Application.configureRouting() {
    val householdName = environment.config.propertyOrNull("app.householdName")?.getString() ?: "Mäxchen"
    val uploadDir = environment.config.propertyOrNull("app.uploadDir")?.getString() ?: "uploads"
    val maxUploadMb = environment.config.propertyOrNull("app.maxUploadMb")?.getString()?.toLongOrNull() ?: 10L
    val noteImageConfig = NoteImageConfig(Paths.get(uploadDir), maxUploadMb * 1024 * 1024)
    routing {
        route("/api/v1") {
            healthRoutes()
            authRoutes()
            authenticate("auth-jwt") {
                configRoutes(householdName)
                todoRoutes()
                shoppingRoutes()
                noteRoutes(noteImageConfig)
                timeRoutes()
                recipeRoutes()
                absenceRoutes()
            }
        }
    }
}
