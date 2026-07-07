# Zeiterfassung-Domänenmodell

> Lies dies, bevor du an der Zeiterfassung, Wochensoll oder Ende-Prognose arbeitest.

Project: id, name, color (Hex), archived, created_by, created_at
TimeEntry: id, project_id, user_id, started_at, stopped_at?,
description?, created_at, updated_at
- duration = stopped_at - started_at (nur wenn stopped_at gesetzt)
- Pro Nutzer höchstens ein laufender Timer (stopped_at IS NULL);
  ein neuer Start stoppt den laufenden automatisch. DB-Garantie über
  partiellen Unique-Index, Anwendungslogik stoppt aktiv.
- Beide Nutzer sehen alle Einträge aller Projekte — und verwalten sie gemeinsam:
  Bearbeiten/Löschen/Splitten geht auch auf Partner-Einträgen, Timer und manuelle
  Einträge (POST /entries mit optionalem `userId`, wie start/stop) lassen sich für
  den Partner anlegen. Die API prüft kein Eigentum; **die UI bestätigt jede
  Cross-Person-Aktion** vorher per `<ConfirmDialog>` (nie `window.confirm()`, #125/#129).
- Endpunkte unter /api/v1/time/ (projects, entries, entries/start,
  entries/stop, running). WebSocket: /api/v1/ws/time (Channel "time").
- CSV-Export: GET /api/v1/time/export.csv (Filter project_id/from/to wie bei
  entries; nur abgeschlossene Einträge). Liefert `text/csv` mit UTF-8-BOM,
  `;`-Trennung und lokalen Zeitstempeln (Excel-DE-freundlich); Dauer als
  Dezimalstunden und hh:mm. Felder, die mit `=`/`+`/`-`/`@` beginnen, bekommen ein
  führendes `'` (CSV-Formel-Injection-Schutz; das Apostroph ist in Excel sichtbar —
  akzeptierter Tradeoff).
- Eintrag splitten (#62): POST /api/v1/time/entries/{id}/split {splitAt,
  breakMinutes?} teilt einen **abgeschlossenen** Eintrag atomar an der Trennzeit —
  Teil 1 behält die id (Ende = splitAt), Teil 2 wird neu angelegt (Start =
  splitAt + Pause, erbt Projekt/Beschreibung). Die Pause ist bewusst nur eine
  unerfasste Lücke, kein eigener Datensatz. Validierung: Trennzeit strikt im
  Eintrag, Pause endet vor dem Eintragsende (400 INVALID_RANGE), laufende Timer
  → 409 ENTRY_RUNNING. Broadcasts: ENTRY_UPDATED (Teil 1) + ENTRY_CREATED (Teil 2).
  Web: Scissors-Aktion an eigenen Einträgen (Liste + Projekt-Detail); Android analog
  (Scissors an eigenen abgeschlossenen Einträgen, Sheet mit Trennzeit/Pause +
  Live-Vorschau beider Teile, #66).
- created_by / user_id werden — wie im restlichen Projekt — als
  username (VARCHAR, FK users.username) gespeichert, nicht als UUID.

## Wochensoll & Ende-Prognose (Issue #31)
- `time_work_targets`: Wochenstunden pro Person×Projekt (Default 0) + genau ein
  Default-Projekt pro Person (`is_default`, partieller Unique-Index). Endpunkte:
  GET /api/v1/time/targets, PUT /api/v1/time/targets/{userId}/{projectId}
  ({weeklyHours?, isDefault?}; haushalts-geteilt wie der Abwesenheitskalender —
  die userId ist die Zielperson). Änderungen senden TARGET_UPDATED auf Channel "time".
  **Default-Pflicht (#59):** Stunden > 0 ⇒ es existiert genau ein Default-Projekt —
  das erste Projekt mit Soll wird automatisch Default; explizites `isDefault:false`
  auf dem letzten Default wird bei verbleibenden Stunden mit 409 DEFAULT_REQUIRED
  abgelehnt (Wechsel weiterhin via `isDefault:true` auf einem anderen Projekt).
  Damit summieren sich Projekt-Saldi stets zum Personen-Saldo.
- GET /api/v1/time/forecast (optional ?date=, für Tests) berechnet **serverseitig**
  pro Person und ISO-Woche (Mo–So): Tagessoll = Wochensoll ÷ Arbeitstage (Mo–Fr
  minus Teilzeit-freie Tage; Feiertage/Abwesenheiten verkleinern den Teiler nicht).
  Urlaub/Krank/Kind-krank und Feiertage schreiben das Tagessoll dem Default-Projekt
  gut (halbe Tage = 0,5×). Tagesziel = offener Wochenrest ÷ verbleibende erfassbare
  Tage (Über-/Unterstunden verschieben sich so in die Restwoche); voraussichtliches
  Ende = jetzt + (Tagesziel − heute erfasst), nur bei laufendem Timer, nie in der
  Vergangenheit. Zusätzlich Pro-Projekt-Saldo. Einträge zählen zum lokalen Datum
  ihres Starts (Serverzone, wie CSV-Export).
- Gesetzliche Feiertage berechnet das Backend selbst: `holidays/GermanHolidays.kt`
  ist der Kotlin-Port von `web/src/components/abwesenheit/holidays.ts` — **beide
  synchron halten**. Bundesland je Nutzer aus abs_settings (nearest-year,
  Fallback BE); eigene/halbe Feiertage (#51) kommen aus der DB.
- Web: Ende-Prognose am Timer-Hero/Partner-Strip/Dashboard-Peek, „Wochensoll"-Karte
  (Soll/Ist, Heute-Ziel, Gutschriften, Projekt-Saldi) + Konfigurations-Modal in
  `TimeView`. Bei laufendem Timer ticken die Karten-Werte live weiter (Sekunden seit
  dem Forecast-Snapshot werden client-seitig addiert, #59). Projekt-Kacheln zeigen
  Tages- + Wochensaldo statt Gesamtsumme — vom aktuellen Tag/Woche, sonst Fallback
  auf den letzten aktiven Tag bzw. die letzte aktive Woche (#59). Android analog:
  Live-Tick + Kachel-Saldi in `ui/time` (TimeMath.kt, #64).
