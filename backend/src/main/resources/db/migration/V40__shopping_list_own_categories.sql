-- Per-list category sets (#412): a shopping list can opt into its OWN category set instead of the
-- shared household catalog (#411) — e.g. a "Baumarkt" list needs Werkzeug/Schrauben, not groceries.
--
-- Design (deliberately minimal — see CLAUDE.md "Einkauf"): the #411 catalog stays THE shared default.
-- A list flips `own_categories = true` and gets a private set that starts with just the universal
-- "Sonstiges" (OTHER) fallback; its categories are the shopping_categories rows tagged with its id.
--   * shopping_categories.list_id NULL  = the shared household catalog (all pre-#412 rows stay NULL).
--   * shopping_categories.list_id = <L> = list L's own categories.
-- `key` stays the (globally unique) PK — uniqueCategoryKey() already generates collision-free keys — so
-- an item's stored category key identifies its row regardless of scope. OTHER is never duplicated: it
-- stays the single shared row (list_id NULL) that every list, custom or not, falls back to.
--
-- The auto-resolve RULES dictionary + usage stats stay shared/global for now: resolve()/resolveForItem
-- already remap any key not in the target list's live set to OTHER, so shared rules + remembered
-- corrections self-filter to each list's scope. A per-list rules dictionary is a later extension.
ALTER TABLE shopping_lists
    ADD COLUMN own_categories BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE shopping_categories
    ADD COLUMN list_id UUID REFERENCES shopping_lists(id) ON DELETE CASCADE;

CREATE INDEX shopping_categories_list_idx ON shopping_categories(list_id);
