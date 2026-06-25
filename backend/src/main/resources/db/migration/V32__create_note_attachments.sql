-- Arbitrary file attachments on notes (#431). Generalises the existing note_images pattern to
-- non-image files (PDF, office docs, text, …) so the household can park insurance/contract files
-- in the note context. Deliberately a separate table from note_images: images keep their inline
-- markdown rendering + thumbnails (resolved via note_images rows), attachments are download-only
-- file chips. Bytes live on disk under UPLOAD_DIR exactly like note_images; the row holds metadata.
CREATE TABLE note_attachments (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    note_id       UUID         NOT NULL REFERENCES notes(id) ON DELETE CASCADE,
    filename      TEXT         NOT NULL,
    original_name TEXT         NOT NULL,
    content_type  VARCHAR(150) NOT NULL,
    size_bytes    BIGINT       NOT NULL,
    sort_order    INTEGER      NOT NULL DEFAULT 0,
    created_by    VARCHAR(50)  NOT NULL REFERENCES users(username),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX note_attachments_note_id_idx ON note_attachments(note_id);
