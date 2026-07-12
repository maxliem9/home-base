# Architektur & Datenfluss

> **Zweck:** Das interne Sync-Modell und seine verbindlichen Invarianten — das, was man wissen
> muss, bevor man Backend-Querschnitt anfasst oder neu ins Projekt einsteigt. Die
> Deployment-Topologie (DSM → nginx → Ktor → Postgres) steht im [README](../README.md#architektur);
> Domänen-Details in [docs/domain/](domain/); Konventions-Kurzfassung in der
> [CLAUDE.md](../CLAUDE.md#backend-konventionen).

## Komponenten

- **backend/** — Kotlin + Ktor. REST unter `/api/v1/`, acht WebSocket-Kanäle, In-Process-Scheduler
  (Digest/Reminder/Recurring). Genau **eine** Instanz (→ [Betriebs-Constraint](#single-instance)).
- **web/** — React-SPA (Vite). nginx serviert die SPA und proxied `/api`.
- **android/** — Jetpack Compose; Retrofit (REST) + OkHttp (WS); ViewModel + Repository, manuelle DI
  (`di/AppContainer.kt`).
- **PostgreSQL 16** — einzige Quelle der Wahrheit; Flyway-Migrationen.
- **uploads/**-Volume — Notiz-/Rezeptbilder + Datei-Anhänge (Dateisystem, nicht DB).

## Das Sync-Modell (Write-Pfad)

Jede Mutation nimmt denselben Weg:

```
Client ──REST-Write (JWT)──▶ Route-Handler
                               │  parst Request, ruft Service
                               ▼
                             service/<Domäne>Service.kt   (#546)
                               │  validiert + persistiert in dbQuery{} (#549),
                               │  liefert sealed Result (Ok/Invalid/NotFound) mit frischem DTO
                               ▼
                             Route mappt Result → HTTP-Status/Body
                               │  und broadcastet NACH dem Commit
                               ▼
                             WsSessionManager.broadcastSync(kanal, TYP, dto)   (#552)
                               │  {"type": "...", "payload": {...}}
                               ▼
                             alle Sessions des Kanals (beide Nutzer, alle Geräte)
```

Die Gegenseite **upsertet das gepushte DTO direkt** in ihren lokalen State — Web über die
Reducer im View/Hook, Android über sealed `WsEvent`s → Repository-Flow → ViewModel. Es gibt
keinen Delta-/Versions-Mechanismus: der Broadcast trägt immer das vollständige Objekt.

**Invarianten:**

1. **Broadcast nie vor dem Commit.** Der Service gibt das DTO zurück; die Route broadcastet erst,
   wenn die Transaktion durch ist. (Sonst rendert das Gegenüber Zustände, die ein Rollback wieder
   wegnimmt.)
2. **Client-Handler sind idempotent.** Der Auslöser bekommt den Broadcast auch (zusätzlich zur
   REST-Antwort) — `CREATED` mit bereits bekannter id ist ein Upsert, kein Duplikat (#61).
3. **WS ist nur Server→Client.** Clients senden keine Frames (außer Close); alle Writes gehen
   über REST. `syncChannel()` hält die Session nur offen.
4. **Sichtbarkeit wird an der Broadcast-Stelle erzwungen** (→ nächster Abschnitt).

## WS-Kanäle

Alle unter `authenticate("auth-jwt")`, registriert via `syncChannel("<kanal>")` in der jeweiligen
Route-Datei:

| Kanal | Beispiel-Events | Anmerkung |
|---|---|---|
| `todos` | `TODO_CREATED/UPDATED/DELETED`, `TODO_LIST_*` | Sichtbarkeits-Übersetzung (s. u.) |
| `shopping` | `SHOPPING_*`, `SHOPPING_LIST_*`, `SHOPPING_TEMPLATE_*`, `SHOPPING_CATEGORY[_RULE]_CHANGED` | Kategorie-/Regel-Events sind Refetch-Trigger |
| `notes` | `NOTE_*` | Visibility wie Todos (→ [notes.md](domain/notes.md)) |
| `time` | Timer-/Entry-/Target-Events | treibt u. a. den „Timer läuft“-Punkt |
| `recipes` | `RECIPE_*` | auch vom Wochenplan konsumiert |
| `absence` | Refetch-Trigger | Client lädt den Absence-State neu |
| `meal-plan` | Refetch-Trigger | dto-lose type-only-Envelopes |
| `events` | Refetch-Trigger | Termine (#434) |

Type-only-Events (ohne Payload) sind legitim, wo der Client ohnehin einen zusammengesetzten
State neu lädt — `broadcastSync(channel, type)` lässt den `payload`-Key dann komplett weg
(`encodeDefaults = false`).

## Sichtbarkeit auf gemeinsamen Kanälen (#73/#75)

Ein Kanal erreicht **beide** Nutzer. Private Inhalte (private Todo-Listen, private Notizen)
dürfen deshalb nie roh gebroadcastet werden. Ausgearbeitetes Muster (Todos, gilt sinngemäß für
Notizen):

- bleibt privat → **kein** Broadcast;
- shared → privat → fürs Gegenüber als **DELETE** übersetzt;
- privat → shared → als **CREATE** gebroadcastet, plus Replay der bislang nie gesendeten Inhalte
  (#75);
- Schreibpfade behandeln fremde private Objekte als **404 „not found“** — dieselbe Antwort wie
  für eine unbekannte id, damit die UUID kein Existenz-Orakel wird (#73). Diese Checks leben in
  den Services und müssen bei jeder neuen Route erhalten bleiben.

## Auth & Token-Transport

JWT (HMAC256, 30 Tage, Claim `username`), zwei feste Nutzer, kein Registration-Flow. Drei
Transportwege in fester Prioritätsreihenfolge (`plugins/Authentication.kt`):

1. `Authorization: Bearer …` — REST + Android-WS.
2. `Sec-WebSocket-Protocol: bearer, <jwt>` — Web-WS-Handshake (Token bleibt aus URL,
   Access-Logs und Browser-History).
3. `?token=` — letzter Ausweg für Loads, die keine Header setzen können (native Bild-Loads,
   iCal-Feed). Log-Masking-Pflichten dazu → [security-invariants.md](security-invariants.md).

## Read-Pfad (bewusste Entscheidung)

Sammel-GETs (`/todos`, `/shopping`, `/notes`, …) liefern die **volle Collection**, kein Paging;
die Clients fenstern lokal (z. B. Erledigt-Fenster #263/#356). Das ist bewusst simpel gehalten —
Wachstumsgrenzen und der inkrementelle Plan stehen in Issue #559.

## Offline-Strategien

- **Web:** App-Shell + Assets über den Service Worker (#519); Daten über durable Read-Caches in
  `localStorage` (#517/#520) — Seed beim Mount, Mirror bei jeder State-Änderung. Das
  `onOpen` des `useWebSocket`-Hooks feuert bei jedem (Re-)Connect und ist das
  „Server-wieder-erreichbar“-Signal für Refetch/Flush (besser als das Browser-`online`-Event).
  Pro Kanal existiert genau **eine** geteilte WS-Verbindung (`hooks/wsConnectionManager.ts`,
  #551) — beliebig viele Views subscriben per Fan-out; `onOpen` feuert pro Subscriber. Das
  Standard-Plumbing (Fetch + Cache + WS-Reducer + 401) kapselt `useSyncedCollection` (#550).
- **Android:** durable **Pending-Queue** (latest-wins pro Item; drei Flush-Trigger: WS-Reconnect,
  `ConnectivityObserver`, periodischer Backstop) für Einkaufs-Abhaken (#170) und Notizen (#323);
  `SnapshotStore<T>` als Read-Cache für den Kaltstart ohne Netz (#517-Familie).

## Hintergrund-Jobs (in-process)

`DigestScheduler` ×2 (Telegram Abend/Morgen), `ReminderScheduler` (60-s-Tick, Quiet-Hours),
`RecurringTodoScheduler` (Safety-Net für Wiederholungen). Alle lesen ihre Einstellungen **je
Zyklus** live aus `app_settings` (Config-Grundsatz #100) — In-App-Änderungen wirken ohne
Neustart. Ohne konfigurierten Kanal (Telegram/VAPID) bleiben die Jobs schlafend.

<a id="single-instance"></a>
## Betriebs-Constraint: genau EINE Backend-Instanz

`WsSessionManager` (Session-Registry), `LoginThrottler` (IP-Buckets) und die Scheduler leben im
Prozess-Speicher. Eine zweite Replika würde **still** kaputtgehen: Sync-Events erreichten nur die
„eigenen“ Sessions, das Login-Throttling verwässerte, Digests/Reminder kämen doppelt.
`docker-compose.yml` betreibt deshalb bewusst genau einen `backend`-Container — **nicht** skalieren.
Falls je nötig: WS-Fanout über Redis/pg `NOTIFY`, Throttler-State in die DB, Scheduler mit
Leader-Election (→ Issue #557, dort auch die Broadcast-Härtung).

## Serialisierung (Verweis)

`encodeDefaults = false` gilt für REST **und** WS-Payloads — jedes optionale Feld kann fehlen
(Regeln + Client-Pflichten in der [CLAUDE.md](../CLAUDE.md#backend-konventionen), #96; Guard für
Broadcasts: #134).
