# Backlog → GitHub Issues

Das früher dateibasierte Backlog (`backlog/NNNN-slug.md` + Index-Tabelle) wurde nach
**GitHub Issues** migriert. Neue Tickets bitte als Issue anlegen — Konvention siehe
[`../claude.md`](../claude.md), Abschnitt „Backlog & Out-of-Scope-Funde".

- **Offene Tickets:** <https://github.com/maxliem9/home-base/issues>
- **Kategorie-Labels:** `security` · `bug` · `tech-debt` · `test-gap` · `feature` · `docs` · `chore`
- **Prioritäts-Labels:** `priority:high` · `priority:medium` · `priority:low`

Warum der Umzug: die handgepflegte fortlaufende ID + Index-Tabelle führte unter parallel
arbeitenden Agenten zu ID-Kollisionen und Merge-Konflikten (beides in Issues nicht möglich);
zudem verlinken sich Issues und PRs nativ (`Closes #<n>`).

## Zuordnung alte ID → Issue

Die alten Markdown-Items bleiben in der Git-Historie erhalten.

```
0001→#41  0003→#42  0004→#43  0005→#44  0007→#45  0008→#46
0009→#47  0010→#48  0011→#49  0012→#50  0013→#51  0014→#52
0015→#53  0016→#54  0017→#55  0018→#56  0019→#57  0020→#58
0021→#59  0022→#60  0023→#61  0024→#62  0025→#63  0026→#64
```

`0002` und `0006` waren bereits `done` und wurden nicht migriert.
