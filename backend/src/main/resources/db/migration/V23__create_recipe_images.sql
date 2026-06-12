-- One optional cover image per recipe. UNIQUE(recipe_id) enforces the single-image rule at
-- the DB level (also serves as the lookup index); a new upload replaces the existing row.
CREATE TABLE recipe_images (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    recipe_id     UUID         NOT NULL UNIQUE REFERENCES recipes(id) ON DELETE CASCADE,
    filename      TEXT         NOT NULL,
    original_name TEXT         NOT NULL,
    content_type  VARCHAR(100) NOT NULL,
    size_bytes    BIGINT       NOT NULL,
    created_by    VARCHAR(50)  NOT NULL REFERENCES users(username),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
