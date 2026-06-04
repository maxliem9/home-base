---
id: 0008
title: Gemergte lokale Git-Branches aufräumen
status: backlog
category: chore
priority: low
source: session 2026-06-05 (Bestandsaufnahme offener Aufgaben)
created: 2026-06-05
---

# 0008 — Gemergte lokale Git-Branches aufräumen

## Kontext
Bei der Bestandsaufnahme am 2026-06-05 lagen viele lokale Feature-Branches herum, die
vollständig in `main` gemergt sind (0 commits ahead) und nur noch Clutter sind, u. a.:
`feat/i18n`, `docs/deployment-guide`, `feature/notes`, `feature/time-tracking`,
`feat/design-overhaul`, `codex-pr-history-followups`, `feature/recipes-module`.

## Aufgabe
- Vollständig gemergte lokale Branches löschen (`git branch -d <name>`).
- `feature/recipes-module` gesondert prüfen: zeigte „ahead 1" — ein alter, abweichender
  Commit. Inhalt ansehen, ob etwas Wertvolles drin ist, sonst verwerfen.

## Offene Fragen / Notizen
- Reine Aufräumarbeit, kein Code-Risiko. Vor dem Löschen je Branch kurz
  `git log main..<branch>` prüfen.
