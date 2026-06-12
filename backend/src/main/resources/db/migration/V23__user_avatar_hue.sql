-- Per-user avatar colour (Teil von #100). Each member can override the hue of their
-- own avatar; NULL means "automatic" — the client derives a stable hue from the
-- username hash (#160), so existing installs render exactly as before until someone
-- picks a colour.
--
-- Stored on the `users` table (NOT the per-user user_prefs from #153) because a
-- member's avatar colour must be visible to the PARTNER too: avatars render across
-- the shared views (timer, calendar, assignees). user_prefs is deliberately
-- own-read-only, so it is the wrong store. The colour is exposed via the existing
-- household-visible roster (GET /users), which both users already read.
--
-- Model is a HUE (0..359) to match the OKLCH avatars: oklch(0.62, 0.09, hue) on web
-- and Android. The CHECK keeps the column inside the valid hue wheel; the app also
-- validates the setter and falls back to the derived hue on any unexpected value.
ALTER TABLE users
    ADD COLUMN avatar_hue INTEGER
    CONSTRAINT users_avatar_hue_range CHECK (avatar_hue IS NULL OR (avatar_hue BETWEEN 0 AND 359));
