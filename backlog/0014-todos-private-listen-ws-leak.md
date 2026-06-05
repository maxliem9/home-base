---
id: 0014
title: Todos privater Listen werden ungefiltert über den geteilten WS-Kanal an den anderen Nutzer gesendet
status: backlog
category: security
priority: high
source: PR #24 (Review session 2026-06-05)
created: 2026-06-05
---

# 0014 — Todos privater Listen lecken über den geteilten WS-Kanal

## Kontext
PR #24 führt SHARED/PRIVATE-Sichtbarkeit für Todo-Listen ein. `GET /todos` blendet Todos aus
fremden privaten Listen korrekt aus (`routes/TodoRoutes.kt:136-143`), aber der Echtzeit-Kanal
tut das **nicht**: jede Erstellung/Änderung/Löschung sowie jede Subtask-Mutation eines Todos
in einer privaten Liste wird per `broadcastTodo` vollständig (Titel, Beschreibung, Subtasks)
über `WsSessionManager.broadcast("todos", …)` an **beide** verbundenen Clients gepusht
(`TodoRoutes.kt:34-38, 192, 247, 297, 324, 342`; `ws/WsSessionManager.kt:23-29` fächert ohne
Pro-Nutzer-Filter auf). Damit sieht der andere Nutzer in Echtzeit Inhalte, die ihm laut
Sichtbarkeitsmodell verborgen bleiben sollen.

Die Notizen-Implementierung löst genau dieses Problem bewusst
(`NoteRoutes.kt:394` „we must never push a private note over it") — bei Todos fehlt das
Pendant ersatzlos.

## Aufgabe
- Sichtbarkeit in **allen** Todo-Broadcasts erzwingen, analog zu `broadcastCreate`/
  `broadcastUpdate` in `NoteRoutes.kt`: vor jedem `broadcastTodo` die Listen-Sichtbarkeit des
  betroffenen Todos auflösen und Todos aus privaten Listen NICHT auf dem geteilten Kanal
  senden. Idealerweise pro Nutzer adressierte Frames oder ein zusätzlicher privater Kanal.
- Sichtbarkeits-Übergänge (Todo wechselt via PUT `listId` von/auf eine private Liste) für den
  anderen Client als DELETE bzw. CREATE übersetzen.
- Regressionstest: eine Mutation an einem Todo in Nutzer A's privater Liste erzeugt **keine**
  WS-Nachricht an Nutzer B.

## Offene Fragen / Notizen
- Gleichwurzelige Lücke beim Fix mitziehen: `PUT /todos/lists/{id}` und
  `DELETE /todos/lists/{id}` (`TodoRoutes.kt:84-128`) prüfen **keine** Eigentümerschaft — wer
  die Listen-UUID kennt, kann die private Liste des anderen umbenennen, auf SHARED stellen
  oder löschen. Im 2-Nutzer-Modell normalerweise unkritisch, wird aber gerade dadurch
  erreichbar, dass dieser WS-Leak die IDs durchsickern lässt. Beim Fix `createdBy == username`
  für PUT/DELETE auf private Listen erzwingen.
- Sicherheitsrelevant (IMPORTANT/high), im 2-Nutzer-Vertrauensmodell aber kein harter
  Release-Blocker.
