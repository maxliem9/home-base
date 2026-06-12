CREATE TABLE recipe_images (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    recipe_id     UUID         NOT NULL REFERENCES recipes(id) ON DELETE CASCADE,
    filename      TEXT         NOT NULL,
    original_name TEXT         NOT NULL,
    content_type  VARCHAR(100) NOT NULL,
    size_bytes    BIGINT       NOT NULL,
    sort_order    INTEGER      NOT NULL DEFAULT 0,
    created_by    VARCHAR(50)  NOT NULL REFERENCES users(username),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX recipe_images_recipe_id_idx ON recipe_images(recipe_id);
