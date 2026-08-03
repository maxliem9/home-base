package com.homebase

import java.util.Properties

/**
 * Die Version dieses Backend-Builds (#626).
 *
 * Gespeist aus `version.properties`, die der Gradle-Build aus der Repo-Root-Datei `VERSION`
 * (bzw. den Docker-Build-Args APP_VERSION/GIT_SHA) generiert und in die Ressourcen legt — sie
 * liegt also auch im Fat-Jar, wo weder die VERSION-Datei noch `.git` existieren.
 *
 * Fehlt die Ressource (z. B. IDE-Run ohne `processResources`), fällt die Version auf
 * `0.0.0-dev` zurück und der Commit bleibt leer — nie ein Startfehler, die Angabe ist rein
 * informativ.
 */
object AppVersion {
    private val props: Properties = Properties().apply {
        AppVersion::class.java.getResourceAsStream("/version.properties")?.use { load(it) }
    }

    /** Semver der Produktversion, z. B. `1.1.0`. */
    val version: String = props.getProperty("version")?.trim()?.takeIf { it.isNotEmpty() } ?: "0.0.0-dev"

    /** Kurz-SHA des Builds, leer wenn beim Bauen kein Git-Kontext vorhanden war. */
    val commit: String = props.getProperty("commit")?.trim().orEmpty()

    /** Für Logs/Anzeige: `1.1.0 (a1b2c3d)` bzw. nur `1.1.0` ohne Commit. */
    val display: String = if (commit.isEmpty()) version else "$version ($commit)"
}
