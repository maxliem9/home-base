---
id: 0020
title: Löschen einer nicht-leeren Todo-Liste verwaist deren Todos (UI unerreichbar, Digest zeigt sie weiter)
status: backlog
category: bug
priority: medium
source: PR #24 (Review session 2026-06-05)
created: 2026-06-05
---

# 0020 — Liste löschen verwaist Todos

## Kontext
Beim Löschen einer Liste werden ihre Todos nicht mitgelöscht, sondern auf `list_id = NULL`
gesetzt (`V7__lists_and_visibility.sql:15` `ON DELETE SET NULL`; `routes/TodoRoutes.kt:118`
`update{ listId = null }`; Test `DELETE list detaches its todos` zementiert das). Die Web-UI
entfernt sie optimistisch aus der Anzeige (`web/src/components/TodosView.tsx:232-247`), der
Nutzer nimmt also an, sie seien weg.

Tatsächlich bleiben sie als **verwaiste Zeilen** in der DB, sind aber über keine Liste mehr
erreichbar: die UI ist strikt Tab-pro-Liste ohne „ohne Liste"-Eimer, und `removeList` ist auf
`lists.length > 1` gesperrt, sodass der NULL-Eimer nie navigierbar wird. Gleichzeitig liest
der Telegram-Digest Todos ohne `list_id`-Filter (`digest/DigestService.kt:37-51`), sodass diese
„gelöschten" Todos weiter als heute-erledigt / neue-Inbox / morgen-fällig gemeldet werden.
(Bei Einkaufslisten ist es konsistent: V7 nutzt dort echtes `ON DELETE CASCADE`.)

## Aufgabe
Verhalten zwischen Backend und UI vereinheitlichen — eine von:
- **(a) „Liste löschen = enthaltene Todos löschen"** (wahrscheinlich erwartete Semantik, da
  die UI Löschung suggeriert): V7-FK auf `ON DELETE CASCADE` ändern bzw. Folgemigration
  ergänzen, in `TodoRoutes.kt:118` statt `update{listId=null}` die Todos der Liste löschen
  (inkl. Subtask-Cascade), Test anpassen. **oder**
- **(b) verwaiste Todos erreichbar machen:** einen „Ohne Liste"-Eimer/Tab in `TodosView.tsx`
  einführen (Todos mit `listId == null`) und das `lists.length > 1`-Gate überdenken.
- In beiden Fällen prüfen, dass der Digest keine als gelöscht wahrgenommenen Todos mehr meldet.

## Offene Fragen / Notizen
- Variante (a) erfordert eine bewusste Produktentscheidung — bei „starken Änderungen"
  Rücksprache mit dem Nutzer.
- `subtasks: List<SubtaskDto> = emptyList()` (`Models.kt:38`) ist dieselbe encodeDefaults-
  Klasse wie 0008 — nur Referenz, nicht erneut anlegen.
