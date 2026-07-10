-- Per-list usage stats (#501, follow-up to #412): scope the shopping autocomplete tally + remembered
-- category/icon corrections (shopping_item_stats) per list instead of household-global.
--
-- Until now the table was keyed by normalized_name alone, i.e. shared across every list. Two v1
-- limitations (from the PR #497 review) fall out of that:
--   1. a remembered correction was global per name — two own-categories lists (#412) mapping the same
--      article to different categories fought over one row (last write won);
--   2. autocomplete names were household-wide — a Baumarkt list suggested grocery names.
-- The scope mirrors the #412 category split: an own-categories list gets its OWN stats scope (its id);
-- every shared list and the null/unfiled bucket share the all-zeros sentinel scope. A real list id can
-- never be all-zeros, so the sentinel never collides — and it keeps the composite PK columns NOT NULL
-- (a nullable PK column is disallowed in Postgres). resolve()/resolveForItem still remap any out-of-
-- scope key to OTHER, so nothing leaks even when a scoped correction points at a foreign category.
ALTER TABLE shopping_item_stats
    ADD COLUMN list_scope UUID NOT NULL DEFAULT '00000000-0000-0000-0000-000000000000';

-- Existing rows are backfilled to the shared sentinel above (they were household-global). Drop the
-- default afterwards so the column mirrors the Exposed model exactly (the app always sets it on insert).
ALTER TABLE shopping_item_stats ALTER COLUMN list_scope DROP DEFAULT;

ALTER TABLE shopping_item_stats DROP CONSTRAINT shopping_item_stats_pkey;
ALTER TABLE shopping_item_stats ADD PRIMARY KEY (normalized_name, list_scope);
