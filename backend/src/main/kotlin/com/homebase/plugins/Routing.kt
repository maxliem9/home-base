package com.homebase.plugins

import com.homebase.routes.NoteImageConfig
import com.homebase.routes.sweepStaleImageUploads
import com.homebase.routes.absenceRoutes
import com.homebase.routes.authRoutes
import com.homebase.routes.configRoutes
import com.homebase.routes.healthRoutes
import com.homebase.routes.noteRoutes
import com.homebase.routes.recipeRoutes
import com.homebase.routes.shoppingRoutes
import com.homebase.routes.timeRoutes
import com.homebase.routes.todoRoutes
import com.homebase.routes.userRoutes
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.routing.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

fun Application.configureRouting() {
    val householdName = environment.config.propertyOrNull("app.householdName")?.getString() ?: "Mäxchen"
    val uploadDir = environment.config.propertyOrNull("app.uploadDir")?.getString() ?: "uploads"
    val maxUploadMb = environment.config.propertyOrNull("app.maxUploadMb")?.getString()?.toLongOrNull() ?: 10L
    val noteImageConfig = NoteImageConfig(Paths.get(uploadDir), maxUploadMb * 1024 * 1024)
    verifyUploadDirWritable(noteImageConfig.uploadDir)
    sweepStaleImageUploads(noteImageConfig).takeIf { it > 0 }?.let {
        log.info("Swept {} orphaned note-image upload temp file(s) from '{}'.", it, noteImageConfig.uploadDir.toAbsolutePath())
    }
    routing {
        route("/api/v1") {
            healthRoutes()
            authRoutes()
            authenticate("auth-jwt") {
                configRoutes(householdName)
                userRoutes()
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

// The backend runs as a non-root user (uid 10001, see backend/Dockerfile), so
// note-image uploads need a writable UPLOAD_DIR. Surface a misconfigured/
// root-owned volume loudly at startup instead of only on the first failed upload.
// Non-fatal on purpose: the rest of the API (todos, recipes, …) must keep working.
private fun Application.verifyUploadDirWritable(dir: Path) {
    val created = runCatching { Files.createDirectories(dir) }
    if (created.isFailure) {
        log.warn(
            "UPLOAD_DIR '{}' could not be created: {} — note-image uploads will fail until it exists and is writable by the backend user (uid 10001).",
            dir.toAbsolutePath(), created.exceptionOrNull()?.message
        )
        return
    }
    if (!Files.isWritable(dir)) {
        log.warn(
            "UPLOAD_DIR '{}' is not writable by the backend user (uid 10001) — note-image uploads will fail. For an existing root-owned volume run: chown -R 10001:10001 {}",
            dir.toAbsolutePath(), dir.toAbsolutePath()
        )
    }
}
