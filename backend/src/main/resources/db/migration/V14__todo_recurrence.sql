-- Wiederkehrende Todos (issue #44): a lightweight recurrence rule lives on the todo itself.
--   recurrence          — frequency: DAILY | WEEKLY | MONTHLY (NULL = one-off todo)
--   recurrence_interval — every N units, e.g. 2 = every other week (>= 1)
-- A recurring todo always carries a due_date as the schedule anchor: completing it spawns the
-- next instance (due_date advanced by the rule), and a daily safety-net rolls a missed, still-open
-- one forward so it stays on schedule instead of rotting in the past.
ALTER TABLE todos ADD COLUMN recurrence          VARCHAR(10);
ALTER TABLE todos ADD COLUMN recurrence_interval INTEGER;

-- Keep the two columns paired and their values sane (defense-in-depth; the app validates too).
ALTER TABLE todos ADD CONSTRAINT todos_recurrence_freq_chk
    CHECK (recurrence IS NULL OR recurrence IN ('DAILY', 'WEEKLY', 'MONTHLY'));
ALTER TABLE todos ADD CONSTRAINT todos_recurrence_paired_chk
    CHECK ((recurrence IS NULL) = (recurrence_interval IS NULL));
ALTER TABLE todos ADD CONSTRAINT todos_recurrence_interval_chk
    CHECK (recurrence_interval IS NULL OR recurrence_interval >= 1);
-- The next instance is computed from due_date, so a recurring todo must have one.
ALTER TABLE todos ADD CONSTRAINT todos_recurrence_due_chk
    CHECK (recurrence IS NULL OR due_date IS NOT NULL);

-- The safety-net scheduler scans only open recurring todos; a partial index keeps that cheap.
CREATE INDEX todos_recurrence_idx ON todos(recurrence) WHERE recurrence IS NOT NULL;
