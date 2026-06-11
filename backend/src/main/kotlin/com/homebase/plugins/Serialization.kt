package com.homebase.plugins

import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import kotlinx.serialization.json.Json

/**
 * Die zentrale Json-Instanz der App (Konvention #46).
 *
 * Wird von [configureSerialization] registriert **und** von
 * [SerializationConventionTest][com.homebase.SerializationConventionTest]
 * direkt referenziert, damit der Guard-Test exakt dieselbe Konfiguration
 * prüft, die Ktor auch einsetzt.
 *
 * **Achtung:** Änderungen hier müssen mit dem Test synchron bleiben —
 * insbesondere `encodeDefaults` darf nicht auf `true` gesetzt werden.
 */
val appJson = Json {
    prettyPrint = false
    isLenient = true
    ignoreUnknownKeys = true
    // Explizit auf false gesetzt, um Konvention #46 abzusichern:
    // Felder mit Default-Wert (null, emptyList()) werden nicht serialisiert.
    // false ist der kotlinx-Default, aber wir setzen es bewusst, damit
    // ein versehentliches encodeDefaults = true die kompakten Payloads nicht
    // still bricht (DTOs und Clients verlassen sich darauf: #46, #54, #82).
    encodeDefaults = false
}

fun Application.configureSerialization() {
    install(ContentNegotiation) {
        json(appJson)
    }
}
