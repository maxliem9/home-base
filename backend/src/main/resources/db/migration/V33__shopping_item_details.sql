-- Item details (#445): free-text quantity ("500 g", "2×", "10er") and a free-text note
-- ("im roten Glas"). Both optional and additive — the batch/Wochenplan flow still encodes the
-- amount in the name; clients prefer an explicit `quantity` and otherwise parse it from the name.
ALTER TABLE shopping_items
    ADD COLUMN quantity VARCHAR(120),
    ADD COLUMN note      TEXT;
