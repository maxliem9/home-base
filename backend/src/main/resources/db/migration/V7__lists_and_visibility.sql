-- Todo-list visibility (replaces color) + list-based shopping (replaces categories).

-- 1. Todo lists: swap color for a SHARED/PRIVATE visibility flag.
ALTER TABLE todo_lists ADD COLUMN visibility VARCHAR(10) NOT NULL DEFAULT 'SHARED';
ALTER TABLE todo_lists DROP COLUMN color;

-- Move listless todos into a default shared list so they stay visible in the
-- new tab-per-list UI (which no longer has a "no list" bucket).
INSERT INTO todo_lists (id, name, visibility, created_by)
SELECT gen_random_uuid(), 'Allgemein', 'SHARED', (SELECT username FROM users ORDER BY created_at LIMIT 1)
WHERE EXISTS (SELECT 1 FROM todos WHERE list_id IS NULL);

UPDATE todos
SET list_id = (SELECT id FROM todo_lists WHERE name = 'Allgemein' ORDER BY created_at LIMIT 1)
WHERE list_id IS NULL;

-- 2. Shopping lists: items now belong to a list instead of a free-text category.
CREATE TABLE shopping_lists (
    id          UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    name        TEXT            NOT NULL,
    created_by  VARCHAR(50)     NOT NULL REFERENCES users(username),
    created_at  TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

ALTER TABLE shopping_items
    ADD COLUMN list_id UUID REFERENCES shopping_lists(id) ON DELETE CASCADE;

-- Backfill: move existing items into a single default list.
INSERT INTO shopping_lists (id, name, created_by)
SELECT gen_random_uuid(), 'Einkaufsliste', (SELECT username FROM users ORDER BY created_at LIMIT 1)
WHERE EXISTS (SELECT 1 FROM shopping_items);

UPDATE shopping_items
SET list_id = (SELECT id FROM shopping_lists ORDER BY created_at LIMIT 1)
WHERE list_id IS NULL;

ALTER TABLE shopping_items DROP COLUMN category;

CREATE INDEX shopping_items_list_idx ON shopping_items(list_id);
