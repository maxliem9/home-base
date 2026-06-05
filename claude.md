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
   (Herkunft im Body festhalten, z. B. „aus Review von PR #30").
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
- Let's Encrypt Zertifikat via Synology DSM (auto-renewal, kein Container-Neustart nötig)
- Backend/Web-Images werden von GitHub Actions gebaut und nach GHCR gepusht
  (ghcr.io/maxliem9/homebase-{backend,web}); die NAS **zieht** sie nur, baut
  nichts aus dem Quellcode. Tag über IMAGE_TAG (default latest). Privates Repo
  ⇒ private Packages ⇒ `docker login ghcr.io` auf der NAS nötig.

## Backend-Konventionen
- Kotlin, Ktor Framework
- Exposed ORM für Datenbankzugriff
- PostgreSQL 16
- JWT Auth (2 feste Nutzer, kein Registration-Flow)
- REST für CRUD, WebSockets für Echtzeit-Sync
- Alle Endpunkte unter /api/v1/
- Fehlerbehandlung: einheitliche ErrorResponse(code, message)
- Konfiguration ausschließlich über Umgebungsvariablen

## Todo-Domänenmodell
Status-Flow: INBOX → PLANNED → DONE
- INBOX:   nur title gesetzt, alle anderen Felder optional
- PLANNED: mindestens assignee oder due_date gesetzt
- DONE:    done_at gesetzt

Felder: id, title, description?, status, assignee?,
due_date?, priority (LOW|MEDIUM|HIGH)?,
created_by, created_at, done_at?

## Rezepte-Domänenmodell
Recipe mit eingebetteten Ingredients + RecipeSteps (1:n, werden
immer zusammen mit dem Rezept gespeichert — kein separater Endpunkt).
- Recipe: id, title, description?, servings, prep_time_minutes?,
  cook_time_minutes?, category (BREAKFAST|LUNCH|DINNER|SNACK|DESSERT|DRINK),
  created_by, created_at, updated_at
- Ingredient: id, recipe_id, name, amount?, unit?, sort_order
- RecipeStep: id, recipe_id, step_number, description
- Endpunkte unter /api/v1/recipes (Liste filterbar via ?category=)
- Portionierung: GET /api/v1/recipes/{id}?servings=N skaliert
  alle Ingredient-Mengen (Faktor N / servings)
- WebSocket /api/v1/ws/recipes (RECIPE_CREATED|UPDATED|DELETED)

## Web-Konventionen
- React 18 + TypeScript + Vite + Tailwind CSS
- Startseite: Inbox-View (alle INBOX-Todos beider Nutzer)
- WebSocket-Hook für Echtzeit-Updates
- Kein Redux — useState/useContext reicht für MVP

## Android-Konventionen
- Jetpack Compose, Kotlin Coroutines + Flow
- Retrofit für HTTP, OkHttp WebSocket für Sync
- ViewModel + Repository Pattern
- Basis-URL konfigurierbar über BuildConfig (zeigt auf DynDNS-Domain)
- FAB → nur Titel eingeben → direkt in Inbox
- Kein Hilt für MVP — manuelle DI reicht

## Datenbank
- PostgreSQL 16 als Docker Container
- Migrationen mit Flyway
- Migrationsdateien: /backend/src/main/resources/db/migration/

## Telegram Digest
- Kotlin-Coroutine-basierter Scheduler im Backend
- Sendet täglich zur konfigurierten Uhrzeit (DIGEST_TIME)
- Inhalt: heute erledigte Todos, neue Inbox-Items, morgen fällige Todos

## Zeiterfassung-Domänenmodell
Project: id, name, color (Hex), archived, created_by, created_at
TimeEntry: id, project_id, user_id, started_at, stopped_at?,
description?, created_at, updated_at
- duration = stopped_at - started_at (nur wenn stopped_at gesetzt)
- Pro Nutzer höchstens ein laufender Timer (stopped_at IS NULL);
  ein neuer Start stoppt den laufenden automatisch. DB-Garantie über
  partiellen Unique-Index, Anwendungslogik stoppt aktiv.
- Beide Nutzer sehen alle Einträge aller Projekte.
- Endpunkte unter /api/v1/time/ (projects, entries, entries/start,
  entries/stop, running). WebSocket: /api/v1/ws/time (Channel "time").
- created_by / user_id werden — wie im restlichen Projekt — als
  username (VARCHAR, FK users.username) gespeichert, nicht als UUID.

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
  kita (POST, POST /kita/range, PUT, DELETE), settings PUT /settings/{userId}.
- WebSocket /api/v1/ws/absence (Channel "absence"): jede Mutation sendet
  {type:"ABSENCE_CHANGED"}; Clients laden den Snapshot neu.
- Deutsche Feiertage werden pro Bundesland aus Ostern (Gauß-Algorithmus) +
  festen Daten berechnet (holidays.ts), nicht gespeichert.

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
TELEGRAM_BOT_TOKEN
TELEGRAM_CHAT_ID
DIGEST_TIME         — z.B. "20:00"
HOUSEHOLD_NAME      — Anzeigename in der Sidebar (default: "Mäxchen"), via GET /api/v1/config
UPLOAD_DIR          — Speicherort der Notizbilder (prod: gemountetes Volume, default "uploads")
MAX_UPLOAD_MB       — max. Größe pro Bild in MB (default 10)

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