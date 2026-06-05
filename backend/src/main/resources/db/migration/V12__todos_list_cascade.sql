-- Deleting a todo list now deletes the todos it contains (issue #58 / former backlog 0020).
--
-- Background: V7 attached todos to lists via `list_id ... ON DELETE SET NULL`, so deleting a
-- non-empty list left its todos behind as orphans (list_id = NULL). The web UI is strictly
-- tab-per-list with no "no list" bucket and removes them optimistically, so to a web user they
-- look deleted while they linger in the DB and keep surfacing in the Telegram digest. The
-- product decision (issue #58) is that deleting a list deletes its todos. Swap the FK to
-- ON DELETE CASCADE so Postgres removes them; their subtasks already cascade off todos.id
-- (V10: todo_subtasks.todo_id ... ON DELETE CASCADE).
--
-- NOT touched here: existing orphans (list_id IS NULL). They are indistinguishable from the
-- legitimate unfiled inbox todos the Android app creates and surfaces on purpose
-- (TodoViewModel: "surfaces unfiled (listId == null) inbox todos so nothing is hidden"), so a
-- blanket delete would also wipe real mobile inbox items. Only the cascade going forward changes.

-- The constraint was created by V7's `ADD COLUMN ... REFERENCES todo_lists(id) ON DELETE SET NULL`
-- (or, on the author's pre-V7 database, by an earlier hand-applied ALTER). Drop whatever FK
-- currently links todos.list_id -> todo_lists by its catalog name, then re-add it with CASCADE.
-- Resolving the name from pg_constraint keeps this correct regardless of how it was named.
DO $$
DECLARE
    fk_name text;
BEGIN
    SELECT con.conname INTO fk_name
    FROM pg_constraint con
    JOIN pg_class rel ON rel.oid = con.conrelid
    JOIN pg_attribute att ON att.attrelid = con.conrelid AND att.attnum = ANY (con.conkey)
    WHERE con.contype = 'f'
      AND rel.relname = 'todos'
      AND att.attname = 'list_id';

    IF fk_name IS NOT NULL THEN
        EXECUTE format('ALTER TABLE todos DROP CONSTRAINT %I', fk_name);
    END IF;
END $$;

ALTER TABLE todos
    ADD CONSTRAINT todos_list_id_fkey
    FOREIGN KEY (list_id) REFERENCES todo_lists(id) ON DELETE CASCADE;
