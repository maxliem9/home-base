-- Shopping categories, item icons & usage stats (#389/#390): the shopping list now auto-groups
-- items into grocery categories, shows a per-item emoji icon, and offers "most used" autocomplete.
-- The backend GroceryCatalog resolves a category key + emoji from each item's name on create; both
-- are stored on the row (denormalized cache, overridable) so clients render without their own
-- lookup. Existing rows stay NULL (treated as the "Sonstiges" bucket until next touched).
ALTER TABLE shopping_items
    ADD COLUMN category VARCHAR(40),
    ADD COLUMN icon     VARCHAR(32);

-- Per-name usage tally powering the autocomplete ("most used") and remembering manual category/icon
-- corrections for next time. Keyed by the normalized item name so it survives item deletion /
-- "Abgehakte entfernen" (clear checked). See GroceryCatalog.normalize().
CREATE TABLE shopping_item_stats (
    normalized_name VARCHAR(200) PRIMARY KEY,
    display_name    TEXT         NOT NULL,
    category        VARCHAR(40),
    icon            VARCHAR(32),
    use_count       INTEGER      NOT NULL DEFAULT 0,
    last_used_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- Ranking index for GET /shopping/suggestions (most used first, then most recent).
CREATE INDEX shopping_item_stats_rank_idx ON shopping_item_stats(use_count DESC, last_used_at DESC);
