-- Editable grocery category catalog (#411): the category LIST (key, label, emoji, route order) moves
-- from the hardcoded GroceryCatalog into the DB so the household can add / rename / reorder / delete
-- its own categories. Seeded from GroceryCatalog.categories on first startup (empty-table convention,
-- like SEED_USERS). The per-name auto-resolve dictionary follows in a later step (#411 PR B); item rows
-- keep their denormalized category/icon cache from V29.
CREATE TABLE shopping_categories (
    key        VARCHAR(40)  PRIMARY KEY,
    label      TEXT         NOT NULL,
    emoji      VARCHAR(32)  NOT NULL,
    sort_order INTEGER      NOT NULL DEFAULT 0,
    is_builtin BOOLEAN      NOT NULL DEFAULT FALSE
);
