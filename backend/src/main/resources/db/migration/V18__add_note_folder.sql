-- Single-level folder label for notes (issue #30). Nullable: NULL = "no folder".
-- Folders are a flat label only; the set of folders is derived client-side from the
-- loaded notes (like tags), so there is no separate folder entity/table.
ALTER TABLE notes ADD COLUMN folder VARCHAR(100);

CREATE INDEX notes_folder_idx ON notes(folder);
