---
id: 0018
title: Dritt-Anbieter-GitHub-Actions nur auf Mutable Tags gepinnt (Supply-Chain) trotz packages:write auf GHCR
status: backlog
category: security
priority: medium
source: PR #12 (Review session 2026-06-05)
created: 2026-06-05
---

# 0018 — GitHub-Actions auf Commit-SHA pinnen

## Kontext
Der `docker`-Job hat `packages: write` und loggt sich mit `secrets.GITHUB_TOKEN` bei GHCR ein,
um Images nach `ghcr.io/maxliem9/homebase-{backend,web}` zu pushen. Die dafür genutzten
Dritt-Anbieter-Actions sind nur auf verschiebbare Tags gepinnt:
`gradle/actions/setup-gradle@v4` (`ci.yml:28,76,155`), `android-actions/setup-android@v3`
(`:152`), `docker/setup-buildx-action@v3` (`:183`), `docker/login-action@v3` (`:190`),
`docker/build-push-action@v6` (`:199,211`).

Wird ein solches Tag kompromittiert (Tag-Repoint auf bösartigen Commit), kann der Schritt den
GHCR-Push-Token abgreifen oder vergiftete Images veröffentlichen, die die NAS anschließend
zieht.

## Aufgabe
- Dritt-Anbieter-Actions auf einen vollen Commit-SHA pinnen (Tag als Kommentar dahinter),
  z. B. `docker/build-push-action@<sha> # v6`. Erst-Anbieter-`actions/*` können auf Tags
  bleiben.
- Updates über Dependabot (`package-ecosystem: github-actions`) automatisieren.

## Offene Fragen / Notizen
- Gleiche Härtung wäre für die Basis-Images sinnvoll (Digest-Pin statt `eclipse-temurin:…`,
  `nginx:alpine`, `node:20-alpine`, `postgres:16-alpine`) — niedrigere Prio, daher hier nur
  als Hinweis.
