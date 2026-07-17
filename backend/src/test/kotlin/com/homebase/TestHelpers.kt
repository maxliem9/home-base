package com.homebase

import com.homebase.plugins.*
import io.ktor.server.config.*
import io.ktor.server.testing.*
import java.nio.file.Files
import java.nio.file.Path

/**
 * Configures a test Ktor application against the shared Testcontainers PostgreSQL ([TestDatabase])
 * and seeds the baseline state (2 fixed users + editable shopping-category catalog).
 *
 * The schema comes from the real Flyway migrations — the same one production deploys against —
 * instead of the old per-test H2 + Exposed SchemaUtils build, which silently diverged from prod
 * (missing FK cascades, CHECK constraints, partial unique indexes; only approximate isolation).
 * One shared container serves the whole suite; each test resets the data via TRUNCATE + re-seed
 * (see [TestDatabase.reset]) rather than standing a new DB up.
 *
 * [extraConfig] appends/overrides config entries for tests that exercise optional
 * settings (e.g. "app.domain" for the CORS origin pinning).
 */
fun ApplicationTestBuilder.configureTestApplication(vararg extraConfig: Pair<String, String>): Path {
    // Each test gets its own throwaway upload directory; a low size cap keeps the
    // "too large" test cheap (just over 1 MB rather than just over 10 MB).
    val uploadDir = Files.createTempDirectory("homebase-test-uploads")
    // Fresh data + seed on the shared Postgres before the app handles any request.
    TestDatabase.reset()
    environment {
        config = MapApplicationConfig(
            "jwt.secret" to "test-secret-key-for-testing-only",
            "jwt.issuer" to "homebase",
            "jwt.audience" to "homebase-users",
            "jwt.realm" to "HomeBase",
            "app.uploadDir" to uploadDir.toString(),
            "app.maxUploadMb" to "1",
            *extraConfig,
        )
    }
    application {
        // Re-assert Postgres as the current Exposed DB for this app's handlers (last-connect-wins),
        // in case an interleaved H2 service test made its own DB current after reset() ran.
        TestDatabase.useAsCurrent()
        configureSerialization()
        configureAuthentication(environment.config)
        configureWebSockets()
        configureStatusPages()
        configureCORS()
        configureRouting()
    }
    return uploadDir
}
