---
id: 0001
title: Mehrsprachigkeit / Sprachumschalter
status: backlog
category: feature
priority: medium
source: prd.md (Post-MVP)
created: 2026-06-05
---

# 0001 — Mehrsprachigkeit / Sprachumschalter

## Kontext
Laut `prd.md` sind die UI-Texte bereits externalisiert, ein Sprachumschalter wurde
aber **bewusst (noch) nicht** implementiert (siehe `prd.md`, Hinweis im Funktionsteil).
Aktuell ist die App durchgängig deutsch.

## Aufgabe
- Sprachumschalter (DE/EN) im Web und in der Android-App.
- Persistenz der gewählten Sprache (localStorage im Web / DataStore in Android — oder
  pro Nutzer im Backend).
- Web: i18n-Lib evaluieren (z. B. `react-i18next`) vs. eigener leichter Context.
- Klären, ob Backend-erzeugte Texte (Telegram-Digest) ebenfalls lokalisiert werden sollen.

## Offene Fragen / Notizen
- Wird Englisch bei 2 festen Nutzern überhaupt gebraucht, oder reicht reine
  Vorbereitung der Struktur?
- Wo liegt die Präferenz — Gerät vs. nutzergebunden im Backend (`users`)?
