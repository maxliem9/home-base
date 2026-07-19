# Abwesenheit-Domänenmodell (Familienkalender)

> Lies dies, bevor du am Abwesenheits-/Familienkalender arbeitest.

Geteilter Haushalts-Abwesenheitsplaner (Excel-Ersatz). Das Backend ist reine
Persistenz; abgeleitete Tageszustände (Feiertage, Teilzeit-frei, Wochenende),
Summen und Arbeitstag-Logik werden im Client (web/src/components/abwesenheit/)
berechnet.
- Absence: id, user_id, date, type (URLAUB|KRANK|KIND_KRANK), half? (vm|nm) —
  max. ein Eintrag pro Nutzer/Tag (Unique-Index user_id+date).
- PartTimeRule: id, user_id, weekday (ISO 1–7), start, end? — feste freie Tage.
- KitaClosure: id, date, label — haushaltsweite Schließtage (Hintergrundmarker).
- AbsSettings: user_id (PK), state (Bundesland-Code), allowance, carryover,
  carryover_expires?, kind_krank_cap — eine Zeile pro Nutzer, lazy beim ersten
  Edit angelegt.
- Endpunkte unter /api/v1/absence: GET / (Snapshot inkl. users-Liste),
  entries (POST set, DELETE clear, POST /batch für Zeiträume), parttime (CRUD),
  kita (POST, POST /kita/range, PUT, DELETE), settings PUT /settings/{userId}/{year}.
- WebSocket /api/v1/ws/absence (Channel "absence"): jede Mutation sendet
  {type:"ABSENCE_CHANGED"}; Clients laden den Snapshot neu.
- Deutsche Feiertage werden pro Bundesland aus Ostern (Gauß-Algorithmus) +
  festen Daten berechnet (holidays.ts), nicht gespeichert.
- Berechtigungsmodell: Der Kalender ist bewusst gemeinsam — beide Nutzer dürfen
  alle Tage/Regeln beider Personen bearbeiten (entries, parttime, kita, settings);
  die in diesen Routen mitgeschickte userId ist daher die Zielperson, nicht der
  Aufrufer. Auch die persönliche Konfiguration (`PUT /settings/{userId}/{year}`:
  Kontingent/Übertrag/Bundesland/Kind-krank-Cap) ist gemeinsam editierbar; die
  frühere Eigentümer-Beschränkung wurde für den 2-Personen-Haushalt bewusst
  aufgehoben.

