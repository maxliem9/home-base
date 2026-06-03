# HomeBase

Privater Familien-Hub für 2 Nutzer mit Echtzeit-Sync
zwischen Android (Compose) und Web (React).

## Projektstruktur (Monorepo)

homebase/
├── backend/                  — Kotlin + Ktor API + WebSocket Server
├── web/                      — React + Vite + TypeScript Frontend
├── android/                  — Jetpack Compose App
├── nginx/
│   └── nginx.conf            — Reverse Proxy Konfiguration
├── docker-compose.yml        — Produktion (Synology NAS)
├── docker-compose.dev.yml    — Lokale Entwicklung (nur DB)
├── .env.example
└── CLAUDE.md

## Deployment
- Synology NAS, DSM 7.x, Container Manager (Docker)
- Erreichbar via DynDNS + HTTPS (kein VPN nötig)
- Nginx als Reverse Proxy auf Port 443
    - /api/ → backend:8080 (inkl. WebSocket Upgrade)
    - /     → web:3000
- Let's Encrypt Zertifikat via Synology DSM (auto-renewal)

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

## Umgebungsvariablen (.env)
DB_URL              — jdbc:postgresql://db:5432/homebase
DB_USER
DB_PASSWORD
JWT_SECRET
TELEGRAM_BOT_TOKEN
TELEGRAM_CHAT_ID
DIGEST_TIME         — z.B. "20:00"
HOUSEHOLD_NAME      — Anzeigename in der Sidebar (default: "Mäxchen"), via GET /api/v1/config

## Docker Services
Produktion (docker-compose.yml):
nginx    — Port 80+443, Reverse Proxy, bindet Synology-Zertifikat ein
backend  — expose 8080 (nur intern)
web      — expose 3000 (nur intern)
db       — postgres:16, Volume pgdata

Entwicklung (docker-compose.dev.yml):
db       — postgres:16 auf Port 5432

## Lokale Entwicklung
- Backend: ./gradlew run         → http://localhost:8080
- Web:     npm run dev           → http://localhost:5173
- DB:      docker compose -f docker-compose.dev.yml up