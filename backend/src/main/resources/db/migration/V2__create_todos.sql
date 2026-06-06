-- status / priority are plain VARCHARs whose allowed values are validated in
-- application code (Exposed maps them as varchar). They are deliberately NOT
-- PostgreSQL ENUM types: an enum rejects the plain-text bindings Exposed sends on
-- INSERT/UPDATE ("column is of type todo_status but expression is character varying").
CREATE TABLE todos (
    id          UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    title       TEXT            NOT NULL,
    description TEXT,
    status      VARCHAR(20)     NOT NULL DEFAULT 'INBOX',
    assignee    VARCHAR(50)     REFERENCES users(username),
    due_date    DATE,
    priority    VARCHAR(10),
    created_by  VARCHAR(50)     NOT NULL REFERENCES users(username),
    created_at  TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    done_at     TIMESTAMPTZ
);

CREATE INDEX todos_status_idx ON todos(status);
CREATE INDEX todos_assignee_idx ON todos(assignee);
