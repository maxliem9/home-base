CREATE TABLE recipes (
    id                UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    title             TEXT            NOT NULL,
    description       TEXT,
    servings          INT             NOT NULL DEFAULT 1 CHECK (servings >= 1),
    prep_time_minutes INT             CHECK (prep_time_minutes IS NULL OR prep_time_minutes >= 0),
    cook_time_minutes INT             CHECK (cook_time_minutes IS NULL OR cook_time_minutes >= 0),
    category          VARCHAR(20)     NOT NULL,
    created_by        VARCHAR(50)     NOT NULL REFERENCES users(username),
    created_at        TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE TABLE ingredients (
    id          UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    recipe_id   UUID            NOT NULL REFERENCES recipes(id) ON DELETE CASCADE,
    name        TEXT            NOT NULL,
    amount      NUMERIC(12, 3) CHECK (amount IS NULL OR amount >= 0),
    unit        VARCHAR(50),
    sort_order  INT             NOT NULL DEFAULT 0
);

CREATE TABLE recipe_steps (
    id          UUID    PRIMARY KEY DEFAULT gen_random_uuid(),
    recipe_id   UUID    NOT NULL REFERENCES recipes(id) ON DELETE CASCADE,
    step_number INT     NOT NULL,
    description TEXT    NOT NULL
);

CREATE INDEX recipes_category_idx ON recipes(category);
CREATE INDEX ingredients_recipe_id_idx ON ingredients(recipe_id);
CREATE INDEX recipe_steps_recipe_id_idx ON recipe_steps(recipe_id);
