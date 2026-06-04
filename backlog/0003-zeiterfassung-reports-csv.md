---
id: 0003
title: "Zeiterfassung: server-seitige Reports / CSV-Export"
status: backlog
category: feature
priority: low
source: prd.md (Post-MVP)
created: 2026-06-05
---

# 0003 — Zeiterfassung: server-seitige Reports / CSV-Export

## Kontext
Die In-App-Auswertung pro Projekt/Woche ist bereits umgesetzt (`prd.md` ✅).
Offen ist ein **server-seitiger** Export für externe Weiterverarbeitung.

## Aufgabe
- Endpunkt unter `/api/v1/time/` für CSV-Export (Filter: Zeitraum, Projekt).
- Download-Auslösung im Web.
- Spalten z. B.: Projekt, Nutzer, Start, Ende, Dauer, Beschreibung.

## Offene Fragen / Notizen
- Format: reines CSV (genügt) oder Excel?
- Locale: Dezimaltrennzeichen/Datumsformat (deutsch vs. ISO).
- Dauer als Stunden (dezimal) oder hh:mm?
