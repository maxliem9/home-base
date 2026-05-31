CREATE TYPE todo_status   AS ENUM ('INBOX', 'PLANNED', 'DONE');
CREATE TYPE todo_priority AS ENUM ('LOW', 'MEDIUM', 'HIGH');

CREATE TABLE todos (
    id          UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    title       TEXT            NOT NULL,
    description TEXT,
    status      todo_status     NOT NULL DEFAULT 'INBOX',
    assignee    VARCHAR(50)     REFERENCES users(username),
    due_date    DATE,
    priority    todo_priority,
    created_by  VARCHAR(50)     NOT NULL REFERENCES users(username),
    created_at  TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    done_at     TIMESTAMPTZ
);

CREATE INDEX todos_status_idx ON todos(status);
CREATE INDEX todos_assignee_idx ON todos(assignee);
