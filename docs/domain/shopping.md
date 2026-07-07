# Einkauf — Offline-Resilienz

> Lies dies, bevor du an der Einkaufsliste / dem Abhaken arbeitest.

**Offline-Resilienz beim Abhaken (Einkauf):** Das Abhaken eines Einkaufs-Items
(`ShoppingView`, `toggleChecked`) schreibt optimistisch **und** legt die Änderung in
eine durable Queue (`localStorage` key `homebase_shopping_pending`, key=Item-UUID,
letzter Wunsch gewinnt). Ein Flush sendet die PUTs und wird von drei Signalen
getriggert: WS-`onOpen`, Browser-`online`-Event und einem 15-s-Intervall-Backstop
(flakiges Laden-WLAN feuert oft kein `online`). Bis zum Erfolg trägt das Item einen
„nicht synchronisiert"-Marker + Sammelbanner (nie still verlieren). Abgehakte sind nach
`checkedAt` desc sortiert (zuletzt abgehakt oben). Android hat dieselbe Resilienz
inzwischen ebenfalls (#170/#179): persistente Pending-Queue (DataStore, latest-wins),
Retry via `ReconnectingWebSocketClient`-onOpen + `ConnectivityManager` + Intervall-Backstop,
„nicht synchronisiert"-Marker und `checkedAt`-Sortierung. Deletes/Clear nutzen weiter den
Toast-Pfad (kein Queue). Items ohne Liste erzeugt Android nicht mehr (#181: beim ersten Item
ohne Liste wird automatisch eine Default-Liste angelegt).

> **Android Test-Falle:** Offline-Queue-ViewModel-Tests können unter `advanceUntilIdle`
> hängen, wenn die Queue am Testende nicht leer ist (der Backstop-Loop dreht virtuelle Zeit
> endlos). Vorbild `ShoppingViewModelTest`: ViewModelStore+vmTest cancelt den Scope, großes
> `flushIntervalMs`, `runCurrent` statt `advanceUntilIdle` solange etwas in der Queue liegt.
