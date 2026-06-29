-- #429 Phase 1: optional time-of-day on a todo's due date + an optional reminder lead (minutes
-- before due). Both are extras on top of due_date and are meaningless without one, so a CHECK ties
-- them to due_date. reminder_lead_minutes carries the data for the later notification work; no
-- scheduler reads it yet.
ALTER TABLE todos ADD COLUMN due_time              TIME;
ALTER TABLE todos ADD COLUMN reminder_lead_minutes INTEGER;

-- A time / reminder only makes sense when the todo has a due date.
ALTER TABLE todos ADD CONSTRAINT todos_due_time_requires_date_chk
    CHECK (due_time IS NULL OR due_date IS NOT NULL);
ALTER TABLE todos ADD CONSTRAINT todos_reminder_requires_date_chk
    CHECK (reminder_lead_minutes IS NULL OR due_date IS NOT NULL);

-- A reminder lead is a non-negative number of minutes.
ALTER TABLE todos ADD CONSTRAINT todos_reminder_non_negative_chk
    CHECK (reminder_lead_minutes IS NULL OR reminder_lead_minutes >= 0);
