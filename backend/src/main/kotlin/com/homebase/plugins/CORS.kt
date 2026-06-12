package com.homebase.plugins

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.cors.routing.*

fun Application.configureCORS() {
    // Browser-origin allow-list. Auth rides in the Authorization header (no cookies),
    // so CORS is defense in depth here rather than the CSRF barrier — but a leaked
    // token must not be replayable from arbitrary websites either. When the public
    // domain is configured (DOMAIN — the same value the deploy health check uses)
    // the origin is pinned to https://<domain>; without one (local dev: vite :5173
    // against :8080, tests) any host stays allowed, matching the old behaviour.
    // Non-browser clients (Android/curl) send no Origin header and are unaffected.
    val domain = environment.config.propertyOrNull("app.domain")?.getString()
        ?.trim()?.removePrefix("https://")?.removePrefix("http://")?.trim('/')
    install(CORS) {
        allowMethod(HttpMethod.Options)
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Patch)
        allowMethod(HttpMethod.Delete)
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)
        if (domain.isNullOrEmpty()) anyHost() else allowHost(domain, schemes = listOf("https"))
    }
}
