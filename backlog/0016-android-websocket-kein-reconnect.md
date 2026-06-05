---
id: 0016
title: Android — WebSocket-Clients ohne onFailure/Reconnect; Echtzeit-Sync bricht nach Netzaussetzer dauerhaft ab
status: backlog
category: bug
priority: medium
source: PR #3 (Review session 2026-06-05)
created: 2026-06-05
---

# 0016 — Android: WebSocket-Clients ohne Reconnect

## Kontext
Fällt der Socket aus (Mobilfunk-Wechsel, Doze/Standby, Backend-Neustart), bleibt er still tot:
**kein** `WebSocketListener` überschreibt `onFailure`/`onClosed`, alle implementieren nur
`onMessage` (`TodoWebSocketClient.kt:43-61`, `ShoppingWebSocketClient.kt:43-61`,
`NotesWebSocketClient.kt:39-55`, `TimeWebSocketClient.kt:42-60`, `RecipeWebSocketClient.kt:39-55`,
`AbsenceWebSocketClient.kt:41-50`). OkHttp-WebSockets reconnecten nicht von selbst, und es gibt
keine Lifecycle-Resume-Logik.

Da die ViewModels in `MainActivity` gehoistet sind und Navigation überleben, wird erst bei
Logout/Login oder Prozess-Tod neu verbunden. Bis dahin erscheinen Änderungen des anderen
Nutzers nie, obwohl die App online wirkt — das untergräbt das Kernversprechen „Echtzeit-Sync
zwischen Android und Web". Der mit #29 hinzugekommene Absence-Client teilt denselben Mangel.

## Aufgabe
- In jedem WS-Client `onFailure`/`onClosed` überschreiben und Reconnect mit Backoff anstoßen.
  Da alle sechs Clients identisch sind: gemeinsame Basis-/Reconnect-Logik (gut mit dem in #30
  angeregten Refactor geteilter Moshi/WS-Infrastruktur aus dem AppContainer zu bündeln).
- Bei App-Resume (`Lifecycle.Event.ON_RESUME`/`repeatOnLifecycle`) den Socket prüfen/neu
  aufbauen.
- Optional `pingInterval` am OkHttpClient setzen, um tote Verbindungen früher zu erkennen.

## Offene Fragen / Notizen
- Kein Crash, aber funktionaler Kernfehler des Sync-Features. Betrifft alle sechs Clients
  gleich.
