package com.homebase.plugins

import com.homebase.routes.ImageUploadConfig
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
import com.homebase.routes.userPrefsRoutes
import com.homebase.routes.userRoutes
import com.homebase.security.LoginThrottler
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.routing.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.LocalTime
import java.time.format.DateTimeFormatter

fun Application.configureRouting() {
    val householdName = environment.config.propertyOrNull("app.householdName")?.getString() ?: "Mäxchen"
    // Digest time default + whether Telegram is configured, surfaced read/write via
    // /config/digest (#100). The stored value (app_settings) overrides this default.
    val digestDefaultTime = (parseDigestTime(environment.config.propertyOrNull("telegram.digestTime")?.getString())
        ?: LocalTime.of(20, 0)).format(DateTimeFormatter.ofPattern("HH:mm"))
    val telegramEnabled = !environment.config.propertyOrNull("telegram.botToken")?.getString().isNullOrBlank() &&
        !environment.config.propertyOrNull("telegram.chatId")?.getString().isNullOrBlank()
    val uploadDir = environment.config.propertyOrNull("app.uploadDir")?.getString() ?: "uploads"
    val maxUploadMb = environment.config.propertyOrNull("app.maxUploadMb")?.getString()?.toLongOrNull() ?: 10L
    // Shared by the note and recipe image endpoints (same upload dir + size cap).
    val imageConfig = ImageUploadConfig(Paths.get(uploadDir), maxUploadMb * 1024 * 1024)
    // How many trusted reverse-proxy hops sit in front of the backend; used to pick the real
    // client IP out of X-Forwarded-For for login throttling (prod: DSM + nginx = 2). See issue #8.
    val trustedProxyCount = environment.config.propertyOrNull("app.trustedProxyCount")?.getString()?.toIntOrNull() ?: 2
    val loginThrottler = LoginThrottler()
    verifyUploadDirWritable(imageConfig.uploadDir)
    sweepStaleImageUploads(imageConfig).takeIf { it > 0 }?.let {
        log.info("Swept {} orphaned image upload temp file(s) from '{}'.", it, imageConfig.uploadDir.toAbsolutePath())
    }
    routing {
        route("/api/v1") {
            healthRoutes()
            authRoutes(loginThrottler, trustedProxyCount)
            authenticate("auth-jwt") {
                configRoutes(householdName, digestDefaultTime, telegramEnabled)
                userRoutes()
                userPrefsRoutes()
                todoRoutes()
                shoppingRoutes()
                noteRoutes(imageConfig)
                timeRoutes()
                recipeRoutes(imageConfig)
                absenceRoutes()
            }
        }
    }
}

// The backend runs as a non-root user (uid 10001, see backend/Dockerfile), so
// image uploads (notes + recipes) need a writable UPLOAD_DIR. Surface a misconfigured/
// root-owned volume loudly at startup instead of only on the first failed upload.
// Non-fatal on purpose: the rest of the API (todos, recipes, …) must keep working.
private fun Application.verifyUploadDirWritable(dir: Path) {
    val created = runCatching { Files.createDirectories(dir) }
    if (created.isFailure) {
        log.warn(
            "UPLOAD_DIR '{}' could not be created: {} — image uploads will fail until it exists and is writable by the backend user (uid 10001).",
            dir.toAbsolutePath(), created.exceptionOrNull()?.message
        )
        return
    }
    if (!Files.isWritable(dir)) {
        log.warn(
            "UPLOAD_DIR '{}' is not writable by the backend user (uid 10001) — image uploads will fail. For an existing root-owned volume run: chown -R 10001:10001 {}",
            dir.toAbsolutePath(), dir.toAbsolutePath()
        )
    }
}
