-- #429 Phase 2b: browser Web Push subscriptions. One row per (browser, device) push endpoint
-- the client registered via the Push API. The reminder scheduler sends a push to every stored
-- subscription; a subscription the push service reports as gone (404/410) is pruned.
--
-- endpoint is the unique push-service URL the browser hands us — it is the natural key (the
-- same browser re-subscribing returns the same endpoint, so we upsert on it). p256dh + auth are
-- the client's base64url public key and auth secret used to encrypt the payload. username records
-- who subscribed (for future per-user filtering); delivery is currently household-wide like the
-- digest, so it is informational.
CREATE TABLE push_subscriptions (
    id         UUID PRIMARY KEY,
    endpoint   TEXT        NOT NULL UNIQUE,
    p256dh     TEXT        NOT NULL,
    auth       TEXT        NOT NULL,
    username   VARCHAR(50) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);
