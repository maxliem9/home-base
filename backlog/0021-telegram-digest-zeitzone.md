---
id: 0021
title: Telegram-Digest nutzt ZoneId.systemDefault() — im Container UTC statt Europe/Berlin, Sendezeit & Tagesgrenzen falsch
status: backlog
category: bug
priority: medium
source: PR #10 (Review session 2026-06-05)
created: 2026-06-05
---

# 0021 — Telegram-Digest: Zeitzone

## Kontext
`DIGEST_TIME="20:00"` ist für einen deutschen Haushalt als Ortszeit gedacht, wird aber gegen
`ZoneId.systemDefault()` ausgewertet (`digest/DigestScheduler.kt:23`, `digest/DigestService.kt:29`;
`plugins/Digest.kt:29-34` übergibt keine Zone). Da weder `backend/Dockerfile` noch
`docker-compose.yml` ein `TZ` setzen, läuft die JVM im Container in **UTC**.

Der Digest feuert dadurch um 20:00 UTC, also 22:00 (Sommerzeit) bzw. 21:00 (Winterzeit)
Ortszeit, und auch die `[heute, morgen)`-Instant-Fenster für „heute erledigt" / „morgen
fällig" werden auf UTC-Tagesgrenzen statt Berliner Tagesgrenzen berechnet.

## Aufgabe
- Zeitzone konfigurierbar machen (z. B. `DIGEST_ZONE`/`TZ` als ENV, Default `Europe/Berlin`)
  und sowohl an `DigestScheduler` als auch an `DigestService` durchreichen, statt
  `ZoneId.systemDefault()`.
- Alternativ/ergänzend `TZ=Europe/Berlin` im `backend`-Service der `docker-compose.yml`
  setzen.
- Test: `millisUntilNextRun` und die Tagesfenster rechnen gegen die konfigurierte Zone, nicht
  gegen die JVM-Default-Zone.

## Offene Fragen / Notizen
- Die in PR #10 behobene `application.conf`-Reihenfolge (Default vor `${?DIGEST_TIME}`) ist in
  main korrekt; das hier ist ein davon unabhängiger Zeitzonen-Bug.
- Verschleppte Sends bei Downtime um 20:00 sind ein bewusster Trade-off, kein separater Fund.
