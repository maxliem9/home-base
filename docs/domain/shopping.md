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

**Offline-Read-Cache — „alter Stand statt leerer Bildschirm" (#517):** Das Abhaken war schon
resilient; **das Laden** war es nicht. Wurde die App ohne Verbindung (kalt) geöffnet, zeigte sie
nichts, obwohl die Liste kurz vorher noch da war (In-Memory-State ist nach Prozess-Tod leer, der
`getOrDefault(state)`-Fallback fällt dann auf *leer* zurück). Fix: die zuletzt erfolgreich geladenen
**Listen + Items** werden durabel gecacht und beim (Kalt-)Start in den State **geseedet**, bevor der
Fetch antwortet — offline sieht man den alten Stand, online ersetzt der erste erfolgreiche Fetch ihn
(stale-while-revalidate). Der Cache wird bei **jeder** Änderung (Server-Fetch *und* optimistische
Edits/Abhaken) mitgeschrieben, spiegelt also exakt das zuletzt Gesehene — inkl. eines offline
abgehakten Items samt „nicht synchronisiert"-Marker.
- **Android:** generischer `SnapshotStore<T>` (`data/cache/`, SharedPreferences+Moshi, `apply()`,
  eigene Prefs-Datei `homebase_shopping_cache`) → `ShoppingSnapshot(lists, items)`. Der VM seedet in
  `restoreAndMirrorSnapshot()` (Disk-Read **vor** dem Mirror-Collector, sonst überschreibt der leere
  Startframe den guten Cache) und spiegelt via `uiState.map{lists,items}.distinctUntilChanged()`.
  `hasServerData` verhindert, dass ein langsamer Disk-Read frische Serverdaten überschreibt; ein
  fehlgeschlagener Refresh setzt **nur** dann einen Blocking-Error, wenn ohnehin nichts anzuzeigen ist.
- **Web:** `localStorage['homebase_shopping_cache']`, geseedet in den `useState`-Initialisierern,
  gespiegelt per `useEffect([lists, items])`. **Wichtig:** Der Service Worker cached **keine** Assets
  (nur Web Push), d. h. *vollständig* offline lädt die SPA-Shell gar nicht erst — der Web-Cache greift
  nur bei flakiger Verbindung (Shell aus dem Browser-Cache, `/api` schlägt fehl) und für den sofortigen
  ersten Paint. Echter Offline-Shell-Betrieb braucht Asset-Caching im SW → #519.
- **Nur Einkauf umgesetzt** (der gemeldete Fall). Kategorien fallen offline auf `BUILTIN_CATEGORIES`
  zurück (Gruppierung funktioniert), Vorlagen/Suggestions sind offline leer — bewusst nicht gecacht.
  Andere Views (Todos, Notizen, Wochenplan …) haben denselben Kalt-Start-Offline-Gap → #520,
  der `SnapshotStore<T>` macht die Erweiterung mechanisch.
