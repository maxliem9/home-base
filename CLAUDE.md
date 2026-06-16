# HomeBase

Privater Familien-Hub für 2 Nutzer mit Echtzeit-Sync
zwischen Android (Compose) und Web (React).

## Projektstruktur (Monorepo)

homebase/
├── backend/                  — Kotlin + Ktor API + WebSocket Server
├── web/                      — React + Vite + TS Frontend (nginx: SPA + /api-Proxy)
├── android/                  — Jetpack Compose App
├── docker-compose.yml        — Produktion (Synology NAS, Images aus GHCR)
├── docker-compose.dev.yml    — Lokale Entwicklung (nur DB)
├── .env.example
├── scripts/                  — setup-env / deploy / backup / restore
├── backlog/                  — dateibasiertes Backlog (ein File pro Vorhaben)
└── CLAUDE.md

## Backlog & Out-of-Scope-Funde
Geplante, aber noch nicht umgesetzte Features sowie Funde, die nicht zur gerade
laufenden Aufgabe gehören, leben als **GitHub Issues**. Das frühere dateibasierte
`backlog/` wurde nach Issues migriert (Hintergrund + alte-ID→Issue-Zuordnung in
`backlog/README.md`).

**Regel für alle (Menschen wie KI-Agenten):** Wenn dir während einer Aufgabe etwas
auffällt, das nicht zur laufenden Änderung gehört — ein Bug in fremdem Code, eine
Refactoring-Gelegenheit, eine Feature-Idee, fehlende Tests/Doku, technische Schuld —
dann blähe nicht den Scope der aktuellen Aufgabe auf und lass den Fund nicht fallen,
sondern leg ein GitHub Issue an:
1. Erst auf Duplikate prüfen: `gh issue list --search "<stichwort>"`.
2. Issue anlegen: `gh issue create --title "<knapp>" --body "<Kontext / Aufgabe / Notizen>"`
   (Herkunft im Body festhalten, z. B. „aus Review von PR #<n>").
3. Labeln: genau **ein** Kategorie-Label (`security` · `bug` · `tech-debt` · `test-gap` ·
   `feature` · `docs` · `chore`) und **ein** Prioritäts-Label
   (`priority:high` · `priority:medium` · `priority:low`).

Sofort miterledigen nur, wenn der Fund trivial ist **und** vom Auftrag gedeckt — sonst
ins Issue; ein ausdrückliches „mach das gleich mit" geht vor. Beim Umsetzen das Issue
über den PR schließen (`Closes #<n>` im PR-Body); Status/Assignee am Issue pflegen.

## Deployment
- Synology NAS, DSM 7.x, Container Manager (Docker)
- Erreichbar via DynDNS + HTTPS (kein VPN nötig)
- Synology DSM Reverse Proxy terminiert HTTPS (Port 443) → web (localhost:3000)
    - der web-Container (nginx) liefert das SPA und proxyt /api + WebSocket → backend:8080
    - kein eigener nginx-Container mehr; DSM ist die einzige TLS-Schicht
    - nginx maskiert `?token=`-Query-Params im Access-Log (`token=***`) und loggt für
      /api nur ab Severity crit ins Error-Log — die JWT-Bild-URLs (Android Coil) dürfen
      nie im Klartext in Logfiles landen. Nicht entfernen (web/nginx-spa.conf). Das
      DSM-Reverse-Proxy-Log liegt außerhalb des Repos und loggt URLs ggf. weiterhin —
      bei Bedarf in DSM konfigurieren.
- Let's Encrypt Zertifikat via Synology DSM (auto-renewal, kein Container-Neustart nötig)
- Backend/Web-Images werden von GitHub Actions gebaut und nach GHCR gepusht
  (ghcr.io/maxliem9/homebase-{backend,web}); die NAS **zieht** sie nur, baut
  nichts aus dem Quellcode. Tag über IMAGE_TAG (default latest). Privates Repo
  ⇒ private Packages ⇒ `docker login ghcr.io` auf der NAS nötig.
- Beide Container laufen **non-root** (backend uid 10001, web nginx uid 101).
  Das `uploads`-Volume muss dem Backend-User gehören: `scripts/deploy.sh` chownt
  es automatisch. Wer ein **bestehendes** (noch root-owned) Volume von Hand
  startet (z. B. Container-Manager-UI statt deploy.sh), muss es einmalig fixen:
  `docker compose run --rm --no-deps --user root --entrypoint chown backend -R 10001:10001 /data/uploads`
  — sonst schlägt der erste Bild-Upload mit AccessDenied fehl.

## Backend-Konventionen
- Kotlin, Ktor Framework
- Exposed ORM für Datenbankzugriff
- PostgreSQL 16
- JWT Auth (2 feste Nutzer, kein Registration-Flow)
- Login-Throttling (`security/LoginThrottler`, Issue #8): `POST /auth/login` wird pro
  Client-IP gedrosselt. Die ersten 5 Fehlversuche sind frei, danach exponentielles
  Backoff (1→2→4→…→15 min Sperre) mit `429 TOO_MANY_ATTEMPTS` + `Retry-After`; ein
  erfolgreicher Login setzt den Zähler zurück. Bewusst **IP**- statt benutzerbasiert
  (Benutzernamen sind bekannt → Username-Keying ermöglichte Account-Lockout-DoS).
  Die echte Client-IP wird spoofing-resistent aus `X-Forwarded-For` gelesen: die
  rechtesten `TRUSTED_PROXY_COUNT` Einträge (prod: DSM + nginx = 2) stammen von eigenen
  Proxies, alles weiter links ist client-gefälscht und wird ignoriert. **Setzt voraus,
  dass der DSM-Reverse-Proxy die echte Client-IP tatsächlich in `X-Forwarded-For`
  einträgt** (sonst `TRUSTED_PROXY_COUNT` an die real anhängenden Proxies anpassen; ein
  falscher Wert drosselt eher zu stark, lässt aber kein Spoofing durch). State nur im
  Speicher (Neustart vergibt jedem).
- REST für CRUD, WebSockets für Echtzeit-Sync
- Alle Endpunkte unter /api/v1/
- Fehlerbehandlung: einheitliche ErrorResponse(code, message)
- Konfiguration ausschließlich über Umgebungsvariablen
- JSON-Serialisierung (`plugins/Serialization.kt`) nutzt `encodeDefaults = false`:
  Felder, deren Wert dem Default entspricht — inkl. `null` und leerer Listen mit
  `= emptyList()` — werden aus der Antwort **weggelassen**. Das ist die gewollte
  Konvention (kompakte Payloads, „null = nicht gesendet"). **Konsequenz für Clients:**
  jedes optionale Feld kann fehlen; Listenfelder, die immer als Array erwartet werden,
  müssen client-seitig sowohl ein fehlendes Feld als auch ein leeres Array vertragen
  (Web: beim Einlesen normalisieren bzw. `?? []`; Android: Moshi-DTOs deklarieren
  Listen als `= emptyList()`, fehlende Keys werden so zu leeren Listen). Siehe Issue #96.

## Todo-Domänenmodell
Status-Flow: INBOX → PLANNED → DONE
- INBOX:   nur title gesetzt, alle anderen Felder optional
- PLANNED: mindestens assignee oder due_date gesetzt
- DONE:    done_at gesetzt

Felder: id, title, description?, status, assignee?,
due_date?, priority (LOW|MEDIUM|HIGH)?, list_id?,
recurrence?, created_by, created_at, done_at?

### Wiederkehrende Todos
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

## Rezepte-Domänenmodell
Recipe mit eingebetteten Ingredients + RecipeSteps (1:n, werden
immer zusammen mit dem Rezept gespeichert — kein separater Endpunkt).
- Recipe: id, title, description?, servings, prep_time_minutes?,
  cook_time_minutes?, category (BREAKFAST|DINNER|SNACK|DESSERT|DRINK),
  created_by, created_at, updated_at
  (LUNCH gibt es nicht mehr: per `V17__recipes_drop_lunch_category` nach DINNER gefaltet;
  `RecipeRoutes.VALID_CATEGORIES` lehnt es beim Schreiben ab. Nicht wieder hinzufügen.)
- Ingredient: id, recipe_id, name, amount?, unit?, sort_order
- RecipeStep: id, recipe_id, step_number, description
- Endpunkte unter /api/v1/recipes (Liste filterbar via ?category=)
- Portionierung: GET /api/v1/recipes/{id}?servings=N skaliert
  alle Ingredient-Mengen (Faktor N / servings)
- Einzelrezept-Export: GET /api/v1/recipes/{id}/export?format=md|pdf&servings=N
  liefert ein Rezept als Markdown (text/markdown) oder PDF (serverseitig via OpenPDF);
  deutscher Inhalt analog CSV-Export, Content-Disposition-Dateiname rezept_<slug>.<ext>.
  Web: Download-Button in der Detailansicht; Android: System-Share-Sheet (FileProvider).
- WebSocket /api/v1/ws/recipes (RECIPE_CREATED|UPDATED|DELETED)

## Wochenplan-Domänenmodell (Essensplaner)
Verbindet Rezepte → Woche → Einkauf (HB-02, #218; Web+Backend, Android #250). Plant pro
Tag-und-Mahlzeit genau ein Rezept; haushaltsweit geteilt (wie Abwesenheit, kein Eigentümer-Check).
- MealPlanEntry: id, date, slot (BREAKFAST|LUNCH|DINNER), recipe_id (FK recipes ON DELETE
  CASCADE), servings?, created_by, created_at. DB-Tabelle `meal_plan_entries` mit **Unique(date,
  slot)** — ein Rezept pro Slot; ein erneutes Setzen ersetzt den Eintrag.
- **Slots ≠ Rezept-Kategorien:** die drei Raster-Mahlzeiten (Frühstück/Mittag/Abend) sind
  bewusst unabhängig von den Rezept-Kategorien (dort kein LUNCH seit V17) — jedes Rezept passt
  in jeden Slot.
- **Portionen pro Eintrag (`servings`, #251/#261):** wie viele Portionen gekocht werden;
  NULL = Rezept-Default (1× wie erfasst). „In Einkaufsliste" skaliert die Zutaten je Eintrag
  (Faktor servings / recipe.servings). Clients persistieren `servings` nur, wenn ≠ Rezept-Default
  (Default bleibt null → saubere Kacheln). DB-Spalte via `V27__meal_plan_servings.sql`
  (nullable, CHECK ≥ 1).
- DTO `MealPlanEntryDto(id, date, slot, recipeId, recipeTitle, recipeCategory, servings?,
  createdBy, createdAt)` — Rezept-Titel/Kategorie sind eingejoint, damit das Raster ohne 2. Fetch
  rendert. Entries werden über (MealPlanEntriesTable innerJoin RecipesTable) geladen.
- Endpunkte unter /api/v1/meal-plan (Vorbild AbsenceRoutes): GET /?from=&to= (inklusiver Range,
  Bound MAX_RANGE_DAYS=370), PUT /{date}/{slot} `{recipeId, servings?}` (setzen/ersetzen, 200),
  DELETE /{date}/{slot} (idempotent, 204). WS /api/v1/ws/meal-plan: jede Mutation sendet
  {type:"MEAL_PLAN_CHANGED"}; Clients laden den sichtbaren Range neu (kein Payload).
- **„In Einkaufsliste" hat keinen eigenen Endpunkt:** der Client sammelt die (skalierten) Zutaten
  aller geplanten Rezepte der Woche und postet sie an das bestehende `POST /api/v1/shopping/batch`
  (summiert nach Name+Einheit). Ein doppelt geplantes Gericht zählt doppelt.
- Web (`components/Wochenplan/WochenplanView.tsx`): Mo-basierte Wochen-Navigation, Desktop-7×3-
  Matrix + mobile vertikale Tagesliste (≤860px), Klick-Picker (Auswahl→Übernehmen, Portionen-
  Stepper). Live über `useWebSocket` auf `meal-plan` **und** `recipes` (ein Recipe-Delete
  cascadet die Plan-Einträge serverseitig, broadcastet aber nur auf dem recipes-Channel — beide
  Kanäle lösen einen Reload aus).
- Android (`ui/wochenplan/`): Spiegelung (Tagesliste, Rezept-Picker-BottomSheet, „In
  Einkaufsliste"). `MealPlanRepository` hält einen eigenen meal-plan- **und** einen dedizierten
  recipe-WebSocket (eigene Instanz, damit der Lifecycle nicht mit der Rezepte-Ansicht kollidiert)
  — selbe Cascade-Logik wie Web.

## Web-Konventionen
- React 18 + TypeScript + Vite + Tailwind CSS
- Startseite: Dashboard-/„Heute"-View (`components/DashboardView.tsx`, erster Nav-Eintrag,
  Default-Tab) — zeitabhängige Begrüßung, Quick-Add → Inbox-Todo, 4 Stat-Kacheln
  (heute fällig / Inbox / morgen fällig / heute erledigt), „Heute dran", laufender
  Timer, Einkaufs-Peek und Digest-Vorschau; aggregiert die bestehenden Reads
  (Todos/Shopping/Time) live über WebSocket. Vorbild: Android `HeuteScreen`.
- Aufgaben-View (`components/TodosView.tsx`): Inbox-Tab als erster Tab vor den
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
- WebSocket-Hook für Echtzeit-Updates. `useWebSocket(target, onMessage, onOpen?)` —
  `onOpen` feuert bei jedem (Re-)Connect (server-erreichbar-Signal, besser als das
  Browser-`online`-Event); genutzt für Offline-Retry (s. u.).
- **Offline-Resilienz beim Abhaken (Einkauf):** Das Abhaken eines Einkaufs-Items
  (`ShoppingView`, `toggleChecked`) schreibt optimistisch **und** legt die Änderung in
  eine durable Queue (`localStorage` key `homebase_shopping_pending`, key=Item-UUID,
  letzter Wunsch gewinnt). Ein Flush sendet die PUTs und wird von drei Signalen
  getriggert: WS-`onOpen`, Browser-`online`-Event und einem 15-s-Intervall-Backstop
  (flakiges Laden-WLAN feuert oft kein `online`). Bis zum Erfolg trägt das Item einen
  „nicht synchronisiert"-Marker + Sammelbanner (nie still verlieren). Abgehakte sind nach
  `checkedAt` desc sortiert (zuletzt abgehakt oben). Android hat dieselbe Resilienz
  inzwischen ebenfalls (#170/#179): persistente Pending-Queue (DataStore, latest-wins),
  Retry via `ReconnectingWebSocketClient`-onOpen + `ConnectivityManager` + Intervall-Backstop,
  „nicht synchronisiert"-Marker und `checkedAt`-Sortierung. Deletes/Clear nutzen weiter den
  Toast-Pfad (kein Queue). Items ohne Liste erzeugt Android nicht mehr (#181: beim ersten Item
  ohne Liste wird automatisch eine Default-Liste angelegt).
- Kein Redux — useState/useContext reicht für MVP
- **Modal vs. Seite/Panel — Leitlinie (Umbrella #29):** Dialoge sparsam einsetzen; pro Stelle die
  passende Form wählen (Primitiven `<Modal>` und `<Sheet>` in `ui/primitives.tsx`, gleiche
  Prop-Surface → mechanischer Tausch):
  - **`<Modal>`** (zentriert) für **kurze, fokussierte** Aktionen: 1–3 Felder, Bestätigungen, keine
    Verschachtelung — z. B. Liste anlegen/umbenennen, Projekt anlegen, Eintrag splitten, Lösch-Confirm,
    Bild-Lightbox.
  - **`<Sheet>`** (Slide-over, mobil als Bottom-Sheet) für **mittlere Formulare mit Mobile-Relevanz**
    (`datetime`-Picker, mehrere Selects) — z. B. Abwesenheits-Tageseditor (#44), Rezept-Zutatenauswahl
    (#48), TimeView-Eintrag-Editoren (#124).
  - **Eigene Seite/Route** für **viel Inhalt / tabellarische, wachsende Formulare / verschachtelte
    Detail-Ansichten** — z. B. Projekt-Detail (#32), Abwesenheits-Einstellungen (#43).
  - **Keine nativen `window.confirm()`** für (destruktive) Aktionen — stets ein Custom-`<Modal>`
    (Konsistenz, #125).
  - Beim Modal→Seite/Slide-over-Umbau die zugehörige `web/e2e/<view>.spec.ts` **im selben PR**
    anpassen (`.hb-modal`-Locator → `.hb-sheet`/Seite) — nur der `e2e`-CI-Job fängt das.
  - Offene Konvertierungs-Kandidaten leben als eigene Issues; #128 (TargetsModal→eigene Seite)
    ist umgesetzt (#187).
    Für Confirms gibt es das Primitive `<ConfirmDialog>` (primitives.tsx, #125/#129) —
    Cross-Person-Aktionen der Zeiterfassung (Partner-Timer, Partner-Einträge) laufen darüber.

## Android-Konventionen
- Jetpack Compose, Kotlin Coroutines + Flow
- Retrofit für HTTP, OkHttp WebSocket für Sync
- ViewModel + Repository Pattern
- Basis-URL konfigurierbar über BuildConfig (zeigt auf DynDNS-Domain)
- FAB → nur Titel eingeben → direkt in Inbox
- Aufgaben-View (`ui/aufgaben/`): eigener Inbox-Tab als erster Tab vor den
  Listen-Tabs mit derselben Semantik wie das Web (siehe Aufgaben-View-Absatz
  unter Web-Konventionen, #71/#77): Status-INBOX-Todos plus alle listen-losen
  Todos, Herkunfts-Liste als Meta, Badge = Anzahl Status-INBOX-Todos
  (`TodoUiState.inboxCount`, auch von der HeuteScreen-Kachel genutzt). Quick-Add
  im Inbox-Tab postet ohne `listId`; das Edit-Sheet bietet beim Planen
  listen-loser Todos eine Listen-Auswahl. Das frühere Catch-all-Verhalten des
  ersten Listen-Tabs entfällt; ohne Listen ist die Inbox der Default-Tab.
- Kein Hilt für MVP — manuelle DI reicht

## Datenbank
- PostgreSQL 16 als Docker Container
- Migrationen mit Flyway
- Migrationsdateien: /backend/src/main/resources/db/migration/

## Telegram Digest
- Kotlin-Coroutine-basierter Scheduler im Backend; ein `DigestScheduler` pro Digest, beide
  über denselben Bot/Chat. Fehlen TELEGRAM_BOT_TOKEN/TELEGRAM_CHAT_ID (env-Secrets), ruhen beide.
- **Zwei tägliche Digests:** Abend-Recap (`DigestService`, Default 20:00 — heute erledigt /
  neu in Inbox / morgen fällig) und Morgen-Briefing (`MorningDigestService`, Default 07:00 —
  heute fällig / überfällig / Inbox / heute abwesend / Kita heute zu).
- **Pro Digest in-app konfigurierbar** (Einstellungen → Benachrichtigungen, `app_settings`,
  pro Zyklus frisch gelesen — kein Neustart): Uhrzeit (`DIGEST_TIME` / `MORNING_DIGEST_TIME`),
  An/Aus (`DIGEST_*_ENABLED`, unset = an) und Inhalts-Auswahl per Checkbox (`DIGEST_*_SECTIONS`,
  CSV von `DigestSection`-IDs; unset = alle Sektionen, leer **gespeichert** = keine). Gesendet
  wird nur, wenn `enabled && telegramConfigured` und mindestens eine **gewählte** Sektion Inhalt
  hat. Lange Sektionen werden bei 20 Einträgen + „… und X weitere" gekappt (#167).
- **Abend-Digest** enthält zusätzlich eine Vorschau auf **morgen** (wer ist morgen abwesend,
  Kita morgen zu) via geteiltem `familyCalendarFor(date)` — deshalb kann er auch an einem sonst
  stillen Tag feuern, wenn nur diese Vorschau Inhalt hat (#182). Web-UI fertig; Android-Spiegelung
  der Digest-Einstellungen offen (#189).

## Zeiterfassung-Domänenmodell
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

### Wochensoll & Ende-Prognose (Issue #31)
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

## Abwesenheit-Domänenmodell (Familienkalender)
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

## Notizen-Domänenmodell
Markdown-Notizen mit Tags, Volltextsuche und Sichtbarkeit (PRIVATE|SHARED).
- Note: id, title, content (Markdown), tags (CSV), visibility, created_by,
  created_at, updated_at. Geteilte Notizen sind für beide Nutzer sicht- und
  editierbar; die Sichtbarkeit darf nur der Ersteller ändern.
- NoteImage (1:n Anhang-Galerie): id, note_id (FK ON DELETE CASCADE), filename
  (auf Platte), original_name, content_type, size_bytes, sort_order, created_by,
  created_at — immer als images-Array in NoteDto eingebettet.
- Bilder liegen als Datei unter UPLOAD_DIR (nicht in der DB); das Original wird
  ausgeliefert, Thumbnails skaliert der Client. Erlaubt: JPEG/PNG/WebP/GIF bis
  MAX_UPLOAD_MB (default 10).
- Endpunkte unter /api/v1/notes: CRUD + POST/GET/DELETE
  /notes/{id}/images[/{imageId}] (Upload als multipart, Auslieferung via ?token=
  wie bei den WS-Endpunkten; Upload und Delete geben die aktualisierte Note
  zurück). WebSocket /api/v1/ws/notes (Channel "notes"):
  NOTE_CREATED|UPDATED|DELETED; Bildänderungen senden NOTE_UPDATED. Private
  Notizen werden nie über den geteilten Kanal gesendet.

## Umgebungsvariablen (.env)
DB_URL              — jdbc:postgresql://db:5432/homebase
DB_USER
DB_PASSWORD
JWT_SECRET
TELEGRAM_BOT_TOKEN  — Secret; fehlt er, ruht der Digest. (Digest-Uhrzeit nicht hier,
                      sondern in-app, siehe unten.)
TELEGRAM_CHAT_ID    — Secret (s. o.)
RECURRING_TIME      — Default/Bootstrap für die tägliche Uhrzeit des Wiederholungs-Schedulers
                      (default "00:30", in TZ). Zur Laufzeit in-app editierbar (Einstellungen →
                      Benachrichtigungen, `app_settings.recurring_time`), siehe unten — env greift
                      nur für die leere Tabelle.
TZ                  — Zeitzone des Backend-Containers (default Europe/Berlin); steuert
                      ZoneId.systemDefault(): Digest-/Scheduler-Uhrzeit und CSV-Export-Zeitstempel
DOMAIN              — öffentliche HTTPS-Domain des Deployments (z. B. homebase.example.com,
                      ohne Schema/Slash). scripts/deploy.sh prüft damit nach dem Deploy den
                      Health-Endpunkt https://<DOMAIN>/api/v1/health; leer ⇒ Check entfällt.
UPLOAD_DIR          — Speicherort der Notizbilder (prod: gemountetes Volume, default "uploads")
MAX_UPLOAD_MB       — max. Größe pro Bild in MB (default 10)
TRUSTED_PROXY_COUNT — Anzahl vertrauenswürdiger Reverse-Proxy-Hops vor dem Backend (default 2:
                      DSM + nginx); bestimmt die echte Client-IP aus X-Forwarded-For fürs
                      Login-Throttling. 0 = Backend direkt erreichbar (kein Proxy). Siehe Issue #8.

**In-app statt env (#100):** Was zur Laufzeit in den Einstellungen editierbar ist, lebt in
der DB (`app_settings`), nicht in der .env. Das Backend definiert nur Code-/Conf-Defaults für
die leere Tabelle. Konkret: **Haushaltsname** (Einstellungen → Haushalt, Default "Mäxchen")
und **Digest-Uhrzeit** (Einstellungen → Benachrichtigungen, `app_settings.digest_time`, Default
"20:00") haben **keine** env-Variable mehr. **Wiederholungs-Planer-Uhrzeit** (Einstellungen →
Benachrichtigungen, `app_settings.recurring_time`, Default "00:30") ist ebenso in-app editierbar;
`RECURRING_TIME` bleibt nur Bootstrap-Default für die leere Tabelle. Beide Uhrzeiten liest der
jeweilige Scheduler pro Zyklus neu (kein Neustart). Faustregel für neue Optionen: editierbar ⇒
DB + UI/API; nur env bleiben Secrets (JWT/DB/Telegram-Token) und reine Infrastruktur (TZ, Ports,
Upload-Pfad, Proxy-Count).

## Docker Services
Produktion (docker-compose.yml) — 3 Services; HTTPS liefert DSM Reverse Proxy davor:
backend  — GHCR-Image, expose 8080 (nur intern), Volume uploads (Notizbilder)
web      — GHCR-Image (nginx); liefert SPA + proxyt /api & WebSocket → backend;
           published auf 127.0.0.1:3000 (Ziel des DSM Reverse Proxy)
db       — postgres:16, Volume pgdata

Entwicklung (docker-compose.dev.yml):
db       — postgres:16 auf Port 5432

## Lokale Entwicklung
- Backend: ./gradlew run         → http://localhost:8080
- Web:     npm run dev           → http://localhost:5173
- DB:      docker compose -f docker-compose.dev.yml up


## Review

Wenn du einen PR erstellt hast, starte einen neuen Agent der das Review des PRs macht.

Reagiere dann auch auf das Review und frage mich bei starken Änderungen.