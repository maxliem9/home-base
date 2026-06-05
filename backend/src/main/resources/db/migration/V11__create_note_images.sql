CREATE TABLE note_images (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    note_id       UUID         NOT NULL REFERENCES notes(id) ON DELETE CASCADE,
    filename      TEXT         NOT NULL,
    original_name TEXT         NOT NULL,
    content_type  VARCHAR(100) NOT NULL,
    size_bytes    BIGINT       NOT NULL,
    sort_order    INTEGER      NOT NULL DEFAULT 0,
    created_by    VARCHAR(50)  NOT NULL REFERENCES users(username),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX note_images_note_id_idx ON note_images(note_id);
