# Wochenplan-Domänenmodell (Essensplaner)

> Lies dies, bevor du am Wochenplan/Essensplaner arbeitest. Siehe auch [recipes.md](recipes.md).

Verbindet Rezepte → Woche → Einkauf (HB-02, #218; Web+Backend, Android #250). Plant pro
Tag-und-Mahlzeit genau ein Rezept; haushaltsweit geteilt (wie Abwesenheit, kein Eigentümer-Check).
- MealPlanEntry: id, date, slot (BREAKFAST|LUNCH|DINNER), recipe_id? (FK recipes ON DELETE
  CASCADE), dish_title?, servings?, created_by, created_at. DB-Tabelle `meal_plan_entries` mit
  **Unique(date, slot)** — ein Eintrag pro Slot; ein erneutes Setzen ersetzt den Eintrag.
- **Rezept ODER Freitext (`dish_title`, #293):** ein Slot trägt **entweder** ein Rezept **oder**
  einen frei eingetippten Gericht-Namen („Pizza bestellen", „Reste") — XOR, per DB-CHECK
  (`V28__meal_plan_free_text_dish.sql`: `recipe_id` nullable + `CHECK ((recipe_id IS NULL) <>
  (dish_title IS NULL))`) und app-seitig im PUT erzwungen (genau eines, sonst 400). So muss nicht
  jedes Gericht als Rezept gepflegt werden. Freitext-Einträge haben **keine** Zutaten/Portionen →
  „In Einkaufsliste" überspringt sie (der recipe-Lookup schlägt fehl). Clients rendern
  `recipeTitle ?? dishTitle`; Freitext bekommt ein neutrales Stift-Icon (`edit`).
- **Slots ≠ Rezept-Kategorien:** die drei Raster-Mahlzeiten (Frühstück/Mittag/Abend) sind
  bewusst unabhängig von den Rezept-Kategorien (dort kein LUNCH seit V17) — jedes Rezept passt
  in jeden Slot.
- **Portionen pro Eintrag (`servings`, #251/#261):** wie viele Portionen gekocht werden;
  NULL = Rezept-Default (1× wie erfasst). „In Einkaufsliste" skaliert die Zutaten je Eintrag
  (Faktor servings / recipe.servings). Clients persistieren `servings` nur, wenn ≠ Rezept-Default
  (Default bleibt null → saubere Kacheln). DB-Spalte via `V27__meal_plan_servings.sql`
  (nullable, CHECK ≥ 1).
- DTO `MealPlanEntryDto(id, date, slot, recipeId?, recipeTitle?, recipeCategory?, dishTitle?,
  servings?, createdBy, createdAt)` — bei Rezept-Einträgen sind Titel/Kategorie eingejoint (Raster
  rendert ohne 2. Fetch), bei Freitext nur `dishTitle`; ungenutzte Felder fehlen
  (encodeDefaults=false). Entries werden über (MealPlanEntriesTable **leftJoin** RecipesTable)
  geladen, damit Freitext-Einträge (ohne Rezept) mitkommen; im Mapper `getOrNull` für die
  Join-Spalten.
- Endpunkte unter /api/v1/meal-plan (Vorbild AbsenceRoutes): GET /?from=&to= (inklusiver Range,
  Bound MAX_RANGE_DAYS=370), PUT /{date}/{slot} `{recipeId?, dishTitle?, servings?}` (genau eines
  von recipeId/dishTitle, setzen/ersetzen, 200), DELETE /{date}/{slot} (idempotent, 204).
  WS /api/v1/ws/meal-plan: jede Mutation sendet
  {type:"MEAL_PLAN_CHANGED"}; Clients laden den sichtbaren Range neu (kein Payload).
- **„In Einkaufsliste" hat keinen eigenen Endpunkt:** der Client sammelt die (skalierten) Zutaten
  aller geplanten Rezepte der Woche und postet sie an das bestehende `POST /api/v1/shopping/batch`
  (summiert nach Name+Einheit). Ein doppelt geplantes Gericht zählt doppelt.
- Web (`components/Wochenplan/WochenplanView.tsx`): Mo-basierte Wochen-Navigation, Desktop-7×3-
  Matrix + mobile vertikale Tagesliste (≤860px), Klick-Picker (Auswahl→Übernehmen, Portionen-
  Stepper). Live über `useWebSocket` auf `meal-plan` **und** `recipes` (ein Recipe-Delete
  cascadet die Plan-Einträge serverseitig, broadcastet aber nur auf dem recipes-Channel — beide
  Kanäle lösen einen Reload aus).
- Android (`ui/wochenplan/`): Spiegelung (Tagesliste, Rezept-Picker-BottomSheet, „In
  Einkaufsliste"). `MealPlanRepository` hält einen eigenen meal-plan- **und** einen dedizierten
  recipe-WebSocket (eigene Instanz, damit der Lifecycle nicht mit der Rezepte-Ansicht kollidiert)
  — selbe Cascade-Logik wie Web.

## Offline-Read-Cache (#520)
Wie beim Einkauf (#517/#518): der zuletzt geladene Plan wird durabel gecacht und beim (Kalt-)Start
geseedet, damit ein Launch ohne Verbindung den letzten Stand zeigt statt eines leeren Rasters. **Die
Einträge sind wochenabhängig**, daher wird die gecachte Woche (`weekStart`, ISO-Montag) mitgespeichert
und die Einträge nur geseedet, wenn sie == aktuell sichtbarer Woche ist; Rezepte + Einkaufslisten sind
wochenunabhängig und werden frei geseedet. Android: `MealPlanSnapshot(weekStart, entries, recipes,
shoppingLists)` über `SnapshotStore<T>`, Prefs-Datei `homebase_mealplan_cache`; Seed vor dem
Mirror-Collector, `hasServerData`-Guard. Web: `localStorage['homebase_mealplan_cache']`, Seed in den
`useState`-Initialisierern (Einträge nur bei Wochen-Match), Mirror per
`useEffect([weekStartIso, entries, recipes, shoppingLists])`. Wie #518 greift der Web-Cache nur bei
flakiger Verbindung / erstem Paint (#519).
