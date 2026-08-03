package com.homebase.plugins

import com.homebase.routes.ImageUploadConfig
import com.homebase.routes.sweepStaleImageUploads
import com.homebase.routes.absenceRoutes
import com.homebase.routes.authRoutes
import com.homebase.routes.calendarRoutes
import com.homebase.routes.configRoutes
import com.homebase.routes.eventRoutes
import com.homebase.routes.healthRoutes
import com.homebase.routes.mealPlanRoutes
import com.homebase.routes.noteRoutes
import com.homebase.routes.pushRoutes
import com.homebase.routes.recipeRoutes
import com.homebase.routes.shoppingRoutes
import com.homebase.routes.shoppingTemplateRoutes
import com.homebase.routes.timeRoutes
import com.homebase.routes.todoRoutes
import com.homebase.routes.userPrefsRoutes
import com.homebase.routes.userRoutes
import com.homebase.routes.versionRoutes
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
    // Morning-briefing time default + read/write via /config/morning-digest. Stored value
    // (app_settings) overrides this, exactly like the evening digest time.
    val morningDigestDefaultTime = (parseDigestTime(environment.config.propertyOrNull("telegram.morningDigestTime")?.getString())
        ?: LocalTime.of(7, 0)).format(DateTimeFormatter.ofPattern("HH:mm"))
    val telegramEnabled = !environment.config.propertyOrNull("telegram.botToken")?.getString().isNullOrBlank() &&
        !environment.config.propertyOrNull("telegram.chatId")?.getString().isNullOrBlank()
    // Recurring-todo safety-net run-time default + read/write via /config/recurring (#100).
    // The stored value (app_settings) overrides this default, exactly like the digest time.
    val recurringDefaultTime = (parseDigestTime(environment.config.propertyOrNull("recurring.time")?.getString())
        ?: LocalTime.of(0, 30)).format(DateTimeFormatter.ofPattern("HH:mm"))
    val uploadDir = environment.config.propertyOrNull("app.uploadDir")?.getString() ?: "uploads"
    val maxUploadMb = environment.config.propertyOrNull("app.maxUploadMb")?.getString()?.toLongOrNull() ?: 10L
    // Shared by the note and recipe image endpoints (same upload dir + size cap).
    val imageConfig = ImageUploadConfig(Paths.get(uploadDir), maxUploadMb * 1024 * 1024)
    // How many trusted reverse-proxy hops sit in front of the backend; used to pick the real
    // client IP out of X-Forwarded-For for login throttling (prod: DSM + nginx = 2). See issue #8.
    val trustedProxyCount = environment.config.propertyOrNull("app.trustedProxyCount")?.getString()?.toIntOrNull() ?: 2
    // VAPID public key for browser Web Push (#429 Phase 2b); null/blank ⇒ web push dormant and
    // GET /push/vapid-public-key reports 404 so the client hides its enable control.
    val vapidPublicKey = environment.config.propertyOrNull("webpush.publicKey")?.getString()
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
                // Build-Version des Backends (#626) — die Clients zeigen sie in den Einstellungen.
                versionRoutes()
                configRoutes(householdName, digestDefaultTime, telegramEnabled, recurringDefaultTime, morningDigestDefaultTime)
                userRoutes()
                userPrefsRoutes()
                // Browser Web Push subscribe/unsubscribe + VAPID public key (#429 Phase 2b).
                pushRoutes(vapidPublicKey)
                todoRoutes()
                // Registered before shoppingRoutes so the static /shopping/templates segment is
                // unambiguous against /shopping/{id} (Ktor prioritises constant over parameter
                // segments regardless, but the explicit order documents the intent).
                shoppingTemplateRoutes()
                shoppingRoutes()
                noteRoutes(imageConfig)
                timeRoutes()
                recipeRoutes(imageConfig)
                mealPlanRoutes()
                absenceRoutes()
                // Calendar events / Termine (#434) — household-shared scheduled events.
                eventRoutes()
                // iCal subscription feed (#427). Under auth-jwt so the JWT may ride in ?token=
                // (calendar apps can't set headers); the JWT mechanism is identical to note-image loads.
                calendarRoutes()
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
