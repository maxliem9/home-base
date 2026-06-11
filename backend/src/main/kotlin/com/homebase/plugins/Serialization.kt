package com.homebase.plugins

import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import kotlinx.serialization.json.Json

fun Application.configureSerialization() {
    install(ContentNegotiation) {
        json(Json {
            prettyPrint = false
            isLenient = true
            ignoreUnknownKeys = true
            // Explizit auf false gesetzt, um Konvention #46 abzusichern:
            // Felder mit Default-Wert (null, emptyList()) werden nicht serialisiert.
            // false ist der kotlinx-Default, aber wir setzen es bewusst, damit
            // ein versehentliches encodeDefaults = true die kompakten Payloads nicht
            // still bricht (DTOs und Clients verlassen sich darauf: #46, #54, #82).
            encodeDefaults = false
        })
    }
}
