-- Drop the LUNCH recipe category. The clients merged LUNCH+DINNER into a single
-- "Hauptgerichte"/dinner bucket, so collapse any existing LUNCH recipes into DINNER.
-- `category` is a free VARCHAR validated application-side (RecipeRoutes.VALID_CATEGORIES),
-- so there is no enum type or CHECK constraint to alter — a data update is enough.
UPDATE recipes SET category = 'DINNER' WHERE category = 'LUNCH';
