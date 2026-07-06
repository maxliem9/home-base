-- Last-modified timestamp for todos, for parity with recipes/notes/time_entries (which already
-- carry updated_at) and so the clients can surface it in the todo metadata.
--
-- Backfill existing rows to their created_at: it is the best lower bound we have for a pre-existing
-- todo's last edit (we never tracked it before). The DEFAULT NOW() satisfies the NOT NULL for the
-- ALTER and for any future raw insert; application code stamps it explicitly on every mutating
-- write. A fired reminder (reminder_sent_at) deliberately does NOT bump it — that is internal
-- scheduler bookkeeping, not a user-visible edit.
ALTER TABLE todos ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW();
UPDATE todos SET updated_at = created_at;
