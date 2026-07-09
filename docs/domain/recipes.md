# Rezepte-Domänenmodell

> Lies dies, bevor du an Rezepten arbeitest. Siehe auch [meal-plan.md](meal-plan.md).

Recipe mit eingebetteten Ingredients + RecipeSteps (1:n, werden
immer zusammen mit dem Rezept gespeichert — kein separater Endpunkt).
- Recipe: id, title, description?, servings, prep_time_minutes?,
  cook_time_minutes?, category (BREAKFAST|DINNER|SNACK|DESSERT|DRINK),
  created_by, created_at, updated_at
  (LUNCH gibt es nicht mehr: per `V17__recipes_drop_lunch_category` nach DINNER gefaltet;
  `RecipeRoutes.VALID_CATEGORIES` lehnt es beim Schreiben ab. Nicht wieder hinzufügen.)
- Ingredient: id, recipe_id, name, amount?, unit?, sort_order
- RecipeStep: id, recipe_id, step_number, description
- Endpunkte unter /api/v1/recipes (Liste filterbar via ?category=)
- Portionierung: GET /api/v1/recipes/{id}?servings=N skaliert
  alle Ingredient-Mengen (Faktor N / servings)
- Einzelrezept-Export: GET /api/v1/recipes/{id}/export?format=md|pdf&servings=N
  liefert ein Rezept als Markdown (text/markdown) oder PDF (serverseitig via OpenPDF);
  deutscher Inhalt analog CSV-Export, Content-Disposition-Dateiname rezept_<slug>.<ext>.
  Web: Download-Button in der Detailansicht; Android: System-Share-Sheet (FileProvider).
- WebSocket /api/v1/ws/recipes (RECIPE_CREATED|UPDATED|DELETED)

## Offline-Read-Cache (#520)
Wie beim Einkauf (#517/#518): die zuletzt geladenen Rezepte werden durabel gecacht und beim
(Kalt-)Start in den State geseedet, damit ein Launch ohne Verbindung den letzten Stand zeigt statt
eines leeren Screens. Android: `RecipesSnapshot(recipes)` über den generischen `SnapshotStore<T>`,
eigene Prefs-Datei `homebase_recipes_cache`; VM seedet in `restoreAndMirrorSnapshot()` (Disk-Read vor
dem Mirror-Collector), Mirror **nur wenn kein Kategoriefilter aktiv ist** (server-seitig gefiltert →
sonst würde eine Teilmenge den Cache überschreiben). Web: `localStorage['homebase_recipes_cache']`;
die Web-Liste wird **unfiltered** geladen (Filter ist client-seitig) → immer die volle Menge, daher
ohne Guard gecacht. Wie #518 greift der Web-Cache nur bei flakiger Verbindung / erstem Paint (#519).
