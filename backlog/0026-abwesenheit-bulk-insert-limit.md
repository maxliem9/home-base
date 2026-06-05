---
id: 0026
title: Abwesenheit — kita/range & entries/batch ohne Bereichs-/Größenlimit (unbegrenzter Insert in einer Transaktion)
status: backlog
category: bug
priority: low
source: PR #27 (Review session 2026-06-05)
created: 2026-06-05
---

# 0026 — Abwesenheit: Bulk-Insert begrenzen

## Kontext
`POST /kita/range` (`routes/AbsenceRoutes.kt:228-243`) iteriert `from..to` tageweise **ohne
Obergrenze** (einzige Korrektur: Vertauschen bei `from.isAfter(to)`). `POST /entries/batch`
(`:107-137`) verarbeitet die clientgelieferte `dates`-Liste ungeprüft auf Länge.

`POST /kita/range` mit z. B. `from=2000-01-01&to=3000-01-01` erzeugt in einer einzigen
Transaktion Hunderttausende `INSERT`s; `entries/batch` kann mit beliebig langer `dates`-Liste
dasselbe (je Datum ein `DELETE`+`INSERT`). Da `kita_closures` nur einen nicht-eindeutigen
Index auf `date` hat (`V8__create_absence.sql`), dupliziert ein erneuter `/range`-Aufruf zudem
alle Schließtage. Bei 2 vertrauten Nutzern primär ein Robustheits-/Datenintegritäts-Problem,
aber ein Client-Bug oder Vertipper kann die DB aufblähen bzw. die Anfrage lange blockieren.

## Aufgabe
- In `kita/range` die Spanne begrenzen (z. B. max. 366/731 Tage, sonst `400`) und Duplikate
  vermeiden (vor `insertKita` prüfen, ob für `date` bereits eine Schließung existiert, oder
  Unique-Index/`ON CONFLICT`).
- In `entries/batch` die `dates`-Listenlänge deckeln (z. B. max. einige hundert) und bei
  Überschreitung `400` zurückgeben.

## Offene Fragen / Notizen
- `BatchAbsenceRequest.dates = emptyList()` ist der bekannte encodeDefaults-Aspekt aus 0008 —
  hier nicht erneut gemeldet.
