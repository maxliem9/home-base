-- Shopping items belong to a named shopping list (not a free-text category).
CREATE TABLE shopping_lists (
    id          UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    name        TEXT            NOT NULL,
    created_by  VARCHAR(50)     NOT NULL REFERENCES users(username),
    created_at  TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE TABLE shopping_items (
    id          UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    name        TEXT            NOT NULL,
    list_id     UUID            REFERENCES shopping_lists(id) ON DELETE CASCADE,
    checked     BOOLEAN         NOT NULL DEFAULT FALSE,
    created_by  VARCHAR(50)     NOT NULL REFERENCES users(username),
    created_at  TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    checked_at  TIMESTAMPTZ
);

CREATE INDEX shopping_items_checked_idx ON shopping_items(checked);
CREATE INDEX shopping_items_list_idx ON shopping_items(list_id);
