---
id: 0024
title: Zeiterfassung — Timer-Start/manueller Eintrag/Projektwechsel erlauben archivierte Projekte
status: backlog
category: bug
priority: low
source: PR #13 (Review session 2026-06-05)
created: 2026-06-05
---

# 0024 — Zeiterfassung lässt archivierte Projekte zu

## Kontext
Archivieren soll ein Projekt aus dem aktiven Workflow nehmen, aber das Backend lässt
`POST /time/entries/start` (`routes/TimeRoutes.kt:199-201`), `POST /time/entries` (manuell,
`:281-283`) und `PUT /time/entries/{id}` (Projektwechsel, `:323`) weiterhin auf archivierte
Projekte zu — geprüft wird ausschließlich die **Existenz** (`ProjectsTable…empty()`), nie
`ProjectsTable.archived`.

Das Web-UI versteckt archivierte Projekte zwar aus dem Start-Picker (`activeProjects`), die API
selbst ist aber ungeschützt; ein direkter Aufruf oder der Android-Client kann jederzeit Zeit
auf ein archiviertes Projekt buchen. Die Archiv-Semantik wird so umgangen.

## Aufgabe
- In `post("/start")`, im manuellen `post` und im `put("/{id}")` (bei gesetztem `newProjectId`)
  nach der Existenzprüfung zusätzlich `ProjectsTable.archived` lesen und bei `true` mit
  `400 INVALID_PROJECT`/`409` („project is archived") antworten.
- Entscheiden, ob Bearbeiten/Löschen bereits existierender Einträge eines nachträglich
  archivierten Projekts erlaubt bleibt (vermutlich ja — nur Neuanlage/Projektwechsel sperren).
- Test: Start auf archiviertem Projekt → erwarteter Fehlercode.

## Offene Fragen / Notizen
- Bewusst niedrig (2 vertraute Nutzer, kein Missbrauchsvektor).
- Sauberer Pfad für künftige Reports (0003), damit archivierte Projekte keine neuen Einträge
  mehr ansammeln.
