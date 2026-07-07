# HomeBase

Privater Familien-Hub für 2 Nutzer mit Echtzeit-Sync
zwischen Android (Compose) und Web (React).

> **Detail-Docs (bei Bedarf lesen, nicht auf Verdacht):** Die tiefen Domänen- und
> Feature-Beschreibungen liegen in `docs/` und werden **on demand** geladen. Bevor du an
> einem Feature arbeitest, lies die passende Datei aus dem [Doc-Index](#doc-index) unten —
> sie enthält die verbindlichen Invarianten, Migrations- und API-Konventionen für diesen
> Bereich. Diese `CLAUDE.md` hält nur die querschnittlichen Regeln, die für **jede** Aufgabe gelten.

## Projektstruktur (Monorepo)

homebase/
├── backend/                  — Kotlin + Ktor API + WebSocket Server
├── web/                      — React + Vite + TS Frontend (nginx: SPA + /api-Proxy)
├── android/                  — Jetpack Compose App
├── docs/                     — Feature-/Domänen-Docs (on demand, siehe Doc-Index)
├── docker-compose.yml        — Produktion (Synology NAS, Images aus GHCR)
├── docker-compose.dev.yml    — Lokale Entwicklung (nur DB)
├── .env.example
├── scripts/                  — setup-env / deploy / backup / restore
├── backlog/                  — README: alte-ID→Issue-Zuordnung (Backlog lebt in GitHub Issues)
└── CLAUDE.md

<a id="doc-index"></a>
## Doc-Index — was lesen, bevor du woran arbeitest
| Arbeitest du an … | Lies zuerst |
|---|---|
| Aufgaben/Todos, Inbox, Zuständige, Wiederholung, Todo-Edit-Sheet | [docs/domain/todos.md](docs/domain/todos.md) |
| Rezepten | [docs/domain/recipes.md](docs/domain/recipes.md) |
| Wochenplan/Essensplaner | [docs/domain/meal-plan.md](docs/domain/meal-plan.md) |
| Einkaufsliste / Offline-Abhaken | [docs/domain/shopping.md](docs/domain/shopping.md) |
| Zeiterfassung, Wochensoll, Ende-Prognose | [docs/domain/time-tracking.md](docs/domain/time-tracking.md) |
| Abwesenheit / Familienkalender | [docs/domain/absence.md](docs/domain/absence.md) |
| Notizen (Modell + Editor-UX) | [docs/domain/notes.md](docs/domain/notes.md) |
| Dashboard / „Heute"-View | [docs/domain/dashboard.md](docs/domain/dashboard.md) |
| Telegram-Digest, Todo-Erinnerungen, Web Push | [docs/domain/notifications.md](docs/domain/notifications.md) |
| **env-Variablen** (vollständig, kommentiert) | [`.env.example`](.env.example) — Single Source of Truth |
| **Deployment**, Docker, DSM/FRITZ!Box, Android-Build, Troubleshooting | [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md) |
| Security-Invarianten (nginx-Log-Masking, Login-Throttling #8) | [docs/security-invariants.md](docs/security-invariants.md) |

## Backlog & Out-of-Scope-Funde
Geplante, aber noch nicht umgesetzte Features sowie Funde, die nicht zur gerade
laufenden Aufgabe gehören, leben als **GitHub Issues** (das frühere dateibasierte
`backlog/` wurde migriert; Zuordnung in `backlog/README.md`).

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

### In-Bearbeitung markieren (gegen Doppelarbeit)
Damit nicht zwei Menschen/KI-Agenten/Sessions **dasselbe Ticket parallel** anfangen
(wie bei #293, das doppelt umgesetzt wurde — PR #301 vs. #315):
1. **Vor dem Start** prüfen, ob das Issue bereits das Label `in-progress` trägt
   (`gh issue view <n> --json labels` bzw. `gh issue list --label in-progress`). Trägt es das
   Label, arbeitet schon jemand daran ⇒ nicht parallel anfangen (oder vorher abstimmen).
2. **Beim Start** das Label setzen: `gh issue edit <n> --add-label in-progress`.
3. Das Label fällt mit dem mergenden PR weg (`Closes #<n>` schließt das Issue mitsamt Labels);
   wird die Arbeit abgebrochen, das Label wieder entfernen
   (`gh issue edit <n> --remove-label in-progress`).

## Backend-Konventionen
- Kotlin, Ktor, Exposed ORM, PostgreSQL 16
- JWT Auth (2 feste Nutzer, kein Registration-Flow). Login-Throttling pro IP →
  [docs/security-invariants.md](docs/security-invariants.md).
- REST für CRUD, WebSockets für Echtzeit-Sync; alle Endpunkte unter /api/v1/
- Fehlerbehandlung: einheitliche ErrorResponse(code, message)
- Konfiguration über Umgebungsvariablen — **aber** editierbare Optionen leben in der DB, nicht in
  der .env (siehe Config-Grundsatz unten).
- **JSON-Serialisierung — `encodeDefaults = false`** (`plugins/Serialization.kt`, gilt überall):
  Felder, deren Wert dem Default entspricht — inkl. `null` und leerer Listen mit `= emptyList()` —
  werden aus der Antwort **weggelassen** (kompakte Payloads, „null = nicht gesendet"). **Konsequenz
  für Clients:** jedes optionale Feld kann fehlen; Listenfelder, die immer als Array erwartet werden,
  müssen ein fehlendes Feld **und** ein leeres Array vertragen (Web: `?? []` beim Einlesen; Android:
  Moshi-DTOs deklarieren Listen als `= emptyList()`). Siehe Issue #96.

## Config-Grundsatz (#100)
Env-Variablen sind **nur Bootstrap-Defaults**. Alles, was zur Laufzeit in den Einstellungen
editierbar ist, lebt in der DB (`app_settings`) + UI/API, **nie** als Pflicht-env. Nur env bleiben
Secrets (JWT/DB/Telegram-Token, VAPID) und reine Infrastruktur (TZ, Ports, Upload-Pfad,
Proxy-Count). Vollständige, kommentierte Variablen-Liste → [`.env.example`](.env.example).

## Datenbank & Migrationen
- PostgreSQL 16, Flyway. Migrationsdateien: `/backend/src/main/resources/db/migration/`.
- **Nächste `Vn` gegen `origin/main` wählen** (nicht gegen den Branch-Base) — sonst
  Duplicate-Version-FlywayException beim PR-Merge-Build.
- **Angewandte Migrationen sind unveränderlich** (`validateOnMigrate`): nie den Inhalt einer
  bereits gemergten/angewandten Migration ändern (auch keinen Kommentar) — Checksum-Mismatch beim
  nächsten Deploy. Immer eine neue `Vn` anlegen.

## Web-Konventionen
- React 18 + TypeScript + Vite + Tailwind CSS. Kein Redux — useState/useContext reicht für MVP.
- WebSocket-Hook `useWebSocket(target, onMessage, onOpen?)` — `onOpen` feuert bei jedem
  (Re-)Connect (server-erreichbar-Signal, besser als das Browser-`online`-Event; genutzt für
  Offline-Retry).
- **Modal vs. Seite/Panel — Leitlinie (Umbrella #29):** Dialoge sparsam; pro Stelle die passende
  Form wählen (Primitiven `<Modal>` und `<Sheet>` in `ui/primitives.tsx`, gleiche Prop-Surface →
  mechanischer Tausch):
  - **`<Modal>`** (zentriert) für **kurze, fokussierte** Aktionen: 1–3 Felder, Bestätigungen, keine
    Verschachtelung — z. B. Liste anlegen/umbenennen, Eintrag splitten, Lösch-Confirm, Lightbox.
  - **`<Sheet>`** (Slide-over, mobil Bottom-Sheet) für **mittlere Formulare mit Mobile-Relevanz**
    (`datetime`-Picker, mehrere Selects) — z. B. Abwesenheits-Tageseditor (#44), Rezept-Zutaten
    (#48), TimeView-Editoren (#124).
  - **Eigene Seite/Route** für **viel Inhalt / tabellarische, wachsende Formulare / verschachtelte
    Detail-Ansichten** — z. B. Projekt-Detail (#32), Abwesenheits-Einstellungen (#43).
  - **Keine nativen `window.confirm()`** für (destruktive) Aktionen — stets Custom-`<Modal>` bzw.
    das Primitive `<ConfirmDialog>` (primitives.tsx, #125/#129; nutzen z. B. die
    Cross-Person-Aktionen der Zeiterfassung).
  - **Beim Modal→Seite/Slide-over-Umbau die zugehörige `web/e2e/<view>.spec.ts` im selben PR
    anpassen** (`.hb-modal`-Locator → `.hb-sheet`/Seite) — nur der `e2e`-CI-Job fängt das.
- **Todos & Notizen auto-saven beim Bearbeiten** (kein Speichern-Button; Neuanlage explizit). Die
  view-spezifischen Details/Hazards stehen in [docs/domain/todos.md](docs/domain/todos.md) bzw.
  [docs/domain/notes.md](docs/domain/notes.md).

## Android-Konventionen
- Jetpack Compose, Kotlin Coroutines + Flow. Retrofit (HTTP), OkHttp WebSocket (Sync).
- ViewModel + Repository Pattern; kein Hilt für MVP — manuelle DI reicht.
- Basis-URL über BuildConfig (DynDNS-Domain). FAB → nur Titel eingeben → direkt in Inbox.
- **Compose-Layout-Falle (CI-Guard, #348):** In `Column { Box(weight=1f){…}; <bar> }` misst Compose
  das nicht-gewichtete Kind zuerst mit voller Höhe — nutzt es `fillMaxHeight()`/`fillMaxSize()`,
  frisst es die Höhe und das `weight(1f)`-Geschwister kollabiert auf 0dp (war der #347-Bottom-Nav-Bug).
  `scripts/check-compose-layout.sh` (Android-CI-Job) flaggt genau diese Konstellation; bewusste
  Ausnahme (Overlay-Drawer/Sheet) mit `// layout-guard:allow` auf der fillMax-Zeile.
- Todo-Edit-Sheet: Auto-Save beim Bearbeiten (Orchestrierung im ViewModel), Neuanlage explizit —
  Details in [docs/domain/todos.md](docs/domain/todos.md).

## Lokale Entwicklung
- Backend: `./gradlew run`   → http://localhost:8080
- Web:     `npm run dev`     → http://localhost:5173
- DB:      `docker compose -f docker-compose.dev.yml up`

## Review
Wenn du einen PR erstellt hast, starte einen neuen Agent, der das Review des PRs macht.
Reagiere dann auf das Review und frage mich bei starken Änderungen.
