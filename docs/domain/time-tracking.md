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
  breakMinutes?} teilt einen Eintrag atomar an der Trennzeit —
  Teil 1 behält die id (Ende = splitAt), Teil 2 wird neu angelegt (Start =
  splitAt + Pause, erbt Projekt/Beschreibung). Die Pause ist bewusst nur eine
  unerfasste Lücke, kein eigener Datensatz. Validierung: Trennzeit strikt im
  Eintrag, Pause endet vor dem Eintragsende (400 INVALID_RANGE).
  Broadcasts: ENTRY_UPDATED (Teil 1) + ENTRY_CREATED (Teil 2).
  Web: Scissors-Aktion an eigenen Einträgen (Liste + Projekt-Detail); Android analog
  (Scissors an abgeschlossenen Einträgen, Sheet mit Trennzeit/Pause +
  Live-Vorschau beider Teile, #66).
  - **Laufende Timer splitten (#634):** Der Split geht auch auf einem laufenden Eintrag —
    genau der Fall „vergessen, für die Pause zu stoppen und neu zu starten". Teil 1 wird
    abgeschlossen (Ende = Trennzeit), **Teil 2 läuft weiter** (`stoppedAt` bleibt null).
    Das offene Ende übernimmt dabei die Rolle von `stoppedAt`: Trennzeit **und** Pausenende
    müssen strikt vor `now` liegen, sonst 400 INVALID_RANGE (den früheren 409 ENTRY_RUNNING
    gibt es nicht mehr — Code samt Client-Mappings ist entfernt). Die
    Ein-laufender-Timer-pro-Person-Invariante hält, weil Teil 1 in derselben Transaktion
    geschlossen wird. UI: Scheren-Aktion am Timer-Hero (Web `TimeView`, Android `RunningHero`);
    der Split-Dialog rechnet gegen die tickende „jetzt"-Zeit, Vorschau zeigt für Teil 2
    „Läuft" statt einer Endzeit, und Fehlertexte sagen „… und jetzt" statt „… und Ende".
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
- **Effektiv-datierte Perioden (#31-Folge, V44):** Das Wochensoll kann sich ab einem
  Datum ändern (z. B. 40 h bis August, 32 h ab September), frühere Wochen behalten den
  damals gültigen Wert. `time_work_targets` hat dazu ein `valid_from` (DATE); jede
  (Person×Projekt) kann mehrere Perioden-Zeilen haben. Bestandsdaten liegen in der
  **Basisperiode `1970-01-01`** (immer vorhandener Fallback). Eindeutigkeit jetzt über
  (user, project, valid_from); der „ein Default pro Person"-Partialindex greift **pro
  Periode** (user, valid_from) WHERE is_default — die #59-Invariante gilt je Periode.
  Endpunkte: `POST /targets/{userId}/periods {validFrom}` legt eine Periode an, **geseedet
  aus der zu `validFrom` gültigen Periode** (die letzte mit valid_from ≤ validFrom); Basis
  ist nicht anlegbar (400), Duplikat → 409 PERIOD_EXISTS. `DELETE
  /targets/{userId}/periods/{validFrom}` löscht eine ganze Periode (Basis nicht löschbar →
  400 BASE_PERIOD, unbekannt → 404). `PUT /targets/{userId}/{projectId}` nimmt optional
  `validFrom` im Body (fehlt → Basis; **encodeDefaults=false lässt validFrom für die Basis
  weg** → Clients lesen „fehlt" als `1970-01-01`). Periode-Create/Delete senden ein
  **payload-loses** TARGET_UPDATED (Clients refetchen die ganze Liste). Web/Android: die
  Wochensoll-Editoren wählen die Periode oben aus und legen neue an; die Perioden sind in
  der UI **haushaltsweit** (Create/Delete loopt über beide Personen).
- GET /api/v1/time/forecast (optional ?date=, für Tests) berechnet **serverseitig**
  pro Person und ISO-Woche (Mo–So): Für jede Person greift die **zum Montag der Woche
  gültige Periode** (letztes valid_from ≤ weekStart; Wochen vor der frühesten Periode haben
  0 h). Tagessoll = Wochensoll ÷ Arbeitstage (Mo–Fr
  minus Teilzeit-freie Tage; Feiertage/Abwesenheiten verkleinern den Teiler nicht).
  Urlaub/Krank/Kind-krank und Feiertage schreiben das Tagessoll dem Default-Projekt
  gut (halbe Tage = 0,5×). Tagesziel = offener Wochenrest ÷ verbleibende erfassbare
  Tage (Über-/Unterstunden verschieben sich so in die Restwoche); voraussichtliches
  Ende = jetzt + (Tagesziel − heute erfasst), nur bei laufendem Timer, nie in der
  Vergangenheit. Zusätzlich Pro-Projekt-Saldo. Einträge zählen zum lokalen Datum
  ihres Starts (Serverzone, wie CSV-Export).
- **Historische Zeitgutschriften (#31):** Dieselbe Krank-/Urlaub-/Kind-krank-/Feiertags-Gutschrift,
  die die Wochenbilanz für die *laufende* Woche rechnet, zählt auch rückwirkend. Die Gutschrift-Mathematik
  liegt geteilt in `time/TimeCredits.kt` (`workPortion`/`dayCredit`/`stateFor` + `TimeCreditService`) —
  **einzige Quelle**, `ForecastService` ruft dieselben Helfer auf, damit Verlauf und Live-Bilanz für dieselben
  Tage nie auseinanderlaufen. **Bewusster Unterschied:** die historischen Flächen enden bei **heute** (Verlauf =
  „bisher"), die Live-Wochenbilanz rechnet die **ganze** laufende Woche inkl. künftiger Tage — eine vorab für
  später in dieser Woche eingetragene Abwesenheit steht schon im Hero, aber erst am jeweiligen Tag in der
  Pro-Woche-Liste. Eine Gutschrift = Tagessoll der Person auf ihr **Default-Projekt** an einem Abwesenheits-/
  Feiertag (halbe Tage 0,5×); ohne Default-Projekt entsteht keine Gutschrift. Label-Präzedenz: die eingetragene
  Abwesenheit gewinnt vor einem zusammenfallenden Feiertag (Summe bleibt exakt, Label bleibt aussagekräftig).
  `TimeCreditService` wählt das Wochensoll **pro Woche** aus der dafür gültigen Periode (wie der Forecast, s. o.),
  damit rückwirkende Gutschriften gegen den **damals** gültigen Wert rechnen (z. B. 8 h/Tag vor, 6,4 h/Tag ab der
  Reduktion) — Voraussetzung dafür, dass historische Überstunden zum alten Soll passen.
  - `GET /api/v1/time/credits?from=&to=` (beide Pflicht, YYYY-MM-DD) → `[{userId,date,projectId,seconds,type}]`.
    Web `TimeView` **und** Android `TimeViewModel` laden sie best-effort über die Spanne der geladenen Einträge
    (frühester Eintragstag → heute); die Projekt-Detail-**Pro-Woche**-Liste faltet sie in Wochensumme/Balken/Nutzer
    ein (`count` bleibt Eintrags-only; reine Abwesenheitswochen erzeugen eigene Zeilen). Die Faltung liegt geteilt/
    testbar in `ui/time/TimeMath.kt::buildWeekStats` (Android) bzw. inline in `ProjectDetail` (Web). Credits werden
    **nicht** offline gecacht (wie der Forecast, #520). Nachträglich im Kalender eingetragene Abwesenheiten erscheinen
    erst beim nächsten Load/Sync (Verlauf ist nicht live).
  - **CSV-Export** (`/time/export.csv`) mischt Gutschrift-Zeilen (Datum, gutgeschriebene Stunden, leeres Ende,
    Label „Krank/Urlaub/… (Zeitgutschrift)") nach Tag sortiert unter die Einträge; Bereich = Query-Grenzen, sonst
    Einträge-Spanne bis heute; `project_id`-Filter greift auch für Gutschriften (nur das jeweilige Default-Projekt).
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

## Offline-Read-Cache (#520)
Wie beim Einkauf (#517/#518): die zuletzt geladenen Zeit-Daten werden durabel gecacht und beim
(Kalt-)Start geseedet, damit ein Launch ohne Verbindung die letzten Einträge zeigt statt eines leeren
Screens. Android: `TimeSnapshot(projects, entries, users, forecast, targets)` über `SnapshotStore<T>`,
Prefs-Datei `homebase_time_cache`; der laufende Timer (`running`/`othersRunning`) wird beim Seed aus den
gecachten `entries` neu abgeleitet, `forecastAt` wird **nicht** gecacht (kein Live-Tick offline). Web:
`localStorage['homebase_time_cache']` mit projects+entries+users (Forecast/Wochenbilanz bewusst nicht
gecacht — sie blendet sich ohne Daten sauber aus und ihr Live-Tick braucht einen Fetch-Zeitstempel).
Seed vor dem Mirror-Collector (Android) bzw. in den `useState`-Initialisierern (Web), `hasServerData`-Guard,
`error` nur wenn nichts anzuzeigen ist. Wie #518 greift der Web-Cache nur bei flakiger Verbindung /
erstem Paint (#519).
