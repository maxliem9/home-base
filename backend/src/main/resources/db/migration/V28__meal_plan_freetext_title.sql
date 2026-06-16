-- Wochenplan free-text dishes (#293): a slot may now hold a free-text dish name (e.g. "Reste",
-- "Pizza bestellt") instead of referencing a recipe. Exactly one of (recipe_id, title) is set.
--
-- recipe_id becomes nullable; a new nullable `title` carries the free-text name. The CHECK enforces
-- the xor so a row can never have both or neither. Existing rows all have recipe_id (and no title),
-- so they satisfy the constraint. Unique(date, slot), the recipe_id FK (ON DELETE CASCADE) and
-- `servings` are unchanged.
ALTER TABLE meal_plan_entries
    ALTER COLUMN recipe_id DROP NOT NULL,
    ADD COLUMN title VARCHAR(200),
    ADD CONSTRAINT meal_plan_entries_recipe_xor_title
        CHECK ((recipe_id IS NOT NULL) <> (title IS NOT NULL));
