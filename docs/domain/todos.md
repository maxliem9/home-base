# Todo-Domänenmodell

> Lies dies, bevor du an Aufgaben/Todos, Inbox, Zuständigen oder Wiederholung arbeitest.
> Cross-cutting Regeln (Serialisierung, Modal-vs-Seite, Auto-Save-Prinzip) stehen in der `CLAUDE.md`.

## Offline-Read-Cache — „alter Stand statt leerem Bildschirm" (#520)
Analog zum Einkauf (#517, siehe [docs/domain/shopping.md](shopping.md)) cachet die Aufgaben-View die
zuletzt erfolgreich geladenen **Listen + Todos** durabel und seedet sie beim (Kalt-)Start in den State,
**bevor** der Fetch antwortet — offline sieht man den alten Stand, online ersetzt der erste erfolgreiche
Fetch ihn (stale-while-revalidate). Gecacht werden beide Datensätze, die die View lädt (volle Fidelity):
`lists` (die Tabs) und `todos` (die Zeilen inkl. Zuständige/Subtasks/Wiederholung). Die
konfigurierbare „Erledigt"-Fensterlänge (`done_window_days`, #356) wird **nicht** gecacht — sie hat einen
sicheren Code-Default, auf den die View ohnehin bis zum GET zurückfällt (degradiert offline sauber).
- **Android:** generischer `SnapshotStore<T>` (`data/cache/`, SharedPreferences+Moshi, eigene Prefs-Datei
  `homebase_todos_cache`) → `TodoSnapshot(lists, todos)` (`data/aufgaben/`). Der VM seedet in
  `restoreAndMirrorSnapshot()` (Disk-Read **vor** dem Mirror-Collector, sonst überschreibt der leere
  Startframe den guten Cache) und spiegelt via `uiState.map{lists,todos}.distinctUntilChanged()`.
  `hasServerData` verhindert, dass ein langsamer Disk-Read frische Serverdaten überschreibt; `reload`
  hält `error` nur noch dann, wenn ohnehin nichts anzuzeigen ist (mit gecachten/vorhandenen Daten
  schluckt ein fehlgeschlagener Offline-Refresh den Fehler — die reconnect/backstop-Resync korrigiert).
  Der `snapshotStore`-Param ist nullable-default → bestehende Tests laufen ohne Cache unverändert.
- **Web:** `localStorage['homebase_todos_cache']`, geseedet in den `useState`-Initialisierern
  (`useMemo(loadCache)`), gespiegelt per `useEffect([lists, todos])`. Wie beim Einkauf greift der
  Cache nur bei flakiger Verbindung (Shell aus dem Browser-Cache, `/api` schlägt fehl) und für den
  sofortigen ersten Paint — echter Offline-Shell-Betrieb braucht Asset-Caching im SW → #519.

Status-Flow: INBOX → PLANNED → DONE
- INBOX:   nur title gesetzt, alle anderen Felder optional
- PLANNED: mindestens ein:e Zuständige:r oder due_date gesetzt
- DONE:    done_at gesetzt

Felder: id, title, description?, status, assignees[],
due_date?, priority (LOW|MEDIUM|HIGH)?, list_id?,
recurrence?, created_by, created_at, done_at?

## Zuständige (Mehrfachauswahl, V39)
Ein Todo kann an **eine beliebige Teilmenge** des Haushalts gehen (niemand, eine:r, „beide"). Statt
der früheren einzelnen `assignee`-Spalte liegen die Zuständigen in der Join-Tabelle
`todo_assignees(todo_id → todos, username → users)` (Composite-PK; `V39__todo_multi_assignee.sql`
legt sie an, backfillt die alte Spalte und dropt sie). DTO-Feld **`assignees: string[]`** (leer =
weggelassen, encodeDefaults=false → Clients `?? []` bzw. Moshi `= emptyList()`). Update-Konvention
(#265-Analog als Liste): `null`/fehlend = unverändert, `[]` = alle entfernen, nicht-leer = ganze
Menge ersetzen. Unbekannte Usernamen werden beim Schreiben mit **400 INVALID_ASSIGNEE** abgelehnt.
Auf Rezept-/Kalender-/Reminder-Wegen werden die Zuständigen per Join geladen; Ausgabe stabil nach
Username sortiert. **Benachrichtigungen bleiben haushaltsweit** (geteilter Telegram-Chat + alle
Web-Push-Subscriptions) — die Zuständigen stehen nur informativ im Text, sie steuern nicht den
Empfängerkreis. Multiselect-Chips (Web `AssigneePicker`, Android `AssigneeChips`); ein Klick auf
Datum bzw. Zuständige einer Zeile öffnet einen Quick-Edit-Dialog (Web + Android, „Speichern") nur
für dieses eine Feld. Der Dialog schließt **nur bei Erfolg** — schlägt der PUT fehl, bleibt er mit
der Eingabe offen und zeigt den Grund inline (sonst geht die Eingabe verloren, etwa wenn das
Löschen der Fälligkeit an einer wiederkehrenden Aufgabe mit `INVALID_RECURRENCE` abgelehnt wird).
Der Status wird dabei wie im Plan-Sheet neu abgeleitet, der jeweils **andere** Anker aber live
gelesen: eine erledigte Aufgabe bleibt erledigt, eine parallele Änderung wird nicht überschrieben.

## Wiederkehrende Todos
Leichtgewichtige Wiederholung direkt am Todo (kein Template/Instanz-Split, keine RRULE):
- `recurrence` (DTO `{freq, interval}`): freq DAILY|WEEKLY|MONTHLY, interval = alle N Einheiten
  (default 1). DB-Spalten `recurrence` + `recurrence_interval`; auf dem Update-DTO löscht
  freq `"NONE"` die Regel. Ein wiederkehrendes Todo braucht immer ein `due_date` als Anker
  (per Validierung + DB-CHECK erzwungen).
- **Abschluss-getrieben:** Wird ein wiederkehrendes Todo auf DONE gesetzt, erzeugt das Backend
  sofort die nächste Instanz (due_date = nächste Fälligkeit, festes Schema ab altem due_date;
  Subtasks werden unerledigt mitkopiert). Die Regel wandert auf den Nachfolger; das erledigte
  Todo wird zu schlichter Historie (recurrence geleert) und als TODO_CREATED gesendet.
- **Safety-Net-Scheduler** (`recurrence/RecurringTodoScheduler`, analog Telegram-Digest, täglich
  zu RECURRING_TIME, default 00:30): rollt ein verpasstes, noch offenes wiederkehrendes Todo
  (due_date in der Vergangenheit) auf die aktuelle Periode vor — überspringt nur ganz
  verstrichene Perioden, erzeugt **keine** zweite Zeile, sendet TODO_UPDATED. So bleibt genau
  eine offene Instanz, kein Auflaufen.

## Aufgaben-View (Web & Android): Inbox-Semantik
Web (`components/TodosView.tsx`): Inbox-Tab als erster Tab vor den
Listen-Tabs. **Inbox-Semantik (#71): „alles Unverplante"** — der Tab zeigt alle
Todos mit Status INBOX (auch wenn sie schon in einer Liste liegen; Quick-Add in
eine Liste erzeugt solche) **plus** alle listen-losen Todos unabhängig vom Status
(Dashboard-Quick-Add/Android-FAB; so bleibt nichts unerreichbar, #69). Unverplante
Listen-Todos tragen im Inbox-Tab ihre Herkunfts-Liste als Meta. Badge = Anzahl
Status-INBOX-Todos — dieselbe Zählweise wie die Dashboard-Kachel „In der Inbox"
und die Digest-Vorschau. Quick-Add im Inbox-Tab postet ohne `listId`; das
Planen-Modal bietet für listen-lose Todos zusätzlich eine Listen-Auswahl (PUT mit
`listId` verschiebt in die Liste). Default-Tab bleibt die erste Liste; ohne
Listen ist die Inbox der Default (kein „Noch keine Liste"-Empty-State mehr).
Siehe Issues #69/#71.

Android (`ui/aufgaben/`): eigener Inbox-Tab als erster Tab vor den
Listen-Tabs mit derselben Semantik wie das Web (#71/#77): Status-INBOX-Todos plus
alle listen-losen Todos, Herkunfts-Liste als Meta, Badge = Anzahl Status-INBOX-Todos
(`TodoUiState.inboxCount`, auch von der HeuteScreen-Kachel genutzt). Quick-Add
im Inbox-Tab postet ohne `listId`; das Edit-Sheet bietet die Listen-Auswahl für
**jede** bestehende Aufgabe (Web-Parität #409/#509): aktuelle Liste vorausgewählt,
„Ohne Liste (Inbox)" als erste Option, Wechsel per PUT `listId`. Das frühere
Catch-all-Verhalten des ersten Listen-Tabs entfällt; ohne Listen ist die Inbox der Default-Tab.

**Liste im Edit-Sheet wechseln (#509):** Der Picker sitzt im Auto-Save-Draft-Fluss —
`TodoDraft.targetListId` ist die gewählte Liste (`null` = Inbox), beim Öffnen auf die
eigene Liste der Aufgabe geseedet. Der VM hält die Liste bei Öffnen als Baseline
(`editorListIdOriginal`) und schickt `listId` nur bei echter Änderung (`null` = unverändert,
`""` = raus aus der Liste/Inbox, UUID = in diese Liste — #265-Sentinel); nach jedem eigenen
Move wird die Baseline rebaset, damit ein späterer, unabhängiger Auto-Save den Wechsel nicht
erneut sendet und einen parallelen Partner-Move nicht überschreibt (analog `listIdOriginal` auf Web).

## Edit-Sheet: Auto-Save beim Bearbeiten (Android), Neuanlage explizit
**Auto-Save beim Bearbeiten (live, wie der Notizen-Editor); Neuanlage explizit.**
Ein **bestehendes** Todo im Edit-Sheet hat **keinen** „Speichern"-Button mehr — Änderungen
persistieren automatisch (~1 s debounced nach der letzten Eingabe **plus** beim Schließen über
✕/Scrim/Back). Die Orchestrierung liegt im **`TodoViewModel`** (nicht im Composable), damit ein
Save das Schließen des Sheets überlebt (analog `NotesViewModel`): `openTodoEditor`
(Baseline/Dirty-Check), `updateTodoDraft` (Debounce), `closeTodoEditor` (Flush + Editor leeren),
`discardTodoEditor` (Papierkorb = löscht das Todo). Saves sind **serialisiert** (`saveJob`-Loop).
Das Sheet schiebt pro Feldänderung einen normalisierten `TodoDraft`; ungültige Entwürfe (leerer
Titel / Wiederholung ohne Fälligkeit) speichern nicht. Status-Chip (Speichert…/Gespeichert) +
In-Sheet-Fehler; ein fehlgeschlagener Close-Save fällt auf den globalen Toast zurück.
**Neue Todos werden bewusst NICHT auto-angelegt:** das Erstell-Sheet hat „Abbrechen"/„Erstellen"
(Schließen/Back = verwerfen, nur der Button legt an — `createTodoFromDraft`, sendet **alle** Felder
inkl. Uhrzeit/Wiederholung). So spawnt ein „Titel tippen und zumachen" kein Geister-Todo. Web-Todos
haben denselben Ansatz (Edit-Panel auto-saved, Neuanlage per Quick-Add/Plan-Button).
