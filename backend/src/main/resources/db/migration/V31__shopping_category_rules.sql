-- Editable auto-resolve dictionary (#411 PR B): the per-name → category + icon mapping moves from the
-- hardcoded GroceryCatalog seed into the DB so the household can edit which item names auto-fill which
-- category/emoji. Seeded from GroceryCatalog.seed on first startup (empty-table convention). Keyed by
-- the normalized name (GroceryCatalog.normalize); `category` holds a shopping_categories key as a
-- denormalized string (like shopping_items.category — a deleted category reassigns its rules to OTHER).
CREATE TABLE shopping_category_rules (
    normalized_name VARCHAR(200) PRIMARY KEY,
    display_name    TEXT         NOT NULL,
    category        VARCHAR(40)  NOT NULL,
    icon            VARCHAR(32)  NOT NULL
);
