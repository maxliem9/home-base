-- Abwesenheit / Familienkalender — shared household absence planner.
-- Stores only explicit, user-set data; derived day-states (public holidays,
-- part-time free days, weekends) are computed in the client.

-- One stored absence per person per day. type ∈ (URLAUB|KRANK|KIND_KRANK),
-- half ∈ (NULL=full day | vm=Vormittag/AM | nm=Nachmittag/PM).
CREATE TABLE absences (
    id       UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id  VARCHAR(50)  NOT NULL REFERENCES users(username),
    date     DATE         NOT NULL,
    type     VARCHAR(20)  NOT NULL,
    half     VARCHAR(2)
);

-- At most one absence entry per (user, date).
CREATE UNIQUE INDEX absences_user_date_uniq ON absences(user_id, date);

-- Recurring "fixed free day" rules for part-time schedules.
-- weekday is ISO (1=Mon … 7=Sun); end_date NULL = open-ended.
CREATE TABLE part_time_rules (
    id         UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    VARCHAR(50)  NOT NULL REFERENCES users(username),
    weekday    INTEGER      NOT NULL,
    start_date DATE         NOT NULL,
    end_date   DATE
);

CREATE INDEX part_time_rules_user_idx ON part_time_rules(user_id);

-- Household-wide Kita (daycare) closures — a background marker, not per person.
CREATE TABLE kita_closures (
    id    UUID  PRIMARY KEY DEFAULT gen_random_uuid(),
    date  DATE  NOT NULL,
    label TEXT  NOT NULL
);

-- One closure per date — a closure is a household-wide marker, so a second row for
-- the same day is meaningless. The unique index also serves date lookups.
CREATE UNIQUE INDEX kita_closures_date_uniq ON kita_closures(date);

-- Per-person calendar settings (one row per user). Created lazily on first edit.
CREATE TABLE abs_settings (
    user_id           VARCHAR(50)  PRIMARY KEY REFERENCES users(username),
    state             VARCHAR(2)   NOT NULL DEFAULT 'BE',
    allowance         DOUBLE PRECISION NOT NULL DEFAULT 30,
    carryover         DOUBLE PRECISION NOT NULL DEFAULT 0,
    carryover_expires DATE,
    kind_krank_cap    INTEGER      NOT NULL DEFAULT 15
);
