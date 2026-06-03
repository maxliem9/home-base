CREATE TABLE projects (
    id          UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    name        TEXT            NOT NULL,
    color       VARCHAR(7)      NOT NULL,
    archived    BOOLEAN         NOT NULL DEFAULT FALSE,
    created_by  VARCHAR(50)     NOT NULL REFERENCES users(username),
    created_at  TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE TABLE time_entries (
    id          UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id  UUID            NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    user_id     VARCHAR(50)     NOT NULL REFERENCES users(username),
    started_at  TIMESTAMPTZ     NOT NULL,
    stopped_at  TIMESTAMPTZ,
    description TEXT,
    created_at  TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE INDEX time_entries_user_idx     ON time_entries(user_id);
CREATE INDEX time_entries_project_idx  ON time_entries(project_id);
CREATE INDEX time_entries_started_idx  ON time_entries(started_at);

-- A user may have at most one running timer (stopped_at IS NULL) at a time.
-- The application stops the previous timer before starting a new one; this partial
-- unique index is the database-level safety net guaranteeing the invariant.
CREATE UNIQUE INDEX time_entries_one_running_per_user
    ON time_entries(user_id) WHERE stopped_at IS NULL;
