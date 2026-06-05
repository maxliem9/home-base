---
id: 0007
title: iOS-App
status: backlog
category: feature
priority: low
source: prd.md (Post-MVP)
created: 2026-06-05
---

# 0007 — iOS-App

## Kontext
Es gibt eine Android-App (Jetpack Compose) und das Web-Frontend. Eine iOS-App fehlt.

## Aufgabe
- Native iOS-App (SwiftUI) gegen das bestehende Backend (REST + WebSocket).
- Feature-Parität mit Android: Todos, Einkauf, Notizen, Zeit, Rezepte, Abwesenheit.

## Offene Fragen / Notizen
- **Tech-Entscheidung zuerst:** natives SwiftUI vs. Kotlin Multiplatform (Logik mit
  Android teilen).
- Großes Vorhaben — nur sinnvoll, wenn iOS tatsächlich gebraucht wird.
