CREATE TABLE shopping_items (
    id          UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    name        TEXT            NOT NULL,
    category    VARCHAR(50),
    checked     BOOLEAN         NOT NULL DEFAULT FALSE,
    created_by  VARCHAR(50)     NOT NULL REFERENCES users(username),
    created_at  TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    checked_at  TIMESTAMPTZ
);

CREATE INDEX shopping_items_checked_idx ON shopping_items(checked);
