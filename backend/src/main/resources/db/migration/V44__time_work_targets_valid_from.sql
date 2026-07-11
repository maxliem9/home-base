-- Effective-dated Wochensoll (#31 follow-up): a person's weekly hours can change
-- from a chosen date onwards (e.g. 40h until August, 32h from September) while past
-- weeks keep the value that was valid then. Each (user, project) now has one row per
-- period; a period is identified by valid_from. The forecast for an ISO week picks,
-- per person, the period with the greatest valid_from <= that week's Monday.
--
-- Existing rows become the base period (valid_from = 1970-01-01), so nothing changes
-- for households that never schedule a change.
ALTER TABLE time_work_targets ADD COLUMN valid_from DATE NOT NULL DEFAULT DATE '1970-01-01';
-- The application always sets valid_from explicitly; the default only backfilled the
-- pre-existing rows above.
ALTER TABLE time_work_targets ALTER COLUMN valid_from DROP DEFAULT;

-- One target row per person, project and period.
DROP INDEX time_work_targets_user_project_uniq;
CREATE UNIQUE INDEX time_work_targets_user_project_uniq
    ON time_work_targets(user_id, project_id, valid_from);

-- At most one default project per person *and period* (the application clears the
-- others on change; this is the DB backstop against concurrent writers). Same index
-- name as before so the 409-conflict detection in the route keeps matching it.
DROP INDEX time_work_targets_one_default;
CREATE UNIQUE INDEX time_work_targets_one_default
    ON time_work_targets(user_id, valid_from) WHERE is_default;
