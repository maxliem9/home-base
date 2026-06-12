-- Household-level app settings as a generic key/value store (#100, Phase 2).
-- First (and so far only) key: 'household_name' — the sidebar brand name, now
-- editable by either user. The application reads a value here when present and
-- otherwise falls back to the configured default ('Mäxchen'), so an empty table
-- behaves exactly as before this migration. Future household-wide settings
-- (e.g. the Telegram digest time) can reuse this table without a new migration.
CREATE TABLE app_settings (
    key   VARCHAR(64) PRIMARY KEY,
    value TEXT        NOT NULL
);
