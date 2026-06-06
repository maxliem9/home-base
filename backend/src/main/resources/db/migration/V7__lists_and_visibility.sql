-- Todos belong to a list; each list is SHARED or PRIVATE.
CREATE TABLE todo_lists (
    id          UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    name        TEXT            NOT NULL,
    visibility  VARCHAR(10)     NOT NULL DEFAULT 'SHARED',
    created_by  VARCHAR(50)     NOT NULL REFERENCES users(username),
    created_at  TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

-- Deleting a list deletes the todos it contains (issue #58); their subtasks in turn
-- cascade off todos.id (see V10). The web UI is strictly tab-per-list with no
-- "no list" bucket, so a list and its todos are a single unit.
ALTER TABLE todos
    ADD COLUMN list_id UUID REFERENCES todo_lists(id) ON DELETE CASCADE;
