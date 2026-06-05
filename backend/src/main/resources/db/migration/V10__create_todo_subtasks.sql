-- todo_subtasks is modelled in Exposed (TodoSubtasksTable) and read/written by the todo
-- routes, but — like todo_lists before it — was never created by a migration. On a fresh
-- database the first todo write 500s with "relation todo_subtasks does not exist".
-- Create it if missing; a no-op on databases that already have it.
CREATE TABLE IF NOT EXISTS todo_subtasks (
    id          UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    todo_id     UUID            NOT NULL REFERENCES todos(id) ON DELETE CASCADE,
    title       TEXT            NOT NULL,
    done        BOOLEAN         NOT NULL DEFAULT FALSE,
    sort_order  INTEGER         NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS todo_subtasks_todo_idx ON todo_subtasks(todo_id);
