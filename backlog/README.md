# Backlog

Leichtgewichtiges, dateibasiertes Backlog für HomeBase. **Eine Datei = ein Vorhaben.**
Format angelehnt an ADRs: fortlaufende 4-stellige ID + Slug (`NNNN-slug.md`).

Kein Jira, kein Tool-Zwang — nur Markdown im Repo, versioniert mit dem Code.

## Wie es funktioniert

1. **Neues Item anlegen:** `TEMPLATE.md` nach `NNNN-kurzer-slug.md` kopieren (nächste freie ID),
   Frontmatter + Abschnitte ausfüllen, unten in die Tabelle eintragen.
2. **Status pflegen:** `status:` im Frontmatter ändern
   (`backlog` → `in-progress` → `done`, oder `wont-do`). Die Datei bleibt erhalten
   (Historie/Begründung); nur die Tabelle hier aktualisieren.
3. **Keine Duplikate:** vor dem Anlegen erst in der Tabelle nachsehen.

Es gilt die Regel aus [`../claude.md`](../claude.md) (Abschnitt „Backlog & Out-of-Scope-Funde"):
Funde, die **nicht** zur gerade laufenden Aufgabe gehören (Out-of-Scope), werden **hier**
abgelegt — statt den Scope der aktuellen Änderung aufzublähen oder den Fund zu verlieren.

## Offene Items

| ID | Titel | Kategorie | Prio | Status | Quelle |
|----|-------|-----------|------|--------|--------|
| [0001](0001-i18n-sprachumschalter.md) | Mehrsprachigkeit / Sprachumschalter | feature | medium | backlog | prd.md |
| [0002](0002-rezept-zutaten-einkaufsliste.md) | Rezept-Zutaten auf Einkaufsliste übernehmen | feature | medium | done | prd.md |
| [0003](0003-zeiterfassung-reports-csv.md) | Zeiterfassung: server-seitige Reports / CSV-Export | feature | low | backlog | prd.md |
| [0004](0004-einkauf-angebote.md) | Angebote zu Einkaufsitems (Rewe/Kaufland) | feature | low | backlog | prd.md |
| [0005](0005-wiederkehrende-todos.md) | Wiederkehrende Todos | feature | medium | backlog | prd.md |
| [0006](0006-bilder-in-notizen.md) | Bilder in Notizen | feature | low | done | prd.md |
| [0007](0007-ios-app.md) | iOS-App | feature | low | backlog | prd.md |
| [0008](0008-json-defaults-leere-arrays.md) | Leere Arrays werden aus JSON weggelassen (encodeDefaults) | bug | medium | backlog | session 2026-06-05 |
| [0011](0011-flyway-repair-unbedingt-bei-jedem-start.md) | flyway.repair() läuft unbedingt bei jedem Start | tech-debt | medium | backlog | PR #33 |
| [0012](0012-migration-ci-test-haerten.md) | Migrations-Integrationstest + CI-Skip-Guard härten | tech-debt | low | backlog | PR #35 |
| [0013](0013-passwort-hashing-sha256-kein-kdf.md) | Passwörter als ungesalzenes SHA-256 ohne KDF | security | high | backlog | PR #4 |
| [0014](0014-todos-private-listen-ws-leak.md) | Todos privater Listen lecken über geteilten WS-Kanal | security | high | backlog | PR #24 |
| [0015](0015-android-token-klartext-datastore.md) | Android: JWT-Token im Klartext im DataStore | security | medium | backlog | PR #3 |
| [0016](0016-android-websocket-kein-reconnect.md) | Android: WebSocket-Clients ohne Reconnect | bug | medium | backlog | PR #3 |
| [0017](0017-ci-workflow-permissions-least-privilege.md) | CI-Workflows ohne top-level permissions: | security | medium | backlog | PR #6 |
| [0018](0018-ci-actions-sha-pinning.md) | GitHub-Actions nur auf Mutable Tags gepinnt (Supply-Chain) | security | medium | backlog | PR #12 |
| [0019](0019-docker-ci-java21-statt-24.md) | Docker/CI auf Java 24 statt 21 LTS | tech-debt | medium | backlog | PR #11 |
| [0020](0020-todo-liste-loeschen-verwaiste-todos.md) | Liste löschen verwaist Todos (UI/Digest) | bug | medium | backlog | PR #24 |
| [0021](0021-telegram-digest-zeitzone.md) | Telegram-Digest Zeitzone (UTC statt Europe/Berlin) | bug | medium | backlog | PR #10 |
| [0022](0022-vitest-suite-nicht-in-ci.md) | Vitest-Unit-Suite läuft in keinem CI-Job | test-gap | medium | backlog | PR #26 |
| [0023](0023-backend-container-non-root.md) | Backend-Container läuft als root | security | low | backlog | PR #11 |
| [0024](0024-zeiterfassung-archivierte-projekte.md) | Zeiterfassung erlaubt archivierte Projekte | bug | low | backlog | PR #13 |
| [0025](0025-abwesenheit-authz-userid-aus-jwt.md) | Abwesenheit: Authz aus JWT statt Request-userId | security | low | backlog | PR #27 |
| [0026](0026-abwesenheit-bulk-insert-limit.md) | Abwesenheit: kita/range & batch ohne Limit | bug | low | backlog | PR #27 |

## Legende

- **Status:** `backlog` · `in-progress` · `done` · `wont-do`
- **Kategorie:** `feature` · `tech-debt` · `bug` · `security` · `test-gap` · `chore` · `docs`
- **Prio:** `low` · `medium` · `high`
