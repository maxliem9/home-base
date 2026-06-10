-- Wochensoll-Stunden (#31) — weekly work-hour targets per person × project.
-- weekly_hours is the person's contracted hours on that project per ISO week
-- (default 0 = no target). is_default marks the person's one default project:
-- absence/holiday day credits are booked against it (see the forecast endpoint).
CREATE TABLE time_work_targets (
    id           UUID             PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      VARCHAR(50)      NOT NULL REFERENCES users(username),
    project_id   UUID             NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    weekly_hours DOUBLE PRECISION NOT NULL DEFAULT 0 CHECK (weekly_hours >= 0),
    is_default   BOOLEAN          NOT NULL DEFAULT FALSE
);

-- One target row per person and project.
CREATE UNIQUE INDEX time_work_targets_user_project_uniq ON time_work_targets(user_id, project_id);

-- At most one default project per person (the application clears the others on change;
-- this is the DB backstop against concurrent writers).
CREATE UNIQUE INDEX time_work_targets_one_default ON time_work_targets(user_id) WHERE is_default;
