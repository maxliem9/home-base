-- Per-year calendar settings (#144). Vacation allowance, carryover ("Resturlaub")
-- and its expiry are inherently annual, so abs_settings becomes one row per
-- (user, year) instead of one row per user. Bundesland and the kind-krank cap are
-- carried along per year too (new years inherit them from the nearest year client-/
-- server-side). Existing single rows describe the current calendar year.
ALTER TABLE abs_settings ADD COLUMN year INTEGER;
UPDATE abs_settings SET year = EXTRACT(YEAR FROM CURRENT_DATE)::int WHERE year IS NULL;
ALTER TABLE abs_settings ALTER COLUMN year SET NOT NULL;

-- Swap the single-column PK (user_id) for the composite (user_id, year).
ALTER TABLE abs_settings DROP CONSTRAINT abs_settings_pkey;
ALTER TABLE abs_settings ADD PRIMARY KEY (user_id, year);
