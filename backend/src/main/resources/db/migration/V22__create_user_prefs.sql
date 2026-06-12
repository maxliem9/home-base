-- Generic PER-USER key/value preferences (#100, Phase 2). Unlike app_settings
-- (household-wide, shared) these are personal: each user reads/writes only their
-- own rows. Modelled as a generic KV store so future prefs (default todo list,
-- dashboard tiles, week-start, notification opt-outs) attach without a new
-- migration each. First consumer: the 'theme' key (light|dark|system).
--
-- user_id is the username (VARCHAR, FK users.username) per project convention —
-- identity is the username everywhere, not a UUID. ON DELETE CASCADE so a user's
-- prefs vanish with the user. value is TEXT (short scalar/JSON strings); an empty
-- table behaves like "every pref at its client default".
CREATE TABLE user_prefs (
    user_id VARCHAR(50) NOT NULL REFERENCES users(username) ON DELETE CASCADE,
    key     VARCHAR(64) NOT NULL,
    value   TEXT        NOT NULL,
    PRIMARY KEY (user_id, key)
);
