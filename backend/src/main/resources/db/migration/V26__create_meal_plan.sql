-- Wochenplan / Essensplaner (#218): plans one recipe into a (date, meal slot) of the
-- weekly grid. Household-wide shared like the absence calendar — no owner column / check.
--
-- `slot` is one of the three grid meal times BREAKFAST|LUNCH|DINNER. These are the
-- planner's own slots and are DELIBERATELY independent of the recipe categories (which
-- dropped LUNCH in V17): a recipe of any category may be planned into any slot.
CREATE TABLE meal_plan_entries (
    id         UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    date       DATE         NOT NULL,
    slot       VARCHAR(20)  NOT NULL,
    recipe_id  UUID         NOT NULL REFERENCES recipes(id) ON DELETE CASCADE,
    created_by VARCHAR(50)  NOT NULL REFERENCES users(username),
    created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- At most one recipe per (date, slot): setting a slot replaces the existing entry. The
-- unique index also serves the weekly range lookups (filtered by date).
CREATE UNIQUE INDEX meal_plan_entries_date_slot_uniq ON meal_plan_entries(date, slot);
