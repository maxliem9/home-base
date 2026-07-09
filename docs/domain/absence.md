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
