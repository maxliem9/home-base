---
id: 0022
title: Vitest-Unit-Suite (format.ts) läuft in keinem CI-Job — Regressionen bleiben unentdeckt
status: backlog
category: test-gap
priority: medium
source: PR #26 (Review session 2026-06-05)
created: 2026-06-05
---

# 0022 — Vitest-Suite läuft nicht in CI

## Kontext
PR #26 hat eine 26-Tests-Vitest-Suite für `web/src/ui/format.ts` hinzugefügt — inhaltlich
solide (handberechnete Erwartungswerte, deterministisch via Fake-Timers + `TZ=UTC`, deckt
Grenzfälle wie `diff===7`, Negativ-/Bruch-Dauern und kaputte JWTs ab). Die Suite wird aber in
**keiner** Pipeline ausgeführt: der `web`-Job in `ci.yml:115-136` führt nur `npm run build`
(tsc typecheckt `format.ts`, führt sie aber nicht aus), `web-e2e.yml:36-39` startet nur
Playwright. `grep` über `.github/workflows/` findet nur `npm run test:e2e`.

Damit würde genau die in der PR-Beschreibung als Motivation genannte „off-by-one / boundary /
timezone"-Regression in `format.ts` (genutzt in jeder View: `dueLabel`, `weekKey`, `fmtClock`,
`usernameFromToken` …) von CI **nicht** gefangen — die Tests laufen nur, wenn jemand lokal
manuell `npm test` tippt. Die Coverage ist real vorhanden, aber faktisch wirkungslos.

## Aufgabe
- In `.github/workflows/ci.yml` im `web`-Job (nach `npm ci`) einen Schritt `npm run test:unit`
  ergänzen (oder einen eigenen Job). Sicherstellen, dass ein roter Unit-Test den PR blockiert.
- Da die Scripts `TZ=UTC` via Shell-Prefix setzen, läuft das auf den Ubuntu-Runnern korrekt;
  alternativ `TZ` über `env:` setzen.

## Offene Fragen / Notizen
- Reines CI-Wiring, kein Test-Inhalt zu ändern.
- Der `typecheck:e2e`-Schritt in `web-e2e.yml` ist nicht betroffen (deckt nur die
  e2e-Fixtures ab, nicht die Vitest-Ausführung).
