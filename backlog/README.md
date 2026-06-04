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
| [0002](0002-rezept-zutaten-einkaufsliste.md) | Rezept-Zutaten auf Einkaufsliste übernehmen | feature | medium | backlog | prd.md |
| [0003](0003-zeiterfassung-reports-csv.md) | Zeiterfassung: server-seitige Reports / CSV-Export | feature | low | backlog | prd.md |
| [0004](0004-einkauf-angebote.md) | Angebote zu Einkaufsitems (Rewe/Kaufland) | feature | low | backlog | prd.md |
| [0005](0005-wiederkehrende-todos.md) | Wiederkehrende Todos | feature | medium | backlog | prd.md |
| [0006](0006-bilder-in-notizen.md) | Bilder in Notizen | feature | low | backlog | prd.md |
| [0007](0007-ios-app.md) | iOS-App | feature | low | backlog | prd.md |
| [0008](0008-branches-aufraeumen.md) | Gemergte lokale Git-Branches aufräumen | chore | low | backlog | session 2026-06-05 |

## Legende

- **Status:** `backlog` · `in-progress` · `done` · `wont-do`
- **Kategorie:** `feature` · `tech-debt` · `bug` · `chore` · `docs`
- **Prio:** `low` · `medium` · `high`