## Termine/Events (#434)
Kalender-Termine (Arzt, Tierarzt, Geburtstage …) — eine eigene, vom Abwesenheitsplaner **getrennte**
Domäne, die aber im selben Familienkalender-Overlay (#435) und im iCal-Feed (#427/#462) auftaucht,
darum hier mitdokumentiert. **Kein Wiederholungsmodell:** jedes Event ist ein Einzeltermin an genau
einem Datum (die im Feed sichtbare Wochen-`RRULE` gehört zu den Teilzeit-Regeln, nicht zu Events).

### Datenmodell (`calendar_events`, Migration V35)
`CalendarEvent`: id, title (≤ 200), type, date, all_day, start_time?, end_time?, location?, notes?,
created_by, created_at.
- **type** ist ein roher String aus einem festen Set (`EVENT_TYPES` in `EventRoutes.kt`):
  `APPOINTMENT | BIRTHDAY | VET | OTHER`. `OTHER` ist der neutrale Default; unbekannter/leerer Typ
  wird beim Schreiben auf `OTHER` normalisiert bzw. (wenn explizit falsch) mit 400 abgelehnt.
- **Zeit-Invarianten** (per DB-CHECK gespiegelt, in `parseEvent` validiert): `all_day=true` trägt
  **keine** Uhrzeit (start/end beide NULL); bei `all_day=false` ist `start_time` optional, `end_time`
  nur mit `start_time` erlaubt und `end_time ≥ start_time`. Uhrzeiten als `HH:mm` (auch `HH:mm:ss`
  akzeptiert).
- `location`/`notes` werden getrimmt (blank → null) und längenbegrenzt (500 / 5000; 400 bei
  Überschreitung).

### API (`/api/v1/events`, `EventRoutes.kt`)
Haushalts-geteilt — jeder sieht/erstellt/ändert/löscht jedes Event; **keine** Owner-Prüfung.
`created_by`/`created_at` bleiben bei `PUT` als Herkunft der Erstanlage erhalten.
- `GET /events?from=YYYY-MM-DD&to=YYYY-MM-DD` — Events im **inklusiven** Datumsbereich (das Kalender-Fenster
  holt den sichtbaren Monat). Sortiert nach date, dann start_time (NULLs zuerst). Der Bereich ist auf
  `MAX_RANGE_DAYS = 370` begrenzt (RANGE_TOO_LARGE), `from > to`/kaputte Daten → 400 (INVALID_RANGE).
- `POST /events` → 201 mit dem angelegten `CalendarEventDto`; ungültige Eingabe → 400 (INVALID_EVENT).
- `PUT /events/{id}` → ersetzt das Event komplett (kein Tri-State-Merge; das Request-DTO trägt das
  ganze Objekt); fehlend → 404.
- `DELETE /events/{id}` → 204; fehlend → 404.

### WS-Sync (Kanal `events`)
Type-only-Broadcast: **jede** Mutation sendet `{"type":"EVENT_CHANGED"}` **ohne Payload** (wie
`absence`/`meal-plan`) über `WsSessionManager.broadcastSync(EVENTS_WS_CHANNEL, "EVENT_CHANGED")`; der
Endpunkt hängt über `syncChannel("events")`. Clients laden bei Empfang den sichtbaren Bereich neu
(kein inkrementeller Upsert wie bei todos/shopping — der Range-Fetch ist billig).

### iCal-Feed (#462, `CalendarRoutes.kt`)
Der `EVENTS`-Abschnitt des `/calendar.ics`-Feeds spiegelt die Events, sofern der Abonnent die Kategorie
gewählt hat (`CalendarFeedSection.EVENTS`, per-User in `user_prefs`; unset = alle). Getimte Events werden
zu einem echten `DTSTART/DTEND`-VEVENT (Server-Zeitzone), all-day/zeitlose zu einem Datums-Banner;
`notes → DESCRIPTION`, `location → LOCATION`, ein Emoji-Prefix je Typ (`EVENT_EMOJI`).

### Konsumenten
- **Android:** `EventWebSocketClient` (parst nur `EVENT_CHANGED` → `WsEvent.Changed`) + `getEvents(from,to)`
  in `CalendarRepository`; der `FamilienkalenderViewModel` re-fetcht den Monat. Der Offline-Cache
  (`CalendarSnapshot`, #520) speichert die Events des aktuell sichtbaren Monats mit.
- **Web:** `useCalendarData.ts` — `useWebSocket({ url: wsUrl('events') }, fetchEvents)` lädt bei jedem
  `EVENT_CHANGED` (bzw. Reconnect) den Bereich neu.

## Offline-Read-Cache (#520)
Wie beim Einkauf (#517/#518): der zuletzt geladene Planner-Snapshot wird durabel gecacht und beim
(Kalt-)Start geseedet, damit ein Launch ohne Verbindung den letzten Stand zeigt statt eines leeren
Rasters. Android: `AbsenceSnapshot(data)` (die ganze `AbsenceStateDto`) über `SnapshotStore<T>`,
Prefs-Datei `homebase_absence_cache`; VM seedet in `restoreAndMirrorSnapshot()` (Guard via
Default-Gleichheit + `hasServerData`), Mirror auf jede Änderung. Web: `useAbsenceData` seedet `data`
aus `localStorage['homebase_absence_cache']` und spiegelt per `useEffect([data])`. Wie #518 greift der
Web-Cache nur bei flakiger Verbindung / erstem Paint (#519).

## Familienkalender-Overlay: Offline-Read-Cache (#520)
Das read-only Monats-Overlay (#435) cacht denselben Weg wie die anderen Views. Meals + Events sind
**monatsabhängig** (Range-Fetch), daher wird die gecachte Grid-Start-Woche (`monthAnchor` bzw. `from`)
mitgespeichert und diese beiden nur geseedet, wenn sie == aktuell sichtbarem Monat ist; Todos + der
Abwesenheits-Snapshot sind ganz (frei geseedet). Android: `CalendarSnapshot(monthAnchor, todos,
absences, kitaClosures, meals, events)` über `SnapshotStore<T>`, Prefs `homebase_calendar_cache`. Web:
`localStorage['homebase_calendar_cache']` in `useCalendarData` (Seed in den `useState`-Initialisierern,
Mirror per `useEffect`). Wie #518 greift der Web-Cache nur bei flakiger Verbindung / erstem Paint (#519).
