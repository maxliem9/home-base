# Backlog → GitHub Issues

Das früher dateibasierte Backlog (`backlog/NNNN-slug.md` + Index-Tabelle) wurde nach
**GitHub Issues** migriert. Neue Tickets bitte als Issue anlegen — Konvention siehe
[`../CLAUDE.md`](../CLAUDE.md), Abschnitt „Backlog & Out-of-Scope-Funde".

- **Offene Tickets:** <https://github.com/maxliem9/home-base/issues>
- **Kategorie-Labels:** `security` · `bug` · `tech-debt` · `test-gap` · `feature` · `docs` · `chore`
- **Prioritäts-Labels:** `priority:high` · `priority:medium` · `priority:low`

Warum der Umzug: die handgepflegte fortlaufende ID + Index-Tabelle führte unter parallel
arbeitenden Agenten zu ID-Kollisionen und Merge-Konflikten (beides in Issues nicht möglich);
zudem verlinken sich Issues und PRs nativ (`Closes #<n>`).

## Zuordnung alte ID → Issue

Die alten Markdown-Items bleiben in der Git-Historie erhalten (`git log -- backlog/`).

> **Hinweis:** Die frühere ID→Issue-Tabelle (0001→#41 … 0026→#64) zeigte auf
> Issue-Nummern aus dem **ursprünglichen Repo vor der Neuanlage**. Nach der
> Repo-Neuanlage wurde alles neu durchnummeriert — diese alten Nummern verweisen
> heute auf unzusammenhängende Vorgänge und sind daher nicht mehr gültig. Die
> Zuordnung wird nicht weiter gepflegt; der Inhalt der alten Items steht in der
> Git-Historie. Siehe Issue #137.
