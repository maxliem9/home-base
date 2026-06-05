---
id: 0025
title: Abwesenheit — Schreibrouten leiten userId aus Request statt JWT ab; A kann B's Einstellungen/Allowance überschreiben
status: backlog
category: security
priority: low
source: PR #27 (Review session 2026-06-05)
created: 2026-06-05
---

# 0025 — Abwesenheit: Authz aus JWT statt Request

## Kontext
Alle Abwesenheits-Mutationen vertrauen der vom Client gelieferten `userId`, ohne sie gegen den
authentifizierten Aufrufer zu prüfen: in `PUT /settings/{userId}` (`routes/AbsenceRoutes.kt:277-313`)
kommt `userId` aus dem Pfad, in den `entries`-Routen (`:86-151`) aus Body/Query — geprüft wird
nur `userExists`. `AbsenceRoutes.kt` referenziert nirgends den JWT-`principal`. Zum Vergleich
nehmen `TimeRoutes.kt:220/289` und `NoteRoutes.kt` die Identität aus `ApplicationCall.username()`.

Besonders bei `PUT /settings/{userId}` (persönliches Urlaubskontingent, Übertrag, Bundesland,
Kind-krank-Cap) kann Nutzer A so die persönlichen Einstellungen von Nutzer B überschreiben.
Das gemeinsame Bearbeiten von Kalendertagen ist beim geteilten Familienplaner plausibel
gewollt; die Einstellungs-Zeile pro Nutzer ist aber persönliche Konfiguration.

## Aufgabe
- Mindestens in `PUT /settings/{userId}` erzwingen, dass `userId == ApplicationCall.username()`
  (sonst `403 FORBIDDEN`), analog zur Eigentümerprüfung in `NoteRoutes`.
- Für die `entries`-Routen entscheiden, ob fremdes Schreiben bewusst erlaubt sein soll; falls
  ja, im Domänenmodell (CLAUDE.md) explizit als „beide dürfen alle Tage editieren" festhalten,
  sonst ebenfalls gegen den Principal absichern.

## Offene Fragen / Notizen
- Trust-Modell: 2 feste, vertraute Nutzer → low/MINOR.
- Es existieren keine Authz-Tests in `AbsenceRouteTest.kt` — beim Fix ergänzen.
