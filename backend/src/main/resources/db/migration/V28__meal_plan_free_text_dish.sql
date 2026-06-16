-- Wochenplan free-text dishes (#293): a (date, slot) may now hold a plain dish name instead of a
-- recipe, so quick/one-off meals ("Pizza bestellen", "Reste") don't need a full recipe authored
-- first. Exactly one of recipe_id / dish_title is set per row (XOR) — the prior NOT NULL on
-- recipe_id is dropped and the CHECK below enforces the invariant (existing recipe rows have
-- dish_title NULL, so they satisfy it). Free-text entries carry no ingredients, so the clients'
-- "In Einkaufsliste" aggregation simply skips them.
ALTER TABLE meal_plan_entries
    ALTER COLUMN recipe_id DROP NOT NULL,
    ADD COLUMN dish_title TEXT,
    ADD CONSTRAINT meal_plan_entries_recipe_xor_dish
        CHECK ((recipe_id IS NULL) <> (dish_title IS NULL));
