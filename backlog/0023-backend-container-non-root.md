---
id: 0023
title: Backend-Container läuft als root (kein USER) — fehlende Container-Härtung
status: backlog
category: security
priority: low
source: PR #11 (Review session 2026-06-05)
created: 2026-06-05
---

# 0023 — Backend-Container als non-root

## Kontext
Die Laufzeit-Stage des `backend/Dockerfile:12-16` (`eclipse-temurin:…-jre`) legt keinen
non-root-User an und setzt kein `USER`, der Backend-Prozess läuft also als **root** im
Container (`grep "USER " backend/Dockerfile web/Dockerfile` → kein Treffer). Bei einer RCE im
Backend (Ktor/JVM) erleichtert root die Privilege-Eskalation, inkl. Schreibzugriff auf das
gemountete `uploads`-Volume.

Für einen privaten 2-Nutzer-Hub hinter dem DSM-Reverse-Proxy ist das Restrisiko gering, aber
die Härtung ist trivial.

## Aufgabe
- In der Runtime-Stage einen unprivilegierten User anlegen und vor dem ENTRYPOINT setzen,
  z. B. `RUN useradd -r -u 10001 app && chown -R app /app` + `USER app`.
- Sicherstellen, dass `UPLOAD_DIR` (`/data/uploads`) für diesen User beschreibbar bleibt.

## Offene Fragen / Notizen
- Der web-Container (`nginx:alpine`) läuft ebenfalls als root — bei Bedarf zusammen härten.
- Gut zusammen mit 0019 (JDK-21-Umstellung) im selben Dockerfile-PR zu erledigen.
