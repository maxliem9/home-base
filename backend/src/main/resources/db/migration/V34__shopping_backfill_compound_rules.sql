-- Backfill the compound-word auto-resolve rules added in #441 (Leberkäse/Fleischkäse → Fleisch,
-- Apfelschorle/Schorle → Getränke). These ship in GroceryCatalog.seed, but the seed only populates a
-- *fresh* shopping_category_rules table (the empty-table convention in ShoppingCatalog.seedIfEmpty), so
-- an already-seeded household DB never receives them. This adds them to an already-populated table.
--
-- The WHERE EXISTS gate is essential: on a brand-new DB the table is still empty at migration time, so
-- this inserts nothing and lets the app's full seed run afterward. Inserting here would make the table
-- non-empty and *suppress* that seed, leaving a fresh DB with only these four rules. ON CONFLICT keeps
-- it idempotent and never overrides a household's manual edit of the same name.
INSERT INTO shopping_category_rules (normalized_name, display_name, category, icon)
SELECT v.normalized_name, v.display_name, v.category, v.icon
FROM (VALUES
    ('leberkäse',    'Leberkäse',    'MEAT_FISH', '🍖'),
    ('fleischkäse',  'Fleischkäse',  'MEAT_FISH', '🍖'),
    ('apfelschorle', 'Apfelschorle', 'DRINKS',    '🧃'),
    ('schorle',      'Schorle',      'DRINKS',    '🧃')
) AS v (normalized_name, display_name, category, icon)
WHERE EXISTS (SELECT 1 FROM shopping_category_rules)
ON CONFLICT (normalized_name) DO NOTHING;
