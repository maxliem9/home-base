-- Multi-assignee todos: a todo can be assigned to any subset of the household (zero, one, or
-- several users). Replaces the single todos.assignee column with a join table; "both" is just two
-- rows. Delivery of reminders stays household-wide — assignees are informational (like before).

CREATE TABLE todo_assignees (
    todo_id  UUID        NOT NULL REFERENCES todos(id) ON DELETE CASCADE,
    username VARCHAR(50) NOT NULL REFERENCES users(username),
    PRIMARY KEY (todo_id, username)
);

-- Quick lookup of a user's todos (mirrors the old index on todos.assignee).
CREATE INDEX idx_todo_assignees_username ON todo_assignees(username);

-- Backfill: every existing single assignee becomes one join row. The old column was already a FK
-- to users(username), so all present values are valid users and satisfy the new FK.
INSERT INTO todo_assignees (todo_id, username)
SELECT id, assignee FROM todos WHERE assignee IS NOT NULL;

-- The single-assignee column is now redundant; its index (todos_assignee_idx, V2) drops with it.
DROP INDEX IF EXISTS todos_assignee_idx;
ALTER TABLE todos DROP COLUMN assignee;
