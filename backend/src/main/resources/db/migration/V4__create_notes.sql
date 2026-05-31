CREATE TABLE notes (
    id          UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    title       TEXT            NOT NULL,
    content     TEXT            NOT NULL DEFAULT '',
    tags        TEXT            NOT NULL DEFAULT '',
    visibility  VARCHAR(10)     NOT NULL DEFAULT 'SHARED',
    created_by  VARCHAR(50)     NOT NULL REFERENCES users(username),
    created_at  TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE INDEX notes_visibility_idx ON notes(visibility);
CREATE INDEX notes_created_by_idx ON notes(created_by);
