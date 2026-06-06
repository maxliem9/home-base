-- Checklist subtasks belonging to a todo; deleted with their parent todo.
CREATE TABLE todo_subtasks (
    id          UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    todo_id     UUID            NOT NULL REFERENCES todos(id) ON DELETE CASCADE,
    title       TEXT            NOT NULL,
    done        BOOLEAN         NOT NULL DEFAULT FALSE,
    sort_order  INTEGER         NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE INDEX todo_subtasks_todo_idx ON todo_subtasks(todo_id);
