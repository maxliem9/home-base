-- Per-list auto-resolve rules (#501, optional follow-up to #412): scope the editable name→category+icon
-- dictionary (shopping_category_rules) per list, so an own-categories list can pre-declare its own
-- auto-fill rules (e.g. "Schrauben" → Kleinteile) instead of only the shared grocery dictionary.
--
-- Mirrors V42 (shopping_item_stats): the scope is the same all-zeros sentinel for the shared household
-- dictionary (every pre-#501 rule, and every shared/non-own list) and the list id for an own-categories
-- list's private rules. The composite PK keeps the same normalized name usable once per scope, and the
-- sentinel keeps the PK columns NOT NULL (a nullable PK column is disallowed in Postgres). resolve()
-- loads only the target list's scope, so grocery rules never bleed into a Baumarkt list and vice versa.
ALTER TABLE shopping_category_rules
    ADD COLUMN list_scope UUID NOT NULL DEFAULT '00000000-0000-0000-0000-000000000000';

-- Existing rows backfill to the shared sentinel above (they were the household-global dictionary). Drop
-- the default afterwards so the column mirrors the Exposed model exactly (the app always sets it).
ALTER TABLE shopping_category_rules ALTER COLUMN list_scope DROP DEFAULT;

ALTER TABLE shopping_category_rules DROP CONSTRAINT shopping_category_rules_pkey;
ALTER TABLE shopping_category_rules ADD PRIMARY KEY (normalized_name, list_scope);
