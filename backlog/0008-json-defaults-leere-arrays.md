---
id: 0008
title: Leere Arrays/Defaults werden aus JSON weggelassen (encodeDefaults=false)
status: backlog
category: bug
priority: medium
source: session 2026-06-05 (beim Umsetzen von 0006 Bilder in Notizen entdeckt)
created: 2026-06-05
---

# 0008 — Leere Arrays/Defaults werden aus JSON weggelassen

## Kontext
Die Backend-JSON-Konfiguration (`plugins/Serialization.kt`) nutzt die kotlinx-
serialization-Standardeinstellung `encodeDefaults = false`. Dadurch wird jedes
DTO-Feld, dessen Wert seinem **Default entspricht**, komplett aus der Antwort
weggelassen — auch leere Listen mit `= emptyList()`.

Das ist gefährlich, wenn ein Client das Feld als immer vorhanden annimmt:
- `RecipeDto.ingredients` / `RecipeDto.steps` haben `= emptyList()`
  (`model/Models.kt`). Ein Rezept ohne Schritte (oder ohne Zutaten) liefert die
  Keys also gar nicht.
- Im Web ist `Recipe.ingredients: Ingredient[]` / `steps: RecipeStep[]`
  **nicht optional** (`web/src/types.ts`) und wird ungeschützt benutzt, z. B.
  `r.ingredients.length`, `recipe.steps.map(...)`
  (`web/src/components/RecipesView.tsx:57,60,337,350,374,…`).
  → `TypeError: Cannot read properties of undefined` beim Laden eines Rezepts
  ohne Schritte/Zutaten.

Bei `0006` wurde das umgangen, indem `NoteDto.images` **bewusst keinen Default**
bekam (so wird das leere Array immer serialisiert). Das ist aber eine
punktuelle Lösung; das Grundproblem betrifft weitere DTOs.

## Aufgabe
- Reproduzieren: Rezept ohne Schritte (oder ohne Zutaten) anlegen und im Web
  öffnen.
- Grundsatzentscheidung treffen und konsistent umsetzen, z. B. eine von:
  - `encodeDefaults = true` in `Serialization.kt` (wirkt global — Auswirkungen
    auf alle DTOs prüfen, v. a. nullable-Felder, die dann als `null` statt gar
    nicht erscheinen), **oder**
  - bei betroffenen „immer vorhanden“-Feldern den Default entfernen (wie bei
    `NoteDto.images`), **oder**
  - Clients defensiv machen (`r.ingredients ?? []`).
- Weitere Kandidaten prüfen: `TodoDto.subtasks`, `RecipeDto.ingredients/steps`,
  evtl. nullable-Felder, auf die Web/Android ungeschützt zugreifen.

## Offene Fragen / Notizen
- Android ist hier robuster: dortige DTOs geben Listen `= emptyList()` als
  Default an, sodass fehlende Keys von Moshi zu leeren Listen werden.
- Querschnittsthema — am besten eine Konvention dokumentieren (CLAUDE.md),
  welche Variante gilt.
