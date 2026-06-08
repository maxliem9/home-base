-- Optional named section/group for an ingredient (e.g. "Boden", "Topping").
-- NULL / empty = ungrouped ingredient, rendered in the header-less top section.
-- Lightweight: just a label on the ingredient, no separate sections table (issue #123).
ALTER TABLE ingredients ADD COLUMN section TEXT;
