---
id: 0005
title: Wiederkehrende Todos
status: backlog
category: feature
priority: medium
source: prd.md (Post-MVP)
created: 2026-06-05
---

# 0005 — Wiederkehrende Todos

## Kontext
Das Todo-Domänenmodell (`INBOX → PLANNED → DONE`) kennt keine Wiederholung.
Wiederkehrende Aufgaben (z. B. „Müll rausbringen, wöchentlich") müssen manuell
neu angelegt werden.

## Aufgabe
- Recurrence am Todo modellieren (z. B. leichtgewichtige Regel: täglich / wöchentlich /
  monatlich + Intervall) — Flyway-Migration nötig.
- Beim Abschluss bzw. zur Fälligkeit automatisch die nächste Instanz erzeugen.
- Erzeugung über den vorhandenen Backend-Scheduler (analog Telegram-Digest).

## Offene Fragen / Notizen
- Modellierung: Template + generierte Instanzen vs. eine fortgeschriebene Aufgabe?
- Umfang der Regeln — voller RRULE-Standard ist Overkill; simple Presets reichen wohl.
