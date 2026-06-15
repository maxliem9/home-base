-- Wochenplan portions (#251): how many servings to cook for a planned meal. NULL = use the
-- recipe's own authored servings (the prior 1×-as-authored behavior). When set, "In Einkaufsliste"
-- scales that dish's ingredients by servings / recipe.servings before aggregating.
ALTER TABLE meal_plan_entries
    ADD COLUMN servings INTEGER CHECK (servings IS NULL OR servings >= 1);
