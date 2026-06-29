-- #429 Phase 2a: fire-once bookkeeping for todo reminders. The reminder scheduler stamps this
-- when it has delivered (or retired) a todo's reminder, so it never sends twice. NULL = not yet
-- reminded; re-armed (set back to NULL) when the todo's due moment is edited.
ALTER TABLE todos ADD COLUMN reminder_sent_at TIMESTAMPTZ;
