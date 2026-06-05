---
id: 0019
title: Backend-Dockerfile + CI bauen/laufen auf Java 24 (non-LTS), obwohl der Build seit #22 auf 21 LTS zielt
status: backlog
category: tech-debt
priority: medium
source: PR #11 (Review session 2026-06-05)
created: 2026-06-05
---

# 0019 — Docker/CI auf Java 21 LTS nachziehen

## Kontext
PR #22 hat den Backend-Build bewusst auf **Java 21 LTS** gesenkt
(`backend/build.gradle.kts:54-66`, Java- und Kotlin-Target `VERSION_21`/`JVM_21`; SDKMAN
bietet kein 24, ein LTS deckt Backend + Android ab). Dockerfile und CI wurden **nicht**
nachgezogen: `backend/Dockerfile:1-4,12` nutzt weiterhin `eclipse-temurin:24-jdk`/`24-jre`,
`ci.yml:20-25,68-73` richtet weiterhin JDK 24 ein, und Kommentare behaupten „match the
backend's Java 24 source/target" (`Dockerfile`, `ci.yml:196-197`) — seit #22 sachlich falsch.

Funktional bricht nichts (JDK 24 kompiliert nach Bytecode 21, läuft auf JRE 24), aber das
Laufzeit-Image ist `eclipse-temurin:24-jre`, also liefert die NAS auf einem **non-LTS-JRE**
(kurzer Supportzeitraum) aus — die in #22 getroffene LTS-Entscheidung wird in der Auslieferung
still unterlaufen.

## Aufgabe
- `backend/Dockerfile` auf `eclipse-temurin:21-jdk`/`21-jre` umstellen, Kommentare auf
  „Java 21 LTS" korrigieren.
- In `ci.yml` die Backend- und Migrations-Jobs auf `java-version: '21'` setzen und die
  „Java 24"-Kommentare (Zeilen 20, 68, 196-197) anpassen.
- Danach `gradlew test`/`buildFatJar` und den Docker-Build gegenprüfen.

## Offene Fragen / Notizen
- Bezug zur Memory-Notiz „Backend Kotlin jvmTarget pinned to 21". Build-intern ist
  `build.gradle.kts` konsistent (21/21) — die Inkonsistenz liegt ausschließlich zwischen
  Build-Target (21) und Container-/CI-JDK (24).
- Kein Überlapp mit 0012 (das betrifft nur die Migrations-CI-Härtung).
