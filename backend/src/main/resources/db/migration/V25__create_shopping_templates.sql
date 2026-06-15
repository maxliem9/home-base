-- Named "standard/template shopping lists" (#215): a household saves several named templates,
-- each a plain list of item names, to re-add for the recurring weekly shop. Lean tables of their
-- own (not overloaded onto shopping_lists); applying a template reuses the existing shopping
-- batch-add and is a client concern (no apply endpoint here). Shared household model like the
-- shopping lists themselves — both users manage all templates, no ownership check.
CREATE TABLE shopping_templates (
    id          UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(255)    NOT NULL,
    created_by  VARCHAR(50)     NOT NULL REFERENCES users(username),
    created_at  TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE TABLE shopping_template_items (
    id          UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    template_id UUID            NOT NULL REFERENCES shopping_templates(id) ON DELETE CASCADE,
    name        VARCHAR(255)    NOT NULL,
    sort_order  INT             NOT NULL
);

CREATE INDEX shopping_template_items_template_idx ON shopping_template_items(template_id);
