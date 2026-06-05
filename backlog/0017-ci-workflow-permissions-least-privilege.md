---
id: 0017
title: CI-Workflows ohne top-level permissions: — GITHUB_TOKEN läuft mit Repo-Default (oft write-all)
status: backlog
category: security
priority: medium
source: PR #6 (Review session 2026-06-05)
created: 2026-06-05
---

# 0017 — CI ohne Least-Privilege-permissions

## Kontext
`.github/workflows/ci.yml` definiert **keinen** top-level `permissions:`-Block; nur der
`docker`-Job (ab `:172`) schränkt explizit ein (`contents: read`, `packages: write`). Alle
anderen Jobs (`backend`, `migrations`, `web`, `android`) erben damit die Repo-Standardrechte
des `GITHUB_TOKEN`, die in vielen Repos noch „read & write all" sind.

Da der Trigger `pull_request` (nicht `pull_request_target`) ist, sind Fork-PRs zwar auf
read-only beschränkt, aber Pushes auf `main` und repo-interne Branches laufen mit den vollen
Default-Rechten — mehr als Test-/Build-Jobs brauchen.

## Aufgabe
- Top-level `permissions: { contents: read }` in `ci.yml` ergänzen (Least-Privilege als
  Default) und nur dort hochstufen, wo nötig (der `docker`-Job behält sein lokales
  `packages: write`).
- Analog in `web-e2e.yml` prüfen/ergänzen.

## Offene Fragen / Notizen
- Mit `pull_request` ist das Risiko geringer als bei `pull_request_target`, aber
  Least-Privilege bleibt Best Practice und ist mit einer Zeile umsetzbar.
