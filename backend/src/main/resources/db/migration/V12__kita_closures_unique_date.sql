-- Enforce "one Kita closure per date" at the DB level. Previously kita_closures
-- had only a non-unique index on date, so a re-run of POST /kita/range (or two
-- concurrent range/POST calls racing past the app-level dedup) could create
-- duplicate closures for the same day.

-- Defensive dedup first so the unique index applies cleanly even if duplicates
-- already exist: keep the row with the lowest id per date, drop the rest.
DELETE FROM kita_closures a
USING kita_closures b
WHERE a.date = b.date AND a.id > b.id;

-- Replace the plain lookup index with a unique one (it still serves lookups).
DROP INDEX IF EXISTS kita_closures_date_idx;
CREATE UNIQUE INDEX kita_closures_date_uniq ON kita_closures(date);
